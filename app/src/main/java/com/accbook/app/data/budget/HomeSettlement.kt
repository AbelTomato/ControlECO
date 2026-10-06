package com.accbook.app.data.budget

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
     * 金额 → 视觉金币数映射：金额越大弹出的金币略多、力度略强，但封顶 16 枚，
     * 避免大额消费产生大量绘制对象。0 返回 0（无动画）。
     */
    fun moneyToVisualCoinCount(cents: Long): Int {
        if (cents <= 0) return 0
        val yuan = cents / 100
        return when {
            yuan >= 5_000 -> 16
            yuan >= 2_000 -> 13
            yuan >= 1_000 -> 11
            yuan >= 500 -> 9
            yuan >= 200 -> 8
            yuan >= 100 -> 6
            yuan >= 50 -> 5
            yuan >= 20 -> 4
            yuan >= 5 -> 3
            else -> 2
        }
    }
}
