package com.accbook.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.accbook.app.BuildConfig
import com.accbook.app.util.AppLog

/**
 * 阶段三无障碍采集服务：监听微信/支付宝窗口变化，抓取「支付成功页/账单详情页」文本快照交给 CaptureCenter。
 *
 * 事件风暴对策（层层收窄）：
 * 1. accessibility_service_config 限定包名 + 事件类型（系统级第一道闸）；
 * 2. notificationTimeout=300ms（系统级节流）；
 * 3. 400ms 防抖合并高频 CONTENT_CHANGED（进行中的事件风暴只取最后一个稳定状态）；
 * 4. 页面文本指纹去重（60 秒内同内容只解析一次；超时重新解析——
 *    同额重复支付的成功页内容完全相同，第二笔靠 dedupKey 分钟桶去重而不是指纹）。
 *
 * 采集范围：活动窗口 + 同包名的其余窗口——支付成功页可能是 dialog/非焦点窗口，
 * 永远不会成为 rootInActiveWindow（实测支付宝成功页可被 active root 覆盖，微信待验证）。
 * 只读窗口内容，不申请手势/按键能力；不依赖 viewId，仅收 text 与 contentDescription。
 * 节点不显式 recycle：API 33+ 已废弃该操作，旧版本节点本身进内部池复用，不做也安全。
 */
class AccBookAccessibilityService : AccessibilityService() {

    companion object {
        private const val DEBOUNCE_MS = 400L
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 30
        private const val FINGERPRINT_CAP = 64
        private const val FINGERPRINT_TTL_MS = 60_000L
        private const val EVENT_LOG_INTERVAL_MS = 2_000L

        fun serviceComponent(context: Context): ComponentName =
            ComponentName(context, AccBookAccessibilityService::class.java)

        /** 系统无障碍设置中是否已开启本服务（安卓 13+ 侧载需先「允许受限制的设置」才出现在列表里） */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = serviceComponent(context).flattenToString()
            return enabled.split(':').any { it.trim().equals(me, ignoreCase = true) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var collectScheduled = false

    /** 最近一次事件的包名（采集时只取该包名的窗口，避免混入其他应用的后台窗口文本） */
    private var pendingPkg: String? = null

    /** 事件到达诊断日志节流：区分"事件根本没来"与"事件来了但页面没匹配" */
    private val lastEventLogAt = HashMap<String, Long>()

    /** 已处理页面文本指纹 → 处理时刻（elapsedRealtime） */
    private val recentFingerprints = object : LinkedHashMap<Int, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>): Boolean = size > FINGERPRINT_CAP
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureCenter.markA11yConnected(true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        CaptureCenter.markA11yConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        CaptureCenter.markA11yConnected(false)
        super.onDestroy()
    }

    override fun onInterrupt() {
        AppLog.w("Page", "a11y interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        // config 已限包名，这里再挡一层，防配置失效或系统行为差异
        if (pkg !in NotificationRules.knownPackages) return
        // 抓取开关全关时不白耗控件树遍历
        if (!CaptureCenter.isA11yCaptureEnabled() && !CaptureCenter.isDebugCaptureAll(BuildConfig.DEBUG)) return

        // 事件到达诊断（2s 节流）：微信页面没捕获时，先看这行有没有出现
        val now = SystemClock.elapsedRealtime()
        if (now - (lastEventLogAt[pkg] ?: 0L) > EVENT_LOG_INTERVAL_MS) {
            lastEventLogAt[pkg] = now
            AppLog.d("Page", "a11y event pkg=$pkg type=0x${Integer.toHexString(event.eventType)}")
        }

        pendingPkg = pkg
        scheduleCollect()
    }

    private fun scheduleCollect() {
        if (collectScheduled) return
        collectScheduled = true
        handler.postDelayed({
            collectScheduled = false
            collectCurrentPage()
        }, DEBOUNCE_MS)
    }

    private fun collectCurrentPage() {
        val pkg = pendingPkg ?: return

        val texts = ArrayList<String>(160)
        // 活动窗口优先
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() == pkg) collectTexts(root, 0, texts)
        }
        // 同包名的其余窗口：支付成功页可能是 dialog/非焦点窗口，不会成为 active root
        if (texts.size < MAX_NODES) {
            runCatching {
                for (window in windows) {
                    if (texts.size >= MAX_NODES) break
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = window.root ?: continue
                    if (root.packageName?.toString() != pkg) continue
                    collectTexts(root, 0, texts)
                }
            }.onFailure { AppLog.w("Page", "windows enumerate failed", it) }
        }
        if (texts.isEmpty()) {
            AppLog.d("Page", "collect empty pkg=$pkg（窗口内容不可读？）")
            return
        }

        val fingerprint = texts.hashCode()
        val now = SystemClock.elapsedRealtime()
        synchronized(recentFingerprints) {
            val prev = recentFingerprints[fingerprint]
            if (prev != null && now - prev < FINGERPRINT_TTL_MS) {
                AppLog.d("Page", "skip same page fingerprint pkg=$pkg nodes=${texts.size}")
                return
            }
            recentFingerprints[fingerprint] = now
        }

        // debug 构建全量打印页面快照（tag=AccBook [Page]），真实页面样本靠它收集
        AppLog.d("Page", "snapshot pkg=$pkg nodes=${texts.size} texts=[${texts.joinToString(" | ").take(1200)}]")
        CaptureCenter.onPageCaptured(pkg, texts, System.currentTimeMillis(), BuildConfig.DEBUG)
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<String>) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), depth + 1, out)
        }
    }
}
