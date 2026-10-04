package com.accbook.app.data.model

import com.accbook.app.data.db.TxType

/** 账单来源（阶段一：CSV 导出文件；后续阶段扩展通知、短信等） */
enum class BillSource(val displayName: String) {
    WECHAT("微信支付"),
    ALIPAY("支付宝"),
}

/** 解析后待确认的单条账单 */
data class ImportedBillRow(
    val type: TxType,
    val amountCents: Long,
    val timestamp: Long,
    val counterparty: String,
    val goods: String,
    val note: String,
    val payMethod: String,
    val externalId: String?,
    val guessedCategoryName: String,
)

/** 一个账单文件的解析结果 */
data class ParsedBill(
    val source: BillSource,
    val rows: List<ImportedBillRow>,
    /** 不计收支（如零钱充值、提现、理财申购）跳过的条数 */
    val skippedOtherType: Int,
    /** 状态未完成或含退款而跳过的条数 */
    val skippedStatus: Int,
)

data class ImportCommitResult(val inserted: Int, val duplicated: Int)
