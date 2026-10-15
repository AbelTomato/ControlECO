package com.controleco.app.data.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 分类预算的"金额 ⇄ 比例"换算与四个数字格（"00.00"）编解码。
 * 比例按万分比存储：1% = 100，30.00% = 3000，上限 99.99% = 9999。
 */
class CategoryBudgetMathTest {

    @Test
    fun `比例折算金额按当前月预算`() {
        // 30% 的 1000 元 = 300 元
        assertEquals(300_00L, CategoryBudgetMath.centsOf(3_000, 1_000_00))
        // 99.99% 的 100 元 = 99.99 元
        assertEquals(99_99L, CategoryBudgetMath.centsOf(9_999, 100_00))
        // 5.55% 的 100 元 = 5.55 元
        assertEquals(5_55L, CategoryBudgetMath.centsOf(555, 100_00))
        // 月预算未设置 → 0
        assertEquals(0L, CategoryBudgetMath.centsOf(3_000, 0L))
        // 未设置比例 → 0
        assertEquals(0L, CategoryBudgetMath.centsOf(0, 1_000_00))
    }

    @Test
    fun `金额反推比例四舍五入`() {
        assertEquals(3_000, CategoryBudgetMath.percentOf(300_00, 1_000_00)!!)
        // 1/3 的月预算 ≈ 33.33%
        assertEquals(3_333, CategoryBudgetMath.percentOf(1_000_00, 3_000_00)!!)
        // 月预算未设置 / 金额非正 → 无法折算
        assertNull(CategoryBudgetMath.percentOf(300_00, 0L))
        assertNull(CategoryBudgetMath.percentOf(0L, 1_000_00))
    }

    @Test
    fun `金额比例往返折算误差不超过一分`() {
        val monthly = 1_234_56L
        for (cents in listOf(1L, 999L, 12_345L, 100_000L)) {
            val bp = CategoryBudgetMath.percentOf(cents, monthly)!!
            val back = CategoryBudgetMath.centsOf(bp, monthly)
            assertTrueClose(cents, back, 1L)
        }
    }

    private fun assertTrueClose(expected: Long, actual: Long, tolerance: Long) {
        val diff = kotlin.math.abs(expected - actual)
        if (diff > tolerance) {
            throw AssertionError("expected $expected ± $tolerance but was $actual")
        }
    }

    @Test
    fun `数字格编码解码`() {
        // "3000" = 30.00% = 3000 万分比
        assertEquals(3_000, CategoryBudgetMath.digitsToBp("3000")!!)
        // "0500" = 05.00% = 500
        assertEquals(500, CategoryBudgetMath.digitsToBp("0500")!!)
        assertEquals(0, CategoryBudgetMath.digitsToBp("")!!)
        assertEquals(0, CategoryBudgetMath.digitsToBp("0000")!!)
        assertEquals("3000", CategoryBudgetMath.bpToDigits(3_000))
        assertEquals("0500", CategoryBudgetMath.bpToDigits(500))
        assertEquals("", CategoryBudgetMath.bpToDigits(0))
    }

    @Test
    fun `数字格上限四位_超限判非法`() {
        assertEquals(9_999, CategoryBudgetMath.digitsToBp("9999")!!)
        assertNull(CategoryBudgetMath.digitsToBp("12345"))
        // 超过 99.99% 的输入被夹到上限（防手滑输入 9999 以上的历史值）
        assertEquals("9999", CategoryBudgetMath.bpToDigits(12_345))
    }

    @Test
    fun `比例展示文本固定两位小数`() {
        assertEquals("30.00%", CategoryBudgetMath.formatPercent(3_000))
        assertEquals("5.00%", CategoryBudgetMath.formatPercent(500))
        assertEquals("99.99%", CategoryBudgetMath.formatPercent(9_999))
        assertEquals("0.00%", CategoryBudgetMath.formatPercent(0))
        assertEquals("99.99%", CategoryBudgetMath.formatPercent(20_000)) // 超上限夹取
    }
}
