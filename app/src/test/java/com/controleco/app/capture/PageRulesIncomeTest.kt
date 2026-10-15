package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收入类页面（转账收款 / 红包 / 收款到账）解析测试。
 * 这些是**通知通道覆盖不到**的场景：微信转账收款、红包拆开、支付宝转账入账都不产生含金额的通知。
 * 与支出路径同为宁漏勿错："对方已收钱"（付款方状态）与"等待对方确认"必须显式排除。
 */
class PageRulesIncomeTest {

    private val capturedAt = 1_700_000_000_000L

    /** 与 CaptureCenter 一致：先试支出方向（支付成功页/账单详情页），没有再试收入方向 */
    private fun parse(pkg: String, texts: List<String>) =
        PageRules.parse(pkg, texts, capturedAt) ?: PageRules.parseIncome(pkg, texts, capturedAt)

    // ---------- 微信转账收款 ----------

    @Test
    fun `wechat transfer received is income`() {
        val r = parse(
            NotificationRules.WECHAT,
            listOf("已收钱", "¥100.00", "已存入零钱", "转账", "来自张三"),
        )!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(10_000L, r.amountCents)
        assertEquals("微信·收款", r.ruleName)
        assertEquals("张三", r.counterparty)
    }

    @Test
    fun `wechat transfer waiting to receive is income`() {
        val r = parse(NotificationRules.WECHAT, listOf("待确认收款", "￥88.88", "转账", "李四向你转账"))!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(8888L, r.amountCents)
    }

    @Test
    fun `wechat transfer sent is not income`() {
        // "对方已收钱"是转账**支出**页的状态，同时含"已收钱"——必须先判排除词
        assertNull(
            parse(
                NotificationRules.WECHAT,
                listOf("转账给", "对方已收钱", "¥100.00", "已收钱", "等待对方确认"),
            ),
        )
    }

    @Test
    fun `wechat transfer pending counterparty is not income`() {
        assertNull(parse(NotificationRules.WECHAT, listOf("转账", "¥50.00", "等待对方确认收款", "你发起了一笔转账")))
    }

    // ---------- 微信红包 ----------

    @Test
    fun `wechat red packet received is income`() {
        val r = parse(NotificationRules.WECHAT, listOf("微信红包", "¥8.88", "已存入零钱", "领取了王五的红包"))!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(888L, r.amountCents)
        assertEquals("微信·红包", r.ruleName)
    }

    @Test
    fun `wechat red packet sent is an expense not an income`() {
        // 发红包是支出：页面会同时出现"发出红包/塞钱进红包"与"支付成功"，
        // 由支出路径按普通支付成功页记账（收入路径必须先把它排除掉）
        val r = parse(NotificationRules.WECHAT, listOf("发出红包", "¥66.00", "塞钱进红包", "支付成功"))!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(6600L, r.amountCents)
    }

    // ---------- 支付宝转账收款 ----------

    @Test
    fun `alipay transfer received is income`() {
        val r = parse(
            NotificationRules.ALIPAY,
            listOf("已收钱", "￥200.00", "转账", "对方已转账", "收款成功"),
        )!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(20_000L, r.amountCents)
        assertEquals("支付宝·收款", r.ruleName)
    }

    @Test
    fun `alipay transfer to others is an expense not an income`() {
        // 向对方转账是支出，走支出路径；收入路径的排除词必须挡住它
        val r = parse(NotificationRules.ALIPAY, listOf("转账给", "￥200.00", "向对方转账", "付款成功"))!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(20_000L, r.amountCents)
    }

    // ---------- 保守性（宁漏勿错） ----------

    @Test
    fun `chat page with red packet word is not captured`() {
        assertNull(parse(NotificationRules.WECHAT, listOf("张三", "给你发了个红包", "哈哈")))
    }

    @Test
    fun `amount in transfer phrasing is not taken as payer`() {
        // 句式兜底："20.06 向你转账"里的金额不是人名；没有真实付款方时留空
        val r = parse(NotificationRules.ALIPAY, listOf("已收钱", "￥20.06", "转账", "20.06 向你转账"))!!
        assertEquals(TxType.INCOME, r.type)
        assertEquals(2006L, r.amountCents)
        assertEquals("", r.counterparty)
    }

    @Test
    fun `wallet detail list is not captured`() {
        // 零钱明细列表：有"已存入零钱"字样但页面不是某笔转账/红包的结果页
        assertNull(parse(NotificationRules.WECHAT, listOf("零钱明细", "已存入零钱", "¥50.00", "余额 ¥1,000.00")))
    }

    @Test
    fun `income page without amount is skipped`() {
        assertNull(parse(NotificationRules.WECHAT, listOf("已收钱", "转账")))
    }

    @Test
    fun `expense page still wins for payment success`() {
        val r = parse(NotificationRules.WECHAT, listOf("支付成功", "￥12.80", "商户", "便利店"))!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1280L, r.amountCents)
    }
}
