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
    fun `四格按位取值_空格算 0`() {
        // "7500" = 75.00%
        assertEquals(7_500, CategoryBudgetMath.cellsToBp(listOf('7', '5', '0', '0')))
        // 先导空格 = 0："_500" 与 "0500" 等价（5.00%）
        assertEquals(500, CategoryBudgetMath.cellsToBp(listOf(null, '5', '0', '0')))
        // 中间空格算 0："7_ 0 4" = 70.04%
        assertEquals(7_004, CategoryBudgetMath.cellsToBp(listOf('7', null, '0', '4')))
        assertEquals(0, CategoryBudgetMath.cellsToBp(CategoryBudgetMath.EMPTY_CELLS))
    }

    @Test
    fun `万分比转四格_先导 0 显示为空格`() {
        assertEquals(listOf('7', '5', '0', '0'), CategoryBudgetMath.bpToCells(7_500))
        assertEquals(listOf(null, '5', '0', '0'), CategoryBudgetMath.bpToCells(500))
        assertEquals(listOf(null, null, null, '5'), CategoryBudgetMath.bpToCells(5))
        assertEquals(CategoryBudgetMath.EMPTY_CELLS, CategoryBudgetMath.bpToCells(0))
    }

    @Test
    fun `四格与万分比往返一致`() {
        listOf(0, 5, 50, 500, 5_000, 7_500, 9_999).forEach { bp ->
            assertEquals(bp, CategoryBudgetMath.cellsToBp(CategoryBudgetMath.bpToCells(bp)))
        }
    }

    @Test
    fun `覆盖输入_光标所在格被替换并自然后移`() {
        // 用户实测场景：75.00 光标在第 0 格输入 4 → 45.00，光标到第 1 格
        val cells = listOf<Char?>('7', '5', '0', '0')
        val (next, caret) = CategoryBudgetMath.typeDigit(cells, caret = 0, digit = '4')
        assertEquals(listOf<Char?>('4', '5', '0', '0'), next)
        assertEquals(4_500, CategoryBudgetMath.cellsToBp(next))
        assertEquals(1, caret)

        // 一路输入到末格：光标停在最后一格（继续输入就是覆盖最后一格）
        val (a, c1) = CategoryBudgetMath.typeDigit(cells, caret = 1, digit = '9')
        assertEquals(listOf<Char?>('7', '9', '0', '0'), a)
        assertEquals(2, c1)
        val (b, c2) = CategoryBudgetMath.typeDigit(a, caret = 2, digit = '1')
        assertEquals(listOf<Char?>('7', '9', '1', '0'), b)
        assertEquals(3, c2)
        val (c, c3) = CategoryBudgetMath.typeDigit(b, caret = 3, digit = '2')
        assertEquals(listOf<Char?>('7', '9', '1', '2'), c)
        assertEquals(3, c3)

        // 空四格直接输入：填在光标格，前面保持空格（位置固定，不左移）
        val (d, cd) = CategoryBudgetMath.typeDigit(CategoryBudgetMath.EMPTY_CELLS, caret = 2, digit = '3')
        assertEquals(listOf<Char?>(null, null, '3', null), d)
        assertEquals(30, CategoryBudgetMath.cellsToBp(d)) // 0.30%
        assertEquals(3, cd)
    }

    @Test
    fun `退格_先删光标左侧_最左端时删最右位`() {
        val full = listOf<Char?>('7', '5', '0', '0')

        // 光标在第 1 格：删左侧（第 0 格）并后退 → 光标 0
        val (a, ca) = CategoryBudgetMath.backspace(full, caret = 1)
        assertEquals(listOf<Char?>(null, '5', '0', '0'), a)
        assertEquals(0, ca)

        // 光标在最左端（打开弹窗的初始状态）：删最右侧数字，立刻见效
        val (b, cb) = CategoryBudgetMath.backspace(full, caret = 0)
        assertEquals(listOf<Char?>('7', '5', '0', null), b)
        assertEquals(0, cb)
        val (c2, _) = CategoryBudgetMath.backspace(b, caret = 0)
        assertEquals(listOf<Char?>('7', '5', null, null), c2)

        // 全空再退格：保持原样
        val (empty, _) = CategoryBudgetMath.backspace(CategoryBudgetMath.EMPTY_CELLS, caret = 0)
        assertEquals(CategoryBudgetMath.EMPTY_CELLS, empty)
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
