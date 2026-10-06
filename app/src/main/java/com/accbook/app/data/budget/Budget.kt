package com.accbook.app.data.budget

import java.time.LocalDate

/**
 * 预算周期（自然月）状态计算——纯函数，可单测。
 *
 * 首页三层信息：
 * 1. 剩余预算 = 预算 + 当月收入 − 当月支出（金币堆资源池口径：收入让资源池变大，退款收入天然计入）
 * 2. 本月已花 / 预算消耗进度
 * 3. 今天建议最多花 = 剩余 ÷ 本月剩余天数（含今天）
 *
 * 消费状态三档：Normal（充足）/ Warning（接近上限）/ Critical（用完或超支），
 * 只通过颜色与简短文案表达，不做情绪化措辞。
 */
object Budget {

    enum class State { NORMAL, WARNING, CRITICAL }

    /** Warning 阈值：已消耗预算比例达到 75% 时进入警示 */
    const val WARNING_RATIO = 0.75

    data class Snapshot(
        val budgetCents: Long,
        val expenseCents: Long,
        val incomeCents: Long,
        /** 剩余预算（可为负 = 超支；收入多时可超过预算本身） */
        val remainingCents: Long,
        /** 本月剩余天数（含今天，最少 1） */
        val daysLeft: Int,
        /** 预算消耗比例 = 支出 / 预算，0..1+（与收入无关，用于状态判定） */
        val spentRatio: Float,
        /** 建议今日消费上限（剩余 ≤ 0 时为 0） */
        val suggestedDailyCents: Long,
        val state: State,
    )

    /** 预算未设置（≤ 0）返回 null，首页降级为"未设预算"引导 */
    fun compute(budgetCents: Long, expenseCents: Long, incomeCents: Long, today: LocalDate): Snapshot? {
        if (budgetCents <= 0) return null
        val daysLeft = (today.lengthOfMonth() - today.dayOfMonth + 1).coerceAtLeast(1)
        val remaining = budgetCents + incomeCents - expenseCents
        val ratio = expenseCents.toDouble() / budgetCents
        val state = when {
            remaining <= 0 -> State.CRITICAL
            ratio >= WARNING_RATIO -> State.WARNING
            else -> State.NORMAL
        }
        val suggested = if (remaining > 0) remaining / daysLeft else 0L
        return Snapshot(
            budgetCents = budgetCents,
            expenseCents = expenseCents,
            incomeCents = incomeCents,
            remainingCents = remaining,
            daysLeft = daysLeft,
            spentRatio = ratio.toFloat(),
            suggestedDailyCents = suggested,
            state = state,
        )
    }
}
