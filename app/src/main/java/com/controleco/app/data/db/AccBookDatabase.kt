package com.controleco.app.data.db

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
        CaptureTraceEntity::class,
        MerchantRuleEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AccBookDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun captureDao(): CaptureDao
    abstract fun pendingDao(): PendingDao
    abstract fun traceDao(): TraceDao
    abstract fun merchantRuleDao(): MerchantRuleDao

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

        /** v4：待确认队列表新增退款标记列（defaultValue 与实体声明一致，保证迁移库与新建库 schema 完全一致） */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN refundMark TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        /** v5：捕获日志表新增通道列，区分通知监听与无障碍页面抓取（defaultValue 与实体声明一致） */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE captured_notifications ADD COLUMN source TEXT NOT NULL DEFAULT 'NOTIFICATION'")
            }
        }

        /** v6：分类表新增图标列（emoji / 内置图形 / 上传图片，defaultValue 与实体声明一致） */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v7：自动记账溯源表（开发者功能「记录记账来源」，默认关闭时零写入） */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `capture_traces` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`channel` TEXT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`bigText` TEXT NOT NULL, " +
                        "`capturedAt` INTEGER NOT NULL, " +
                        "`matchedRule` TEXT NOT NULL, " +
                        "`type` TEXT, " +
                        "`amountCents` INTEGER NOT NULL, " +
                        "`counterparty` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, " +
                        "`dedupKey` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_capture_traces_dedupKey` " +
                        "ON `capture_traces` (`dedupKey`)",
                )
            }
        }


        /** v8：商户归类学习规则表（确认/编辑捕获交易时学习，越用越准） */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `merchant_rules` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`matchKey` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`categoryId` INTEGER NOT NULL, " +
                        "`accountId` INTEGER, " +
                        "`hits` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_rules_matchKey_type` " +
                        "ON `merchant_rules` (`matchKey`, `type`)",
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
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8,
                    )
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
