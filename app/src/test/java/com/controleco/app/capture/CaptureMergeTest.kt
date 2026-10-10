package com.controleco.app.capture

import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨通道合并：10s 窗口内同包名 + 同方向视为同一笔；页面信息优先（覆盖通知侧的稀疏值与金额）。
 */
class CaptureMergeTest {

    private val base = 1_000_000L

    private fun pending(
        id: Long = 1,
        packageName: String = NotificationRules.WECHAT,
        type: TxType = TxType.EXPENSE,
        amount: Long = 1290,
        createdAt: Long = base,
        status: PendingStatus = PendingStatus.PENDING,
        counterparty: String = "",
        note: String = "微信支付",
        ruleName: String = "微信·支付",
        timestamp: Long = base,
    ) = PendingTransactionEntity(
        id = id,
        type = type,
        amountCents = amount,
        timestamp = timestamp,
        counterparty = counterparty,
        note = note,
        sourcePackage = packageName,
        ruleName = ruleName,
        dedupKey = "$packageName|$type|$amount|${timestamp / 60_000}",
        status = status,
        createdAt = createdAt,
    )

    // ---------- 同笔判定 ----------

    @Test
    fun `同包同向同金额在十秒内命中`() {
        val hit = pickRelatedPending(listOf(pending()), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 3_000)!!
        assertEquals(1L, hit.pending.id)
        assertTrue(hit.sameAmount)
    }

    @Test
    fun `超过十秒不算同一笔`() {
        assertNull(pickRelatedPending(listOf(pending()), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 10_001))
        // 边界：正好 10s 仍算同笔
        assertTrue(
            pickRelatedPending(listOf(pending()), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 10_000) != null,
        )
    }

    @Test
    fun `金额不同时_页面侧唯一候选仍算同笔_页面金额为准`() {
        val hit = pickRelatedPending(
            listOf(pending(amount = 100)), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 1_000,
            allowAmountMismatch = true,
        )!!
        assertFalse(hit.sameAmount)
        assertEquals(100L, hit.pending.amountCents)
    }

    @Test
    fun `金额不同时_通知侧不认作同笔_避免两笔付款并成一条`() {
        // 默认（通知侧）：严格比金额
        assertNull(pickRelatedPending(listOf(pending(amount = 100)), NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 1_000))
    }

    @Test
    fun `金额不同且候选不止一条时不算同笔`() {
        val pool = listOf(pending(id = 1, amount = 100), pending(id = 2, amount = 200))
        assertNull(
            pickRelatedPending(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 1_000, allowAmountMismatch = true),
        )
    }

    @Test
    fun `包名方向或退款窗口不同不算同笔`() {
        val pool = listOf(pending())
        assertNull(pickRelatedPending(pool, NotificationRules.ALIPAY, TxType.EXPENSE, 1290, base + 1_000))
        assertNull(pickRelatedPending(pool, NotificationRules.WECHAT, TxType.INCOME, 1290, base + 1_000))
    }

    @Test
    fun `同金额多条取捕获时刻最近的一条`() {
        val pool = listOf(pending(id = 1, createdAt = base), pending(id = 2, createdAt = base + 8_000))
        val hit = pickRelatedPending(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 9_000)!!
        assertEquals(2L, hit.pending.id)
    }

    @Test
    fun `自动入账条目同样参与合并`() {
        val pool = listOf(pending(status = PendingStatus.AUTO))
        val hit = pickRelatedPending(pool, NotificationRules.WECHAT, TxType.EXPENSE, 1290, base + 500)!!
        assertEquals(PendingStatus.AUTO, hit.pending.status)
    }

    // ---------- 页面优先合并 ----------

    @Test
    fun `页面信息覆盖通知侧的稀疏值`() {
        val existing = pending(counterparty = "", note = "微信支付", ruleName = "微信·支付", timestamp = base)
        val merged = mergePageInfo(
            existing = existing,
            pageAmountCents = 1290,
            pageTxTime = base + 2_000,
            pageCounterparty = "肯德基",
            pageNote = "肯德基",
            pagePaymentMethod = "零钱",
            pageRuleName = "微信·支付成功页",
        )
        assertEquals("肯德基", merged.counterparty)
        assertEquals("肯德基 · 支付方式 零钱", merged.note)
        assertEquals("微信·支付成功页", merged.ruleName)
        assertEquals(base + 2_000, merged.timestamp)
        assertEquals(1290L, merged.amountCents)
    }

    @Test
    fun `页面金额覆盖通知误解析的金额`() {
        val merged = mergePageInfo(
            existing = pending(amount = 100),
            pageAmountCents = 1290,
            pageTxTime = null,
            pageCounterparty = "",
            pageNote = "",
            pagePaymentMethod = null,
            pageRuleName = "",
        )
        assertEquals(1290L, merged.amountCents)
        // 页面没有的保留原值
        assertEquals("微信支付", merged.note)
        assertEquals("微信·支付", merged.ruleName)
        assertEquals(base, merged.timestamp)
    }

    @Test
    fun `备注里已有的支付方式不重复追加`() {
        val merged = mergePageInfo(
            existing = pending(),
            pageAmountCents = 1290,
            pageTxTime = null,
            pageCounterparty = "肯德基",
            pageNote = "扫码付款（零钱）",
            pagePaymentMethod = "零钱",
            pageRuleName = "微信·支付成功页",
        )
        assertEquals("扫码付款（零钱）", merged.note)
    }

    // ---------- 去重键与"同笔家族" ----------

    @Test
    fun `去重键解析兼容退款段的键`() {
        val plain = parseDedupKey("com.tencent.mm|EXPENSE|1290|29500")!!
        assertEquals("com.tencent.mm", plain.packageName)
        assertEquals("EXPENSE", plain.type)
        assertEquals(1290L, plain.amountCents)
        assertEquals(29500L, plain.minute)

        val refund = parseDedupKey("com.tencent.mm|INCOME|R|1290|29500")!!
        assertEquals("INCOME", refund.type)
        assertEquals(1290L, refund.amountCents)
        assertEquals(29500L, refund.minute)
    }

    @Test
    fun `去重键解析拒绝残缺键`() {
        assertNull(parseDedupKey("com.tencent.mm|EXPENSE|1290"))
        assertNull(parseDedupKey("com.tencent.mm|EXPENSE|abc|29500"))
    }

    @Test
    fun `同笔家族不比金额_跨分钟容错一分钟`() {
        val page = parseDedupKey("com.tencent.mm|EXPENSE|1290|29500")!!
        // 通知误解析成 ¥1.00，键里的金额段不同，仍是同一笔
        val notification = parseDedupKey("com.tencent.mm|EXPENSE|100|29500")!!
        assertTrue(samePendingFamily(page, notification))
        // 跨分钟：±1 分钟仍算
        assertTrue(samePendingFamily(page, parseDedupKey("com.tencent.mm|EXPENSE|1290|29501")!!))
        assertTrue(samePendingFamily(page, parseDedupKey("com.tencent.mm|EXPENSE|1290|29499")!!))
        // 隔了两分钟 / 不同包名 / 不同方向：不算
        assertFalse(samePendingFamily(page, parseDedupKey("com.tencent.mm|EXPENSE|1290|29502")!!))
        assertFalse(samePendingFamily(page, parseDedupKey("com.eg.android.AlipayGphone|EXPENSE|1290|29500")!!))
        assertFalse(samePendingFamily(page, parseDedupKey("com.tencent.mm|INCOME|1290|29500")!!))
    }
}
