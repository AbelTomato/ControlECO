package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知规则引擎测试。样本来自真实通知（用户实测样本见 docs/开发日志/）
 * 与已知格式；新样本请加入捕获日志后对照扩规则。
 */
class NotificationRulesTest {

    private val postTime = 1_700_000_000_000L

    private fun parse(pkg: String, title: String, text: String, bigText: String = "") =
        NotificationRules.parse(pkg, title, text, bigText, postTime)

    // ---------- 收款/入账 ----------

    @Test
    fun `wechat income`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "微信支付收款0.01元（朋友到账）")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1L, r.amountCents)
        assertEquals("com.tencent.mm|INCOME|1|${postTime / 60_000}", r.dedupKey)
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

    // ---------- 支出（真实样本：2026-10 实测） ----------

    @Test
    fun `wechat expense with currency prefix`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "已支付¥12.80", bigText = " ；")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1280L, r.amountCents)
        assertEquals(false, r.isRefund)
        assertEquals("com.tencent.mm|EXPENSE|1280|${postTime / 60_000}", r.dedupKey)
    }

    @Test
    fun `alipay expense with promo after keyword`() {
        // 真实样本（2026-10）：关键词后的"领1元生活缴费红包"是营销文案，
        // 金额在关键词前（0.01元），反向正则必须优先于正向
        val r = parse(NotificationRules.ALIPAY, "交易提醒", "你有一笔0.01元的支出，领1元生活缴费红包。")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1L, r.amountCents)
    }

    @Test
    fun `alipay expense with amount before keyword`() {
        val r = parse(NotificationRules.ALIPAY, "交易提醒", "你有一笔14.90元的支出，点此查看详情。")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1490L, r.amountCents)
        assertEquals(false, r.isRefund)
    }

    @Test
    fun `alipay expense with long modifier before keyword`() {
        // 真实样本（2026-10-11）：免密扣款通知，金额与"扣款"之间的修饰语超过 3 个字；
        // 关键词后的"领取6个支付宝积分"不能被当成金额
        val r = parse(
            NotificationRules.ALIPAY,
            "交易提醒",
            "你在浙江嗨便利网络科技有限公司有一笔2.98元的免密/自动扣款支付，点击领取6个支付宝积分。",
        )!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(298L, r.amountCents)
    }

    @Test
    fun `unionpay expense`() {
        val r = parse(NotificationRules.UNIONPAY, "云闪付", "您尾号1234的账户消费50.00元")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(5000L, r.amountCents)
    }

    // ---------- 退款（真实样本：2026-10 实测） ----------

    @Test
    fun `alipay refund income with amount before keyword`() {
        val r = parse(NotificationRules.ALIPAY, "退款提醒", "你收到一笔14.90元退款，点此查看账单详情！")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1490L, r.amountCents)
        assertTrue(r.isRefund)
        assertTrue(r.ruleName.contains("退款"))
        // 退款与同额收款去重键不同
        assertEquals("com.eg.android.AlipayGphone|INCOME|R|1490|${postTime / 60_000}", r.dedupKey)
    }

    @Test
    fun `wechat grouped refund without amount is skipped`() {
        // "[2条]"折叠汇总通知没有金额，属预期不匹配（明细行捕获后若含金额仍可解析）
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "[2条]微信支付: 退款到账通知"))
    }

    @Test
    fun `refund wins over income keyword`() {
        // "退款到账"同时命中"到账"，必须按退款处理而不是普通收款
        val r = parse(NotificationRules.WECHAT, "微信支付", "退款到账通知：12.80元")!!
        assertEquals(TxType.INCOME, r.type)
        assertTrue(r.isRefund)
        assertEquals(1280L, r.amountCents)
    }

    // ---------- 边界 ----------

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
