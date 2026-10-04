package com.accbook.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 交易方向 */
enum class TxType { EXPENSE, INCOME }

/** 交易来源：手动输入 / 微信账单 / 支付宝账单（后续新增：通知、短信、无障碍） */
enum class TxSource { MANUAL, WECHAT, ALIPAY }

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val initialBalanceCents: Long = 0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: TxType,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "transactions",
    indices = [Index(value = ["externalId"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TxType,
    val amountCents: Long,
    val categoryId: Long,
    val accountId: Long,
    val timestamp: Long,
    val note: String = "",
    val counterparty: String = "",
    val source: TxSource = TxSource.MANUAL,
    /** 外部单号（微信交易单号/支付宝交易号），用于账单重复导入去重；手动记录为 null */
    val externalId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
