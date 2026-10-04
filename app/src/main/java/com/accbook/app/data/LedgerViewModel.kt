package com.accbook.app.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.accbook.app.AccBookApp
import com.accbook.app.csv.BillParsers
import com.accbook.app.data.db.AccountBalanceRow
import com.accbook.app.data.db.AccountEntity
import com.accbook.app.data.db.CategoryEntity
import com.accbook.app.data.db.CategorySumRow
import com.accbook.app.data.db.TransactionEntity
import com.accbook.app.data.db.TransactionRow
import com.accbook.app.data.db.TypeTotalRow
import com.accbook.app.data.db.TxSource
import com.accbook.app.data.db.TxType
import com.accbook.app.data.model.BillSource
import com.accbook.app.data.model.ImportCommitResult
import com.accbook.app.data.model.ParsedBill
import com.accbook.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
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

    private val zone: ZoneId = ZoneId.systemDefault()

    // ---------- 月份 ----------

    private val _month = MutableStateFlow(YearMonth.now(zone))
    val month: StateFlow<YearMonth> = _month

    fun prevMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { _month.value = _month.value.plusMonths(1) }

    private fun range(m: YearMonth): Pair<Long, Long> {
        val start = m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val monthRows: Flow<List<TransactionRow>> = _month.flatMapLatest { m ->
        val (s, e) = range(m); txDao.observeRows(s, e)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val monthTotals: Flow<List<TypeTotalRow>> = _month.flatMapLatest { m ->
        val (s, e) = range(m); txDao.observeTypeTotals(s, e)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val expenseByCategory: Flow<List<CategorySumRow>> = _month.flatMapLatest { m ->
        val (s, e) = range(m); txDao.observeCategorySums(s, e, TxType.EXPENSE)
    }

    val accounts: StateFlow<List<AccountEntity>> =
        accountDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        categoryDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accountBalances: Flow<List<AccountBalanceRow>> = txDao.observeBalances()

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

    fun saveCategory(id: Long, name: String, type: TxType, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (id > 0) {
                    categoryDao.getAll().firstOrNull { it.id == id }?.let { old ->
                        categoryDao.update(old.copy(name = name, type = type))
                    }
                } else {
                    categoryDao.insert(CategoryEntity(name = name, type = type))
                }
                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                AppLog.e("category", "save failed", e)
                withContext(Dispatchers.Main) { onResult("保存失败") }
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
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
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
}
