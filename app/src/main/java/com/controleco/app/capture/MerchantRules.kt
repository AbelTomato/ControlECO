package com.controleco.app.capture

import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.MerchantRuleEntity
import com.controleco.app.data.db.MerchantRuleDao
import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog

/**
 * 商户归类学习（"越用越准"，自研方案的商户规则学习思路，见
 * docs/自动记账捕获机制.md 第一节「归类学习」）。
 *
 * 规则：确认/编辑捕获来的交易时，把"商户词 → 分类/账户"记下来；下次同商户捕获时优先用学到的归类。
 * 刻意不做的事：
 * - 不从手动记账学习（一次性备注会污染规则）；
 * - 平台通用词（微信支付/拼多多…）、纯数字、过短的词不入库（学了也不会命中，还占地方）；
 * - 规则条数封顶（每次学习后按最近保留 500 条）；
 * - 应用规则前校验分类/账户仍然存在（分类被删后规则自动失效，不会写坏账目）。
 */
internal object MerchantRules {

    /** 规则条数上限：够覆盖常用商户，又不至于无限膨胀 */
    const val MAX_RULES = 500

    /** 不入库的通用词/平台名（这些不是"商户"） */
    private val stopWords = setOf(
        "微信支付", "支付宝", "云闪付", "银联", "微信收款",
        "转账", "红包", "收款", "付款", "支付", "消费", "扫码", "商户", "订单", "交易",
        "零钱", "零钱通", "余额", "余额宝", "花呗", "借呗", "银行卡", "储蓄卡", "信用卡",
        "京东", "京东支付", "淘宝", "天猫", "拼多多", "抖音", "美团", "美团外卖", "饿了么",
        "微信", "支付宝转账", "收款方", "对方",
    )

    private val punctuation = Regex("""[\s\-_·,，。;；:：、|/\\()（）\[\]【】"'“”‘’]+""")

    /**
     * 学习键：优先商户字段，空则退回备注；归一化（去空白标点、小写、截断 20 字）。
     * 返回 null = 不值得学（空/过短/纯数字/通用词/像整条通知原文）。
     */
    fun keyOf(counterparty: String, note: String): String? {
        val source = counterparty.trim().ifBlank { note.trim() }
        if (source.isBlank()) return null
        val key = punctuation.replace(source, "").lowercase().take(20)
        if (key.length < 2) return null
        if (key.all { it.isDigit() }) return null
        if (key in stopWords) return null
        // 像整条通知原文（很长、含"元/¥"）的备注不做键——它是一次性的，不是"商户"
        if (key.contains('¥') || key.contains("元") && key.length > 12) return null
        return key
    }

    /**
     * 学习一条规则：已有同键同方向的规则 → 命中 +1 并更新归类；没有则新建。
     * 失败只记日志，绝不影响入账主流程。
     */
    suspend fun learn(
        dao: MerchantRuleDao,
        type: TxType,
        categoryId: Long,
        accountId: Long?,
        counterparty: String,
        note: String,
    ) {
        val key = keyOf(counterparty, note) ?: return
        runCatching {
            val existing = dao.find(key, type)
            if (existing == null) {
                dao.upsert(
                    MerchantRuleEntity(
                        matchKey = key,
                        type = type,
                        categoryId = categoryId,
                        accountId = accountId,
                    ),
                )
                AppLog.i("learn", "merchant rule added key=$key -> cat=$categoryId acc=$accountId")
            } else if (existing.categoryId != categoryId || existing.accountId != accountId) {
                dao.upsert(
                    existing.copy(
                        categoryId = categoryId,
                        accountId = accountId,
                        hits = existing.hits + 1,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                AppLog.i("learn", "merchant rule updated key=$key -> cat=$categoryId acc=$accountId")
            } else {
                dao.upsert(existing.copy(hits = existing.hits + 1, updatedAt = System.currentTimeMillis()))
            }
            dao.keepNewest(MAX_RULES)
        }.onFailure { AppLog.w("learn", "merchant learn failed", it) }
    }

    /** 命中的规则（键 + 方向）；没命中返回 null */
    fun match(rules: List<MerchantRuleEntity>, type: TxType, counterparty: String, note: String): MerchantRuleEntity? {
        if (rules.isEmpty()) return null
        val key = keyOf(counterparty, note) ?: return null
        return rules.firstOrNull { it.matchKey == key && it.type == type }
    }

    /**
     * 应用规则得到分类 id：规则命中且分类仍存在才生效（分类被删 → 规则失效，回退猜测链）。
     */
    fun categoryIdFrom(
        rules: List<MerchantRuleEntity>,
        categories: List<CategoryEntity>,
        type: TxType,
        counterparty: String,
        note: String,
    ): Long? {
        val rule = match(rules, type, counterparty, note) ?: return null
        return categories.firstOrNull { it.id == rule.categoryId && it.type == type }?.id
    }

    /** 应用规则得到账户 id：规则命中且账户仍存在才生效 */
    fun accountIdFrom(
        rules: List<MerchantRuleEntity>,
        accounts: List<AccountEntity>,
        type: TxType,
        counterparty: String,
        note: String,
    ): Long? {
        val rule = match(rules, type, counterparty, note) ?: return null
        val id = rule.accountId ?: return null
        return accounts.firstOrNull { it.id == id }?.id
    }
}
