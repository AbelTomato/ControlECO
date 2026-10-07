package com.accbook.app.data.budget

import kotlin.math.ln
import java.time.YearMonth

/** 首页金币堆数据源：当前自然月的支出/收入合计（两者独立，供结算动画分别表现） */
data class MonthCashFlow(
    val expenseCents: Long,
    val incomeCents: Long,
)

/**
 * 首页结算会话：两次进入 Home 之间的收支变化 → 动画会话的纯计算。
 *
 * 核心约束：expenseDelta 与 incomeDelta 完全独立——同期的"支出 ¥100 + 收入 ¥100"
 * 净变化为 0，但仍必须依次播放两个动画，两个事件都要被用户感知。
 * 任何情况下不得用 netDelta 代替。
 */
object HomeSettlement {

    /** 上一次 Home 完成结算（消费边界推进）时的数据状态。属于 presentation state（存于 VM），进程死亡即失——
     *  失去后下次进入只播入场动画，不补播历史变化，数据永远以数据库为准。 */
    data class HomeSnapshot(
        val month: YearMonth,
        val budgetCents: Long,
        val expenseCents: Long,
        val incomeCents: Long,
    ) {
        fun remainingCents(): Long = budgetCents + incomeCents - expenseCents
    }

    data class HomeSession(
        val expenseDelta: Long,
        val incomeDelta: Long,
        /** 动画起点状态（数字从这里滚动）；无动画场景 = current */
        val from: HomeSnapshot,
        /** 动画终点状态 */
        val to: HomeSnapshot,
    ) {
        val hasDeltas: Boolean get() = expenseDelta > 0 || incomeDelta > 0
    }

    /**
     * 计算结算会话。无快照 / 跨月 / 预算被修改时返回零 delta 会话（from = current，仅播入场动画）：
     * 跨月补播整月变化会造成误导性的巨型动画，预算修改则让 from 状态失去意义。
     */
    fun compute(last: HomeSnapshot?, current: HomeSnapshot): HomeSession {
        if (last == null || last.month != current.month || last.budgetCents != current.budgetCents) {
            return HomeSession(expenseDelta = 0, incomeDelta = 0, from = current, to = current)
        }
        return HomeSession(
            expenseDelta = (current.expenseCents - last.expenseCents).coerceAtLeast(0),
            incomeDelta = (current.incomeCents - last.incomeCents).coerceAtLeast(0),
            from = last,
            to = current,
        )
    }

    /**
     * 预算金额决定视觉池的基准规模，剩余比例决定池内保留程度；金额不是金币数量。
     * 对数规模因子让大预算即使剩余比例较低，也可以表现为更大的资源堆。
     */
    fun remainingToPileCount(remainingCents: Long, monthlyBudgetCents: Long, capacity: Int): Float {
        if (remainingCents <= 0L || monthlyBudgetCents <= 0L || capacity <= 0) return 0f
        val ratio = (remainingCents.toDouble() / monthlyBudgetCents.toDouble()).coerceIn(0.0, 1.0)
        val budgetYuan = monthlyBudgetCents / 100.0
        val budgetScale = (0.12 + 0.18 * ln(1.0 + budgetYuan / 2_000.0))
            .coerceIn(0.12, 1.0)
        val ratioShape = 0.35 + 0.65 * ratio
        return (capacity * budgetScale * ratioShape).toFloat().coerceIn(0f, capacity.toFloat())
    }

    /**
     * 结算纸币计划：金额 → (张数, 面额)。
     * 百元及以上统一用 ¥100 堆叠表示——"堆叠的 100 面值表示 1k/1w"，张数封顶 8 控制绘制压力；
     * 小额收支才使用对应面值（不同颜色与大小的纸币只在小额时出现）。
     * 0/负数返回 0 张（无动画）。
     */
    fun settlementNotePlan(cents: Long): Pair<Int, Denomination> {
        if (cents <= 0) return 0 to Denomination.HUNDRED
        val yuan = cents / 100
        return when {
            yuan >= 100 -> hundredNoteCount(yuan) to Denomination.HUNDRED
            yuan >= 20 -> (yuan / 20).coerceIn(1, 3).toInt() to Denomination.TWENTY
            yuan >= 10 -> 1 to Denomination.TEN
            yuan >= 5 -> 1 to Denomination.FIVE
            else -> 1 to Denomination.ONE
        }
    }

    private fun hundredNoteCount(yuan: Long): Int = when {
        yuan >= 5_000 -> 8
        yuan >= 2_000 -> 7
        yuan >= 1_000 -> 6
        yuan >= 500 -> 5
        yuan >= 300 -> 4
        else -> 3
    }
}

/** 人民币面额（几何与颜色由 UI 按 ordinal 从主题 noteColors 取，保持数据层纯函数） */
enum class Denomination(val yuan: Long, val label: String) {
    ONE(1, "1"),
    FIVE(5, "5"),
    TEN(10, "10"),
    TWENTY(20, "20"),
    FIFTY(50, "50"),
    HUNDRED(100, "100"),
}
