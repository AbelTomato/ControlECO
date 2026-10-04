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

    @Query(
        """SELECT t.id AS id, t.type AS type, t.amountCents AS amountCents, t.timestamp AS timestamp,
           t.note AS note, t.counterparty AS counterparty, t.source AS source,
           c.id AS categoryId, c.name AS categoryName, a.id AS accountId, a.name AS accountName
           FROM transactions t
           JOIN categories c ON t.categoryId = c.id
           JOIN accounts a ON t.accountId = a.id
           WHERE t.timestamp >= :startMillis AND t.timestamp < :endMillis
           ORDER BY t.timestamp DESC, t.id DESC"""
    )
    fun observeRows(startMillis: Long, endMillis: Long): Flow<List<TransactionRow>>

    @Query(
        """SELECT type AS type, SUM(amountCents) AS totalCents FROM transactions
           WHERE timestamp >= :startMillis AND timestamp < :endMillis
           GROUP BY type"""
    )
    fun observeTypeTotals(startMillis: Long, endMillis: Long): Flow<List<TypeTotalRow>>

    @Query(
        """SELECT c.id AS categoryId, c.name AS categoryName, SUM(t.amountCents) AS totalCents
           FROM transactions t JOIN categories c ON t.categoryId = c.id
           WHERE t.timestamp >= :startMillis AND t.timestamp < :endMillis AND t.type = :type
           GROUP BY c.id, c.name ORDER BY totalCents DESC"""
    )
    fun observeCategorySums(startMillis: Long, endMillis: Long, type: TxType): Flow<List<CategorySumRow>>

    @Query(
        """SELECT a.id AS accountId, a.initialBalanceCents AS initialBalanceCents,
           COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' THEN -t.amountCents ELSE t.amountCents END), 0) AS signedSumCents
           FROM accounts a LEFT JOIN transactions t ON t.accountId = a.id
           GROUP BY a.id"""
    )
    fun observeBalances(): Flow<List<AccountBalanceRow>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

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
