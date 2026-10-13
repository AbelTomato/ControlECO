package com.controleco.app.capture

import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import kotlin.math.abs

/**
 * 采集层与 UI 层共用的辅助判定（纯函数，便于单测）。
 */

/**
 * 自动入账前的重复预检窗口（±60s 数据库校验）：
 * 合并池里已有"同来源 + 同方向 + 同金额、时间在窗口内"的条目时不再静默入账——
 * 重复通知/分钟边界重发不会造成双记账；调用方回落到"入队 + 提醒"，用户仍然看得到这笔。
 */
internal const val AUTO_ENTRY_DEDUP_WINDOW_MS = 60_000L

internal fun hasRecentSameEntry(
    candidates: List<PendingTransactionEntity>,
    sourcePackage: String,
    type: TxType,
    amountCents: Long,
    now: Long,
    windowMs: Long = AUTO_ENTRY_DEDUP_WINDOW_MS,
): Boolean = candidates.any {
    it.sourcePackage == sourcePackage && it.type == type && it.amountCents == amountCents &&
        abs(now - it.createdAt) <= windowMs
}
