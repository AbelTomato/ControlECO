package com.accbook.app.data

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.accbook.app.AccBookApp
import com.accbook.app.BuildConfig
import com.accbook.app.capture.AccBookAccessibilityService
import com.accbook.app.capture.CaptureCenter
import com.accbook.app.capture.NotificationRules
import com.accbook.app.csv.BillParsers
import com.accbook.app.data.db.AccountBalanceRow
import com.accbook.app.data.db.AccountEntity
import com.accbook.app.data.db.CaptureChannel
import com.accbook.app.data.db.CapturedNotificationEntity
import com.accbook.app.data.db.CategoryEntity
import com.accbook.app.data.db.CategorySumRow
import com.accbook.app.data.db.PendingTransactionEntity
import com.accbook.app.data.db.RefundMark
import com.accbook.app.data.db.TransactionEntity
import com.accbook.app.data.db.TransactionRow
import com.accbook.app.data.db.TypeTotalRow
import com.accbook.app.data.db.TxSource
import com.accbook.app.data.db.TxType
import com.accbook.app.data.budget.Budget
import com.accbook.app.data.model.BillSource
import com.accbook.app.data.model.ImportCommitResult
import com.accbook.app.data.model.ParsedBill
import com.accbook.app.util.AppLog
import com.accbook.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneId

/**
 * 阶段一的全局 ViewModel：月份切换为流水/统计共用状态；
 * 后续阶段（自动记账）在此扩展捕获通道状态。
 */
