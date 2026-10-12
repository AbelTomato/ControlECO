package com.controleco.app.capture

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.controleco.app.AccBookApp
import com.controleco.app.BuildConfig
import com.controleco.app.data.db.AccountDao
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CaptureChannel
import com.controleco.app.data.db.CaptureDao
import com.controleco.app.data.db.CaptureTraceEntity
import com.controleco.app.data.db.CapturedNotificationEntity
import com.controleco.app.data.db.CategoryDao
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.PendingDao
import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TraceDao
import com.controleco.app.data.db.TraceOutcome
import com.controleco.app.data.db.TransactionDao
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 采集中心：通知监听与无障碍页面两条通道的唯一入口。
 * 通道流程一致：原始快照落库（不解析）→ 规则引擎 → 待确认队列。
 * 跨通道去重：两条通道在 10s 内捕获同一笔（同包名 + 同方向 + 金额）只保留一条，
 * **页面信息优先**——页面侧带商户/支付方式，捕获在后时覆盖通知侧的稀疏值与金额（见 CaptureMerge）。
 * 设置开启「自动入账」时识别到即直接记账（不再逐笔确认），条目留 AUTO 记录供页面回填。
 * debug 构建可全量记录（设置开关），Logcat tag=ControlECO 便于真实样本调试。
 * 开发者功能「记录记账来源」开启时，另把每次入账相关捕获的原始来源写入溯源表（见 traceCapture）。
 */
object CaptureCenter {

    private const val PREFS_NAME = "settings"
    private const val KEY_CAPTURE_ENABLED = "auto_capture_enabled"
    private const val KEY_A11Y_CAPTURE_ENABLED = "a11y_capture_enabled"
    private const val KEY_REFUND_LINK_ENABLED = "refund_link_enabled"
    private const val KEY_CAPTURE_NOTIFY_ENABLED = "capture_notify_enabled"
    private const val KEY_AUTO_ENTRY_ENABLED = "auto_entry_enabled"
    private const val KEY_DEBUG_CAPTURE_ALL = "debug_capture_all"
    private const val KEY_DEBUG_CAPTURE_ALL_PAGES = "debug_capture_all_pages"
    private const val KEY_TRACE_ENABLED = "capture_trace_enabled"

    /** 点击通知跳转的路由（MainActivity intent extra） */
    const val EXTRA_ROUTE = "controleco.route"
    const val ROUTE_PENDING = "pending"
    const val ROUTE_REFUND_LINK = "refundLink"

    /** 「已自动入账」横幅直达该笔记录的编辑页（route = 前缀 + 交易 id，追加路由，不改既有契约） */
    const val ROUTE_EDIT_PREFIX = "edit/"

    /** 渠道 v2：横幅提醒（IMPORTANCE_HIGH 才会弹屏幕上方横幅）；旧渠道 capture（DEFAULT 静默）删除 */
    const val CHANNEL_CAPTURE = "capture_alert"
    private const val CHANNEL_LEGACY = "capture"
    private const val NOTIF_TAG = "capture"

    /** 同一内容通知的重发窗口（微信/支付宝常会更新同一条通知导致重复回调） */
    private const val REPOST_WINDOW_MS = 10_000L
    private const val RECENT_KEY_CAP = 256

    /** 捕获日志保留策略 */
    private const val KEEP_DAYS = 7L
    private const val KEEP_ROWS = 2_000

    /** 溯源记录保留条数：只按条数封顶、不按时间清理（开发者证据），可手动清空 */
    private const val TRACE_KEEP_ROWS = 1_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recentKeys = LinkedHashMap<String, Long>()

