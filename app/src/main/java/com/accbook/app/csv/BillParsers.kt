package com.accbook.app.csv

import com.accbook.app.data.db.TxType
import com.accbook.app.data.model.BillSource
import com.accbook.app.data.model.ImportedBillRow
import com.accbook.app.data.model.ParsedBill
import com.accbook.app.util.AppLog
import com.accbook.app.util.CategoryGuesser
import com.accbook.app.util.Money
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 微信/支付宝账单 CSV 解析。
 * 列名映射而非固定列位，列顺序变化不影响；表头行按特征自动识别。
 */
object BillParsers {

    private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 解析账单字节流：解码 → CSV → 识别来源 → 分发。无法识别返回 null。 */
    fun parse(bytes: ByteArray): ParsedBill? {
        val rows = CsvReader.parse(CsvReader.decode(bytes))
        val source = detect(rows) ?: return null
        AppLog.i("import", "detected bill source=$source, totalLines=${rows.size}")
        return when (source) {
            BillSource.WECHAT -> parseWechat(rows)
            BillSource.ALIPAY -> parseAlipay(rows)
        }
    }

    fun detect(rows: List<List<String>>): BillSource? {
        val head = rows.take(20)
        fun has(keyword: String) = head.any { row -> row.any { it.contains(keyword) } }
        return when {
            has("微信支付账单明细") || has("交易单号") -> BillSource.WECHAT
            has("商家订单号") || has("交易号") -> BillSource.ALIPAY
            else -> null
        }
    }

    // ---------- 微信 ----------

    fun parseWechat(rows: List<List<String>>): ParsedBill {
        val headerIdx = rows.indexOfFirst { row ->
            row.any { normalize(it) == "交易时间" } && row.any { normalize(it) == "交易单号" }
        }
        require(headerIdx >= 0) { "未找到微信账单表头（交易时间/交易单号）" }
        val header = rows[headerIdx].map { normalize(it) }
        fun col(prefix: String) = header.indexOfFirst { it.startsWith(prefix) }

        val iTime = col("交易时间")
        val iCounterparty = col("交易对方")
        val iGoods = col("商品")
        val iInOut = col("收/支")
        val iAmount = col("金额")
        val iPayMethod = col("支付方式")
        val iStatus = col("当前状态")
        val iTxnId = col("交易单号")
        val iNote = col("备注")

        val parsed = mutableListOf<ImportedBillRow>()
        var skipType = 0
        var skipStatus = 0

        for (raw in rows.subList(headerIdx + 1, rows.size)) {
            fun cell(idx: Int) = if (idx in raw.indices) raw[idx].trim() else ""
            if (raw.all { it.isBlank() }) continue

            val type = when (cell(iInOut)) {
                "收入" -> TxType.INCOME
                "支出" -> TxType.EXPENSE
                else -> { skipType++; continue }   // "/"：不计收支（充值、提现、理财等）
            }
            val amount = Money.parseCents(cell(iAmount))
            if (amount == null) {
                AppLog.w("import", "微信账单金额无法解析: '${cell(iAmount)}'")
                continue
            }
            val status = cell(iStatus)
            if (status.contains("退款")) { skipStatus++; continue }   // 退款行不导入，避免净额重复

            val ts = parseTime(cell(iTime))
            if (ts == null) {
                AppLog.w("import", "微信账单时间无法解析: '${cell(iTime)}'")
                continue
            }
            val counterparty = cell(iCounterparty)
            val goods = cell(iGoods)
            val note = cell(iNote).ifEmpty { goods }
            parsed += ImportedBillRow(
                type = type,
                amountCents = amount,
                timestamp = ts,
                counterparty = counterparty,
                goods = goods,
                note = note,
                payMethod = cell(iPayMethod),
                externalId = cell(iTxnId).ifEmpty { null },
                guessedCategoryName = CategoryGuesser.guess("$counterparty $goods"),
            )
        }
        return ParsedBill(BillSource.WECHAT, parsed, skipType, skipStatus)
    }

    // ---------- 支付宝 ----------

    fun parseAlipay(rows: List<List<String>>): ParsedBill {
        val headerIdx = rows.indexOfFirst { row ->
            row.any { normalize(it) == "交易号" } && row.any { normalize(it) == "交易创建时间" }
        }
        require(headerIdx >= 0) { "未找到支付宝账单表头（交易号/交易创建时间）" }
        val header = rows[headerIdx].map { normalize(it) }
        fun col(prefix: String) = header.indexOfFirst { it.startsWith(prefix) }

        val iTradeNo = col("交易号")
        val iCreated = col("交易创建时间")
        val iPaid = col("付款时间")
        val iCounterparty = col("交易对方")
        val iGoods = col("商品名称")
        val iAmount = col("金额")
        val iInOut = col("收/支")
        val iStatus = col("交易状态")
        val iNote = col("备注")

        val parsed = mutableListOf<ImportedBillRow>()
        var skipType = 0
        var skipStatus = 0

        for (raw in rows.subList(headerIdx + 1, rows.size)) {
            fun cell(idx: Int) = if (idx in raw.indices) raw[idx].trim() else ""
            if (raw.all { it.isBlank() }) continue

            val type = when (cell(iInOut)) {
                "收入" -> TxType.INCOME
                "支出" -> TxType.EXPENSE
                else -> { skipType++; continue }   // 不计收支（充值、提现、理财等）
            }
            val status = cell(iStatus)
            if (!(status.contains("成功") || status.contains("已到账"))) { skipStatus++; continue }

            val amount = Money.parseCents(cell(iAmount))
            if (amount == null) {
                AppLog.w("import", "支付宝账单金额无法解析: '${cell(iAmount)}'")
                continue
            }
            val paidText = cell(iPaid)
            val createdText = cell(iCreated)
            val ts = parseTime(paidText) ?: parseTime(createdText)
            if (ts == null) {
                AppLog.w("import", "支付宝账单时间无法解析: '$paidText'/'$createdText'")
                continue
            }
            val counterparty = cell(iCounterparty)
            val goods = cell(iGoods)
            val note = cell(iNote).ifEmpty { goods }
            parsed += ImportedBillRow(
                type = type,
                amountCents = amount,
                timestamp = ts,
                counterparty = counterparty,
                goods = goods,
                note = note,
                payMethod = "",
                externalId = cell(iTradeNo).ifEmpty { null },
                guessedCategoryName = CategoryGuesser.guess("$counterparty $goods"),
            )
        }
        return ParsedBill(BillSource.ALIPAY, parsed, skipType, skipStatus)
    }

    // ---------- 公共 ----------

    private fun normalize(s: String): String =
        s.replace("（", "(").replace("）", ")").replace(" ", "").trim()

    private fun parseTime(text: String): Long? = try {
        LocalDateTime.parse(text, DATETIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
}
