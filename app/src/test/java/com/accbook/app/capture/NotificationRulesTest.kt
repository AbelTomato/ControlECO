package com.accbook.app.capture

import com.accbook.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 通知规则引擎测试。样本基于已知的微信/支付宝/云闪付通知格式；
 * 真实新样本请加入捕获日志后对照扩规则（docs/开发日志.md 有流程）。
 */
class NotificationRulesTest {

    private val postTime = 1_700_000_000_000L

    private fun parse(pkg: String, title: String, text: String, bigText: String = "") =
        NotificationRules.parse(pkg, title, text, bigText, postTime)

    @Test
    fun `wechat income`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "微信支付收款0.01元（朋友到账）")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1L, r.amountCents)
        assertEquals("com.tencent.mm|1|${postTime / 60_000}", r.dedupKey)
    }

    @Test
    fun `wechat income with colon`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "收款入账：10.00元\n存入零钱")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1000L, r.amountCents)
    }

    @Test
    fun `wechat income with currency in bigText`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "已存入零钱", bigText = "收款金额¥0.01")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1L, r.amountCents)
    }

    @Test
    fun `wechat income integer amount`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "微信支付收款100元")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(10000L, r.amountCents)
    }

    @Test
    fun `alipay income`() {
        val r = parse(NotificationRules.ALIPAY, "支付宝通知", "支付宝成功收款1.00元")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(100L, r.amountCents)
        assertEquals("支付宝", r.sourceLabel)
    }

    @Test
    fun `unionpay income`() {
        val r = parse(
            NotificationRules.UNIONPAY,
            "云闪付",
            "您的尾号1234的账户于10月04日17:33成功入账100.00元，可用余额200.00元",
        )!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(10000L, r.amountCents)
    }

    @Test
    fun `unionpay expense`() {
        val r = parse(NotificationRules.UNIONPAY, "云闪付", "您尾号1234的账户消费50.00元")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(5000L, r.amountCents)
    }

    @Test
    fun `uninterested package returns null`() {
        assertNull(parse("com.android.systemui", "无关", "收款10.00元"))
    }

    @Test
    fun `ambiguous direction returns null`() {
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "收款10.00元同时有支出20.00元"))
    }

    @Test
    fun `no amount returns null`() {
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "微信支付服务通知"))
    }

    @Test
    fun `blank body returns null`() {
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "  "))
    }
}
