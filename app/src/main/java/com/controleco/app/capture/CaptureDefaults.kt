package com.controleco.app.capture

import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.util.CategoryGuesser

/**
 * 捕获入账的分类/账户推测（待确认弹窗与自动入账共用一套规则，纯函数便于单测）：
 * 分类优先按商户/备注关键词猜（CategoryGuesser，"滴滴出行"→交通、"医院"→医疗…），
 * 猜中的分类必须与方向匹配才采用，否则回退该类型的「其他」（没有则第一个）；
 * 账户按来源包名推测（微信/支付宝/银行），未命中取第一个。
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

    fun accountId(accounts: List<AccountEntity>, sourcePackage: String): Long? {
        val keyword = when (sourcePackage) {
            NotificationRules.WECHAT -> "微信"
            NotificationRules.ALIPAY -> "支付宝"
            else -> "银行"
        }
        return accounts.firstOrNull { it.name.contains(keyword) }?.id
            ?: accounts.firstOrNull()?.id
    }
}
