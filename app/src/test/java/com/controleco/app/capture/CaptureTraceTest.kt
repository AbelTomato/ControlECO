package com.controleco.app.capture

import com.controleco.app.data.db.TraceOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 溯源状态判定：处置方式 + 联查结果（对应账单 / 待确认条目）→ 展示结论。
 * 状态不在写入时固化，因此确认入账、丢弃、删除账单都会立刻反映到历史记录上——
 * 这里锁定的是各处置方式在这些组合下的结论映射。
 */
class CaptureTraceTest {

    // ---------- 已入账 ----------

    @Test
    fun `解析到账单即已入账（四种处置方式通用）`() {
        val outcomes = listOf(
            TraceOutcome.AUTO_ENTRY,
            TraceOutcome.PENDING,
            TraceOutcome.MERGED_PAGE,
            TraceOutcome.SKIPPED_DUPLICATE,
        )
        outcomes.forEach { outcome ->
            assertEquals(TraceState.BOOKED, traceStateOf(outcome, linkedTxId = 42L, pendingCount = 0))
            // 已入账优先于队列残留（理论上两者不会并存，防御性取更确定的结论）
            assertEquals(TraceState.BOOKED, traceStateOf(outcome, linkedTxId = 42L, pendingCount = 1))
        }
    }

    // ---------- 待确认 ----------

    @Test
    fun `无账单但仍在队列入队中（合并与重复跳过同笔同理）`() {
        assertEquals(TraceState.PENDING, traceStateOf(TraceOutcome.PENDING, linkedTxId = null, pendingCount = 1))
        assertEquals(TraceState.PENDING, traceStateOf(TraceOutcome.MERGED_PAGE, linkedTxId = null, pendingCount = 1))
        assertEquals(TraceState.PENDING, traceStateOf(TraceOutcome.SKIPPED_DUPLICATE, linkedTxId = null, pendingCount = 1))
    }

    // ---------- 已丢弃 ----------

    @Test
    fun `无账单也不在队列即已丢弃（含账目事后被删除）`() {
        val outcomes = listOf(
            TraceOutcome.AUTO_ENTRY,
            TraceOutcome.PENDING,
            TraceOutcome.MERGED_PAGE,
            TraceOutcome.SKIPPED_DUPLICATE,
        )
        outcomes.forEach { outcome ->
            assertEquals(TraceState.DISCARDED, traceStateOf(outcome, linkedTxId = null, pendingCount = 0))
        }
    }

    @Test
    fun `退款提醒不产生账目 联查结果如何都只算提醒`() {
        assertEquals(TraceState.NOTICE, traceStateOf(TraceOutcome.REFUND_NOTICE, linkedTxId = null, pendingCount = 0))
        assertEquals(TraceState.NOTICE, traceStateOf(TraceOutcome.REFUND_NOTICE, linkedTxId = 42L, pendingCount = 1))
    }
}
