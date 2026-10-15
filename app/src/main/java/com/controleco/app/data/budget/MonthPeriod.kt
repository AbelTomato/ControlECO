package com.controleco.app.data.budget

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 会计月：把"月"统一定义为**同一日期到同一日期**的区间（首日可选 1..30 或「最后一日」）。
 * 首日 = 1 时即自然月；首日 = 2 时 10 月区间是 10-02 ~ 11-02（左闭右开）。
 *
 * 短月规则（静默，不向用户解释）：设为 29/30 号时，遇到没有这一天的小月（2 月、4 月…）
 * 自动用**该月最后一天**作为首日（2 月 30 号 = 2 月末）；「最后一日」则每月都跟月末走。
 * 期间因此可能长短不一（如 2-28 ~ 3-30 是 30 天），这是预期行为。
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
        /** 「最后一日」：跟每个月月末走（2 月自动落到 28/29） */
        const val LAST_DAY = 0
        const val MIN_START_DAY = 1
        const val MAX_START_DAY = 30

        /** 合法化用户输入：1..30，或 [LAST_DAY]（滚轮选项里没有 31 号——月末用「最后一日」表达） */
        fun clampStartDay(day: Int): Int =
            if (day == LAST_DAY || day < MIN_START_DAY) LAST_DAY else day.coerceAtMost(MAX_START_DAY)

        /** 某个自然月的有效首日：29/30 遇短月静默取该月最后一天；[LAST_DAY] 一律取最后一天 */
        fun startDayOf(month: YearMonth, startDay: Int): Int = when (startDay) {
            LAST_DAY -> month.lengthOfMonth()
            else -> startDay.coerceIn(MIN_START_DAY, month.lengthOfMonth())
        }

        /** 滚轮选项：["1日".."30日", "最后一日"] */
        fun startDayOptions(): List<String> =
            (MIN_START_DAY..MAX_START_DAY).map { formatStartDay(it) } + formatStartDay(LAST_DAY)

        fun formatStartDay(startDay: Int): String =
            if (clampStartDay(startDay) == LAST_DAY) "最后一日" else "${startDay.coerceIn(MIN_START_DAY, MAX_START_DAY)}日"

        /** 首日值 → 滚轮下标（与 [startDayAtIndex] 互逆） */
        fun startDayIndex(startDay: Int): Int =
            if (clampStartDay(startDay) == LAST_DAY) MAX_START_DAY else clampStartDay(startDay) - 1

        /** 滚轮下标 → 首日值 */
        fun startDayAtIndex(index: Int): Int =
            if (index >= MAX_START_DAY) LAST_DAY else (index + 1).coerceIn(MIN_START_DAY, MAX_START_DAY)

        private fun startOfMonth(month: YearMonth, startDay: Int): LocalDate =
            month.atDay(startDayOf(month, startDay))

        /** 某一天所在的会计月 */
        fun of(date: LocalDate, startDay: Int): MonthPeriod {
            val m = YearMonth.from(date)
            val thisStart = startOfMonth(m, startDay)
            val start = if (!date.isBefore(thisStart)) thisStart else startOfMonth(m.minusMonths(1), startDay)
            // 结束 = 下一个自然月的有效首日（首日被短月截断时本期自动顺延到那一天）
            val nextMonth = YearMonth.from(start).plusMonths(1)
            return MonthPeriod(start, startOfMonth(nextMonth, startDay))
        }

        /** "月标签"（账单/统计翻月用的 [YearMonth]，语义 = 会计月开始所在的月）对应的区间 */
        fun of(month: YearMonth, startDay: Int): MonthPeriod =
            MonthPeriod(startOfMonth(month, startDay), startOfMonth(month.plusMonths(1), startDay))

        /** 年视角区间：12 个连续会计月（y 年 1 月的有效首日 → 次年 1 月的有效首日） */
        fun year(year: Int, startDay: Int): MonthPeriod =
            MonthPeriod(
                startOfMonth(YearMonth.of(year, 1), startDay),
                startOfMonth(YearMonth.of(year + 1, 1), startDay),
            )

        /** 某一天所属会计月的"月标签"（用于"当前周期 = 翻月选中的月？"判定） */
        fun monthOf(date: LocalDate, startDay: Int): YearMonth = YearMonth.from(of(date, startDay).start)
    }
}
