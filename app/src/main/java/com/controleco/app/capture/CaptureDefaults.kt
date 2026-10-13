package com.controleco.app.capture

import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import com.controleco.app.util.CategoryGuesser

/**
 * 捕获入账的分类/账户推测（待确认弹窗与自动入账共用一套规则，纯函数便于单测）：
 * 分类优先按商户/备注关键词猜（CategoryGuesser，"滴滴出行"→交通、"医院"→医疗…），
 * 猜中的分类必须与方向匹配才采用，否则回退该类型的「其他」（没有则第一个）；
 * 账户按来源推测（微信/支付宝/银行名），未命中取第一个。
 */
internal object CaptureDefaults {

    fun categoryId(categories: List<CategoryEntity>, type: TxType, merchantHint: String = ""): Long? {
        val hint = merchantHint.trim()
        if (hint.isNotEmpty()) {
            val guessed = CategoryGuesser.guess(hint)
            categories.firstOrNull { it.type == type && it.name == guessed }?.id?.let { return it }
        }
        return categories.firstOrNull { it.type == type && it.name == "其他" }?.id
            ?: categories.firstOrNull { it.type == type }?.id
    }

    /**
     * 账户推测。银行侧（短信 / 银行 App 推送）先按银行名匹配账户名
     * （账户常起名"中国银行储蓄卡"），再退化到"银行"关键词；支付渠道按渠道名匹配。
     */
    fun accountId(accounts: List<AccountEntity>, sourcePackage: String, bankName: String? = null): Long? {
        if (bankName != null) {
            // 账户名常起"招行卡""工行"这类简称：按标准名 + 全部别名逐个匹配
            for (alias in BankLedgerRules.aliasesOf(bankName)) {
                if (alias.length < 2) continue
                accounts.firstOrNull { it.name.contains(alias) }?.id?.let { return it }
            }
        }
        val keyword = when (sourcePackage) {
            NotificationRules.WECHAT -> "微信"
            NotificationRules.ALIPAY -> "支付宝"
            else -> "银行"
        }
        return accounts.firstOrNull { it.name.contains(keyword) }?.id
            ?: accounts.firstOrNull()?.id
    }

    /** 是否为银行侧来源（短信前缀 / 云闪付 / 银行 App 推送包名）：决定交易来源标记与账户推测口径 */
    fun isBankSource(sourcePackage: String): Boolean =
        sourcePackage.startsWith(NotificationRules.SMS_SOURCE_PREFIX) ||
            sourcePackage == NotificationRules.UNIONPAY ||
            BankLedgerRules.bankNameOfPackage(sourcePackage) != null

    /**
     * 从来源键/规则名里取回银行名（账户推测用）：
     * 短信来源键是 `SMS:工商银行`（号码兜底时形如 `SMS:95588`，须再从规则名里找签名）；
     * 银行 App 推送按包名短名单，找不到再退回规则名（规则名形如"工商银行通知·支出"）。
     */
    fun bankNameOf(sourcePackage: String, ruleName: String = ""): String? {
        if (sourcePackage.startsWith(NotificationRules.SMS_SOURCE_PREFIX)) {
            val fromKey = sourcePackage.removePrefix(NotificationRules.SMS_SOURCE_PREFIX)
            if (fromKey.any { !it.isDigit() }) return fromKey
            return BankLedgerRules.detectBankName(ruleName)
        }
        return BankLedgerRules.bankNameOfPackage(sourcePackage) ?: BankLedgerRules.detectBankName(ruleName)
    }

    /** 捕获来源 → 交易来源标记（银行侧单独标记，便于溯源与来源筛选） */
    fun txSource(sourcePackage: String): TxSource =
        if (isBankSource(sourcePackage)) TxSource.BANK else TxSource.NOTIFICATION
}
