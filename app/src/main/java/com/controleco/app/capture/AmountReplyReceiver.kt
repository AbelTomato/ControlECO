package com.controleco.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money

/**
 * 通知栏「输入金额」的回复入口：方向可辨但金额缺失的捕获不再丢弃，用户在通知上直接打字补录
 * （RemoteInput 回填闭环，见 docs/自动记账捕获机制.md）。
 *
 * 安全：PendingIntent 因为 RemoteInput 必须是 FLAG_MUTABLE（Android 12+ 起为硬要求），
 * 所以这里显式校验 intent 只可能是本应用组件触发的；清单里也声明了 exported="false"。
 */
class AmountReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.component?.packageName != context.packageName) {
            AppLog.w("Ntf", "amount reply rejected (foreign component ${intent.component})")
            return
        }
        if (!CaptureCenter.isAmountReplyEnabled()) return

        val results = RemoteInput.getResultsFromIntent(intent)
        val raw = results?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim()
        if (raw.isNullOrBlank()) {
            AppLog.w("Ntf", "amount reply empty")
            return
        }
        val pendingId = intent.getLongExtra(EXTRA_PENDING_ID, -1L)
        val dedupKey = intent.getStringExtra(EXTRA_DEDUP_KEY).orEmpty()
        if (pendingId <= 0) {
            AppLog.w("Ntf", "amount reply without pending id")
            return
        }

        // 用户可以输入 "12.8" / "12.80" / "¥12.8" / "12.8元" —— 先剥掉非数字字符再严格解析
        val sanitized = raw.replace(Regex("[^0-9.,]"), "")
        val cents = Money.parseCents(sanitized)
        AppLog.i("Ntf", "amount reply raw=[$raw] sanitized=[$sanitized] cents=$cents pending#$pendingId")
        if (cents == null) {
            CaptureCenter.onAmountReplied(pendingId, dedupKey, CaptureCenter.AmountUpdateResult.Invalid)
            return
        }
        CaptureCenter.applyPendingAmount(pendingId, cents) { result ->
            CaptureCenter.onAmountReplied(pendingId, dedupKey, result)
        }
    }

    companion object {
        const val KEY_TEXT_REPLY = "controleco_amount_reply"
        const val EXTRA_PENDING_ID = "controleco_pending_id"
        const val EXTRA_DEDUP_KEY = "controleco_dedup_key"
    }
}
