package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知解析加固测试（2026-10-13 对照设计记录吸收，
 * 见 docs/自动记账捕获机制.md）。
 * 每个用例都对应一类**真实会错账**的输入，改动规则时这些断言就是护栏。
 */
class NotificationRulesHardeningTest {

    private val postTime = 1_700_000_000_000L

    private fun parse(pkg: String, title: String, text: String, bigText: String = "") =
        NotificationRules.parse(pkg, title, text, bigText, postTime)

    // ---------- 千分位金额：修复"¥6,064.76 被抓成 ¥6.00"的静默错账 ----------

    @Test
    fun `thousand separator in currency amount`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "已支付¥6,064.76")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(606_476L, r.amountCents)
    }

    @Test
    fun `thousand separator before yuan keyword`() {
        val r = parse(NotificationRules.UNIONPAY, "云闪付", "您尾号1234的账户消费1,234.56元")!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(123_456L, r.amountCents)
    }

    @Test
    fun `thousand separator in income`() {
        val r = parse(NotificationRules.ALIPAY, "支付宝通知", "支付宝成功收款1,000.00元")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(100_000L, r.amountCents)
    }

    @Test
    fun `amount above sanity cap is rejected instead of recorded`() {
        // 订单号/手机号被当金额时宁可漏记也不记错账
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "已支付¥9,999,999,999.99"))
    }

    // ---------- 卡尾号：不应被当成金额 ----------

    @Test
    fun `card tail is not parsed as amount`() {
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "微信支付收款尾号1234"))
    }

    @Test
    fun `card tail in parentheses is masked but bank name kept`() {
        val masked = NotificationRules.maskCardTails("【中国银行储蓄卡(8920)】已支付¥12.80")
        assertFalse(masked.contains("8920"))
        // 括号外的银行名保留下来供支付方式识别
        assertTrue(masked.contains("中国银行储蓄卡"))
    }

    @Test
    fun `tail word masking`() {
        assertEquals("尾号****", NotificationRules.maskCardTails("尾号7619"))
        assertEquals("储蓄卡(****)", NotificationRules.maskCardTails("储蓄卡(8920)"))
    }

    // ---------- 文本归一化 ----------

    @Test
    fun `full width digits are normalized`() {
        assertEquals("已支付¥12.80", NotificationRules.normalize("已支付￥１２.８０"))
    }

    @Test
    fun `full width digits can be parsed end to end`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "已支付￥１２.８０")!!
        assertEquals(1280L, r.amountCents)
    }

    @Test
    fun `bracketed count prefix is stripped`() {
        assertEquals("微信支付: 收款12.00元", NotificationRules.normalize("[3条]微信支付: 收款12.00元"))
    }

    @Test
    fun `bare count after app name is stripped`() {
        assertEquals("微信支付 已支付¥12.80", NotificationRules.normalize("微信支付 2条 已支付¥12.80"))
    }

    @Test
    fun `parsing works after count prefix normalization`() {
        val r = parse(NotificationRules.WECHAT, "微信支付", "[3条]微信支付: 收款12.00元")!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(1200L, r.amountCents)
    }

    // ---------- 噪声过滤 ----------

    @Test
    fun `coupon marketing is filtered`() {
        // 含"1元消费券"会被金额正则抓成 ¥1.00 支出，必须靠噪声词拦掉
        assertNull(parse(NotificationRules.ALIPAY, "支付宝", "您有一笔1元消费券待领取，点击领取"))
    }

    @Test
    fun `credit limit notice is filtered`() {
        assertNull(parse(NotificationRules.UNIONPAY, "云闪付", "您尾号1234信用卡可用额度提升至50000元"))
    }

    @Test
    fun `verification code is filtered`() {
        assertNull(parse(NotificationRules.WECHAT, "微信支付", "验证码123456，请勿泄露"))
    }

    @Test
    fun `real payment with coupon tail is still recorded`() {
        // 有交易确认短语就放行：真实通知常带"使用优惠券省 3 元"的尾巴，不能连交易一起丢
        val r = parse(NotificationRules.WECHAT, "微信支付", "已支付¥12.80，使用优惠券省3元")!!
        assertEquals(1280L, r.amountCents)
        assertFalse(NotificationRules.isNoise("已支付¥12.80，使用优惠券省3元"))
    }

    // ---------- 去重键构造 ----------

    @Test
    fun `dedup key format is stable across channels`() {
        assertEquals(
            "com.tencent.mm|EXPENSE|1280|${postTime / 60_000}",
            NotificationRules.dedupKey(NotificationRules.WECHAT, TxType.EXPENSE, false, 1280, postTime),
        )
        assertEquals(
            "com.tencent.mm|INCOME|R|1280|${postTime / 60_000}",
            NotificationRules.dedupKey(NotificationRules.WECHAT, TxType.INCOME, true, 1280, postTime),
        )
    }
}
