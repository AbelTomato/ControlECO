package com.accbook.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AccountEntity::class, CategoryEntity::class, TransactionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AccBookDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        @Volatile
        private var instance: AccBookDatabase? = null

        fun get(context: Context): AccBookDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AccBookDatabase::class.java,
                    "accbook.db",
                ).build().also { instance = it }
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
