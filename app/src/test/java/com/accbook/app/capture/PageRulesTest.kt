package com.accbook.app.capture

import com.accbook.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 页面规则引擎测试。输入是无障碍服务收集的页面文本快照（按控件树顺序）。
 * 当前用例基于常见版式的推演样本；真机采样后按捕获日志的真实快照补充/修正（流程见 docs/通知规则维护指南.md）。
 */
class PageRulesTest {

    /** 固定"查看时刻"：2026-10-06 12:35:00 本地时区 */
    private val capturedAt: Long = LocalDateTime.of(2026, 10, 6, 12, 35, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun parse(pkg: String, texts: List<String>, at: Long = capturedAt) =
        PageRules.parse(pkg, texts, at)

    // ---------- 支付成功页 ----------

    @Test
    fun `wechat payment success with separate label nodes`() {
        val r = parse(NotificationRules.WECHAT, listOf("支付成功", "¥12.80", "收款方", "老王奶茶店", "支付方式", "零钱"))!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1280L, r.amountCents)
        assertEquals("老王奶茶店", r.counterparty)
        assertEquals("零钱", r.paymentMethod)
        assertEquals("支付方式 零钱", r.note)
        assertEquals("微信·支付页", r.ruleName)
        // 无交易时间 → 分钟桶用捕获时刻，与通知通道同构
        assertEquals("com.tencent.mm|EXPENSE|1280|${capturedAt / 60_000}", r.dedupKey)
    }

    @Test
    fun `alipay payment success with inline labels`() {
        val r = parse(NotificationRules.ALIPAY, listOf("付款成功", "¥14.90", "收款方：某某百货", "付款方式：余额宝"))!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1490L, r.amountCents)
        assertEquals("某某百货", r.counterparty)
        assertEquals("余额宝", r.paymentMethod)
        assertNull(r.txTime)
    }

    @Test
    fun `labeled amount with thousand separator`() {
        val r = parse(NotificationRules.WECHAT, listOf("支付成功", "付款金额：¥1,280.00", "收款方", "某店"))!!
        assertEquals(128_000L, r.amountCents)
    }

    // ---------- 账单详情页 ----------

    @Test
    fun `wechat bill detail extracts all fields`() {
        val r = parse(
            NotificationRules.WECHAT,
            listOf(
                "账单详情", "¥128.00", "当前状态", "支付成功", "商户全称", "某某超市有限公司",
                "支付方式", "招商银行(1234)", "交易时间", "2026-10-06 12:34:56",
                "交易单号", "4200182202610061234567890",
            ),
        )!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(128_00L, r.amountCents)
        assertEquals("某某超市有限公司", r.counterparty)
        assertEquals("招商银行(1234)", r.paymentMethod)
        val expected = LocalDateTime.of(2026, 10, 6, 12, 34, 56)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, r.txTime)
        assertEquals("微信·账单详情", r.ruleName)
        // 有交易时间 → 分钟桶对齐交易时间，与该笔支付通知天然去重
        assertEquals("com.tencent.mm|EXPENSE|12800|${expected / 60_000}", r.dedupKey)
    }

    @Test
    fun `alipay bill detail with expense tag`() {
        val r = parse(
            NotificationRules.ALIPAY,
            listOf("账单详情", "支出", "¥14.90", "对方", "某某百货商行", "付款方式", "余额宝", "交易时间：2026-10-06 20:30"),
        )!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1490L, r.amountCents)
        assertEquals("某某百货商行", r.counterparty)
        assertEquals("余额宝", r.paymentMethod)
        val expected = LocalDateTime.of(2026, 10, 6, 20, 30, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, r.txTime)
    }

    @Test
    fun `wechat transfer out bill detail keeps expense direction`() {
        // 转账支出页同时出现"对方已收钱"与"收款方"标签——"收款方"不能把方向判成收入，
        // "对方已收钱"不能被当成收款方名称
        val r = parse(
            NotificationRules.WECHAT,
            listOf("账单详情", "¥100.00", "当前状态", "对方已收钱", "收款方", "张三", "支付方式", "零钱", "交易时间", "2026-10-06 09:00"),
        )!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(10_000L, r.amountCents)
        assertEquals("张三", r.counterparty)
        assertEquals("零钱", r.paymentMethod)
    }

    // ---------- 跳过场景（宁漏勿错） ----------

    @Test
    fun `income bill detail skipped`() {
        assertNull(parse(NotificationRules.WECHAT, listOf("账单详情", "¥50.00", "当前状态", "已存入零钱", "付款方", "李四")))
    }

    @Test
    fun `refund page skipped`() {
        assertNull(parse(NotificationRules.ALIPAY, listOf("退款成功", "¥14.90", "对方", "某某百货")))
    }

    @Test
    fun `chat page skipped`() {
        assertNull(parse(NotificationRules.WECHAT, listOf("张三", "哈哈好的", "明天见", "[图片]")))
    }

    @Test
    fun `payment page without amount skipped`() {
        assertNull(parse(NotificationRules.WECHAT, listOf("支付成功", "完成")))
    }

    @Test
    fun `old bill detail skipped`() {
        val threeDaysLater = capturedAt + 3 * 24 * 3_600_000L
        assertNull(
            parse(
                NotificationRules.WECHAT,
                listOf("账单详情", "¥20.00", "当前状态", "支付成功", "支付方式", "零钱", "交易时间", "2026-10-06 12:34"),
                at = threeDaysLater,
            ),
        )
    }

    @Test
    fun `unknown package skipped`() {
        assertNull(parse("com.example.other", listOf("支付成功", "¥12.00")))
    }

    // ---------- 捕获日志入库判定 ----------

    @Test
    fun `looksInteresting distinguishes payment pages from noise`() {
        assertTrue(PageRules.looksInteresting(listOf("支付成功", "¥12.80")))
        assertTrue(PageRules.looksInteresting(listOf("账单详情", "交易单号", "4200182...")))
        assertFalse(PageRules.looksInteresting(listOf("张三", "哈哈好的", "明天见")))
    }
}
