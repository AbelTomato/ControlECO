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

    /** 数字格（"00.00" 四位，只认数字）→ 万分比：空/全 0 = 0；超过 4 位判无效（null） */
    fun digitsToBp(digits: String): Int? {
        val d = digits.filter { it.isDigit() }
        if (d.length > 4) return null
        if (d.isEmpty()) return 0
        return d.toInt().coerceAtMost(MAX_PERCENT)
    }

    /** 万分比 → 数字格字符串（固定 4 位；0/非法返回空串，让占位符 0 显示出来） */
    fun bpToDigits(bp: Int): String =
        if (bp <= 0) "" else bp.coerceAtMost(MAX_PERCENT).toString().padStart(4, '0')

    /** 万分比 → 展示文本："30.00%"；0 = 未设置 */
    fun formatPercent(bp: Int): String {
        val v = bp.coerceIn(0, MAX_PERCENT)
        return "${v / 100}.${(v % 100).toString().padStart(2, '0')}%"
    }
}
