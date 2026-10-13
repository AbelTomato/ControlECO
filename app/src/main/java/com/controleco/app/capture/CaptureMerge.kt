package com.controleco.app.capture

import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import kotlin.math.abs

/**
 * 跨通道合并：通知监听与无障碍页面抓取可能先后捕获同一笔付款。
 * 判定与合并规则做成纯函数（无 Android 依赖，可直接单测）：
 *
 * - 同一笔 = 同包名 + 同方向 + 两次捕获相隔 ≤ [SAME_ORDER_WINDOW_MS]；
 *   金额相同直接命中；金额不同时（通知误解析历史：支付宝 ¥1.00）只有候选唯一才认作同笔。
 * - 命中后**页面信息优先**：页面侧有商户口/支付方式/金额/时间，覆盖通知侧的稀疏值。
 */

/** 两通道"同时捕获到一笔"的判定窗口：捕获时刻相差 ≤ 10s */
internal const val SAME_ORDER_WINDOW_MS = 10_000L

/** 命中结果：既有条目 + 金额是否一致（不一致 = 页面金额覆盖通知金额） */
internal data class RelatedPending(
    val pending: PendingTransactionEntity,
    val sameAmount: Boolean,
)

/**
 * 在合并池（待确认 + 自动入账条目）里找同一笔：同包名同方向、捕获时刻在窗口内，
 * 金额相同优先取时间最近的。
 *
 * [allowAmountMismatch] 只给"页面捕获"这一侧开：页面是权威来源，金额对不上时
 * （通知误解析历史：支付宝 ¥1.00）窗口内唯一候选即认作同笔、由页面金额覆盖。
 * 通知侧的捕获必须严格比金额——否则同 App 内 10s 内两笔金额不同的付款会被并成一笔，丢记录。
 */
internal fun pickRelatedPending(
    candidates: List<PendingTransactionEntity>,
    packageName: String,
    type: TxType,
    amountCents: Long,
    captureAt: Long,
    windowMs: Long = SAME_ORDER_WINDOW_MS,
    allowAmountMismatch: Boolean = false,
): RelatedPending? {
    val inWindow = candidates.filter {
        it.sourcePackage == packageName && it.type == type && abs(captureAt - it.createdAt) <= windowMs
    }
    inWindow.filter { it.amountCents == amountCents }
        .minByOrNull { abs(captureAt - it.createdAt) }
        ?.let { return RelatedPending(it, sameAmount = true) }
    if (!allowAmountMismatch) return null
    return inWindow.singleOrNull()?.let { RelatedPending(it, sameAmount = false) }
}

/** 页面优先合并后的字段值 */
internal data class PageMerge(
    val amountCents: Long,
    val timestamp: Long,
    val counterparty: String,
    val note: String,
    val ruleName: String,
)

/** 去重键解析：包名 | 方向（退款多一段 R）| 金额分 | 分钟 */
internal data class DedupKey(
    val packageName: String,
    val type: String,
    val amountCents: Long,
    val minute: Long,
)

internal fun parseDedupKey(key: String): DedupKey? {
    val parts = key.split('|')
    if (parts.size < 4) return null
    return DedupKey(
        packageName = parts[0],
        type = parts[1],
        amountCents = parts[parts.size - 2].toLongOrNull() ?: return null,
        minute = parts.last().toLongOrNull() ?: return null,
    )
}

/**
 * 去重键换金额：金额待补录回填后重算键（保留来源段/方向段/退款段与分钟桶，只替换金额段）。
 * 返回 null = 键格式不对（调用方放弃重算、按原键处理）。
 */
internal fun dedupKeyWithAmount(key: String, amountCents: Long): String? {
    val parts = key.split('|')
    if (parts.size < 4) return null
    val head = parts.dropLast(2)
    val minute = parts.last()
    return (head + amountCents.toString() + minute).joinToString("|")
}

/**
 * 同笔家族：同包名 + 同方向 + 分钟相差 ≤1，**不比金额**——
 * 页面优先合并会改写通知侧条目的金额（通知误解析 ¥1.00），完整键就不再相等；
 * 确认/忽略时按家族清理，保证不会留下同一笔的第二条待确认。
 */
internal fun samePendingFamily(a: DedupKey, b: DedupKey): Boolean =
    a.packageName == b.packageName && a.type == b.type && abs(a.minute - b.minute) <= 1

/**
 * 页面信息优先的字段合并：页面有值则覆盖（商户/金额/时间），页面没有的（金额之外的空白）
 * 保留通知侧原值；支付方式并入备注（页面独有信息，不入独立列）。
 */
internal fun mergePageInfo(
    existing: PendingTransactionEntity,
    pageAmountCents: Long,
    pageTxTime: Long?,
    pageCounterparty: String,
    pageNote: String,
    pagePaymentMethod: String?,
    pageRuleName: String,
): PageMerge {
    val base = pageNote.ifBlank { existing.note }
    val note = buildString {
        append(base)
        if (pagePaymentMethod != null && !base.contains(pagePaymentMethod)) {
            if (isNotEmpty()) append(" · ")
            append("支付方式 $pagePaymentMethod")
        }
    }.take(300)
    return PageMerge(
        amountCents = pageAmountCents,
        timestamp = pageTxTime ?: existing.timestamp,
        counterparty = pageCounterparty.ifBlank { existing.counterparty },
        note = note,
        ruleName = pageRuleName.ifBlank { existing.ruleName },
    )
}
