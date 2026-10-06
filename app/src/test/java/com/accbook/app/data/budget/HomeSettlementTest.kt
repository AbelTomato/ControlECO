package com.accbook.app.data.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class HomeSettlementTest {

    private val oct = YearMonth.of(2026, 10)
    private val nov = YearMonth.of(2026, 11)

    private fun snap(month: YearMonth = oct, budget: Long, expense: Long, income: Long) =
        HomeSettlement.HomeSnapshot(month, budget, expense, income)

    // ---------- computeHomeSession ----------

    @Test
    fun `无快照_零delta_从当前状态开始`() {
        val current = snap(budget = 5_000_00, expense = 2_116_00, income = 0)
        val s = HomeSettlement.compute(null, current)
        assertEquals(0L, s.expenseDelta)
        assertEquals(0L, s.incomeDelta)
        assertFalse(s.hasDeltas)
        assertEquals(current, s.from)
    }

    @Test
    fun `跨月不补播历史变化`() {
        val last = snap(month = oct, budget = 5_000_00, expense = 1_000_00, income = 0)
        val current = snap(month = nov, budget = 5_000_00, expense = 3_000_00, income = 0)
        val s = HomeSettlement.compute(last, current)
        assertFalse(s.hasDeltas)
        assertEquals(current, s.from)
    }

    @Test
    fun `预算被修改_不播结算动画`() {
        val last = snap(budget = 5_000_00, expense = 1_000_00, income = 0)
        val current = snap(budget = 4_000_00, expense = 1_500_00, income = 0)
        val s = HomeSettlement.compute(last, current)
        assertFalse(s.hasDeltas)
        assertEquals(current, s.from)
    }

    @Test
    fun `仅支出`() {
        val last = snap(budget = 5_000_00, expense = 1_000_00, income = 0)
        val current = snap(budget = 5_000_00, expense = 1_438_00, income = 0)
        val s = HomeSettlement.compute(last, current)
        assertEquals(438_00, s.expenseDelta)
        assertEquals(0L, s.incomeDelta)
        assertTrue(s.hasDeltas)
        assertEquals(last, s.from)
    }

    @Test
    fun `仅收入`() {
        val last = snap(budget = 5_000_00, expense = 1_000_00, income = 0)
        val current = snap(budget = 5_000_00, expense = 1_000_00, income = 200_00)
        val s = HomeSettlement.compute(last, current)
        assertEquals(0L, s.expenseDelta)
        assertEquals(200_00, s.incomeDelta)
        assertTrue(s.hasDeltas)
    }

    @Test
    fun `支出与收入完全独立_净变化为零也必须都播`() {
        val last = snap(budget = 5_000_00, expense = 1_000_00, income = 0)
        // 支出 100 + 收入 100：净变化 0，但两个 delta 都必须非零
        val current = snap(budget = 5_000_00, expense = 1_100_00, income = 100_00)
        val s = HomeSettlement.compute(last, current)
        assertEquals(100_00, s.expenseDelta)
        assertEquals(100_00, s.incomeDelta)
        assertTrue(s.hasDeltas)
    }

    @Test
    fun `快照剩余口径_预算加收入减支出`() {
        assertEquals(2_884_00, snap(budget = 5_000_00, expense = 2_116_00, income = 0).remainingCents())
        assertEquals(3_000_00, snap(budget = 5_000_00, expense = 2_116_00, income = 116_00).remainingCents())
    }

    // ---------- moneyToVisualCoinCount ----------

    @Test
    fun `零与负数无视觉金币`() {
        assertEquals(0, HomeSettlement.moneyToVisualCoinCount(0))
        assertEquals(0, HomeSettlement.moneyToVisualCoinCount(-100))
    }

    @Test
    fun `小额下限两枚_大额封顶十六枚`() {
        assertEquals(2, HomeSettlement.moneyToVisualCoinCount(1_00))
        assertEquals(16, HomeSettlement.moneyToVisualCoinCount(500_000_00))
        assertEquals(16, HomeSettlement.moneyToVisualCoinCount(Long.MAX_VALUE / 2))
    }

    @Test
    fun `金额越大金币越多_单调不减`() {
        val amounts = longArrayOf(3_00, 20_00, 50_00, 100_00, 200_00, 500_00, 1_000_00, 2_000_00, 8_000_00)
        val counts = amounts.map { HomeSettlement.moneyToVisualCoinCount(it) }
        assertEquals(listOf(2, 4, 5, 6, 8, 9, 11, 13, 16), counts)
        assertTrue(counts.zipWithNext().all { (a, b) -> a <= b })
    }
}
