package com.accbook.app.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.accbook.app.AccBookApp
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
    private const val KEY_DEBUG_CAPTURE_ALL = "debug_capture_all"

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
        cleanup()
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
            } catch (e: Exception) {
                AppLog.e("Ntf", "capture handling failed", e)
            }
        }
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
                    AppLog.d("Page", "page snapshot stored (unmatched) pkg=$packageName")
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

                if (!isA11yCaptureEnabled()) return@launch // 仅调试快照，不入队
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
            AppLog.d("Page", "floating confirm disabled")
            return
        }
        if (!Settings.canDrawOverlays(appContext)) {
            AppLog.d("Page", "floating confirm skipped (no overlay permission)")
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

    private fun toast(message: String) {
        mainHandler.post {
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
