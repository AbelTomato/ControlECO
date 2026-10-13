package com.controleco.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.controleco.app.util.AppLog

/**
 * 通知栏「忽略」action：直接从通知上丢弃这条待确认，不用打开 App 再找一遍（误报纠错降到一次点击）。
 *
 * 与早期草稿的差异：早期草稿直接删已入账的记录（它没有待确认队列）；本项目的捕获条目都在待确认队列里，
 * 所以这里删的是待确认条目，同时撤销该条通知与可能存在的悬浮确认卡。
 */
class CaptureActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.component?.packageName != context.packageName) {
            AppLog.w("Ntf", "capture action rejected (foreign component ${intent.component})")
            return
        }
        if (intent.action != ACTION_IGNORE) return
        val dedupKey = intent.getStringExtra(EXTRA_DEDUP_KEY)
        if (dedupKey.isNullOrBlank()) {
            AppLog.w("Ntf", "ignore action without dedup key")
            return
        }
        val pendingId = intent.getLongExtra(EXTRA_PENDING_ID, -1L)
        AppLog.i("Ntf", "ignore action from notification pending#$pendingId key=$dedupKey")
        CaptureCenter.ignoreFromNotification(pendingId, dedupKey)
    }

    companion object {
        const val ACTION_IGNORE = "com.controleco.app.action.IGNORE_CAPTURE"
        const val EXTRA_PENDING_ID = "controleco_pending_id"
        const val EXTRA_DEDUP_KEY = "controleco_dedup_key"
    }
}
