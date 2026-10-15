package com.controleco.app.data.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 会计月（月首日可配置 1..28）的区间计算：
 * "月"= 同一日期到同一日期（首日 = 2 时 10-02 ~ 11-02），与自然月只在首日 = 1 时重合。
 */
class MonthPeriodTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `首日为一即自然月`() {
        val p = MonthPeriod.of(LocalDate.of(2026, 10, 6), 1)
        assertEquals(LocalDate.of(2026, 10, 1), p.start)
        assertEquals(LocalDate.of(2026, 11, 1), p.endExclusive)
        assertEquals(31, p.days)
        assertEquals(26, p.daysLeft(LocalDate.of(2026, 10, 6)))
        assertEquals(25, p.daysAfter(LocalDate.of(2026, 10, 6)))
    }

    @Test
    fun `首日为二时区间是二号到下月二号`() {
        val p = MonthPeriod.of(LocalDate.of(2026, 10, 6), 2)
        assertEquals(LocalDate.of(2026, 10, 2), p.start)
        assertEquals(LocalDate.of(2026, 11, 2), p.endExclusive)
        assertEquals(31, p.days)
        assertEquals("10月2日–11月1日", p.label())
    }

    @Test
    fun `首日当天归本期_前一天仍归上一期`() {
        val s = 2
        // 10-02（首日当天）：属于 10-02 ~ 11-02
        val onStart = MonthPeriod.of(LocalDate.of(2026, 10, 2), s)
        assertEquals(LocalDate.of(2026, 10, 2), onStart.start)
        // 10-01（首日前一天）：属于 09-02 ~ 10-02
        val beforeStart = MonthPeriod.of(LocalDate.of(2026, 10, 1), s)
        assertEquals(LocalDate.of(2026, 9, 2), beforeStart.start)
        assertEquals(LocalDate.of(2026, 10, 2), beforeStart.endExclusive)
    }

    @Test
    fun `跨年边界_一月五日归上一年十二月的会计月`() {
        // 首日 = 28：1 月 5 日 < 28 → 归 2025-12-28 ~ 2026-01-28
        val p = MonthPeriod.of(LocalDate.of(2026, 1, 5), 28)
        assertEquals(LocalDate.of(2025, 12, 28), p.start)
        assertEquals(LocalDate.of(2026, 1, 28), p.endExclusive)
        assertEquals(YearMonth.of(2025, 12), MonthPeriod.monthOf(LocalDate.of(2026, 1, 5), 28))
    }

    @Test
    fun `坏首日参数被夹到合法范围`() {
        assertEquals(1, MonthPeriod.clampStartDay(0))
        assertEquals(1, MonthPeriod.clampStartDay(-5))
        assertEquals(28, MonthPeriod.clampStartDay(29))
        assertEquals(28, MonthPeriod.clampStartDay(31))
        assertEquals(15, MonthPeriod.clampStartDay(15))
    }

    @Test
    fun `月标签区间与日期区间一致`() {
        val fromMonth = MonthPeriod.of(YearMonth.of(2026, 10), 2)
        val fromDate = MonthPeriod.of(LocalDate.of(2026, 10, 6), 2)
        assertEquals(fromDate, fromMonth)
    }

    @Test
    fun `天数随月份长度变化_日预算按它折算`() {
        // 首日 = 2：9 月是 09-02 ~ 10-02 = 30 天；10 月是 31 天；2 月是 28 天（平年）
        assertEquals(30, MonthPeriod.of(YearMonth.of(2026, 9), 2).days)
        assertEquals(31, MonthPeriod.of(YearMonth.of(2026, 10), 2).days)
        assertEquals(28, MonthPeriod.of(YearMonth.of(2026, 2), 2).days)
    }

    @Test
    fun `已过天数含今天_区间外夹到边界`() {
        val p = MonthPeriod.of(LocalDate.of(2026, 10, 6), 2)
        assertEquals(5, p.daysElapsed(LocalDate.of(2026, 10, 6)))  // 2,3,4,5,6
        assertEquals(1, p.daysElapsed(LocalDate.of(2026, 9, 20)))  // 早于区间 → 夹到 1
        assertEquals(p.days, p.daysElapsed(LocalDate.of(2026, 12, 1))) // 晚于区间 → 夹到满期
    }

    @Test
    fun `在区间内与分桶下标`() {
        val p = MonthPeriod.of(LocalDate.of(2026, 10, 6), 2)
        assertTrue(p.contains(LocalDate.of(2026, 10, 2)))
        assertTrue(p.contains(LocalDate.of(2026, 11, 1)))
        assertFalse(p.contains(LocalDate.of(2026, 10, 1)))
        assertFalse(p.contains(LocalDate.of(2026, 11, 2)))
        assertEquals(1, p.dayIndexOf(LocalDate.of(2026, 10, 2)))
        assertEquals(31, p.dayIndexOf(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun `年区间是十二个连续会计月`() {
        val y = MonthPeriod.year(2026, 15)
        assertEquals(LocalDate.of(2026, 1, 15), y.start)
        assertEquals(LocalDate.of(2027, 1, 15), y.endExclusive)
        assertEquals(365, y.days)
    }

    @Test
    fun `毫秒区间与区间口径一致_左闭右开`() {
        val p = MonthPeriod.of(YearMonth.of(2026, 10), 2)
        val (start, end) = p.toMillisRange(zone)
        assertEquals(p.start.atStartOfDay(zone).toInstant().toEpochMilli(), start)
        assertEquals(p.endExclusive.atStartOfDay(zone).toInstant().toEpochMilli(), end)
        assertEquals(31L * 24 * 3_600_000, end - start)
    }
}
