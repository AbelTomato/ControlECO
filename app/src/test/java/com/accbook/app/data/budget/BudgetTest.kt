package com.accbook.app.data.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BudgetTest {

    private val day = LocalDate.of(2026, 10, 6)

    @Test
    fun `未设置预算返回 null`() {
        assertNull(Budget.compute(0, 1_000, 0, day))
        assertNull(Budget.compute(-1, 1_000, 0, day))
    }

    @Test
    fun `正常状态_剩余与建议日花`() {
        val s = Budget.compute(5_000_00, 2_116_00, 0, day)!!
        assertEquals(2_884_00, s.remainingCents)
        assertEquals(31 - 6 + 1, s.daysLeft) // 10 月 31 天，含今天剩 26 天
        // 2884 元 / 26 天 = 110.92 元 → 11092 分（整数分向下取整）
        assertEquals(2_884_00 / 26, s.suggestedDailyCents)
        assertEquals(Budget.State.NORMAL, s.state)
    }

    @Test
    fun `收入计入剩余预算`() {
        // 预算 1000 − 支出 800 + 收入 200 = 剩余 400
        val s = Budget.compute(1_000_00, 800_00, 200_00, day)!!
        assertEquals(400_00, s.remainingCents)
        // 消耗比例仍按支出算：80% → 警示（收入不掩盖预算消耗）
        assertEquals(Budget.State.WARNING, s.state)
    }

    @Test
    fun `接近上限进入警示_支出比例口径`() {
        // 支出 75% 但收入让剩余为正 → 仍是警示（状态看预算消耗，不看余额）
        assertEquals(Budget.State.WARNING, Budget.compute(1_000_00, 750_00, 500_00, day)!!.state)
        assertEquals(Budget.State.NORMAL, Budget.compute(1_000_00, 749_00, 0, day)!!.state)
    }

    @Test
    fun `用完或超支进入危险_建议日花为 0`() {
        val used = Budget.compute(1_000_00, 1_000_00, 0, day)!!
        assertEquals(Budget.State.CRITICAL, used.state)
        assertEquals(0L, used.suggestedDailyCents)
        assertEquals(0L, used.remainingCents)

        val over = Budget.compute(1_000_00, 1_200_00, 0, day)!!
        assertEquals(Budget.State.CRITICAL, over.state)
        assertEquals(-200_00, over.remainingCents)
        assertEquals(0L, over.suggestedDailyCents)
    }

    @Test
    fun `月末最后一天建议日花等于剩余`() {
        val last = LocalDate.of(2026, 10, 31)
        val s = Budget.compute(1_000_00, 400_00, 0, last)!!
        assertEquals(1, s.daysLeft)
        assertEquals(600_00, s.suggestedDailyCents)
    }

    @Test
    fun `消耗比例按支出计算且与收入无关`() {
        val s = Budget.compute(2_000_00, 1_000_00, 900_00, day)!!
        assertEquals(0.5f, s.spentRatio, 0.0001f)
    }
}
