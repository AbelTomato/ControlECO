package com.accbook.app.data.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetTest {

    private val day = LocalDate.of(2026, 10, 6)

    @Test
    fun `未设置预算返回 null`() {
        assertNull(Budget.compute(0, 1_000, day))
        assertNull(Budget.compute(-1, 1_000, day))
    }

    @Test
    fun `正常状态_剩余与建议日花`() {
        val s = Budget.compute(5_000_00, 2_116_00, day)!!
        assertEquals(2_884_00, s.remainingCents)
        assertEquals(31 - 6 + 1, s.daysLeft) // 10 月 31 天，含今天剩 26 天
        // 2884 元 / 26 天 = 110.92 元 → 11092 分（整数分向下取整）
        assertEquals(2_884_00 / 26, s.suggestedDailyCents)
        assertEquals(Budget.State.NORMAL, s.state)
    }

    @Test
    fun `接近上限进入警示`() {
        val s = Budget.compute(1_000_00, 750_00, day)!!
        assertEquals(Budget.State.WARNING, s.state)
        // 刚好 74.9% 仍是正常
        assertEquals(Budget.State.NORMAL, Budget.compute(1_000_00, 749_00, day)!!.state)
    }

    @Test
    fun `用完或超支进入危险_建议日花为 0`() {
        val used = Budget.compute(1_000_00, 1_000_00, day)!!
        assertEquals(Budget.State.CRITICAL, used.state)
        assertEquals(0L, used.suggestedDailyCents)
        assertEquals(0L, used.remainingCents)

        val over = Budget.compute(1_000_00, 1_200_00, day)!!
        assertEquals(Budget.State.CRITICAL, over.state)
        assertEquals(-200_00, over.remainingCents)
        assertEquals(0L, over.suggestedDailyCents)
    }

    @Test
    fun `月末最后一天建议日花等于剩余`() {
        val last = LocalDate.of(2026, 10, 31)
        val s = Budget.compute(1_000_00, 400_00, last)!!
        assertEquals(1, s.daysLeft)
        assertEquals(600_00, s.suggestedDailyCents)
    }

    @Test
    fun `消耗比例上限内`() {
        val s = Budget.compute(2_000_00, 1_000_00, day)!!
        assertEquals(0.5f, s.spentRatio, 0.0001f)
    }

    // ---------- 剩余额度三档（饼图 绿-蓝-红 用色） ----------

    @Test
    fun `剩余充足为绿_中等为蓝_紧张为红`() {
        assertEquals(Budget.Tone.OK, Budget.remainingTone(1.0f))
        assertEquals(Budget.Tone.OK, Budget.remainingTone(0.5f))
        assertEquals(Budget.Tone.MID, Budget.remainingTone(0.49f))
        assertEquals(Budget.Tone.MID, Budget.remainingTone(0.2f))
        assertEquals(Budget.Tone.LOW, Budget.remainingTone(0.19f))
        assertEquals(Budget.Tone.LOW, Budget.remainingTone(0.0f))
        assertEquals(Budget.Tone.LOW, Budget.remainingTone(-0.3f))
    }

    @Test
    fun `快照携带剩余比例与档位`() {
        val ok = Budget.compute(1_000_00, 400_00, day)!!
        assertEquals(0.6f, ok.remainingRatio, 0.0001f)
        assertEquals(Budget.Tone.OK, ok.tone)

        val mid = Budget.compute(1_000_00, 900_00, day)!!
        assertEquals(0.1f, mid.remainingRatio, 0.0001f)
        assertEquals(Budget.Tone.LOW, mid.tone)
        assertEquals(Budget.State.WARNING, mid.state) // 消耗 90% 同时 State 为警示

        val over = Budget.compute(1_000_00, 1_200_00, day)!!
        assertEquals(-0.2f, over.remainingRatio, 0.0001f)
        assertEquals(Budget.Tone.LOW, over.tone)
    }

    // ---------- 档位短标签（个人页预算卡/图例） ----------

    @Test
    fun `档位标签与用色边界一致`() {
        assertEquals("充裕", Budget.toneLabel(1.0f))
        assertEquals("充裕", Budget.toneLabel(0.5f))
        assertEquals("留意", Budget.toneLabel(0.49f))
        assertEquals("留意", Budget.toneLabel(0.2f))
        assertEquals("偏紧", Budget.toneLabel(0.19f))
        assertEquals("偏紧", Budget.toneLabel(0f))
        // 超支单独成词，但颜色仍落到 LOW（红）
        assertEquals("已超支", Budget.toneLabel(-0.01f))
        assertEquals(Budget.Tone.LOW, Budget.remainingTone(-0.01f))
    }

    // ---------- 本日剩余（建议日额 − 今日净支出） ----------

    @Test
    fun `本日剩余把今天的净支出整额扣掉`() {
        // 预算 5000、已花 2116（其中今天花了 300），10 月 6 日 → 剩 26 天
        val base = 2_884_00L / 26
        val s = Budget.compute(5_000_00, 2_116_00, day, todaySpentCents = 300_00)!!
        assertEquals(base, s.suggestedDailyCents)
        assertEquals(300_00, s.todaySpentCents)
        assertEquals(base - 300_00, s.todayLeftCents)
    }

    @Test
    fun `今天没花钱时本日剩余就是建议日额`() {
        val s = Budget.compute(5_000_00, 2_116_00, day)!!
        assertEquals(s.suggestedDailyCents, s.todayLeftCents)
    }

    @Test
    fun `今天退款到账会让本日剩余变多`() {
        // 今日净支出为负 = 今天"钱回来了"（退款）：预算口径里退款要正常算回来
        // 注：工资等收入不参与本日剩余（VM 侧只取 支出 − 退款到账）
        val s = Budget.compute(5_000_00, 2_116_00, day, todaySpentCents = -1_000_00)!!
        assertEquals(2_884_00L / 26 + 1_000_00, s.todayLeftCents)
    }

    @Test
    fun `今天花超时本日剩余为负`() {
        val s = Budget.compute(5_000_00, 2_116_00, day, todaySpentCents = 500_00)!!
        assertEquals(2_884_00L / 26 - 500_00, s.todayLeftCents)
        assertTrue(s.todayLeftCents < 0L)
    }

    @Test
    fun `本月已超支时建议日额为 0_本日剩余为负的今日净支出`() {
        val s = Budget.compute(1_000_00, 1_200_00, day, todaySpentCents = 50_00)!!
        assertEquals(0L, s.suggestedDailyCents)
        assertEquals(-50_00, s.todayLeftCents)
    }
}
