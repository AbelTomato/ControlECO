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
 * 采集中心：通知监听、无障碍页面、银行动账短信三条通道的唯一入口。
 * 通道流程一致：原始快照落库（不解析）→ 规则引擎 → 待确认队列。
 * 跨通道去重分两层：
 * - 同源（通知⇄页面，10s 窗口，见 CaptureMerge）：只保留一条，**页面信息优先**；
 * - 异源（支付类⇄银行类，±130s，见 CrossChannelDedup）：同一笔消费被微信通知与银行卡
 *   短信/推送同时捕获时归并成一条，银行侧只补充账户线索。
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
    private const val KEY_BANK_PUSH_ENABLED = "bank_push_enabled"
    private const val KEY_SMS_CAPTURE_ENABLED = "sms_capture_enabled"

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

    /**
     * 银行 App 动账推送（默认开）：银行 App 的动账通知与动账短信同源同形，
     * 走 BankLedgerRules 的「正文【银行名】签名」闸门 + 银行包名短名单兜底。
     * 不需要额外权限（走既有通知监听授权）。
     */
    fun isBankPushEnabled(): Boolean =
        prefs().getBoolean(KEY_BANK_PUSH_ENABLED, true)

    fun setBankPushEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_BANK_PUSH_ENABLED, enabled).apply()
        AppLog.i("Sms", "bankPushEnabled=$enabled")
    }

    /**
     * 银行动账短信（默认关）：银行卡直接刷卡/被扣款而没有任何 App 通知时，短信是唯一线索。
     * 只收新到短信（RECEIVE_SMS），不读历史、不解码彩信、非默认短信应用；
     * 非银行号码与无银行签名的短信在采集层丢弃（内容不入库）。
     */
    fun isSmsCaptureEnabled(): Boolean =
        prefs().getBoolean(KEY_SMS_CAPTURE_ENABLED, false)

    fun setSmsCaptureEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_SMS_CAPTURE_ENABLED, enabled).apply()
        AppLog.i("Sms", "smsCaptureEnabled=$enabled")
    }

    /** 是否已授予收短信权限（安卓 13+ 侧载同样需运行时授权） */
    fun hasSmsPermission(): Boolean =
        initialized && androidx.core.content.ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.RECEIVE_SMS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

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
        // 银行 App 动账推送：不在支付白名单里，走「正文【银行名】签名 / 银行包名短名单」两道线索。
        // 是否真的入库要在解析通过后决定（银行 App 的营销/通知类推送极密集，未命中不入库）。
        val bankCandidate = !interested && !captureAll && isBankPushEnabled() && isCaptureEnabled() &&
            (BankLedgerRules.detectBankName("$title $text $bigText") != null ||
                BankLedgerRules.bankNameOfPackage(packageName) != null)
        if (!interested && !captureAll && !bankCandidate) {
            AppLog.d("Ntf", "skip (not interested) pkg=$packageName title=[$title]")
            return
        }

        scope.launch {
            try {
                if (!interested) {
                    handleBankPush(packageName, title, text, bigText, postTime)
                    return@launch
                }
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

    // ---------- 通道三：银行动账（短信 / 银行 App 推送，共用解析与入队） ----------

    /**
     * 短信通道入口（由 SmsCaptureReceiver 在 IO 协程里调用，goAsync 保证写完才 finish）。
     * 隐私闸门在最前面：既不是已知银行号码、正文里也没有银行签名 → 立即丢弃，不解析不入库
     * （银行动账短信才有记账价值，私人短信一律不碰）。
     */
    suspend fun onSmsReceived(sender: String, body: String, eventTime: Long) {
        if (!initialized || body.isBlank()) return
        if (!isSmsCaptureEnabled() || !isCaptureEnabled()) {
            AppLog.d("Sms", "skip (sms capture disabled)")
            return
        }
        val normalized = NotificationRules.normalize(body)
        val bankName = BankLedgerRules.detectBankName(normalized)
        if (!BankLedgerRules.isBankSender(sender) && bankName == null) {
            AppLog.d("Sms", "skip (not a bank message) sender=$sender")
            return
        }
        val sourceKey = NotificationRules.SMS_SOURCE_PREFIX + (bankName ?: sender.takeLast(5))
        // 先落原始快照（只有银行侧短信会走到这里）；未匹配的银行短信留在捕获日志里供扩规则
        val rawId = captureDao.insert(
            CapturedNotificationEntity(
                packageName = sourceKey,
                title = "短信 $sender",
                text = normalized.take(4000),
                postTime = eventTime,
                source = CaptureChannel.SMS,
            ),
        )
        val ledger = BankLedgerRules.parse(normalized, sourceKey, "短信", eventTime, bankName)
        if (ledger == null) {
            AppLog.i("Sms", "bank sms not matched (#$rawId) sender=$sender text=[${normalized.take(160)}]")
            return
        }
        enqueueBankLedger(ledger, rawId, eventTime, CaptureChannel.SMS)
    }

    /**
     * 银行 App 动账推送：与短信共用 BankLedgerRules。推送没有号码闸门，
     * 用「正文签名 + 包名短名单」两道线索；解析不通过就不落快照（营销推送不入库）。
     */
    private suspend fun handleBankPush(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        postTime: Long,
    ) {
        val body = NotificationRules.normalize(
            listOf(title, text, bigText).filter { it.isNotBlank() }.joinToString(" "),
        )
        val fallbackBankName = BankLedgerRules.bankNameOfPackage(packageName)
        val ledger = BankLedgerRules.parse(body, packageName, "通知", postTime, fallbackBankName)
        if (ledger == null) {
            AppLog.d("Ntf", "bank push not matched pkg=$packageName body=[${body.take(160)}]")
            return
        }
        val rawId = captureDao.insert(
            CapturedNotificationEntity(
                packageName = packageName,
                title = title,
                text = text,
                bigText = bigText,
                postTime = postTime,
            ),
        )
        enqueueBankLedger(ledger, rawId, postTime, CaptureChannel.NOTIFICATION)
    }

    /** 银行动账统一入队：跨通道归并 → 自动入账 → 待确认（与通知/页面通道同一套下游） */
    private suspend fun enqueueBankLedger(
        ledger: BankLedgerRules.BankLedger,
        rawId: Long,
        eventTime: Long,
        channel: CaptureChannel,
    ) {
        val sourceAt = if (eventTime > 0) eventTime else System.currentTimeMillis()

        // 跨通道归并（±130s）：支付侧（微信/支付宝）已捕获同笔 → 银行侧只补充账户线索，
        // 不再产生第二条（真实场景：银行卡绑定微信支付一笔，支付通知 + 银行短信同时到）
        val related = findCrossChannelPending(ledger, sourceAt)
        if (related != null) {
            mergeBankInfoIntoPending(related, ledger)
            captureDao.updateMatch(rawId, ledger.ruleName + "·并入待确认", true)
            traceCapture(
                channel = channel,
                packageName = ledger.sourceKey,
                title = if (channel == CaptureChannel.SMS) "银行短信" else "银行推送",
                text = ledger.note,
                bigText = "",
                capturedAt = sourceAt,
                matchedRule = ledger.ruleName,
                type = ledger.type,
                amountCents = ledger.amountCents,
                counterparty = ledger.merchant,
                note = ledger.note,
                dedupKey = related.dedupKey,
                outcome = TraceOutcome.MERGED_CROSS,
            )
            return
        }

        if (isAutoEntryEnabled() && autoEnter(
                type = ledger.type,
                amountCents = ledger.amountCents,
                timestamp = sourceAt,
                counterparty = ledger.merchant,
                note = ledger.note,
                sourcePackage = ledger.sourceKey,
                dedupKey = ledger.dedupKey,
                ruleName = ledger.ruleName,
                isRefund = ledger.isRefund,
                source = TxSource.BANK,
                bankName = ledger.bankName,
            )
        ) {
            captureDao.updateMatch(rawId, ledger.ruleName, true)
            AppLog.i(
                "Sms",
                "auto entry: ${ledger.ruleName} ${ledger.type} ${Money.formatYuan(ledger.amountCents)}" +
                    (if (ledger.isRefund) " [退款]" else "") + " ${ledger.displayLabel}",
            )
            traceCapture(
                channel = channel,
                packageName = ledger.sourceKey,
                title = if (channel == CaptureChannel.SMS) "银行短信" else "银行推送",
                text = ledger.note,
                bigText = "",
                capturedAt = sourceAt,
                matchedRule = ledger.ruleName,
                type = ledger.type,
                amountCents = ledger.amountCents,
                counterparty = ledger.merchant,
                note = ledger.note,
                dedupKey = ledger.dedupKey,
                outcome = TraceOutcome.AUTO_ENTRY,
            )
            return
        }

        pendingDao.insert(
            PendingTransactionEntity(
                type = ledger.type,
                amountCents = ledger.amountCents,
                timestamp = sourceAt,
                counterparty = ledger.merchant,
                note = ledger.note,
                sourcePackage = ledger.sourceKey,
                ruleName = ledger.ruleName,
                dedupKey = ledger.dedupKey,
                rawNotificationId = rawId,
                refundMark = if (ledger.isRefund) RefundMark.REFUND_INCOME else RefundMark.NONE,
            ),
        )
        captureDao.updateMatch(rawId, ledger.ruleName, true)
        AppLog.i(
            "Sms",
            "queued pending: ${ledger.ruleName} ${ledger.type} ${Money.formatYuan(ledger.amountCents)}" +
                " 账户=${ledger.accountLabel}",
        )
        traceCapture(
            channel = channel,
            packageName = ledger.sourceKey,
            title = if (channel == CaptureChannel.SMS) "银行短信" else "银行推送",
            text = ledger.note,
            bigText = "",
            capturedAt = sourceAt,
            matchedRule = ledger.ruleName,
            type = ledger.type,
            amountCents = ledger.amountCents,
            counterparty = ledger.merchant,
            note = ledger.note,
            dedupKey = ledger.dedupKey,
            outcome = TraceOutcome.PENDING,
        )
        announceCapture(
            type = ledger.type,
            isRefund = ledger.isRefund,
            amountCents = ledger.amountCents,
            sourceLabel = ledger.displayLabel,
            counterparty = ledger.merchant,
            paymentMethod = null,
            dedupKey = ledger.dedupKey,
            ruleName = ledger.ruleName,
            txTime = sourceAt,
            note = ledger.note,
        )
    }

    /** 跨通道归并候选：合并池里"通道类别相异（支付类⇄银行类）且特征全等"的同笔条目 */
    private suspend fun findCrossChannelPending(
        ledger: BankLedgerRules.BankLedger,
        eventTime: Long,
    ): PendingTransactionEntity? {
        val incoming = CrossChannelDedup.Signature(
            amountCents = ledger.amountCents,
            type = ledger.type,
            isRefund = ledger.isRefund,
            source = ledger.sourceKey,
            time = eventTime,
        )
        return pendingDao.getMergeable().firstOrNull { candidate ->
            CrossChannelDedup.isSameTransaction(
                CrossChannelDedup.signatureOf(
                    amountCents = candidate.amountCents,
                    type = candidate.type,
                    refundMark = candidate.refundMark,
                    source = candidate.sourcePackage,
                    time = candidate.timestamp,
                ),
                incoming,
            )
        }
    }

    /**
     * 银行侧信息并入既有条目：支付侧信息更全（商户/支付方式），只追加银行侧独有的账户线索
     * （"银行 工商银行(1234)"），不改金额/时间/方向（金额全等才可能命中）。
     * 目标若是自动入账条目，同步回填交易本身（与页面回填同一路径）。
     */
    private suspend fun mergeBankInfoIntoPending(
        existing: PendingTransactionEntity,
        ledger: BankLedgerRules.BankLedger,
    ) {
        val extra = "银行 ${ledger.accountLabel}"
        val note = if (existing.note.contains(ledger.accountLabel)) {
            existing.note
        } else {
            (if (existing.note.isBlank()) extra else "${existing.note} · $extra").take(300)
        }
        val counterparty = existing.counterparty.ifBlank { ledger.merchant }
        pendingDao.updateFromPage(
            existing.id,
            existing.amountCents,
            existing.timestamp,
            counterparty,
            note,
            existing.ruleName,
        )
        if (existing.status == PendingStatus.AUTO) {
            txDao.updateCapturedByExternalId(
                "NTF:${existing.dedupKey}",
                existing.amountCents,
                existing.timestamp,
                counterparty,
                note,
            )
        }
        AppLog.i(
            "Sms",
            "merged bank info into " +
                (if (existing.status == PendingStatus.AUTO) "auto entry" else "pending") +
                " #${existing.id} ${ledger.ruleName} 账户=${ledger.accountLabel}",
        )
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
        source: TxSource = TxSource.NOTIFICATION,
        bankName: String? = null,
    ): Boolean {
        val categoryId = CaptureDefaults.categoryId(categoryDao.getAll(), type, counterparty.ifBlank { note })
        val accountId = CaptureDefaults.accountId(accountDao.getAll(), sourcePackage, bankName)
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
                    source = source,
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
