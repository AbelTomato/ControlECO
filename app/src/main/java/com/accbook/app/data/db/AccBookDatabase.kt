package com.accbook.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        CapturedNotificationEntity::class,
        PendingTransactionEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AccBookDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun captureDao(): CaptureDao
    abstract fun pendingDao(): PendingDao

    companion object {
        /** v2：交易表新增退款标记列 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN refundMark TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        /** v3：通知采集通道——原始捕获日志表 + 待确认队列表 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `captured_notifications` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`bigText` TEXT NOT NULL, " +
                        "`postTime` INTEGER NOT NULL, " +
                        "`receivedAt` INTEGER NOT NULL, " +
                        "`matchedRule` TEXT NOT NULL, " +
                        "`queued` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_transactions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`amountCents` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`counterparty` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, " +
                        "`sourcePackage` TEXT NOT NULL, " +
                        "`ruleName` TEXT NOT NULL, " +
                        "`dedupKey` TEXT NOT NULL, " +
                        "`rawNotificationId` INTEGER, " +
                        "`status` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_transactions_dedupKey` " +
                        "ON `pending_transactions` (`dedupKey`)",
                )
            }
        }

        @Volatile
        private var instance: AccBookDatabase? = null

        fun get(context: Context): AccBookDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AccBookDatabase::class.java,
                    "accbook.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { instance = it }
            }
    }
}

/** 首次启动的默认数据：常用账户与收支分类 */
object DefaultData {

    val defaultAccounts = listOf("现金", "微信零钱", "支付宝", "银行卡")

    val defaultExpenseCategories =
        listOf("餐饮", "交通", "购物", "日用", "居住", "娱乐", "医疗", "其他")

    val defaultIncomeCategories = listOf("工资", "理财", "红包", "其他")

    suspend fun seedIfNeeded(db: AccBookDatabase) {
        if (db.accountDao().countAll() == 0) {
            db.accountDao().insertAll(defaultAccounts.map { AccountEntity(name = it) })
        }
        if (db.categoryDao().countAll() == 0) {
            val expense = defaultExpenseCategories.mapIndexed { i, name ->
                CategoryEntity(name = name, type = TxType.EXPENSE, sortOrder = i)
            }
            val income = defaultIncomeCategories.mapIndexed { i, name ->
                CategoryEntity(name = name, type = TxType.INCOME, sortOrder = i)
            }
            db.categoryDao().insertAll(expense + income)
        }
    }
}