    private lateinit var appContext: Context
    private lateinit var txDao: TransactionDao
    private lateinit var captureDao: CaptureDao
    private lateinit var pendingDao: PendingDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var accountDao: AccountDao
    private lateinit var traceDao: TraceDao
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
        traceDao = db.traceDao()
        createNotificationChannel()
        cleanup()
    }

    private fun createNotificationChannel() {
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.deleteNotificationChannel(CHANNEL_LEGACY)
            val channel = android.app.NotificationChannel(
                CHANNEL_CAPTURE,
                "自动记账提醒",
                android.app.NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "捕获到动账/退款时横幅提醒确认入账"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }.onFailure { AppLog.w("Ntf", "create channel failed", it) }
    }

    /** 系统通知（横幅）：点击跳转 App 内对应页面（待确认队列 / 退款关联） */
    private fun postCaptureNotification(
        title: String,
        text: String,
        route: String,
        notifId: Int = title.hashCode(),
    ) {
        if (!isCaptureNotifyEnabled()) return
        if (!canPostNotifications()) {
            AppLog.i("Ntf", "notify skipped (POST_NOTIFICATIONS not granted)")
            return
        }
        val intent = android.content.Intent(appContext, com.controleco.app.MainActivity::class.java)
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
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(appContext).notify(NOTIF_TAG, notifId, notification)
            AppLog.i("Ntf", "capture notification posted: $title")
        }.onFailure { AppLog.w("Ntf", "post notification failed", it) }
    }

    /** 待确认入账通知的确定性 id（同一笔被处理时按 id 撤销通知） */
    private fun pendingNotifId(dedupKey: String): Int = "pending|$dedupKey".hashCode()

    private fun pendingNotifText(sourceLabel: String, amountCents: Long, counterparty: String): String =
        "$sourceLabel ${Money.formatYuan(amountCents)}" +
            (if (counterparty.isNotBlank()) " · $counterparty" else "") +
            " · 点击确认入账"

    /**
     * 捕获入队后的提醒：发一条横幅通知（IMPORTANCE_HIGH），点击进待确认队列。
     * 确定化 id（pending|dedupKey），同一笔在待确认里被处理时可直接撤销对应通知。
     */
    private suspend fun announceCapture(
        type: TxType,
        isRefund: Boolean,
        amountCents: Long,
        sourceLabel: String,
        counterparty: String,
        paymentMethod: String?,
        dedupKey: String,
        ruleName: String,
        txTime: Long?,
        note: String,
    ) {
        val notifId = pendingNotifId(dedupKey)
        val kind = when {
            isRefund -> "退款"
            type == TxType.EXPENSE -> "支出"
            else -> "收入"
        }
        postCaptureNotification(
            "捕获到一笔$kind",
            pendingNotifText(sourceLabel, amountCents, counterparty),
            ROUTE_PENDING,
            notifId,
        )
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

    /** 自动入账：识别到付款后按推测分类/账户直接记账，不再逐笔确认（默认关，可选开启） */
    fun isAutoEntryEnabled(): Boolean =
        prefs().getBoolean(KEY_AUTO_ENTRY_ENABLED, false)

    fun setAutoEntryEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_AUTO_ENTRY_ENABLED, enabled).apply()
        AppLog.i("Ntf", "autoEntryEnabled=$enabled")
    }

    /** 横幅通知提醒确认入账（安卓 13+ 需 POST_NOTIFICATIONS 运行时授权） */
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

    /**
     * 捕获所有页面（调试，默认关）：开启后无障碍通道记录**所有应用**的页面快照
     * （配置已不限包名，由服务内运行时判断放行），用于采样新应用的支付页扩规则。
     */
    fun isDebugCaptureAllPages(isDebugBuild: Boolean): Boolean =
        prefs().getBoolean(KEY_DEBUG_CAPTURE_ALL_PAGES, false)

    fun setDebugCaptureAllPages(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_DEBUG_CAPTURE_ALL_PAGES, enabled).apply()
        AppLog.i("Page", "debugCaptureAllPages=$enabled")
    }

    /**
     * 自动记账溯源（开发者功能，默认关）：开启后每次与入账有关的捕获都把
     * 原始来源（通知原文 / 页面快照）连同解析结果与处置方式留档，供回查"这笔账从哪来"。
     * 关闭时零写入（连判断都只有一次 SharedPreferences 读取）。
     */
    fun isTraceEnabled(): Boolean =
        prefs().getBoolean(KEY_TRACE_ENABLED, false)

    fun setTraceEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_TRACE_ENABLED, enabled).apply()
        AppLog.i("Trace", "captureTraceEnabled=$enabled")
    }

    /**
     * 溯源落库：原始来源 + 解析结果 + 处置方式。写入失败只记日志，绝不影响入账主流程
     * （开发者功能不能成为记账链路的故障点）。
     */
    private suspend fun traceCapture(
        channel: CaptureChannel,
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        capturedAt: Long,
        matchedRule: String,
        type: TxType?,
        amountCents: Long,
        counterparty: String = "",
        note: String = "",
        dedupKey: String,
        outcome: TraceOutcome,
    ) {
        if (!isTraceEnabled()) return
        runCatching {
            traceDao.insert(
                CaptureTraceEntity(
                    channel = channel,
                    packageName = packageName,
                    title = title.take(200),
                    text = text.take(4000),
                    bigText = bigText.take(4000),
                    capturedAt = capturedAt,
                    matchedRule = matchedRule,
                    type = type,
                    amountCents = amountCents,
                    counterparty = counterparty,
                    note = note,
                    dedupKey = dedupKey,
                    outcome = outcome,
                ),
            )
        }.onFailure { AppLog.w("Trace", "trace insert failed", it) }
    }

    private fun cleanup() {
        scope.launch {
            captureDao.deleteOlderThan(System.currentTimeMillis() - KEEP_DAYS * 24 * 3_600_000)
            captureDao.keepNewest(KEEP_ROWS)
            traceDao.keepNewest(TRACE_KEEP_ROWS)
            // 自动入账条目只在合并窗口（10s）内有意义，留一天足够，避免长期堆积
            pendingDao.deleteAutoOlderThan(System.currentTimeMillis() - 24 * 3_600_000)
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
                        handleRefundNotice(packageName, rawId, postTime, title, text, bigText)
                    }
                    return@launch
                }
                // 溯源用来源时刻：通知的 when 偶有异常值，退回落库时刻（只影响展示，不回写通知流程）
                val sourceAt = if (postTime > 0) postTime else System.currentTimeMillis()
                // 跨通道去重（10s 窗口）：页面通道先捕获/自动入账了同一笔 → 通知侧信息更少，不覆盖。
                // 通知侧严格比金额：同 App 内 10s 内两笔金额不同的付款不能被并成一笔（丢记录）
                val related = pickRelatedPending(
                    pendingDao.getMergeable(), packageName, parsed.type, parsed.amountCents,
                    System.currentTimeMillis(),
                )
                if (related != null) {
                    AppLog.d("Ntf", "skip duplicate ${parsed.dedupKey} (page side won, related #${related.pending.id})")
                    traceCapture(
                        channel = CaptureChannel.NOTIFICATION,
                        packageName = packageName,
                        title = title, text = text, bigText = bigText,
                        capturedAt = sourceAt,
                        matchedRule = parsed.ruleName,
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        note = parsed.note,
                        dedupKey = related.pending.dedupKey,
                        outcome = TraceOutcome.SKIPPED_DUPLICATE,
                    )
                    return@launch
                }
                // 自动入账（设置开启）：按推测分类/账户直接落账，不进待确认、不发确认提醒
                if (isAutoEntryEnabled() && autoEnter(
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        timestamp = postTime,
                        counterparty = "",
                        note = parsed.note,
                        sourcePackage = packageName,
                        dedupKey = parsed.dedupKey,
                        ruleName = parsed.ruleName,
                        isRefund = parsed.isRefund,
                    )
                ) {
                    captureDao.updateMatch(rawId, parsed.ruleName, true)
                    AppLog.i(
                        "Ntf",
                        "auto entry: ${parsed.ruleName} ${parsed.type} ${Money.formatYuan(parsed.amountCents)}" +
                            (if (parsed.isRefund) " [退款]" else "") + " pkg=$packageName",
                    )
                    traceCapture(
                        channel = CaptureChannel.NOTIFICATION,
                        packageName = packageName,
                        title = title, text = text, bigText = bigText,
                        capturedAt = sourceAt,
                        matchedRule = parsed.ruleName,
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        note = parsed.note,
                        dedupKey = parsed.dedupKey,
                        outcome = TraceOutcome.AUTO_ENTRY,
                    )
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
                traceCapture(
                    channel = CaptureChannel.NOTIFICATION,
                    packageName = packageName,
                    title = title, text = text, bigText = bigText,
                    capturedAt = sourceAt,
                    matchedRule = parsed.ruleName,
                    type = parsed.type,
                    amountCents = parsed.amountCents,
                    note = parsed.note,
                    dedupKey = parsed.dedupKey,
                    outcome = TraceOutcome.PENDING,
                )
                announceCapture(
                    type = parsed.type,
                    isRefund = parsed.isRefund,
                    amountCents = parsed.amountCents,
                    sourceLabel = NotificationRules.sourceLabel(packageName),
                    counterparty = "",
                    paymentMethod = null,
                    dedupKey = parsed.dedupKey,
                    ruleName = parsed.ruleName,
                    txTime = null,
                    note = parsed.note,
                )
            } catch (e: Exception) {
                AppLog.e("Ntf", "capture handling failed", e)
            }
        }
    }

    // ---------- 退款关联提醒（无金额退款通知） ----------

    /** 微信"退款到账通知"没有金额，无法直接入队：发通知提醒用户进 App 选择被退款的账单 */
    private suspend fun handleRefundNotice(
        packageName: String,
        rawId: Long,
        postTime: Long,
        title: String,
        text: String,
        bigText: String,
    ) {
        captureDao.updateMatch(rawId, "退款·待关联", false)
        AppLog.i("Ntf", "amountless refund notice -> link reminder pkg=$packageName")
        // 溯源记录不产生账目：dedupKey 用提醒 id 的同一构造，永远不会和交易对上（状态固定为"仅提醒"）
        traceCapture(
            channel = CaptureChannel.NOTIFICATION,
            packageName = packageName,
            title = title, text = text, bigText = bigText,
            capturedAt = if (postTime > 0) postTime else System.currentTimeMillis(),
            matchedRule = "退款·待关联",
            type = null,
            amountCents = 0,
            dedupKey = "refund|$packageName|$postTime",
            outcome = TraceOutcome.REFUND_NOTICE,
        )
        if (!isRefundLinkEnabled()) return
        val label = NotificationRules.sourceLabel(packageName)
        val notifId = ("refund|$packageName|$postTime").hashCode()
        postCaptureNotification(
            "${label}退款到账",
            "点击选择被退款的账单，记录退款收入",
            ROUTE_REFUND_LINK,
            notifId,
        )
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
        val captureAll = isDebugCaptureAllPages(isDebugBuild)
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
                val snapshotText = texts.joinToString(" | ").take(4000)

                val rawId = captureDao.insert(
                    CapturedNotificationEntity(
                        packageName = packageName,
                        title = parsed?.ruleName ?: "页面快照",
                        text = snapshotText,
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

                // 跨通道合并（10s 窗口）：通知先捕获时用页面信息覆盖（页面更全：商户/支付方式）；
                // 页面是权威来源，金额对不上（通知误解析）也按同笔处理、以页面金额为准
                val related = pickRelatedPending(
                    pendingDao.getMergeable(), packageName, parsed.type, parsed.amountCents, capturedAt,
                    allowAmountMismatch = true,
                )
                if (related != null) {
                    val merged = mergePageInfo(
                        existing = related.pending,
                        pageAmountCents = parsed.amountCents,
                        pageTxTime = parsed.txTime,
                        pageCounterparty = parsed.counterparty,
                        pageNote = parsed.note,
                        pagePaymentMethod = parsed.paymentMethod,
                        pageRuleName = parsed.ruleName,
                    )
                    pendingDao.updateFromPage(
                        related.pending.id,
                        merged.amountCents,
                        merged.timestamp,
                        merged.counterparty,
                        merged.note,
                        merged.ruleName,
                    )
                    val autoEntered = related.pending.status == PendingStatus.AUTO
                    if (autoEntered) {
                        // 自动入账的那笔：把页面信息同步回交易本身
                        txDao.updateCapturedByExternalId(
                            "NTF:${related.pending.dedupKey}",
                            merged.amountCents,
                            merged.timestamp,
                            merged.counterparty,
                            merged.note,
                        )
                    }
                    captureDao.updateMatch(rawId, parsed.ruleName, true)
                    AppLog.i(
                        "Page",
                        (if (autoEntered) "filled auto entry #" else "merged page info into pending #") +
                            "${related.pending.id} ${parsed.ruleName} ${Money.formatYuan(parsed.amountCents)}" +
                            (if (related.sameAmount) "" else " (通知解析 ${Money.formatYuan(related.pending.amountCents)}，页面金额为准)"),
                    )
                    // 溯源：本页捕获的字段是**合并后真正生效的值**，附上页面原始快照，
                    // 键取同笔条目的 dedupKey——金额被页面改写时通知侧的键已对不上交易，只有这个键能解析到账目
                    traceCapture(
                        channel = CaptureChannel.A11Y,
                        packageName = packageName,
                        title = "页面快照",
                        text = snapshotText,
                        bigText = "",
                        capturedAt = capturedAt,
                        matchedRule = merged.ruleName,
                        type = parsed.type,
                        amountCents = merged.amountCents,
                        counterparty = merged.counterparty,
                        note = merged.note,
                        dedupKey = related.pending.dedupKey,
                        outcome = TraceOutcome.MERGED_PAGE,
                    )
                    // 页面富化无需再次提醒：同笔通知在入队时已发出，这里只更新待确认数据
                    return@launch
                }

                // 自动入账（设置开启）：页面信息最全，直接落账
                if (isAutoEntryEnabled() && autoEnter(
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        timestamp = parsed.txTime ?: capturedAt,
                        counterparty = parsed.counterparty,
                        note = parsed.note,
                        sourcePackage = packageName,
                        dedupKey = parsed.dedupKey,
                        ruleName = parsed.ruleName,
                    )
                ) {
                    captureDao.updateMatch(rawId, parsed.ruleName, true)
                    AppLog.i(
                        "Page",
                        "auto entry: ${parsed.ruleName} ${Money.formatYuan(parsed.amountCents)} 收款方=${parsed.counterparty}",
                    )
                    traceCapture(
                        channel = CaptureChannel.A11Y,
                        packageName = packageName,
                        title = "页面快照",
                        text = snapshotText,
                        bigText = "",
                        capturedAt = capturedAt,
                        matchedRule = parsed.ruleName,
                        type = parsed.type,
                        amountCents = parsed.amountCents,
                        counterparty = parsed.counterparty,
                        note = parsed.note,
                        dedupKey = parsed.dedupKey,
                        outcome = TraceOutcome.AUTO_ENTRY,
                    )
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
                traceCapture(
                    channel = CaptureChannel.A11Y,
                    packageName = packageName,
                    title = "页面快照",
                    text = snapshotText,
                    bigText = "",
                    capturedAt = capturedAt,
                    matchedRule = parsed.ruleName,
                    type = parsed.type,
                    amountCents = parsed.amountCents,
                    counterparty = parsed.counterparty,
                    note = parsed.note,
                    dedupKey = parsed.dedupKey,
                    outcome = TraceOutcome.PENDING,
                )
                announceCapture(
                    type = parsed.type,
                    isRefund = false,
                    amountCents = parsed.amountCents,
                    sourceLabel = parsed.sourceLabel,
                    counterparty = parsed.counterparty,
                    paymentMethod = parsed.paymentMethod,
                    dedupKey = parsed.dedupKey,
                    ruleName = parsed.ruleName,
                    txTime = parsed.txTime,
                    note = parsed.note,
                )
            } catch (e: Exception) {
                AppLog.e("Page", "page capture handling failed", e)
            }
        }
    }

    /**
     * 自动入账：按推测分类/账户直接落账（跳过待确认），并发「已自动入账」横幅
     * （点击直达该笔编辑页）。条目以 AUTO 状态留在合并池里——页面通道随后捕获同笔时回填更全的信息。
     * 返回 false = 没有可用的分类/账户或写入失败，调用方回落到待确认流程。
     */
    private suspend fun autoEnter(
        type: TxType,
        amountCents: Long,
        timestamp: Long,
        counterparty: String,
        note: String,
        sourcePackage: String,
        dedupKey: String,
        ruleName: String,
        isRefund: Boolean = false,
    ): Boolean {
        val categoryId = CaptureDefaults.categoryId(categoryDao.getAll(), type, counterparty.ifBlank { note })
        val accountId = CaptureDefaults.accountId(accountDao.getAll(), sourcePackage)
        if (categoryId == null || accountId == null) {
            AppLog.w("Ntf", "auto entry skipped (no category/account to use)")
            return false
        }
        return try {
            val txId = txDao.insert(
                TransactionEntity(
                    type = type,
                    amountCents = amountCents,
                    categoryId = categoryId,
                    accountId = accountId,
                    timestamp = timestamp,
                    note = note.ifBlank { ruleName },
                    counterparty = counterparty,
                    source = TxSource.NOTIFICATION,
                    externalId = "NTF:$dedupKey",
                    refundMark = if (isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                ),
            )
            pendingDao.insert(
                PendingTransactionEntity(
                    type = type,
                    amountCents = amountCents,
                    timestamp = timestamp,
                    counterparty = counterparty,
                    note = note,
                    sourcePackage = sourcePackage,
                    ruleName = ruleName,
                    dedupKey = dedupKey,
                    refundMark = if (isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
                    status = PendingStatus.AUTO,
                ),
            )
            postCaptureNotification(
                "已自动入账",
                Money.formatYuan(amountCents) +
                    (if (counterparty.isNotBlank()) " · $counterparty" else " · $ruleName") +
                    " · 点击可修改",
                "$ROUTE_EDIT_PREFIX$txId",
                notifId = "auto|$dedupKey".hashCode(),
            )
            AppLog.i("Ntf", "auto entered #$txId ${Money.formatYuan(amountCents)} dedup=$dedupKey")
            true
        } catch (e: Exception) {
            // externalId 唯一索引冲突 = 这笔已经入过账（重复通知/双通道），按成功处理不再入队
            if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                AppLog.i("Ntf", "auto entry skipped (already recorded) dedup=$dedupKey")
                true
            } else {
                AppLog.w("Ntf", "auto entry failed, fallback to pending", e)
                false
            }
        }
    }

    // ---------- 通知测试（debug）----------

    /** debug：延迟 20 秒发一条样例捕获通知（验证通知通道可用性与点击跳转） */
    fun scheduleNotifyTest(onResult: (String) -> Unit) {
        if (!initialized) return
        if (!canPostNotifications()) {
            onResult("未授权通知权限：设置页「动账通知提醒」→ 申请权限")
            return
        }
        mainHandler.postDelayed({
            postCaptureNotification("测试通知（我ECO）", "这是一条样例动账提醒 · 点击应进入待确认队列", ROUTE_PENDING)
            toast("延迟测试：通知已发送")
        }, 20_000L)
        onResult("已安排：20 秒后发送测试通知（可切到任意界面等待）")
    }

    private fun toast(message: String) {
        mainHandler.post {
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
