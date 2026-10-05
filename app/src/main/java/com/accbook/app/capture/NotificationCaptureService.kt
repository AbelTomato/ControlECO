package com.accbook.app.capture

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.accbook.app.BuildConfig
import com.accbook.app.util.AppLog

/**
 * 通知监听服务：用户在系统设置授予"通知使用权"后由系统绑定。
 * 只做一件事：提取通知文本原文，交给 CaptureCenter。
 */
class NotificationCaptureService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        CaptureCenter.markConnected(true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        CaptureCenter.markConnected(false)
        AppLog.w("Ntf", "listener disconnected, requesting rebind")
        runCatching { requestRebind(ComponentName(this, NotificationCaptureService::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        // 折叠通知（"[2条]"）的明细行：InboxStyle 在 EXTRA_TEXT_LINES、消息样式在 EXTRA_MESSAGES，
        // 金额常藏在明细行里，一并并入 text 供规则引擎解析
        val detailLines = buildList {
            runCatching {
                extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    ?.forEach { line -> line?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) } }
            }
            runCatching {
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { parcels ->
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(parcels)
                        .forEach { message ->
                            message.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                }
            }
        }

        CaptureCenter.onNotificationPosted(
            packageName = sbn.packageName ?: return,
            title = title,
            text = (listOf(text) + detailLines).filter { it.isNotBlank() }.joinToString(" ").trim(),
            bigText = bigText,
            postTime = sbn.notification.`when`,
            isDebugBuild = BuildConfig.DEBUG,
        )
    }
}
