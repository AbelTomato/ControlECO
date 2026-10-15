package com.controleco.app.data.budget

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 会计月：把"月"统一定义为**同一日期到同一日期**的区间（首日可配置 1..28）。
 * 首日 = 1 时即自然月；首日 = 2 时 10 月区间是 10-02 ~ 11-02（左闭右开）。
 *
 * 为什么首日限制 1..28：每个月都存在这一天，不需要"短月截断"这类边界规则，
 * "相同日期之间为一月"的约定不会被破掉（见开发日志"月首日"条目）。
 *
 * 区间口径与账单/统计的毫秒区间一致：[start, endExclusive)。
 */
data class MonthPeriod(val start: LocalDate, val endExclusive: LocalDate) {

    /** 本期天数（首日 = 2 时，10 月是 31 天、9 月是 30 天） */
    val days: Int get() = ChronoUnit.DAYS.between(start, endExclusive).toInt()

    /** 剩余天数（含今天，最少 1）——与旧"本月剩余天数"口径一致 */
    fun daysLeft(today: LocalDate): Int =
        ChronoUnit.DAYS.between(today, endExclusive).toInt().coerceAtLeast(1)

    /** 已过天数（含今天，1..days）——统计页"日均"的分母 */
    fun daysElapsed(today: LocalDate): Int =
        (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceIn(1, days)

    /** 今天之后还剩几天（不含今天；首页细环旁的 "N Day" 展示用） */
    fun daysAfter(today: LocalDate): Int =
        ChronoUnit.DAYS.between(today.plusDays(1), endExclusive).toInt().coerceAtLeast(0)

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(endExclusive)

    /** 第几天（1 起；date 在区间外时按边界夹取，柱状图分桶用） */
    fun dayIndexOf(date: LocalDate): Int =
        (ChronoUnit.DAYS.between(start, date).toInt() + 1).coerceIn(1, days)

    fun toMillisRange(zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
        start.atStartOfDay(zone).toInstant().toEpochMilli() to
            endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()

    /** 区间展示（预算卡提示用）："10月2日–11月1日" */
    fun label(): String =
        "${start.monthValue}月${start.dayOfMonth}日–${endExclusive.minusDays(1).monthValue}月${endExclusive.minusDays(1).dayOfMonth}日"

    companion object {
        const val MIN_START_DAY = 1
        const val MAX_START_DAY = 28

        fun clampStartDay(day: Int): Int = day.coerceIn(MIN_START_DAY, MAX_START_DAY)

        /** 某一天所在的会计月 */
        fun of(date: LocalDate, startDay: Int): MonthPeriod {
            val s = clampStartDay(startDay)
            val start = if (date.dayOfMonth >= s) {
                date.withDayOfMonth(s)
            } else {
                date.minusMonths(1).withDayOfMonth(s)
            }
            return MonthPeriod(start, start.plusMonths(1))
        }

        /** "月标签"（账单/统计翻月用的 [YearMonth]，语义 = 会计月开始所在的月）对应的区间 */
        fun of(month: YearMonth, startDay: Int): MonthPeriod {
            val start = month.atDay(clampStartDay(startDay))
            return MonthPeriod(start, start.plusMonths(1))
        }

        /** 年视角区间：12 个连续会计月（y 年 1 月首日 → 次年 1 月首日） */
        fun year(year: Int, startDay: Int): MonthPeriod {
            val s = clampStartDay(startDay)
            return MonthPeriod(LocalDate.of(year, 1, s), LocalDate.of(year + 1, 1, s))
        }

        /** 某一天所属会计月的"月标签"（用于"当前周期 = 翻月选中的月？"判定） */
        fun monthOf(date: LocalDate, startDay: Int): YearMonth = YearMonth.from(of(date, startDay).start)
    }
}
