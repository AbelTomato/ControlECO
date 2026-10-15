package com.controleco.app.data.budget

/** 分类预算的两种设置方案（全局开关，见设置页「分类预算」顶部）：金额（月金额）/ 比例（占月预算百分比） */
enum class CategoryBudgetMode { AMOUNT, PERCENT }

/**
 * 分类预算的"金额 ⇄ 比例"换算与数字格（"00.00"）编解码——纯函数，可单测。
 *
 * 比例按**万分比**（basis points）存储：1% = 100，100.00% = 10000；
 * 结算上限 99.99%（单分类占满月预算没有意义，留 0.01 的空隙给"其他"聚合环）。
 * 比例折算成金额时以**当前生效月预算**（日预算模式 = 日预算 × 本期天数）为基数。
 */
object CategoryBudgetMath {

    /** 100.00% */
    const val PERCENT_SCALE = 10_000

    /** 上限 99.99%（四个数字格 9999） */
    const val MAX_PERCENT = 9_999

    /** 比例（万分比）→ 金额（分）：四舍五入；任一为 0 时返回 0 */
    fun centsOf(percentBp: Int, monthlyBudgetCents: Long): Long =
        if (percentBp <= 0 || monthlyBudgetCents <= 0L) {
            0L
        } else {
            (monthlyBudgetCents * percentBp + PERCENT_SCALE / 2) / PERCENT_SCALE
        }

    /** 金额（分）→ 比例（万分比，四舍五入）：月预算未设置/金额非正时返回 null（无法折算） */
    fun percentOf(cents: Long, monthlyBudgetCents: Long): Int? {
        if (cents <= 0L || monthlyBudgetCents <= 0L) return null
        val bp = ((cents * PERCENT_SCALE + monthlyBudgetCents / 2) / monthlyBudgetCents).toInt()
        return bp.coerceIn(0, MAX_PERCENT)
    }

    // ---------- 四格编辑模型（PercentInput 用；纯函数，可单测） ----------

    /** 四个数字格：十位 / 个位 / 十分位 / 百分位；null = 空格（显示半透明 0 占位） */
    val EMPTY_CELLS: List<Char?> = List(4) { null }

    /**
     * 四格 → 万分比：**按位取值，空格算 0**（位置固定，不因空格前移——
     * 这正是"覆盖输入"的语义基础）。
     */
    fun cellsToBp(cells: List<Char?>): Int {
        var v = 0
        for (i in 0 until 4) v = v * 10 + (cells.getOrNull(i)?.digitToIntOrNull() ?: 0)
        return v.coerceIn(0, MAX_PERCENT)
    }

    /** 万分比 → 四格：先导 0 显示为空格（0 → 全空，让占位符现身） */
    fun bpToCells(bp: Int): List<Char?> {
        val s = bp.coerceIn(0, MAX_PERCENT).toString().padStart(4, '0')
        val firstDigit = s.indexOfFirst { it != '0' }
        if (firstDigit < 0) return EMPTY_CELLS // 全 0 = 四格皆空
        return s.mapIndexed { i, ch -> if (i < firstDigit) null else ch }
    }

    /**
     * 在光标所在格**覆盖输入**一个数字，光标自然后移（停在最后一格）。
     * 例：75.00（['7','5','0','0']）光标在第 0 格输入 4 → 45.00（['4','5','0','0']），光标到第 1 格。
     */
    fun typeDigit(cells: List<Char?>, caret: Int, digit: Char): Pair<List<Char?>, Int> {
        if (!digit.isDigit()) return cells to caret
        val c = caret.coerceIn(0, 3)
        val next = cells.toMutableList()
        while (next.size < 4) next.add(null)
        next[c] = digit
        return next.take(4) to (c + 1).coerceAtMost(3)
    }

    /**
     * 退格：优先删光标**左侧**的数字并后退（与文本输入一致）；
     * 光标已在最左端时删**最右侧**的数字——打开弹窗就是全格值时，第一次退格立刻见效。
     */
    fun backspace(cells: List<Char?>, caret: Int): Pair<List<Char?>, Int> {
        val c = caret.coerceIn(0, 3)
        val next = cells.toMutableList()
        while (next.size < 4) next.add(null)
        if (c > 0) {
            next[c - 1] = null
            return next.take(4) to (c - 1)
        }
        val last = next.indexOfLast { it != null }
        if (last >= 0) next[last] = null
        return next.take(4) to c
    }

    /** 万分比 → 展示文本："30.00%"；0 = 未设置 */
    fun formatPercent(bp: Int): String {
        val v = bp.coerceIn(0, MAX_PERCENT)
        return "${v / 100}.${(v % 100).toString().padStart(2, '0')}%"
    }
}
