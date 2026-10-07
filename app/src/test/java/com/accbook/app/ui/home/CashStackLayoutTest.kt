package com.accbook.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CashStackLayoutTest {

    @Test
    fun `布局确定性_同参数两次构建完全一致`() {
        val a = CashStackLayout.build(340f)
        val b = CashStackLayout.build(340f)
        assertEquals(a.stacks, b.stacks)
        assertEquals(a.spanDp, b.spanDp, 0.0001f)
    }

    @Test
    fun `容量为三叠乘每叠上限`() {
        assertEquals(24, CashPileSpecs.CAPACITY)
        val layout = CashStackLayout.build(340f)
        assertEquals(CashPileSpecs.STACK_COUNT, layout.stacks.size)
        layout.stacks.forEach { assertEquals(CashPileSpecs.STACK_MAX_NOTES, it.size) }
    }

    @Test
    fun `填充序号交错映射_同叠内层号递增且cy向上`() {
        val layout = CashStackLayout.build(340f)
        for (i in 0 until CashPileSpecs.CAPACITY) {
            val spec = layout.specAt(i)
            val expected = layout.stacks[i % CashPileSpecs.STACK_COUNT][i / CashPileSpecs.STACK_COUNT]
            assertEquals(expected, spec)
        }
        // 同一叠内：层号越高位置越靠上（cy 更小）
        val stack = layout.stacks[0]
        assertTrue(stack[1].cy < stack[0].cy)
        assertTrue(stack[7].cy < stack[6].cy)
    }

    @Test
    fun `槽位分布在画布宽度内`() {
        val width = 360f
        val layout = CashStackLayout.build(width)
        layout.stacks.forEach { stack ->
            stack.forEach { spec ->
                val absCx = kotlin.math.abs(spec.cx) + CashPileSpecs.NOTE_W_DP / 2f
                assertTrue("cx=${spec.cx} 超出画布", absCx <= width / 2f + 1f)
            }
        }
    }

    @Test
    fun `总张数分配_交错填充_小数部分归入对应叠顶层`() {
        val counts = stackCounts(7.5f)
        // 0..6 整张：叠0 得 0,3,6 → 3；叠2 得 2,5 → 2；叠1 得 1,4 → 2，再加 index7 的小数 0.5
        assertEquals(3f, counts[0], 0.0001f)
        assertEquals(2.5f, counts[1], 0.0001f)
        assertEquals(2f, counts[2], 0.0001f)
    }

    @Test
    fun `总张数零为全空_不超过容量`() {
        val empty = stackCounts(0f)
        assertTrue(empty.all { it == 0f })
        val full = stackCounts(24f)
        assertTrue(full.all { it == 8f })
        val over = stackCounts(99f)
        assertTrue(over.all { it <= 8f })
    }
}
