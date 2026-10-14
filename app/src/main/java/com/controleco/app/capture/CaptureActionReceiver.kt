package com.controleco.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.controleco.app.util.AppLog

/**
 * 通知栏动作入口（显式组件、非导出）：
 * - 「忽略」：丢弃这条待确认（待确认兜底通知），同时撤销该条通知与可能存在的悬浮卡；
 * - 「撤销」：删除这条已入账的账目（「已记入一笔 X」通知上的入口），不打开 App 一步到位。
 */
class CaptureActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.component?.packageName != context.packageName) {
            AppLog.w("Ntf", "capture action rejected (foreign component ${intent.component})")
            return
        }
        when (intent.action) {
            ACTION_IGNORE -> {
                val dedupKey = intent.getStringExtra(EXTRA_DEDUP_KEY)
                if (dedupKey.isNullOrBlank()) {
                    AppLog.w("Ntf", "ignore action without dedup key")
                    return
                }
                val pendingId = intent.getLongExtra(EXTRA_PENDING_ID, -1L)
                AppLog.i("Ntf", "ignore action from notification pending#$pendingId key=$dedupKey")
                CaptureCenter.ignoreFromNotification(pendingId, dedupKey)
            }
            ACTION_UNDO -> {
                val dedupKey = intent.getStringExtra(EXTRA_DEDUP_KEY).orEmpty()
                val txId = intent.getLongExtra(EXTRA_TX_ID, -1L)
                if (dedupKey.isBlank() && txId <= 0) {
                    AppLog.w("Ntf", "undo action without target")
                    return
                }
                AppLog.i("Ntf", "undo action from notification tx=#$txId key=$dedupKey")
                CaptureCenter.undoEntryFromNotification(txId, dedupKey)
            }
        }
    }

    companion object {
        const val ACTION_IGNORE = "com.controleco.app.action.IGNORE_CAPTURE"
        const val ACTION_UNDO = "com.controleco.app.action.UNDO_ENTRY"
        const val EXTRA_PENDING_ID = "controleco_pending_id"
        const val EXTRA_TX_ID = "controleco_tx_id"
        const val EXTRA_DEDUP_KEY = "controleco_dedup_key"
    }
}
