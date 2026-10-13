package com.controleco.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.controleco.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 银行动账短信采集：接收短信广播 → BankLedgerRules 解析 → 待确认队列。
 *
 * 只申请 `RECEIVE_SMS`（收新到短信），**不申请 READ_SMS**、不读取历史短信、不解码彩信，
 * 也不需要成为默认短信应用——因此不碰用户既有短信数据（最小必要权限）。
 * 非银行号码/非银行签名的短信在采集层就被丢弃（内容不入库，见 CaptureCenter.onSmsReceived）。
 *
 * 自测中踩过的坑（见未合并分支 feat/capture-refs-integration 的早期草稿）：
 * - 用 `goAsync()` + IO 协程并把结果 await 完再 `finish()`：进程在写入前被杀也不会丢单
 *   （自建 scope 从不取消就会丢单，通道形同虚设）；
 * - 长短信按 `originatingAddress` 拼接后再解析（只取第一条会丢金额）；
 * - 时间取短信自带时间戳而非处理时刻（与通知通道的时间语义一致）；
 * - 不做任何 abortBroadcast：不干扰系统短信应用与用户的短信通知。
 */
class SmsCaptureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // 捕获中心可能尚未初始化（理论上 Application.onCreate 先跑，这里只是兜底），
        // 广播接收器里抛异常会直接崩到用户面前，所以读取开关也包一层。
        val enabled = runCatching { CaptureCenter.isSmsCaptureEnabled() }.getOrDefault(false)
        if (!enabled) return

        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()
            .orEmpty()
        if (messages.isEmpty()) return

        val sender = messages.firstNotNullOfOrNull { it.originatingAddress }.orEmpty()
        // 长短信会被拆成多条，按到达顺序拼接正文
        val body = buildString { messages.forEach { append(it.displayMessageBody.orEmpty()) } }
        if (body.isBlank()) return
        val eventTime = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        AppLog.i("Sms", "received sender=$sender len=${body.length}")
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                CaptureCenter.onSmsReceived(sender, body, eventTime)
            } catch (e: Exception) {
                AppLog.e("Sms", "sms handling failed", e)
            } finally {
                // goAsync 要求尽快 finish（系统给的时间窗约 10 秒），写入完成即释放
                runCatching { pendingResult.finish() }
            }
        }
    }
}
