package com.accbook.app.data.budget

import java.time.LocalDate

/**
 * 预算周期（自然月）状态计算——纯函数，可单测。
 *
 * 首页三层信息：
 * 1. 剩余可用 = 预算 − 本月已花（第一视觉层级）
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
        val spentCents: Long,
        /** 剩余可用（可为负 = 超支） */
        val remainingCents: Long,
        /** 本月剩余天数（含今天，最少 1） */
        val daysLeft: Int,
        /** 预算消耗比例 0..1+ */
        val spentRatio: Float,
        /** 建议今日消费上限（剩余 ≤ 0 时为 0） */
        val suggestedDailyCents: Long,
        val state: State,
    )

    /** 预算未设置（≤ 0）返回 null，首页降级为"未设预算"引导 */
    fun compute(budgetCents: Long, spentCents: Long, today: LocalDate): Snapshot? {
        if (budgetCents <= 0) return null
        val daysLeft = (today.lengthOfMonth() - today.dayOfMonth + 1).coerceAtLeast(1)
        val remaining = budgetCents - spentCents
        val ratio = if (budgetCents > 0) spentCents.toDouble() / budgetCents else 0.0
        val state = when {
            remaining <= 0 -> State.CRITICAL
            ratio >= WARNING_RATIO -> State.WARNING
            else -> State.NORMAL
        }
        val suggested = if (remaining > 0) remaining / daysLeft else 0L
        return Snapshot(
            budgetCents = budgetCents,
            spentCents = spentCents,
            remainingCents = remaining,
            daysLeft = daysLeft,
            spentRatio = ratio.toFloat(),
            suggestedDailyCents = suggested,
            state = state,
        )
    }
}
