package com.controleco.app.capture

import com.controleco.app.data.db.TraceOutcome

/**
 * 溯源记录对账结果：把「处置方式 + 联查状态」归成一个结论，UI 只负责显示。
 * 状态不在写入时固化，而是展示时联查——确认入账、回填、删除都能立刻反映到历史记录上。
 */
internal enum class TraceState {
    /** 已入账：账目存在（自动入账 / 经待确认确认 / 另一通道入账后本次合并或跳过） */
    BOOKED,

    /** 待确认：还在队列里等用户处理 */
    PENDING,

    /** 已丢弃：既没有账目也不在队列（用户丢弃，或账目事后被删除） */
    DISCARDED,

    /** 仅提醒：无金额退款通知，本就不产生账目 */
    NOTICE,
}

/**
 * 判定顺序即优先级：退款提醒没有账目概念，先短路；
 * 其余三种（自动入账/入队/合并/重复跳过）指向同一个入账键，
 * 用键解析出的账目或队列状态给出最终结论。
 */
internal fun traceStateOf(outcome: TraceOutcome, linkedTxId: Long?, pendingCount: Int): TraceState = when {
    outcome == TraceOutcome.REFUND_NOTICE -> TraceState.NOTICE
    linkedTxId != null -> TraceState.BOOKED
    pendingCount > 0 -> TraceState.PENDING
    else -> TraceState.DISCARDED
}
