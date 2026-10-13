package com.controleco.app.capture

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.controleco.app.BuildConfig
import com.controleco.app.util.AppLog

/**
 * 通知监听服务：用户在系统设置授予"通知使用权"后由系统绑定。
 * 只做两件事：提取通知文本原文（含折叠明细行与副标题），以及**折叠摘要的金额补全**——
 * 摘要自身无金额时，从 `activeNotifications` 找同组子通知，用子通知的文本与时间替代
 * （微信"退款到账通知"等折叠通知实测摘要不带金额，只读摘要必然漏记）。
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
        if (sbn.packageName == packageName) return
        val snapshot = snapshotOf(sbn)
        var title = snapshot.title
        var text = snapshot.mergedText()
        var bigText = snapshot.bigText
        var postTime = sbn.notification.`when`
        var enriched = false

        // 折叠摘要补全：摘要无金额 → 同组子通知里找那条真正的账务明细
        if (GroupNotificationResolver.needsEnrichment(snapshot)) {
            val children = runCatching {
                activeNotifications?.map { snapshotOf(it) }.orEmpty()
            }.getOrElse {
                AppLog.w("Ntf", "activeNotifications unavailable", it)
                emptyList()
            }
            GroupNotificationResolver.pickChild(snapshot, children)?.let { child ->
                title = child.title.ifBlank { title }
                text = child.mergedText()
                bigText = ""
                postTime = child.postTime
                enriched = true
                AppLog.i("Ntf", "grouped summary enriched from child: ${text.take(160)}")
            }
        }

        CaptureCenter.onNotificationPosted(
            packageName = sbn.packageName ?: return,
            title = title,
            text = text,
            bigText = bigText,
            postTime = postTime,
            isDebugBuild = BuildConfig.DEBUG,
            groupEnriched = enriched,
        )
    }

    /** 一条通知的文本快照（与 GroupNotificationResolver 的纯数据类共用同一形态） */
    private fun snapshotOf(sbn: StatusBarNotification): NotificationText {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        // 副标题类（EXTRA_SUB_TEXT / EXTRA_SUMMARY_TEXT）也常带金额，一并纳入
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim().orEmpty()
        // 折叠通知（"[2条]"）的明细行：InboxStyle 在 EXTRA_TEXT_LINES、消息样式在 EXTRA_MESSAGES
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
        return NotificationText(
            key = sbn.key ?: "${sbn.packageName}|${sbn.id}",
            packageName = sbn.packageName.orEmpty(),
            groupKey = sbn.groupKey,
            group = null,
            postTime = sbn.notification.`when`,
            title = title,
            text = NotificationText.joinDistinct(listOf(text, subText, summaryText) + detailLines),
            bigText = bigText,
            ticker = sbn.notification.tickerText?.toString()?.trim().orEmpty(),
        )
    }
}
