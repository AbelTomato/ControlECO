package com.accbook.app.capture

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.accbook.app.AccBookApp
import com.accbook.app.BuildConfig
import com.accbook.app.data.db.AccountDao
import com.accbook.app.data.db.AccountEntity
import com.accbook.app.data.db.CaptureChannel
import com.accbook.app.data.db.CaptureDao
import com.accbook.app.data.db.CapturedNotificationEntity
import com.accbook.app.data.db.CategoryDao
import com.accbook.app.data.db.CategoryEntity
import com.accbook.app.data.db.PendingDao
import com.accbook.app.data.db.PendingTransactionEntity
import com.accbook.app.data.db.RefundMark
import com.accbook.app.data.db.TransactionDao
import com.accbook.app.data.db.TransactionEntity
import com.accbook.app.data.db.TxSource
import com.accbook.app.data.db.TxType
import com.accbook.app.util.AppLog
import com.accbook.app.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 采集中心：通知监听与无障碍页面两条通道的唯一入口。
 * 通道流程一致：原始快照落库（不解析）→ 规则引擎 → 待确认队列。
 * 跨通道去重：dedupKey 同构（包名|TYPE|金额分|分钟），按 ±1 分钟容错查找既有条目——
 * 同一笔交易"通知+页面"双捕获时只保留一条，页面侧信息更全（收款方/支付方式）则回填富化。
 * debug 构建可全量记录（设置开关），Logcat tag=AccBook 便于真实样本调试。
 */
object CaptureCenter {

    private const val PREFS_NAME = "settings"
    private const val KEY_CAPTURE_ENABLED = "auto_capture_enabled"
    private const val KEY_A11Y_CAPTURE_ENABLED = "a11y_capture_enabled"
    private const val KEY_FLOATING_CONFIRM_ENABLED = "floating_confirm_enabled"
    private const val KEY_REFUND_LINK_ENABLED = "refund_link_enabled"
    private const val KEY_CAPTURE_NOTIFY_ENABLED = "capture_notify_enabled"
    private const val KEY_DEBUG_CAPTURE_ALL = "debug_capture_all"

    /** 点击通知/悬浮卡跳转的路由（MainActivity intent extra） */
    const val EXTRA_ROUTE = "accbook.route"
    const val ROUTE_PENDING = "pending"
    const val ROUTE_REFUND_LINK = "refundLink"

    private const val CHANNEL_CAPTURE = "capture"
    private const val NOTIF_TAG = "capture"

    /** 同一内容通知的重发窗口（微信/支付宝常会更新同一条通知导致重复回调） */
    private const val REPOST_WINDOW_MS = 10_000L
    private const val RECENT_KEY_CAP = 256

    /** 捕获日志保留策略 */
    private const val KEEP_DAYS = 7L
    private const val KEEP_ROWS = 2_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recentKeys = LinkedHashMap<String, Long>()

    private lateinit var appContext: Context
    private lateinit var txDao: TransactionDao
    private lateinit var captureDao: CaptureDao
    private lateinit var pendingDao: PendingDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var accountDao: AccountDao
    private val initialized get() = this::appContext.isInitialized

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _a11yConnected = MutableStateFlow(false)
    val a11yConnected: StateFlow<Boolean> = _a11yConnected

    private val _lastEventAt = MutableStateFlow(0L)
    val lastEventAt: StateFlow<Long> = _lastEventAt

