package com.accbook.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 交易方向 */
enum class TxType { EXPENSE, INCOME }

/** 捕获通道：通知监听 / 无障碍页面抓取（捕获日志共用一张表，用 source 区分） */
enum class CaptureChannel { NOTIFICATION, A11Y }

/** 交易来源：手动输入 / 微信账单 / 支付宝账单 / 通知捕获（后续新增：短信、无障碍） */
enum class TxSource { MANUAL, WECHAT, ALIPAY, NOTIFICATION }

/**
 * 退款标记：
 * - NONE：普通记录
 * - REFUNDED_EXPENSE：被全额退款的支出（钱已退回，另有对应的退款收入记录）
 * - REFUND_INCOME：退款到账收入（微信"XXX-退款"收入行 / 支付宝"退款成功"行）
 * 是否显示与计入汇总由设置"显示退款记录"控制；记录始终入库。
 */
enum class RefundMark { NONE, REFUNDED_EXPENSE, REFUND_INCOME }

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
    /** 外部单号（微信交易单号/支付宝交易号/捕获去重键），用于账单重复导入与捕获去重；手动记录为 null */
    val externalId: String? = null,
    val refundMark: RefundMark = RefundMark.NONE,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 待确认条目状态（确认/丢弃后条目即删除，状态仅用于极端情况的残留清理） */
enum class PendingStatus { PENDING, CONFIRMED, DISCARDED }

/** 原始捕获日志：采集层立即落库的原始快照，不解析；兼做调试证据与"未匹配样本池"，定期清理 */
@Entity(tableName = "captured_notifications")
data class CapturedNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String = "",
    val text: String = "",
    val bigText: String = "",
    val postTime: Long,
    val receivedAt: Long = System.currentTimeMillis(),
    /** 捕获通道：通知监听 / 无障碍页面抓取 */
    @ColumnInfo(defaultValue = "NOTIFICATION")
    val source: CaptureChannel = CaptureChannel.NOTIFICATION,
    /** 命中的规则名；空 = 未匹配 */
    val matchedRule: String = "",
    /** 是否已进入待确认队列 */
    val queued: Boolean = false,
)

/** 自动捕获、待用户确认的交易；确认后转为交易（externalId = "NTF:" + dedupKey 防重） */
@Entity(
    tableName = "pending_transactions",
    indices = [Index(value = ["dedupKey"], unique = true)],
)
data class PendingTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TxType,
    val amountCents: Long,
    val timestamp: Long,
    val counterparty: String = "",
    val note: String = "",
    val sourcePackage: String,
    val ruleName: String,
    /** 去重键：来源包名 + 方向 + 金额 + 分钟级时间 */
    val dedupKey: String,
    val rawNotificationId: Long? = null,
    /** 退款标记：通知捕获的退款到账收入打 REFUND_INCOME，与账单导入口径一致 */
    @ColumnInfo(defaultValue = "NONE")
    val refundMark: RefundMark = RefundMark.NONE,
    val status: PendingStatus = PendingStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
)
