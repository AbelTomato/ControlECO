package com.accbook.app.capture

import com.accbook.app.data.db.TxType
import com.accbook.app.util.AppLog
import com.accbook.app.util.Money

/**
 * 阶段二通知规则引擎：对感兴趣包名的通知做收款/动账/退款解析。
 * 规则刻意保持简单（关键词定方向 + 正则提金额），未匹配的通知不会丢——
 * 原始文本已进入捕获日志，拿真实样本对着日志扩规则即可（见 RealFileParseCheck 的用法）。
 *
 * 真实样本驱动的格式覆盖：
 * - 微信收款：text=微信支付收款0.01元（朋友到账）/ 收款入账：10.00元 / bigText=收款金额¥0.01
 * - 微信支出：text=已支付¥12.80（¥ 前缀、关键词后无数字，走兜底）
 * - 微信退款：text=[2条]微信支付: 退款到账通知（折叠汇总常无金额，明细行见捕获日志，无金额不入队）
 * - 支付宝支出：text=你有一笔14.90元的支出，点此查看详情。（金额在关键词前，走反向正则）
 * - 支付宝退款：text=你收到一笔14.90元退款，点此查看账单详情！（退款→收入，打退款标记）
 * - 云闪付：成功入账100.00元 / 消费50.00元
 */
object NotificationRules {

    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val UNIONPAY = "com.unionpay"

    val knownPackages = setOf(WECHAT, ALIPAY, UNIONPAY)

    fun isInterested(packageName: String): Boolean = packageName in knownPackages

    fun sourceLabel(packageName: String): String = when (packageName) {
        WECHAT -> "微信"
        ALIPAY -> "支付宝"
        UNIONPAY -> "云闪付"
        else -> packageName
    }

    /** 正向：关键词后跟金额（微信支付收款0.01元 / 您尾号账户消费50.00元） */
    private val incomeAmount = Regex("""(?:收款|到账|入账|收入)[^\d￥¥.]{0,8}([0-9]+(?:\.[0-9]{1,2})?)""")
    private val expenseAmount = Regex("""(?:已支付|支付成功|支出|消费|付款|扣款)[^\d￥¥.]{0,8}([0-9]+(?:\.[0-9]{1,2})?)""")
    private val refundAmount = Regex("""(?:退款|退回)[^\d￥¥.]{0,8}([0-9]+(?:\.[0-9]{1,2})?)""")

    /** 反向：金额在前（你有一笔14.90元的支出 / 你收到一笔14.90元退款 / 一笔100元的收入） */
    private val reversedAmount =
        Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*元[^，。\d]{0,3}的?\s*(?:支出|消费|付款|扣款|收款|收入|到账|入账|退款|退回)""")

    /** 兜底：文本里直接出现 ¥ 金额（已支付¥12.80 / 收款金额¥0.01） */
    private val currencyAmount = Regex("""[￥¥]\s*([0-9]+(?:\.[0-9]{1,2})?)""")

    data class ParsedNotification(
        val type: TxType,
        val amountCents: Long,
        val sourceLabel: String,
        val ruleName: String,
        val note: String,
        val dedupKey: String,
        /** 退款到账（原支出已由支出通知/账单承载），入账时打 REFUND_INCOME 标记 */
        val isRefund: Boolean = false,
    )

    fun parse(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        postTime: Long,
    ): ParsedNotification? {
        if (packageName !in knownPackages) return null
        val body = sequenceOf(text, bigText).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (body.isBlank()) return null

        val refundHit = body.contains("退款") || body.contains("退回")
        val incomeHit = body.contains("收款") || body.contains("到账") || body.contains("入账") || body.contains("收入")
        val expenseHit = body.contains("已支付") || body.contains("支付成功") || body.contains("支出") ||
            body.contains("消费") || body.contains("付款") || body.contains("扣款") || body.contains("支付凭证")

        val type: TxType
        val isRefund: Boolean
        when {
            // 退款优先：退款到账是收入方向（"退款到账"同时命中"到账"不能误判为普通收款）
            refundHit -> { type = TxType.INCOME; isRefund = true }
            incomeHit && !expenseHit -> { type = TxType.INCOME; isRefund = false }
            expenseHit && !incomeHit -> { type = TxType.EXPENSE; isRefund = false }
            else -> {
                AppLog.d("Ntf", "rule ambiguous (both/none direction) pkg=$packageName body=$body")
                return null
            }
        }

        val primary = when {
            isRefund -> refundAmount
            type == TxType.INCOME -> incomeAmount
            else -> expenseAmount
        }
        val fallbackAllowed = refundHit || (type == TxType.INCOME && incomeHit) || (type == TxType.EXPENSE && expenseHit)
        val amountText = primary.find(body)?.groupValues?.get(1)
            ?: reversedAmount.find(body)?.groupValues?.get(1)
            ?: (if (fallbackAllowed) currencyAmount.find(body)?.groupValues?.get(1) else null)
        if (amountText == null) {
            AppLog.d(
                "Ntf",
                "rule amount not found type=$type refund=$isRefund pkg=$packageName body=$body" +
                    if (isRefund) "（退款汇总通知常无金额，属预期，不入队）" else "",
            )
            return null
        }
        val amountCents = Money.parseCents(amountText) ?: return null

        return ParsedNotification(
            type = type,
            amountCents = amountCents,
            sourceLabel = sourceLabel(packageName),
            ruleName = sourceLabel(packageName) + when {
                isRefund -> "·退款"
                type == TxType.INCOME -> "·收款/入账"
                else -> "·支出/消费"
            },
            note = body.take(200),
            dedupKey = "$packageName|${type}${if (isRefund) "|R" else ""}|$amountCents|${postTime / 60_000}",
            isRefund = isRefund,
        )
    }
}