    fun init(context: Context) {
        appContext = context.applicationContext
        val db = (appContext as AccBookApp).db
        txDao = db.transactionDao()
        captureDao = db.captureDao()
        pendingDao = db.pendingDao()
        categoryDao = db.categoryDao()
        accountDao = db.accountDao()
        createNotificationChannel()
        cleanup()
    }

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            CHANNEL_CAPTURE,
            "自动记账提醒",
            android.app.NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "捕获到动账/退款时提醒确认入账" }
        runCatching {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .createNotificationChannel(channel)
        }.onFailure { AppLog.w("Ntf", "create channel failed", it) }
    }

    /** 系统通知：点击跳转 App 内对应页面（待确认队列 / 退款关联） */
    private fun postCaptureNotification(title: String, text: String, route: String) {
        if (!isCaptureNotifyEnabled()) return
        if (!canPostNotifications()) {
            AppLog.i("Ntf", "notify skipped (POST_NOTIFICATIONS not granted)")
            return
        }
        val intent = android.content.Intent(appContext, com.accbook.app.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_ROUTE, route)
        val pi = android.app.PendingIntent.getActivity(
            appContext,
            (NOTIF_TAG + route + title + text).hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(appContext, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(appContext).notify(NOTIF_TAG, title.hashCode(), notification)
            AppLog.i("Ntf", "capture notification posted: $title")
        }.onFailure { AppLog.w("Ntf", "post notification failed", it) }
    }

    fun markConnected(connected: Boolean) {
        _connected.value = connected
        AppLog.i("Ntf", "listener connected=$connected")
    }

    fun markA11yConnected(connected: Boolean) {
        _a11yConnected.value = connected
        AppLog.i("Page", "a11y service connected=$connected")
    }

    fun isCaptureEnabled(): Boolean =
        prefs().getBoolean(KEY_CAPTURE_ENABLED, true)

    fun setCaptureEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply()
        AppLog.i("Ntf", "captureEnabled=$enabled")
    }

    fun isA11yCaptureEnabled(): Boolean =
        prefs().getBoolean(KEY_A11Y_CAPTURE_ENABLED, true)

    fun setA11yCaptureEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_A11Y_CAPTURE_ENABLED, enabled).apply()
        AppLog.i("Page", "a11yCaptureEnabled=$enabled")
    }

    fun isFloatingConfirmEnabled(): Boolean =
        prefs().getBoolean(KEY_FLOATING_CONFIRM_ENABLED, true)

    fun setFloatingConfirmEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_FLOATING_CONFIRM_ENABLED, enabled).apply()
        AppLog.i("Page", "floatingConfirmEnabled=$enabled")
    }

    fun isRefundLinkEnabled(): Boolean =
        prefs().getBoolean(KEY_REFUND_LINK_ENABLED, true)

    fun setRefundLinkEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_REFUND_LINK_ENABLED, enabled).apply()
        AppLog.i("Ntf", "refundLinkEnabled=$enabled")
    }

    fun isCaptureNotifyEnabled(): Boolean =
        prefs().getBoolean(KEY_CAPTURE_NOTIFY_ENABLED, true)

    fun setCaptureNotifyEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_CAPTURE_NOTIFY_ENABLED, enabled).apply()
        AppLog.i("Ntf", "captureNotifyEnabled=$enabled")
    }

    /** 悬浮窗被支付保护拦截时，用系统通知提醒用户确认入账（安卓 13+ 需 POST_NOTIFICATIONS） */
    fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

    fun isDebugCaptureAll(isDebugBuild: Boolean): Boolean =
        prefs().getBoolean(KEY_DEBUG_CAPTURE_ALL, isDebugBuild)

    fun setDebugCaptureAll(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_DEBUG_CAPTURE_ALL, enabled).apply()
        AppLog.i("Ntf", "debugCaptureAll=$enabled")
    }

    private fun cleanup() {
        scope.launch {
            captureDao.deleteOlderThan(System.currentTimeMillis() - KEEP_DAYS * 24 * 3_600_000)
            captureDao.keepNewest(KEEP_ROWS)
        }
    }

    // ---------- 通道一：通知监听 ----------

    /** 由通知监听服务回调（主线程）；数据库操作全部转移到 IO */
    fun onNotificationPosted(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        postTime: Long,
        isDebugBuild: Boolean,
    ) {
        _lastEventAt.value = System.currentTimeMillis()

        if (!initialized || packageName == appContext.packageName) return
        if (title.isBlank() && text.isBlank() && bigText.isBlank()) return

        // debug 构建全量 Logcat（用户要求：便于真实样本测试与排查）
        AppLog.d("Ntf", "onPosted pkg=$packageName title=[$title] text=[$text] bigText=[$bigText] postTime=$postTime")

        val key = "$packageName|$title|$text|$bigText"
        val now = SystemClock.elapsedRealtime()
        synchronized(recentKeys) {
            val prev = recentKeys[key]
            if (prev != null && now - prev < REPOST_WINDOW_MS) {
                AppLog.d("Ntf", "skip repost (+${now - prev}ms) pkg=$packageName title=[$title]")
                return
            }
            recentKeys[key] = now
            while (recentKeys.size > RECENT_KEY_CAP) {
                val eldest = recentKeys.keys.first()
                recentKeys.remove(eldest)
            }
        }

        val interested = NotificationRules.isInterested(packageName)
        val captureAll = isDebugCaptureAll(isDebugBuild)
        if (!interested && !captureAll) {
            AppLog.d("Ntf", "skip (not interested) pkg=$packageName title=[$title]")
            return
        }

        scope.launch {
            try {
                val rawId = captureDao.insert(
                    CapturedNotificationEntity(
                        packageName = packageName,
                        title = title,
                        text = text,
                        bigText = bigText,
                        postTime = postTime,
                    ),
                )
                AppLog.d("Ntf", "captured #$rawId pkg=$packageName title=[$title]")

                if (!interested || !isCaptureEnabled()) return@launch

                val parsed = NotificationRules.parse(packageName, title, text, bigText, postTime)
                if (parsed == null) {
                    AppLog.d("Ntf", "no rule matched pkg=$packageName title=[$title] text=[$text]")
                    // 无金额的退款到账通知（微信折叠通知实测样本）→ 转退款关联提醒
                    if (interested && isCaptureEnabled() &&
                        NotificationRules.isAmountlessRefundNotice(packageName, title, text)
                    ) {
                        handleRefundNotice(packageName, rawId)
                    }
                    return@launch
                }
                // 跨通道去重（±1 分钟容错）：通知先到时页面捕获会富化这条，而不是另起一条
                val related = findRelatedPending(
                    packageName, parsed.type, parsed.isRefund, parsed.amountCents, postTime / 60_000,
                )
                if (related != null) {
                    AppLog.d("Ntf", "skip duplicate pending ${parsed.dedupKey} (related ${related.dedupKey})")
                    return@launch
                }
                pendingDao.insert(
                    PendingTransactionEntity(
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        timestamp = postTime,
                        note = parsed.note,
                        sourcePackage = packageName,
                        ruleName = parsed.ruleName,
                        dedupKey = parsed.dedupKey,
                        rawNotificationId = rawId,
                        refundMark = if (parsed.isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                    ),
                )
                captureDao.updateMatch(rawId, parsed.ruleName, true)
                AppLog.i(
                    "Ntf",
                    "queued pending: ${parsed.ruleName} ${parsed.type} ${Money.formatYuan(parsed.amountCents)}" +
                        (if (parsed.isRefund) " [退款]" else "") + " pkg=$packageName",
                )
                postCaptureNotification(
                    "捕获到一笔${if (parsed.type == TxType.EXPENSE) "支出" else if (parsed.isRefund) "退款" else "收入"}",
                    "${NotificationRules.sourceLabel(packageName)} ${Money.formatYuan(parsed.amountCents)} · 点击确认入账",
                    ROUTE_PENDING,
                )
            } catch (e: Exception) {
                AppLog.e("Ntf", "capture handling failed", e)
            }
        }
    }

    // ---------- 退款关联提醒（无金额退款通知） ----------

    /** 微信"退款到账通知"没有金额，无法直接入队：提醒用户进 App 选择被退款的账单 */
    private suspend fun handleRefundNotice(packageName: String, rawId: Long) {
        captureDao.updateMatch(rawId, "退款·待关联", false)
        AppLog.i("Ntf", "amountless refund notice -> link reminder pkg=$packageName")
        if (!isRefundLinkEnabled()) return
        val label = NotificationRules.sourceLabel(packageName)
        if (Settings.canDrawOverlays(appContext)) {
            mainHandler.post {
                FloatingConfirmWindow.showRefundLink(
                    appContext,
                    label,
                    onOpen = { openRoute(ROUTE_REFUND_LINK) },
                    onIgnore = {},
                )
            }
        } else {
            AppLog.i("Page", "refund floating skipped (no overlay permission)")
        }
        postCaptureNotification(
            "${label}退款到账",
            "点击选择被退款的账单，记录退款收入",
            ROUTE_REFUND_LINK,
        )
    }

    /** 从悬浮卡/通知跳进 App 内页面（调用点均在主线程） */
    private fun openRoute(route: String) {
        runCatching {
            appContext.startActivity(
                android.content.Intent(appContext, com.accbook.app.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_ROUTE, route),
            )
        }.onFailure { AppLog.w("Page", "open route $route failed", it) }
    }

    // ---------- 通道二：无障碍页面抓取 ----------

    /** 由无障碍服务回调（主线程），texts 为页面文本快照（控件树顺序） */
    fun onPageCaptured(
        packageName: String,
        texts: List<String>,
        capturedAt: Long,
        isDebugBuild: Boolean,
    ) {
        _lastEventAt.value = System.currentTimeMillis()

        if (!initialized || texts.isEmpty()) return
        val captureAll = isDebugCaptureAll(isDebugBuild)
        if (!isA11yCaptureEnabled() && !captureAll) {
            AppLog.d("Page", "skip (a11y capture disabled) pkg=$packageName")
            return
        }

        scope.launch {
            try {
                val parsed = PageRules.parse(packageName, texts, capturedAt)
                // 感兴趣的页面（或调试全量）才落快照，聊天/朋友圈等噪音不进捕获日志
                if (parsed == null && !captureAll && !PageRules.looksInteresting(texts)) {
                    AppLog.d("Page", "skip (not interesting) pkg=$packageName nodes=${texts.size}")
                    return@launch
                }

                // 支付类页面快照进 INFO 日志（华为默认隐藏 DEBUG 级日志）；聊天等噪音不打
                AppLog.i("Page", "page snapshot pkg=$packageName texts=[${texts.joinToString(" | ").take(1200)}]")

                val rawId = captureDao.insert(
                    CapturedNotificationEntity(
                        packageName = packageName,
                        title = parsed?.ruleName ?: "页面快照",
                        text = texts.joinToString(" | ").take(4000),
                        postTime = capturedAt,
                        source = CaptureChannel.A11Y,
                    ),
                )
                if (parsed == null) {
                    AppLog.i("Page", "page not matched pkg=$packageName（对照上方快照扩规则）")
                    return@launch
                }

                if (!isA11yCaptureEnabled()) {
                    // 采样模式（页面抓取关 + 调试全量开）：只留证据，不入队不弹窗
                    captureDao.updateMatch(rawId, parsed.ruleName, false)
                    AppLog.i("Page", "skip enqueue（设置页「自动记录支付页面」开关关闭）${parsed.ruleName}")
                    return@launch
                }

                val related = findRelatedPending(
                    packageName, parsed.type, isRefund = false, parsed.amountCents,
                    (parsed.txTime ?: capturedAt) / 60_000,
                )
                if (related != null) {
                    // 富化：通知捕获的条目缺收款方/支付方式，页面侧信息补进去；
                    // 去重闸门在入账时（externalId 唯一），悬浮窗照常弹出供用户一键确认
                    val note = buildString {
                        append(related.note)
                        if (parsed.counterparty.isNotBlank() && !related.note.contains(parsed.counterparty)) {
                            if (isNotEmpty()) append(" · ")
                            append(parsed.counterparty)
                        }
                        val method = parsed.paymentMethod
                        if (method != null && !related.note.contains(method)) {
                            if (isNotEmpty()) append(" · ")
                            append("支付方式 $method")
                        }
                    }.take(300)
                    pendingDao.updateEnrichment(
                        related.id,
                        counterparty = parsed.counterparty.ifBlank { related.counterparty },
                        note = note,
                    )
                    captureDao.updateMatch(rawId, parsed.ruleName, true)
                    AppLog.i("Page", "enriched pending #${related.id} ${parsed.ruleName} ${Money.formatYuan(parsed.amountCents)}")
                    maybeShowFloatingConfirm(parsed)
                    return@launch
                }

                pendingDao.insert(
                    PendingTransactionEntity(
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        timestamp = parsed.txTime ?: capturedAt,
                        counterparty = parsed.counterparty,
                        note = parsed.note,
                        sourcePackage = packageName,
                        ruleName = parsed.ruleName,
                        dedupKey = parsed.dedupKey,
                        rawNotificationId = rawId,
                    ),
                )
                captureDao.updateMatch(rawId, parsed.ruleName, true)
                AppLog.i("Page", "queued pending: ${parsed.ruleName} ${Money.formatYuan(parsed.amountCents)} 收款方=${parsed.counterparty}")
                postCaptureNotification(
                    "捕获到一笔支出",
                    "${parsed.sourceLabel} ${Money.formatYuan(parsed.amountCents)}" +
                        (if (parsed.counterparty.isNotBlank()) " · ${parsed.counterparty}" else "") +
                        " · 点击确认入账",
                    ROUTE_PENDING,
                )

                maybeShowFloatingConfirm(parsed)
            } catch (e: Exception) {
                AppLog.e("Page", "page capture handling failed", e)
            }
        }
    }

    /**
     * ±1 分钟容错的待确认条目查找：通知 postTime 与页面捕获/交易时间可能跨分钟。
     * 待确认队列表很小（确认即删），全量内存比对足够。
     */
    private suspend fun findRelatedPending(
        packageName: String,
        type: TxType,
        isRefund: Boolean,
        amountCents: Long,
        minute: Long,
    ): PendingTransactionEntity? {
        val candidates = (minute - 1..minute + 1)
            .map { "$packageName|$type${if (isRefund) "|R" else ""}|$amountCents|$it" }
            .toSet()
        return pendingDao.getAllPending().firstOrNull { it.dedupKey in candidates }
    }

    // ---------- 悬浮确认窗 ----------

    private suspend fun maybeShowFloatingConfirm(parsed: PageRules.ParsedPage) {
        if (!isFloatingConfirmEnabled()) {
            AppLog.i("Page", "floating confirm skipped（设置页「支付完成弹出悬浮窗」开关关闭）")
            return
        }
        if (!Settings.canDrawOverlays(appContext)) {
            AppLog.i("Page", "floating confirm skipped（未授权悬浮窗权限/显示在其他应用上层）")
            return
        }
        val categories = categoryDao.getAll().filter { it.type == parsed.type }
        val accounts = accountDao.getAll()
        if (categories.isEmpty() || accounts.isEmpty()) {
            AppLog.w("Page", "floating confirm skipped (no categories/accounts to prefill)")
            return
        }
        mainHandler.post {
            FloatingConfirmWindow.show(
                appContext,
                parsed,
                categories,
                accounts,
                onConfirm = { categoryId, accountId -> confirmDirect(parsed, categoryId, accountId) },
                onIgnore = {
                    scope.launch {
                        deletePendingFor(parsed)
                        AppLog.i("Page", "floating ignored, removed same-transaction pending entries")
                    }
                },
            )
            // debug 判别器：Toast 是系统级窗口不受支付保护拦截——
            // Toast 可见而悬浮窗不可见 = 悬浮窗被系统/支付保护层拦截的铁证
            if (BuildConfig.DEBUG) {
                android.widget.Toast.makeText(appContext, "自动悬浮窗已尝试弹出（debug）", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 悬浮窗确认：直接入账（不走待确认），并清掉同笔待确认条目（含 ±1 分钟邻键——
     * 通知通道先建的条目 dedupKey 与页面键不同）；externalId 唯一索引兜底防双通道重复。
     */
    private fun confirmDirect(parsed: PageRules.ParsedPage, categoryId: Long, accountId: Long) {
        scope.launch {
            try {
                val externalId = "NTF:${parsed.dedupKey}"
                txDao.insert(
                    TransactionEntity(
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        categoryId = categoryId,
                        accountId = accountId,
                        timestamp = parsed.txTime ?: System.currentTimeMillis(),
                        note = parsed.note,
                        counterparty = parsed.counterparty,
                        source = TxSource.NOTIFICATION,
                        externalId = externalId,
                    ),
                )
                deletePendingFor(parsed)
                toast("已入账 ${Money.formatYuan(parsed.amountCents)}")
                AppLog.i("Page", "floating confirmed externalId=$externalId")
            } catch (e: Exception) {
                // externalId 唯一索引冲突 = 该笔已经入过账（如通知通道先行确认）
                deletePendingFor(parsed)
                toast("该笔已入账，跳过重复记录")
                AppLog.w("Page", "floating confirm rejected", e)
            }
        }
    }

    /** 清掉同笔交易的待确认条目：精确 dedupKey + ±1 分钟邻键 */
    private suspend fun deletePendingFor(parsed: PageRules.ParsedPage) {
        val minute = parsed.dedupKey.substringAfterLast('|').toLongOrNull() ?: return
        val prefix = parsed.dedupKey.substringBeforeLast('|')
        val candidates = (minute - 1..minute + 1).map { "$prefix|$it" }.toSet()
        pendingDao.getAllPending()
            .filter { it.dedupKey in candidates }
            .forEach { pendingDao.deleteById(it.id) }
    }

    /**
     * debug：手动弹出测试悬浮窗（设置页入口）。
     * 不经捕获链路、不入账，确认/忽略只回显——用于区分"悬浮窗层坏了/被拦截"与"页面没捕获到"。
     */
    fun showFloatingTest(onResult: (String) -> Unit) {
        if (!initialized) return
        if (!Settings.canDrawOverlays(appContext)) {
            onResult("未授权悬浮窗权限：设置页「悬浮确认窗」→ 去授权")
            return
        }
        scope.launch {
            showSampleFloating()
            onResult("悬浮窗已尝试弹出；若看不到，多半被支付保护/支付保险箱拦截（见保活设置），或看 Logcat [Page] 日志")
        }
    }

    /**
     * debug：延迟弹测试悬浮窗——按下后 20 秒才弹，用于切到支付宝付款页验证
     * "悬浮窗在支付页上层是否被系统/支付保护拦截"（测试弹窗本身能弹但支付页上不弹的判别）。
     */
    fun scheduleFloatingTest(onResult: (String) -> Unit) {
        if (!initialized) return
        if (!Settings.canDrawOverlays(appContext)) {
            onResult("未授权悬浮窗权限：设置页「悬浮确认窗」→ 去授权")
            return
        }
        mainHandler.postDelayed({
            scope.launch {
                showSampleFloating()
                toast("延迟测试：悬浮窗已尝试弹出")
            }
        }, 20_000L)
        onResult("已安排：20 秒后弹窗，请立刻切到支付宝付款页并停留等待")
    }

    /** debug：延迟 20 秒发一条样例捕获通知（验证通知通道可用性与点击跳转） */
    fun scheduleNotifyTest(onResult: (String) -> Unit) {
        if (!initialized) return
        if (!canPostNotifications()) {
            onResult("未授权通知权限：设置页「动账通知提醒」→ 申请权限")
            return
        }
        mainHandler.postDelayed({
            postCaptureNotification("测试通知（AccBook）", "这是一条样例动账提醒 · 点击应进入待确认队列", ROUTE_PENDING)
            toast("延迟测试：通知已发送")
        }, 20_000L)
        onResult("已安排：20 秒后发送测试通知（可切到任意界面等待）")
    }

    private suspend fun showSampleFloating() {
        val categories = categoryDao.getAll().filter { it.type == TxType.EXPENSE }
        val accounts = accountDao.getAll()
        if (categories.isEmpty() || accounts.isEmpty()) {
            toast("无分类/账户数据，无法预填")
            return
        }
        val parsed = PageRules.ParsedPage(
            type = TxType.EXPENSE,
            amountCents = 1290,
            counterparty = "拼多多平台商户",
            paymentMethod = "中国银行储蓄卡(8920)",
            txTime = null,
            sourceLabel = "测试",
            ruleName = "悬浮窗测试",
            note = "测试数据，确认不会入账",
            dedupKey = "test|floating",
        )
        mainHandler.post {
            FloatingConfirmWindow.show(
                appContext,
                parsed,
                categories,
                accounts,
                onConfirm = { _, _ -> toast("测试窗口：确认回调正常（未入账）") },
                onIgnore = { toast("测试窗口：忽略回调正常") },
            )
        }
    }

    private fun toast(message: String) {
        mainHandler.post {
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
