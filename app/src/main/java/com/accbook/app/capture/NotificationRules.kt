package com.accbook.app.capture

import com.accbook.app.data.db.TxType
import com.accbook.app.util.AppLog
import com.accbook.app.util.Money

/**
 * 阶段二通知规则引擎：对感兴趣包名的通知做收款/动账解析。
 * 规则刻意保持简单（关键词定方向 + 正则提金额），未匹配的通知不会丢——
 * 原始文本已进入捕获日志，拿真实样本对着日志扩规则即可（见 RealFileParseCheck 的用法）。
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

    /** 收入类金额：收款/到账/入账/收入 后跟数字（允许夹少量符号文字，如"入账：100.00"） */
    private val incomeAmount = Regex("""(?:收款|到账|入账|收入)[^\d￥¥.]{0,8}([0-9]+(?:\.[0-9]{1,2})?)""")

    /** 支出类金额：支出/消费/付款/扣款 后跟数字 */
    private val expenseAmount = Regex("""(?:支出|消费|付款|扣款)[^\d￥¥.]{0,8}([0-9]+(?:\.[0-9]{1,2})?)""")

    /** 兜底：文本里直接出现 ¥ 金额（如微信 bigText "收款金额¥0.01"） */
    private val currencyAmount = Regex("""[￥¥]\s*([0-9]+(?:\.[0-9]{1,2})?)""")

    data class ParsedNotification(
        val type: TxType,
        val amountCents: Long,
        val sourceLabel: String,
        val ruleName: String,
        val note: String,
        val dedupKey: String,
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

        val incomeHit = body.contains("收款") || body.contains("到账") || body.contains("入账") || body.contains("收入")
        val expenseHit = body.contains("支出") || body.contains("消费") || body.contains("付款") ||
            body.contains("扣款") || body.contains("支付凭证")
        val type = when {
            incomeHit && !expenseHit -> TxType.INCOME
            expenseHit && !incomeHit -> TxType.EXPENSE
            else -> {
                AppLog.d("Ntf", "rule ambiguous (both/none direction) pkg=$packageName body=$body")
                return null
            }
        }

        val primary = if (type == TxType.INCOME) incomeAmount else expenseAmount
        val fallbackAllowed = if (type == TxType.INCOME) incomeHit else expenseHit
        val amountText = primary.find(body)?.groupValues?.get(1)
            ?: (if (fallbackAllowed) currencyAmount.find(body)?.groupValues?.get(1) else null)
        if (amountText == null) {
            AppLog.d("Ntf", "rule amount not found type=$type pkg=$packageName body=$body")
            return null
        }
        val amountCents = Money.parseCents(amountText) ?: return null

        return ParsedNotification(
            type = type,
            amountCents = amountCents,
            sourceLabel = sourceLabel(packageName),
            ruleName = sourceLabel(packageName) + if (type == TxType.INCOME) "·收款/入账" else "·支出/消费",
            note = body.take(200),
            dedupKey = "$packageName|$amountCents|${postTime / 60_000}",
        )
    }
}
