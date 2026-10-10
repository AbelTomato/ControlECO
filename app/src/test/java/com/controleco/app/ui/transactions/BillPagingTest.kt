package com.controleco.app.ui.transactions

import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 账单分页的三个纯函数：按条数分页取天、跳转某天要加载到多少条、日期头在列表里的位置。
 * 规则：从最近一天往回取、**整天推进**（不切开某一天）、至少给一天。
 */
class BillPagingTest {

    private fun row(id: Long, day: LocalDate): TransactionRow = TransactionRow(
        id = id,
        type = TxType.EXPENSE,
        amountCents = 100,
        timestamp = day.toEpochDay() * 86_400_000L,
        note = "",
        counterparty = "",
        source = TxSource.MANUAL,
        categoryId = 1,
        categoryName = "餐饮",
        accountId = 1,
        accountName = "现金",
        refundMark = RefundMark.NONE,
    )

    // 最近 5 天：3 / 2 / 5 / 1 / 4 条，共 15 条
    private val days = listOf(
        LocalDate.of(2026, 3, 5),
        LocalDate.of(2026, 3, 4),
        LocalDate.of(2026, 3, 3),
        LocalDate.of(2026, 3, 2),
        LocalDate.of(2026, 3, 1),
    )
    private val grouped: Map<LocalDate, List<TransactionRow>> =
        days.zip(listOf(3, 2, 5, 1, 4)).associate { (d, n) -> d to (1..n).map { row(it.toLong() * 10 + n, d) } }

    @Test
    fun `首屏按条数取天_整天推进不切开某一天`() {
        // 目标 3 条：第一天正好 3 条 → 只给第一天
        val (d1, c1) = pageDays(days, grouped, 3)
        assertEquals(listOf(LocalDate.of(2026, 3, 5)), d1)
        assertEquals(3, c1)
        // 目标 4 条：第一天 3 条不够 → 连第二天一起给（共 5 条，不是切 1 条）
        val (d2, c2) = pageDays(days, grouped, 4)
        assertEquals(2, d2.size)
        assertEquals(5, c2)
    }

    @Test
    fun `条数超过总数时把全部天都给出来`() {
        val (d, c) = pageDays(days, grouped, 999)
        assertEquals(5, d.size)
        assertEquals(15, c)
    }

    @Test
    fun `空列表不炸`() {
        val (d, c) = pageDays(emptyList(), emptyMap(), 30)
        assertEquals(0, d.size)
        assertEquals(0, c)
    }

    @Test
    fun `跳到某天要加载到该天的累计条数`() {
        assertEquals(3, rowsUpToDay(days, grouped, LocalDate.of(2026, 3, 5)))
        assertEquals(5, rowsUpToDay(days, grouped, LocalDate.of(2026, 3, 4)))
        assertEquals(15, rowsUpToDay(days, grouped, LocalDate.of(2026, 3, 1)))
        // 不在列表里的天：返回全部条数（= 全加载，由调用方退到最近的一天）
        assertEquals(15, rowsUpToDay(days, grouped, LocalDate.of(2026, 2, 20)))
    }

    @Test
    fun `用累计条数当页大小_正好能把目标那天排出来`() {
        val target = LocalDate.of(2026, 3, 4)
        val limit = rowsUpToDay(days, grouped, target)
        val (shown, _) = pageDays(days, grouped, limit)
        assertEquals(2, shown.size)
        assertEquals(target, shown.last())
    }

    @Test
    fun `日期头位置_每天一个头加当天条数`() {
        // 第 0 天：头部在 0；第 1 天：0(头)+3(行) = 4；第 2 天：4+1+2 = 7
        assertEquals(0, headerIndexForDay(days, grouped, 0))
        assertEquals(4, headerIndexForDay(days, grouped, 1))
        assertEquals(7, headerIndexForDay(days, grouped, 2))
    }
}
