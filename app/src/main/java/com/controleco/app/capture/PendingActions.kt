package com.controleco.app.capture

import com.controleco.app.data.db.PendingDao
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TransactionDao
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.util.AppLog

/**
 * 待确认条目的入账/丢弃动作（采集层与 UI 层共用）。
 *
 * 为什么抽共享层：悬浮确认卡没有 Activity/ViewModel 宿主，确认动作必须在采集层独立可执行；
 * 待确认列表（ViewModel）也要做同一件事。放一处，保证两侧口径一致——
 * externalId 规则、来源标记（TxSource）、退款标记、重复插入的处理方式都只有一份。
 */
internal object PendingActions {

    /**
     * 确认入账：写交易（externalId = "NTF:" + dedupKey，唯一索引兜底防重）+ 删除待确认条目。
     * 返回新交易 id；若该笔已经入过账（重复通知/双通道竞态），唯一索引冲突按"已记录"处理，
     * 返回 null 且不抛异常、不重复写账。
     */
    suspend fun confirm(
        txDao: TransactionDao,
        pendingDao: PendingDao,
        item: PendingTransactionEntity,
        categoryId: Long,
        accountId: Long,
        counterparty: String = item.counterparty,
        amountCents: Long = item.amountCents,
    ): Long? {
        val externalId = "NTF:${item.dedupKey}"
        val txId = try {
            txDao.insert(
                TransactionEntity(
                    type = item.type,
                    amountCents = amountCents,
                    categoryId = categoryId,
                    accountId = accountId,
                    timestamp = item.timestamp,
                    note = item.note.ifBlank { item.ruleName },
                    counterparty = counterparty.trim().ifBlank { item.counterparty },
                    source = CaptureDefaults.txSource(item.sourcePackage),
                    externalId = externalId,
                    refundMark = item.refundMark,
                ),
            )
        } catch (e: Exception) {
            if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                AppLog.i("pending", "already recorded, skip duplicate externalId=$externalId")
                null
            } else {
                throw e
            }
        }
        pendingDao.deleteById(item.id)
        AppLog.i("pending", "confirmed #${item.id} externalId=$externalId tx=$txId")
        return txId
    }

    /** 丢弃（误报纠错）：只删待确认条目，不产生账目 */
    suspend fun discard(pendingDao: PendingDao, id: Long) {
        pendingDao.deleteById(id)
        AppLog.i("pending", "discarded #$id")
    }
}
