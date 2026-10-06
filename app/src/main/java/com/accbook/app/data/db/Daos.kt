package com.accbook.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 列表页一行：交易 + 关联分类/账户名 */
data class TransactionRow(
    val id: Long,
    val type: TxType,
    val amountCents: Long,
    val timestamp: Long,
    val note: String,
    val counterparty: String,
    val source: TxSource,
    val categoryId: Long,
    val categoryName: String,
    val accountId: Long,
    val accountName: String,
    val refundMark: RefundMark,
)

data class TypeTotalRow(
    val type: TxType,
    val totalCents: Long,
)

data class CategorySumRow(
    val categoryId: Long,
    val categoryName: String,
    val totalCents: Long,
)

/** 账户余额行：初始金额 + 该账户交易净额（支出为负） */
data class AccountBalanceRow(
    val accountId: Long,
    val initialBalanceCents: Long,
    val signedSumCents: Long,
)

@Dao
interface TransactionDao {

    /**
     * includeRefunds=false 时过滤掉退款标记记录（设置"显示退款记录"关闭）。
     */
    @Query(
        """SELECT t.id AS id, t.type AS type, t.amountCents AS amountCents, t.timestamp AS timestamp,
           t.note AS note, t.counterparty AS counterparty, t.source AS source,
           c.id AS categoryId, c.name AS categoryName, a.id AS accountId, a.name AS accountName,
           t.refundMark AS refundMark
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.timestamp >= :startMillis AND t.timestamp < :endMillis
             AND (:includeRefunds = 1 OR t.refundMark = 'NONE')
           ORDER BY t.timestamp DESC, t.id DESC"""
    )
    fun observeRows(startMillis: Long, endMillis: Long, includeRefunds: Boolean): Flow<List<TransactionRow>>

    @Query(
        """SELECT type AS type, SUM(amountCents) AS totalCents FROM transactions
           WHERE timestamp >= :startMillis AND timestamp < :endMillis
             AND (:includeRefunds = 1 OR refundMark = 'NONE')
           GROUP BY type"""
    )
    fun observeTypeTotals(startMillis: Long, endMillis: Long, includeRefunds: Boolean): Flow<List<TypeTotalRow>>

    @Query(
        """SELECT c.id AS categoryId, c.name AS categoryName, SUM(t.amountCents) AS totalCents
           FROM transactions t JOIN categories c ON t.categoryId = c.id
           WHERE t.timestamp >= :startMillis AND t.timestamp < :endMillis AND t.type = :type
             AND (:includeRefunds = 1 OR t.refundMark = 'NONE')
           GROUP BY c.id, c.name ORDER BY totalCents DESC"""
    )
    fun observeCategorySums(startMillis: Long, endMillis: Long, type: TxType, includeRefunds: Boolean): Flow<List<CategorySumRow>>

    /** 退款到账金额合计（"已取消收支"），与显示开关无关，由 UI 决定是否展示 */
    @Query(
        """SELECT COALESCE(SUM(amountCents), 0) FROM transactions
           WHERE timestamp >= :startMillis AND timestamp < :endMillis
             AND type = 'INCOME' AND refundMark = 'REFUND_INCOME'"""
    )
    fun observeRefundIncomeTotal(startMillis: Long, endMillis: Long): Flow<Long>

    @Query(
        """SELECT a.id AS accountId, a.initialBalanceCents AS initialBalanceCents,
           COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' THEN -t.amountCents ELSE t.amountCents END), 0) AS signedSumCents
           FROM accounts a LEFT JOIN transactions t
             ON t.accountId = a.id AND (:includeRefunds = 1 OR t.refundMark = 'NONE')
           GROUP BY a.id"""
    )
    fun observeBalances(includeRefunds: Boolean): Flow<List<AccountBalanceRow>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    /** 退款关联候选：近 60 天未退款的支出（供"无金额退款通知→手动关联"使用） */
    @Query(
        """SELECT t.id AS id, t.type AS type, t.amountCents AS amountCents, t.timestamp AS timestamp,
           t.note AS note, t.counterparty AS counterparty, t.source AS source,
           c.id AS categoryId, c.name AS categoryName, a.id AS accountId, a.name AS accountName,
           t.refundMark AS refundMark
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.type = 'EXPENSE' AND t.refundMark = 'NONE' AND t.timestamp >= :since
           ORDER BY t.timestamp DESC LIMIT 50"""
    )
    suspend fun recentUnrefundedExpenses(since: Long): List<TransactionRow>

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countByCategory(categoryId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId")
    suspend fun countByAccount(accountId: Long): Int

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(txs: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(tx: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY type, sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun countAll(): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Insert
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun countAll(): Int

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Insert
    suspend fun insertAll(accounts: List<AccountEntity>)

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CaptureDao {

    @Insert
    suspend fun insert(notification: CapturedNotificationEntity): Long

    @Query("UPDATE captured_notifications SET matchedRule = :rule, queued = :queued WHERE id = :id")
    suspend fun updateMatch(id: Long, rule: String, queued: Boolean)

    @Query("SELECT * FROM captured_notifications ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CapturedNotificationEntity>>

    @Query("SELECT * FROM captured_notifications WHERE source = :source ORDER BY id DESC LIMIT :limit")
    fun observeRecentBySource(source: CaptureChannel, limit: Int): Flow<List<CapturedNotificationEntity>>

    @Query("DELETE FROM captured_notifications WHERE receivedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM captured_notifications WHERE id NOT IN (SELECT id FROM captured_notifications ORDER BY id DESC LIMIT :keep)")
    suspend fun keepNewest(keep: Int)

    @Query("DELETE FROM captured_notifications")
    suspend fun clearAll()
}

@Dao
interface PendingDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pending: PendingTransactionEntity): Long

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE dedupKey = :dedupKey")
    suspend fun countByDedupKey(dedupKey: String): Int

    @Query("UPDATE pending_transactions SET counterparty = :counterparty, note = :note WHERE id = :id")
    suspend fun updateEnrichment(id: Long, counterparty: String, note: String)

    @Query("SELECT * FROM pending_transactions WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun observePending(): Flow<List<PendingTransactionEntity>>

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE status = 'PENDING'")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_transactions WHERE status = 'PENDING'")
    suspend fun getAllPending(): List<PendingTransactionEntity>

    @Query("DELETE FROM pending_transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_transactions WHERE dedupKey = :dedupKey")
    suspend fun deleteByDedupKey(dedupKey: String)
}