class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as AccBookApp).db
    private val txDao = db.transactionDao()
    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()
    private val pendingDao = db.pendingDao()
    private val captureDao = db.captureDao()

    private val zone: ZoneId = ZoneId.systemDefault()

    // ---------- 设置：显示退款记录 ----------

    private val prefs = getApplication<AccBookApp>()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _showRefunds = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_REFUNDS, false),
    )
    val showRefunds: StateFlow<Boolean> = _showRefunds

    fun setShowRefunds(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_REFUNDS, enabled).apply()
        _showRefunds.value = enabled
        AppLog.i("settings", "showRefunds=$enabled")
    }

    // ---------- 设置：月度预算（首页 HUD 的数据源） ----------

    private val _monthlyBudget = MutableStateFlow(prefs.getLong(KEY_MONTHLY_BUDGET, 0L))
    val monthlyBudgetCents: StateFlow<Long> = _monthlyBudget

    /** cents ≤ 0 视为未设置 */
    fun setMonthlyBudget(cents: Long) {
        prefs.edit().putLong(KEY_MONTHLY_BUDGET, cents).apply()
        _monthlyBudget.value = cents
        AppLog.i("settings", "monthlyBudget=${Money.formatYuan(cents)}")
    }

    private val homeMonth = YearMonth.now(zone)

    private val homeRange = range(homeMonth)

    /**
     * 首页"本月已花"：固定取当前自然月（不随流水页翻月变化）。
     * 口径 = 当月支出合计 − 当月退款到账（现金净流出，退款始终计入，与显示开关无关）。
     * 剩余/建议日花/状态由 UI 用 Budget.compute 纯函数计算。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeNetSpent: StateFlow<Long> = combine(
        txDao.observeTypeTotals(homeRange.first, homeRange.second, true),
        txDao.observeRefundIncomeTotal(homeRange.first, homeRange.second),
    ) { totals, refundIncome ->
        (totals.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L) - refundIncome
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** 首页"分类剩余预算"预览：当月各支出分类合计（口径与首页一致，退款始终计入） */
    val homeCategorySums: StateFlow<List<CategorySumRow>> = txDao
        .observeCategorySums(homeRange.first, homeRange.second, TxType.EXPENSE, true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------- 月份 ----------

    private val _month = MutableStateFlow(YearMonth.now(zone))
    val month: StateFlow<YearMonth> = _month

    fun prevMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { _month.value = _month.value.plusMonths(1) }

    /** 直接跳到指定月份（账单页月份胶囊的下拉选择） */
    fun setMonth(m: YearMonth) { _month.value = m }

    private fun range(m: YearMonth): Pair<Long, Long> {
        val start = m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val monthRows: Flow<List<TransactionRow>> =
        combine(_month, _showRefunds) { m, r -> m to r }.flatMapLatest { (m, r) ->
            val (s, e) = range(m); txDao.observeRows(s, e, r)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val monthTotals: Flow<List<TypeTotalRow>> =
        combine(_month, _showRefunds) { m, r -> m to r }.flatMapLatest { (m, r) ->
            val (s, e) = range(m); txDao.observeTypeTotals(s, e, r)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val expenseByCategory: Flow<List<CategorySumRow>> =
        combine(_month, _showRefunds) { m, r -> m to r }.flatMapLatest { (m, r) ->
            val (s, e) = range(m); txDao.observeCategorySums(s, e, TxType.EXPENSE, r)
        }

    /** 退款到账金额合计（"已取消收支"），是否展示由 UI 根据开关决定 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val refundIncomeTotal: Flow<Long> = _month.flatMapLatest { m ->
        val (s, e) = range(m); txDao.observeRefundIncomeTotal(s, e)
    }

    val accounts: StateFlow<List<AccountEntity>> =
        accountDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        categoryDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val accountBalances: Flow<List<AccountBalanceRow>> = _showRefunds.flatMapLatest { r ->
        txDao.observeBalances(r)
    }

    /** 首页"总存款"预览：全部账户（初始金额 + 交易净额）合计 */
    val totalDepositCents: StateFlow<Long> = accountBalances
        .map { rows -> rows.sumOf { it.initialBalanceCents + it.signedSumCents } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // ---------- 交易 ----------

    fun saveTransaction(
        id: Long,
        type: TxType,
        amountCents: Long,
        categoryId: Long,
        accountId: Long,
        timestamp: Long,
        note: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (id > 0) {
                txDao.getById(id)?.let { old ->
                    txDao.update(
                        old.copy(
                            type = type, amountCents = amountCents, categoryId = categoryId,
                            accountId = accountId, timestamp = timestamp, note = note,
                        )
                    )
                }
            } else {
                txDao.insert(
                    TransactionEntity(
                        type = type, amountCents = amountCents, categoryId = categoryId,
                        accountId = accountId, timestamp = timestamp, note = note,
                    )
                )
            }
        }
    }

    fun deleteTransaction(id: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteById(id)
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "delete failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    // ---------- 批量删除（账单页：多选 / 按月 / 按年 / 全部） ----------

    suspend fun countTransactionsInRange(startMillis: Long, endMillis: Long): Int =
        withContext(Dispatchers.IO) { txDao.countByRange(startMillis, endMillis) }

    suspend fun countAllTransactions(): Int =
        withContext(Dispatchers.IO) { txDao.countAll() }

    fun deleteTransactionsByIds(ids: Collection<Long>, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteByIds(ids.toList())
                AppLog.i("tx", "batch deleted ${ids.size} by ids")
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "batch delete failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    fun deleteTransactionsInRange(startMillis: Long, endMillis: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteByRange(startMillis, endMillis)
                AppLog.i("tx", "batch deleted in range [$startMillis, $endMillis)")
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "range delete failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    fun deleteAllTransactions(onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                txDao.deleteAll()
                AppLog.i("tx", "all transactions deleted")
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("tx", "delete all failed", e)
                withContext(Dispatchers.Main) { onResult("删除失败") }
            }
        }
    }

    suspend fun transactionById(id: Long): TransactionEntity? =
        withContext(Dispatchers.IO) { txDao.getById(id) }

    // ---------- 账户 ----------

    fun saveAccount(id: Long, name: String, initialBalanceCents: Long, note: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (id > 0) {
                    accountDao.getById(id)?.let { old ->
                        accountDao.update(old.copy(name = name, initialBalanceCents = initialBalanceCents, note = note))
                    }
                } else {
                    accountDao.insert(AccountEntity(name = name, initialBalanceCents = initialBalanceCents, note = note))
                }
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("account", "save failed", e)
                withContext(Dispatchers.Main) { onResult("保存失败") }
            }
        }
    }

    fun deleteAccount(id: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val used = txDao.countByAccount(id)
            if (used > 0) {
                withContext(Dispatchers.Main) { onResult("该账户下有 $used 笔交易，无法删除") }
            } else {
                accountDao.deleteById(id)
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    // ---------- 分类 ----------

    /** 保存分类；回调携带保存后的 id（新建时用于紧接着写入分类预算） */
    fun saveCategory(id: Long, name: String, type: TxType, onResult: (savedId: Long, error: String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val savedId = if (id > 0) {
                    categoryDao.getAll().firstOrNull { it.id == id }?.let { old ->
                        categoryDao.update(old.copy(name = name, type = type))
                    }
                    id
                } else {
                    categoryDao.insert(CategoryEntity(name = name, type = type))
                }
                withContext(Dispatchers.Main) { onResult(savedId, null) }
            } catch (e: Exception) {
                AppLog.e("category", "save failed", e)
                withContext(Dispatchers.Main) { onResult(0L, "保存失败") }
            }
        }
    }

    fun deleteCategory(id: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val used = txDao.countByCategory(id)
            if (used > 0) {
                withContext(Dispatchers.Main) { onResult("该分类下有 $used 笔交易，无法删除") }
            } else {
                categoryDao.deleteById(id)
                setCategoryBudget(id, 0L) // 分类删除时同步清理其分类预算
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    // ---------- 设置：分类月预算（首页"分类剩余预算"预览的数据源） ----------

    private val _categoryBudgets = MutableStateFlow(loadCategoryBudgets())
    val categoryBudgets: StateFlow<Map<Long, Long>> = _categoryBudgets

    private fun loadCategoryBudgets(): Map<Long, Long> = prefs.all.entries
        .mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_CAT_BUDGET_PREFIX)) return@mapNotNull null
            val id = key.removePrefix(KEY_CAT_BUDGET_PREFIX).toLongOrNull() ?: return@mapNotNull null
            val cents = (value as? Long) ?: return@mapNotNull null
            if (cents > 0) id to cents else null
        }
        .toMap()

    /** cents ≤ 0 表示清除该分类预算 */
    fun setCategoryBudget(categoryId: Long, cents: Long) {
        val editor = prefs.edit()
        if (cents > 0) {
            editor.putLong(KEY_CAT_BUDGET_PREFIX + categoryId, cents)
        } else {
            editor.remove(KEY_CAT_BUDGET_PREFIX + categoryId)
        }
        editor.apply()
        _categoryBudgets.value = loadCategoryBudgets()
        AppLog.i("settings", "categoryBudget[$categoryId]=${Money.formatYuan(cents)}")
    }

    // ---------- 账单导入 ----------

    private val _parsedBill = MutableStateFlow<ParsedBill?>(null)
    val parsedBill: StateFlow<ParsedBill?> = _parsedBill

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError

    private val _importDone = MutableStateFlow<ImportCommitResult?>(null)
    val importDone: StateFlow<ImportCommitResult?> = _importDone

    fun parseBillFile(uriString: String) {
        _parsedBill.value = null
        _importError.value = null
        _importDone.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<AccBookApp>()
                val bytes = context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取所选文件")
                val bill = BillParsers.parse(bytes)
                    ?: throw IllegalArgumentException("无法识别账单格式，请选择微信/支付宝导出的账单 CSV")
                _parsedBill.value = bill
            } catch (e: Exception) {
                AppLog.e("import", "parse failed", e)
                _importError.value = e.message ?: "解析失败"
            }
        }
    }

    fun commitImport(bill: ParsedBill, accountId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cats = categoryDao.getAll()
                val entities = bill.rows.map { row ->
                    val cat = cats.firstOrNull { it.type == row.type && it.name == row.guessedCategoryName }
                        ?: cats.firstOrNull { it.type == row.type && it.name == "其他" }
                        ?: cats.firstOrNull { it.type == row.type }
                        ?: error("缺少${if (row.type == TxType.EXPENSE) "支出" else "收入"}分类，请先在分类管理中添加")
                    TransactionEntity(
                        type = row.type,
                        amountCents = row.amountCents,
                        categoryId = cat.id,
                        accountId = accountId,
                        timestamp = row.timestamp,
                        note = row.note,
                        counterparty = row.counterparty,
                        source = when (bill.source) {
                            BillSource.WECHAT -> TxSource.WECHAT
                            BillSource.ALIPAY -> TxSource.ALIPAY
                        },
                        externalId = row.externalId,
                        refundMark = row.refundMark,
                    )
                }
                val ids = txDao.insertAllIgnore(entities)
                val inserted = ids.count { it != -1L }
                val duplicated = ids.count { it == -1L }
                AppLog.i("import", "committed: inserted=$inserted duplicated=$duplicated")
                withContext(Dispatchers.Main) { _importDone.value = ImportCommitResult(inserted, duplicated) }
            } catch (e: Exception) {
                AppLog.e("import", "commit failed", e)
                withContext(Dispatchers.Main) { _importError.value = e.message ?: "导入失败" }
            }
        }
    }

    fun clearImportState() {
        _parsedBill.value = null
        _importError.value = null
        _importDone.value = null
    }

    // ---------- 自动记账（阶段二：通知监听通道） ----------

    val pendingList: StateFlow<List<PendingTransactionEntity>> =
        pendingDao.observePending().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingCount: StateFlow<Int> =
        pendingDao.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 捕获日志通道筛选：null = 全部（通知 + 页面合并） */
    val captureFilter = MutableStateFlow<CaptureChannel?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val captureRows: StateFlow<List<CapturedNotificationEntity>> = captureFilter
        .flatMapLatest { channel ->
            if (channel == null) captureDao.observeRecent(300)
            else captureDao.observeRecentBySource(channel, 300)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setCaptureFilter(channel: CaptureChannel?) {
        captureFilter.value = channel
    }

    val listenerConnected: StateFlow<Boolean> = CaptureCenter.connected
    val a11yConnected: StateFlow<Boolean> = CaptureCenter.a11yConnected
    val lastCaptureAt: StateFlow<Long> = CaptureCenter.lastEventAt

    private val _captureEnabled = MutableStateFlow(CaptureCenter.isCaptureEnabled())
    val captureEnabled: StateFlow<Boolean> = _captureEnabled

    fun setCaptureEnabled(enabled: Boolean) {
        CaptureCenter.setCaptureEnabled(enabled)
        _captureEnabled.value = enabled
    }

    private val _a11yCaptureEnabled = MutableStateFlow(CaptureCenter.isA11yCaptureEnabled())
    val a11yCaptureEnabled: StateFlow<Boolean> = _a11yCaptureEnabled

    fun setA11yCaptureEnabled(enabled: Boolean) {
        CaptureCenter.setA11yCaptureEnabled(enabled)
        _a11yCaptureEnabled.value = enabled
    }

    private val _floatingConfirmEnabled = MutableStateFlow(CaptureCenter.isFloatingConfirmEnabled())
    val floatingConfirmEnabled: StateFlow<Boolean> = _floatingConfirmEnabled

    fun setFloatingConfirmEnabled(enabled: Boolean) {
        CaptureCenter.setFloatingConfirmEnabled(enabled)
        _floatingConfirmEnabled.value = enabled
    }

    private val _debugCaptureAll = MutableStateFlow(CaptureCenter.isDebugCaptureAll(BuildConfig.DEBUG))
    val debugCaptureAll: StateFlow<Boolean> = _debugCaptureAll

    fun setDebugCaptureAll(enabled: Boolean) {
        CaptureCenter.setDebugCaptureAll(enabled)
        _debugCaptureAll.value = enabled
    }

    fun isListenerGranted(): Boolean {
        val app = getApplication<AccBookApp>()
        return NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
    }

    private val _refundLinkEnabled = MutableStateFlow(CaptureCenter.isRefundLinkEnabled())
    val refundLinkEnabled: StateFlow<Boolean> = _refundLinkEnabled

    fun setRefundLinkEnabled(enabled: Boolean) {
        CaptureCenter.setRefundLinkEnabled(enabled)
        _refundLinkEnabled.value = enabled
    }

    private val _captureNotifyEnabled = MutableStateFlow(CaptureCenter.isCaptureNotifyEnabled())
    val captureNotifyEnabled: StateFlow<Boolean> = _captureNotifyEnabled

    fun setCaptureNotifyEnabled(enabled: Boolean) {
        CaptureCenter.setCaptureNotifyEnabled(enabled)
        _captureNotifyEnabled.value = enabled
    }

    /** 通知权限（安卓 13+ 运行时） */
    fun canPostNotifications(): Boolean = CaptureCenter.canPostNotifications()

    /** debug：20 秒后弹测试悬浮窗（切到支付页验证是否被拦截） */
    fun scheduleFloatingTest(onResult: (String) -> Unit) = CaptureCenter.scheduleFloatingTest(onResult)

    /** debug：20 秒后发测试捕获通知 */
    fun scheduleNotifyTest(onResult: (String) -> Unit) = CaptureCenter.scheduleNotifyTest(onResult)

    // ---------- 退款关联（无金额退款通知 → 手动选择原账单） ----------

    /** 近 60 天未退款的支出候选 */
    suspend fun refundCandidates(): List<TransactionRow> = withContext(Dispatchers.IO) {
        txDao.recentUnrefundedExpenses(System.currentTimeMillis() - 60L * 24 * 3_600_000)
    }

    /**
     * 关联退款：生成退款收入（REFUND_INCOME，金额可改以支持部分退款，入原账户），
     * 并把原支出标记为 REFUNDED_EXPENSE——与账单导入口径一致，受"显示退款记录"开关控制。
     */
    fun linkRefund(expenseId: Long, amountCents: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val expense = txDao.getById(expenseId)
                when {
                    expense == null -> { onDone("原支出记录不存在"); return@launch }
                    expense.refundMark != RefundMark.NONE -> { onDone("该笔支出已标记退款"); return@launch }
                    amountCents <= 0 -> { onDone("退款金额需大于 0"); return@launch }
                }
                val cats = categoryDao.getAll()
                val incomeCat = cats.firstOrNull { it.type == TxType.INCOME && it.name == "其他" }
                    ?: cats.firstOrNull { it.type == TxType.INCOME }
                    ?: error("缺少收入分类，请先在分类管理中添加")
                txDao.insert(
                    TransactionEntity(
                        type = TxType.INCOME,
                        amountCents = amountCents,
                        categoryId = incomeCat.id,
                        accountId = expense.accountId,
                        timestamp = System.currentTimeMillis(),
                        note = "退款收入（手动关联）",
                        counterparty = expense.counterparty,
                        source = TxSource.NOTIFICATION,
                        refundMark = RefundMark.REFUND_INCOME,
                    ),
                )
                txDao.update(expense.copy(refundMark = RefundMark.REFUNDED_EXPENSE))
                AppLog.i("refund", "linked refund for expense #$expenseId amount=${Money.formatYuan(amountCents)}")
                withContext(Dispatchers.Main) { onDone(null) }
            } catch (e: Exception) {
                AppLog.e("refund", "link failed", e)
                withContext(Dispatchers.Main) { onDone("关联失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    /** 系统无障碍设置中是否已开启采集服务 */
    fun isA11yEnabled(): Boolean = AccBookAccessibilityService.isEnabled(getApplication())

    /** 悬浮窗授权（悬浮确认窗的前置） */
    fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(getApplication())

    /** debug：手动弹出测试悬浮窗（区分悬浮窗层故障与页面未捕获） */
    fun showFloatingTest(onResult: (String) -> Unit) = CaptureCenter.showFloatingTest(onResult)

    fun discardPending(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            pendingDao.deleteById(id)
            AppLog.i("pending", "discarded #$id")
        }
    }

    fun confirmPending(item: PendingTransactionEntity, categoryId: Long, accountId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val externalId = "NTF:${item.dedupKey}"
            txDao.insert(
                TransactionEntity(
                    type = item.type,
                    amountCents = item.amountCents,
                    categoryId = categoryId,
                    accountId = accountId,
                    timestamp = item.timestamp,
                    note = item.note.ifBlank { item.ruleName },
                    counterparty = item.counterparty,
                    source = TxSource.NOTIFICATION,
                    externalId = externalId,
                    refundMark = item.refundMark,
                ),
            )
            pendingDao.deleteById(item.id)
            AppLog.i("pending", "confirmed #${item.id} externalId=$externalId")
        }
    }

    fun confirmAllPending() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = pendingDao.getAllPending()
            if (items.isEmpty()) return@launch
            val cats = categoryDao.getAll()
            val accs = accountDao.getAll()
            val entities = items.map { item ->
                val cat = cats.firstOrNull { it.type == item.type && it.name == "其他" }
                    ?: cats.firstOrNull { it.type == item.type }
                    ?: return@launch
                TransactionEntity(
                    type = item.type,
                    amountCents = item.amountCents,
                    categoryId = cat.id,
                    accountId = guessAccountId(item.sourcePackage, accs) ?: return@launch,
                    timestamp = item.timestamp,
                    note = item.note.ifBlank { item.ruleName },
                    counterparty = item.counterparty,
                    source = TxSource.NOTIFICATION,
                    externalId = "NTF:${item.dedupKey}",
                    refundMark = item.refundMark,
                )
            }
            val ids = txDao.insertAllIgnore(entities)
            val inserted = ids.count { it != -1L }
            items.forEach { pendingDao.deleteById(it.id) }
            AppLog.i("pending", "confirmAll: ${items.size} items, inserted=$inserted")
        }
    }

    /** 待确认确认弹窗的默认分类（该类型的"其他"，缺省取该类型第一个） */
    fun guessCategoryId(type: TxType): Long? =
        categories.value.firstOrNull { it.type == type && it.name == "其他" }?.id
            ?: categories.value.firstOrNull { it.type == type }?.id

    /** 按来源包名推测账户：微信→微信零钱、支付宝→支付宝、云闪付/其他→银行卡 */
    fun guessAccountId(sourcePackage: String): Long? =
        guessAccountId(sourcePackage, accounts.value)

    fun clearCaptureLog() {
        viewModelScope.launch(Dispatchers.IO) {
            captureDao.clearAll()
            AppLog.i("Ntf", "capture log cleared")
        }
    }

    private fun guessAccountId(sourcePackage: String, accounts: List<AccountEntity>): Long? {
        val keyword = when (sourcePackage) {
            NotificationRules.WECHAT -> "微信"
            NotificationRules.ALIPAY -> "支付宝"
            else -> "银行"
        }
        return accounts.firstOrNull { it.name.contains(keyword) }?.id
            ?: accounts.firstOrNull()?.id
    }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_SHOW_REFUNDS = "show_refunds"
        const val KEY_MONTHLY_BUDGET = "monthly_budget_cents"
        const val KEY_CAT_BUDGET_PREFIX = "category_budget_"
    }
}
