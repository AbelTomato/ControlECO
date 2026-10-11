package com.controleco.app.data

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.controleco.app.AccBookApp
import com.controleco.app.BuildConfig
import com.controleco.app.capture.AccBookAccessibilityService
import com.controleco.app.capture.CaptureCenter
import com.controleco.app.capture.CaptureDefaults
import com.controleco.app.capture.NotificationRules
import com.controleco.app.csv.BillParsers
import com.controleco.app.data.db.AccountBalanceRow
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CaptureChannel
import com.controleco.app.data.db.CapturedNotificationEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.CategorySumRow
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TransactionRow
import com.controleco.app.data.db.TypeTotalRow
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import com.controleco.app.data.budget.Budget
import com.controleco.app.data.model.BillSource
import com.controleco.app.data.model.ImportCommitResult
import com.controleco.app.data.model.ParsedBill
import com.controleco.app.update.AppUpdate
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.time.LocalDate
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

    // ---------- 设置：账单页结余卡背景图（null = 默认几何背景） ----------

    private val _cardImageUri = MutableStateFlow(prefs.getString(KEY_CARD_IMAGE, null))
    val cardImageUri: StateFlow<String?> = _cardImageUri

    fun setCardImage(uri: String?) {
        prefs.edit().putString(KEY_CARD_IMAGE, uri).apply()
        _cardImageUri.value = uri
        if (uri == null) {
            prefs.edit().remove(KEY_CARD_COLOR).remove(KEY_CARD_LUMINANCE).apply()
            _cardImageColor.value = null
            _cardImageLuminance.value = null
            pruneCardImages() // 恢复默认背景：图片文件也一并清掉
        }
        loadCardImage(uri)
        AppLog.i("settings", "cardImage=${uri ?: "default"}")
    }

    /**
     * 清掉不再使用的卡面图（只留当前这张）。
     * 选图时**不**清：编辑器里点「取消」要把上一张原样恢复，得等真正「应用」时才收尾。
     */
    fun pruneCardImages() {
        val keep = _cardImageUri.value
        viewModelScope.launch(Dispatchers.IO) { deleteOtherCardImages(getApplication(), keep) }
    }

    /**
     * 卡面图片：进程启动（或换图）时就异步解码，账单页第一次组合时通常已经就绪，
     * 不会出现"先默认背景再闪回图片"。先出缩略图（几毫秒）再出全尺寸。
     */
    private val _cardImage = MutableStateFlow<CardImage?>(null)
    val cardImage: StateFlow<CardImage?> = _cardImage

    private var cardImageJob: Job? = null

    private fun loadCardImage(path: String?) {
        cardImageJob?.cancel()
        if (path.isNullOrBlank()) {
            _cardImage.value = null
            return
        }
        cardImageJob = viewModelScope.launch(Dispatchers.IO) {
            // 1) 超小缩略图：拿到真实（模糊）图 + 平均色，立刻可用
            val preview = CardImageLoader.decode(getApplication(), path, CardImageLoader.PREVIEW_SIZE)
            val persistedColor = prefs.getInt(KEY_CARD_COLOR, 0).takeIf { it != 0 }
            val persistedLum = prefs.getFloat(KEY_CARD_LUMINANCE, -1f).takeIf { it >= 0f }
            val color = preview?.let { CardImageLoader.averageColor(it) } ?: persistedColor
            val luminance = preview?.let { CardImageLoader.meanLuminance(it) } ?: persistedLum
            if (color != null && luminance != null) {
                // 老数据（图片在"平均色"功能之前导入）在这里回填一次，下次冷启动直接用
                if (persistedColor == null || persistedLum == null) setCardImageStats(color, luminance)
                _cardImage.value = CardImage(full = null, preview = preview, colorArgb = color, luminance = luminance)
            }
            // 2) 正常尺寸
            val full = CardImageLoader.decode(getApplication(), path, 1600)
            if (full != null) {
                val finalColor = color ?: CardImageLoader.averageColor(full)
                val finalLum = luminance ?: CardImageLoader.meanLuminance(full)
                _cardImage.value = CardImage(full, preview, finalColor, finalLum)
            }
        }
    }

    /**
     * 背景图的"平均色 + 整体亮度"：随图一起持久化。
     * 冷启动时位图还要解码，先拿这个颜色打底 → 不会先闪一帧默认几何背景。
     */
    private val _cardImageColor = MutableStateFlow(
        prefs.getInt(KEY_CARD_COLOR, 0).takeIf { it != 0 },
    )
    val cardImageColor: StateFlow<Int?> = _cardImageColor

    private val _cardImageLuminance = MutableStateFlow(
        prefs.getFloat(KEY_CARD_LUMINANCE, -1f).takeIf { it >= 0f },
    )
    val cardImageLuminance: StateFlow<Float?> = _cardImageLuminance

    fun setCardImageStats(argbColor: Int, luminance: Float) {
        prefs.edit()
            .putInt(KEY_CARD_COLOR, argbColor)
            .putFloat(KEY_CARD_LUMINANCE, luminance)
            .apply()
        _cardImageColor.value = argbColor
        _cardImageLuminance.value = luminance
    }

    /** 背景图的位移/缩放/旋转（选图后可在卡片上直接调） */
    private val _cardTransform = MutableStateFlow(
        CardTransform.parse(prefs.getString(KEY_CARD_TRANSFORM, null)),
    )
    val cardTransform: StateFlow<CardTransform> = _cardTransform

    init {
        // 冷启动预热：立刻在 IO 线程碰一次数据库，让 SQLite 打开/建连的开销
        // 与 UI 首帧并行，而不是等页面来查时才付（配合"未就绪先不画"减少空白时间）
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { txDao.countAll() }
        }
        // 卡面图片同样在启动时就开始解码（先缩略图再全尺寸）
        loadCardImage(_cardImageUri.value)
        // 顺手清一次历史遗留图片（编辑器里没走完流程就退出的那几张）
        pruneCardImages()
    }

    fun setCardTransform(transform: CardTransform) {
        val t = transform.normalized()
        prefs.edit().putString(KEY_CARD_TRANSFORM, t.serialize()).apply()
        _cardTransform.value = t
    }

    private val _monthlyBudget = MutableStateFlow(prefs.getLong(KEY_MONTHLY_BUDGET, 0L))
    val monthlyBudgetCents: StateFlow<Long> = _monthlyBudget

    /** cents ≤ 0 视为未设置 */
    fun setMonthlyBudget(cents: Long) {
        prefs.edit().putLong(KEY_MONTHLY_BUDGET, cents).apply()
        _monthlyBudget.value = cents
        AppLog.i("settings", "monthlyBudget=${Money.formatYuan(cents)}")
    }

    // ---------- 设置：货币符号（人民币 / 美元 / 欧元 …；只改符号，不做换算） ----------

    private val _currencySymbol = MutableStateFlow(
        (prefs.getString(KEY_CURRENCY_SYMBOL, Money.DEFAULT_SYMBOL) ?: Money.DEFAULT_SYMBOL)
            .also { Money.symbol = it },
    )
    val currencySymbol: StateFlow<String> = _currencySymbol

    fun setCurrencySymbol(symbol: String) {
        val value = symbol.ifBlank { Money.DEFAULT_SYMBOL }
        prefs.edit().putString(KEY_CURRENCY_SYMBOL, value).apply()
        _currencySymbol.value = value
        Money.symbol = value
        AppLog.i("settings", "currencySymbol=$value")
    }

    private val homeMonth = YearMonth.now(zone)

    private val homeRange = range(homeMonth)

    /**
     * 首页"本月已花"：固定取当前自然月（不随流水页翻月变化）。
     * 口径 = 当月支出合计 − 当月退款到账（现金净流出，退款始终计入，与显示开关无关）。
     * 剩余/建议日花/状态由 UI 用 Budget.compute 纯函数计算。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val homeNetSpent: StateFlow<Long?> = combine(
        txDao.observeTypeTotals(homeRange.first, homeRange.second, true),
        txDao.observeRefundIncomeTotal(homeRange.first, homeRange.second),
    ) { totals, refundIncome ->
        (totals.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L) - refundIncome
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 首页"分类剩余预算"预览：当月各支出分类合计（口径与首页一致，退款始终计入） */
    val homeCategorySums: StateFlow<List<CategorySumRow>?> = txDao
        .observeCategorySums(homeRange.first, homeRange.second, TxType.EXPENSE, true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 首页"本日剩余"用：**今日净支出** = 今日支出 − **今日退款到账**。
     *
     * 预算看的是"花了多少"，所以**工资/红包这类收入不参与**（收入进来不会让今天的额度变多），
     * 但退款要正常算回来（钱退回来了，等于当天没花）。与月度"现金净流出"同口径，只是不看收入侧。
     *
     * 范围跟着"今天"走（[refreshToday]），跨零点或从后台回来时刷新一次，
     * 否则零点后还会一直算昨天的账。
     */
    private val todayRef = MutableStateFlow(LocalDate.now(zone))

    /** 首页 ON_RESUME 时调用：跨天后把"今天"的统计范围换过来 */
    fun refreshToday() {
        val now = LocalDate.now(zone)
        if (now != todayRef.value) {
            todayRef.value = now
            AppLog.i("home", "today=$now")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val homeTodayNetSpent: StateFlow<Long?> = todayRef.flatMapLatest { day ->
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        combine(
            txDao.observeTypeTotals(start, end, true),
            txDao.observeRefundIncomeTotal(start, end),
        ) { totals, refundIncome ->
            (totals.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L) - refundIncome
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------- 月份 ----------

    private val _month = MutableStateFlow(YearMonth.now(zone))
    val month: StateFlow<YearMonth> = _month

    /** 统计周期：按月 / 按年（账单页与统计页共用） */
    private val _periodMode = MutableStateFlow(PeriodMode.MONTH)
    val periodMode: StateFlow<PeriodMode> = _periodMode

    fun setPeriodMode(mode: PeriodMode) {
        _periodMode.value = mode
        AppLog.i("period", "mode=$mode")
    }

    fun prevMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { _month.value = _month.value.plusMonths(1) }

    /** 步进：月模式走一个月，年模式走一年 */
    fun prevPeriod() {
        _month.value = if (_periodMode.value == PeriodMode.YEAR) {
            _month.value.minusYears(1)
        } else {
            _month.value.minusMonths(1)
        }
    }

    fun nextPeriod() {
        _month.value = if (_periodMode.value == PeriodMode.YEAR) {
            _month.value.plusYears(1)
        } else {
            _month.value.plusMonths(1)
        }
    }

    /** 直接跳到指定月份（账单页月份胶囊的选择面板） */
    fun setMonth(m: YearMonth) { _month.value = m }

    private fun range(m: YearMonth): Pair<Long, Long> {
        val start = m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    /** 年区间：1 月 1 日 00:00（含）→ 次年 1 月 1 日 00:00（不含） */
    private fun yearRange(y: Int): Pair<Long, Long> {
        val start = LocalDate.of(y, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(y + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    /** 当前周期的毫秒区间（随月份/年份与周期模式变化） */
    private val periodRange: StateFlow<Pair<Long, Long>> =
        combine(_month, _periodMode) { m, mode ->
            when (mode) {
                PeriodMode.MONTH -> range(m)
                PeriodMode.YEAR -> yearRange(m.year)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), range(YearMonth.now(zone)))

    // 账单/统计共用的当月数据：stateIn 缓存上一次结果 —— 切标签页回来先显示缓存、
    // 后台再刷新，不再"每次进页面从空闪到满"；没有订阅者 5 秒后自动停掉 Room 观察。
    @OptIn(ExperimentalCoroutinesApi::class)
    val periodRows: StateFlow<List<TransactionRow>?> =
        combine(periodRange, _showRefunds) { rng, show -> rng to show }.flatMapLatest { (rng, show) ->
            txDao.observeRows(rng.first, rng.second, show)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val periodTotals: StateFlow<List<TypeTotalRow>?> =
        combine(periodRange, _showRefunds) { rng, show -> rng to show }.flatMapLatest { (rng, show) ->
            txDao.observeTypeTotals(rng.first, rng.second, show)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val periodCategorySums: StateFlow<List<CategorySumRow>?> =
        combine(periodRange, _showRefunds) { rng, show -> rng to show }.flatMapLatest { (rng, show) ->
            txDao.observeCategorySums(rng.first, rng.second, TxType.EXPENSE, show)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 退款到账金额合计（"已取消收支"），是否展示由 UI 根据开关决定 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val periodRefundTotal: StateFlow<Long?> = periodRange.flatMapLatest { rng ->
        txDao.observeRefundIncomeTotal(rng.first, rng.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val accounts: StateFlow<List<AccountEntity>> =
        accountDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        categoryDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val accountBalances: Flow<List<AccountBalanceRow>> = _showRefunds.flatMapLatest { r ->
        txDao.observeBalances(r)
    }

    /** 首页"总存款"预览：全部账户（初始金额 + 交易净额）合计 */
    val totalDepositCents: StateFlow<Long?> = accountBalances
        .map { rows -> rows.sumOf { it.initialBalanceCents + it.signedSumCents } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------- 交易 ----------

    fun saveTransaction(
        id: Long,
        type: TxType,
        amountCents: Long,
        categoryId: Long,
        accountId: Long,
        timestamp: Long,
        note: String,
        /** 商户/收款方：通知捕获自带，手动记录可自定义（账单列表标题优先显示它） */
        counterparty: String = "",
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (id > 0) {
                txDao.getById(id)?.let { old ->
                    txDao.update(
                        old.copy(
                            type = type, amountCents = amountCents, categoryId = categoryId,
                            accountId = accountId, timestamp = timestamp, note = note,
                            counterparty = counterparty,
                        )
                    )
                }
            } else {
                txDao.insert(
                    TransactionEntity(
                        type = type, amountCents = amountCents, categoryId = categoryId,
                        accountId = accountId, timestamp = timestamp, note = note,
                        counterparty = counterparty,
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

    /** 保存分类（新增或改名 / 换图标 / 改类型）；icon 规格见 [CategoryIcon] */
    fun saveCategory(
        id: Long,
        name: String,
        type: TxType,
        icon: String,
        onResult: (savedId: Long, error: String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val savedId = if (id > 0) {
                    categoryDao.getAll().firstOrNull { it.id == id }?.let { old ->
                        // 换图标时清掉旧的图片文件，避免内部存储越攒越多
                        if (old.icon != icon) {
                            CategoryIcon.deleteImage(getApplication(), CategoryIcon.imageOf(old.icon))
                        }
                        categoryDao.update(old.copy(name = name, type = type, icon = icon))
                    }
                    id
                } else {
                    categoryDao.insert(CategoryEntity(name = name, type = type, icon = icon))
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
                val icon = categoryDao.getAll().firstOrNull { it.id == id }?.icon
                categoryDao.deleteById(id)
                CategoryIcon.deleteImage(getApplication(), CategoryIcon.imageOf(icon ?: ""))
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

    private val _autoEntryEnabled = MutableStateFlow(CaptureCenter.isAutoEntryEnabled())
    val autoEntryEnabled: StateFlow<Boolean> = _autoEntryEnabled

    fun setAutoEntryEnabled(enabled: Boolean) {
        CaptureCenter.setAutoEntryEnabled(enabled)
        _autoEntryEnabled.value = enabled
    }

    private val _autoUpdateEnabled = MutableStateFlow(AppUpdate.isAutoUpdateEnabled(getApplication()))
    val autoUpdateEnabled: StateFlow<Boolean> = _autoUpdateEnabled

    fun setAutoUpdateEnabled(enabled: Boolean) {
        AppUpdate.setAutoUpdateEnabled(getApplication(), enabled)
        _autoUpdateEnabled.value = enabled
    }

    /** 手动检查更新（设置页「检查更新」）：无视开关与间隔；发现新版本走全局弹窗，无更新/失败经回调提示 */
    fun checkUpdateNow(onResult: (String?) -> Unit) = AppUpdate.checkNow(getApplication(), onResult)

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

    fun confirmPending(
        item: PendingTransactionEntity,
        categoryId: Long,
        accountId: Long,
        counterparty: String = item.counterparty,
    ) {
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
                    counterparty = counterparty.trim().ifBlank { item.counterparty },
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
                    accountId = CaptureDefaults.accountId(accs, item.sourcePackage) ?: return@launch,
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

    /** 待确认确认弹窗的默认分类（该类型的"其他"，缺省取该类型第一个；规则与自动入账共用） */
    fun guessCategoryId(type: TxType): Long? = CaptureDefaults.categoryId(categories.value, type)

    /** 按来源包名推测账户：微信→微信零钱、支付宝→支付宝、云闪付/其他→银行卡 */
    fun guessAccountId(sourcePackage: String): Long? =
        CaptureDefaults.accountId(accounts.value, sourcePackage)

    fun clearCaptureLog() {
        viewModelScope.launch(Dispatchers.IO) {
            captureDao.clearAll()
            AppLog.i("Ntf", "capture log cleared")
        }
    }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_SHOW_REFUNDS = "show_refunds"
        const val KEY_CARD_IMAGE = "card_image_uri"
        const val KEY_CARD_TRANSFORM = "card_image_transform"
        const val KEY_CARD_COLOR = "card_image_color"
        const val KEY_CARD_LUMINANCE = "card_image_luminance"
        const val KEY_MONTHLY_BUDGET = "monthly_budget_cents"
        const val KEY_CURRENCY_SYMBOL = "currency_symbol"
        const val KEY_CAT_BUDGET_PREFIX = "category_budget_"
    }
}
