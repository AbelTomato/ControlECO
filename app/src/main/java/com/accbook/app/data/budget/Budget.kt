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

    /**
     * 剩余额度三档（绿-蓝-红）：中心饼图与"今日可用"统一用色。
     * 与 State（按消耗比例判定）不同，Tone 直接按剩余比例分档，
     * 让"还剩多少可以花"在视觉上始终有三段清晰的读数。
     */
    enum class Tone { OK, MID, LOW }

    /** 剩余比例 ≥ 50% 充足（绿） */
    const val TONE_OK_RATIO = 0.5f

    /** 剩余比例 20%–50% 中等（黄），< 20% 紧张（红） */
    const val TONE_LOW_RATIO = 0.2f

    fun remainingTone(remainingRatio: Float): Tone = when {
        remainingRatio >= TONE_OK_RATIO -> Tone.OK
        remainingRatio >= TONE_LOW_RATIO -> Tone.MID
        else -> Tone.LOW
    }

    data class Snapshot(
        val budgetCents: Long,
        val spentCents: Long,
        /** 剩余可用（可为负 = 超支） */
        val remainingCents: Long,
        /** 剩余比例 = 剩余 / 预算（可为负），饼图与档位用色的数据源 */
        val remainingRatio: Float,
        /** 剩余额度档位（绿-蓝-红） */
        val tone: Tone,
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
        val remainingRatio = remaining.toDouble() / budgetCents
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
            remainingRatio = remainingRatio.toFloat(),
            tone = remainingTone(remainingRatio.toFloat()),
            daysLeft = daysLeft,
            spentRatio = ratio.toFloat(),
            suggestedDailyCents = suggested,
            state = state,
        )
    }
}
