package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 阶段三页面规则引擎：解析无障碍服务送来的页面文本快照（微信/支付宝的「支付成功页」「账单详情页」）。
 * 方法论与 NotificationRules 一致：纯函数、可单测、样本驱动；规则失效时靠 debug 页面快照日志定位。
 *
 * 输入是"文本节点列表"（按控件树顺序，展开 text 与 contentDescription），刻意不依赖 viewId——
 * 目标应用改版时文本特征比控件结构稳定得多。
 *
 * v1 覆盖范围（宁漏勿错）：
 * - 支出方向的支付成功页（微信"支付成功"/支付宝"付款成功"）与账单详情页（"账单详情"）
 * - 退款页、收入页、超过 24 小时的旧账单详情一律跳过（通知/账单导入通道已覆盖，页面误开旧账单不产生垃圾条目）
 * - 提取字段：金额、收款方/商家、支付方式、交易时间（note 只存这些字段，不存整页文本，避免卡号/余额入库）
 */
object PageRules {

    /** 收银台页（还没付）关键词：出现即跳过——"支付成功得xx券"这类营销文案会让收银台页误判成支付成功页 */
    private val cashierKeywords = listOf("确认付款", "使用密码", "请验证")

    /** 页面类型判定关键词 */
    private val billDetailKeywords = listOf("账单详情", "交易详情")
    private val successKeywords = listOf("支付成功", "付款成功", "支付完成", "支付详情")

    /** 状态短语：提取"标签→值"时用于排除把状态行当收款方/支付方式 */
    private val statusPhrases = listOf(
        "对方已收钱", "已收钱", "已存入", "支付成功", "付款成功", "支付完成",
        "退款成功", "交易成功", "已支付", "已领取", "待收钱", "已退款", "已全额退款",
    )

    /** 收款方/商家标签（具体在前，"对方"最宽泛放最后） */
    private val counterpartyLabels = listOf("商户全称", "商户名称", "收单商户", "商家全称", "收款方", "收款人", "商家", "对方")

    private val paymentMethodLabels = listOf("支付方式", "付款方式")
    private val timeLabels = listOf("交易时间", "付款时间", "支付时间", "创建时间")

    /**
     * 无标签商户的排除项（支付宝成功页的商户是独立节点，如"拼多多平台商户"）：
     * 真实样本（2026-10）节点序 = [支付成功￥12.90, 拼多多平台商户, ￥12.90, 付款方式, 中国银行储蓄卡(8920), x3, 完成…]
     * 商户紧跟"支付成功"标题节点，兜底从锚点向后扫描第一个"像名称"的节点。
     */
    private val fallbackExcluded = counterpartyLabels + paymentMethodLabels + timeLabels +
        listOf("完成", "确认", "取消", "查看", "收起", "展开", "复制", "返回", "同意", "关闭", "继续", "知道了",
            "账单详情", "交易详情", "优惠", "红包",
            // 页面尚未渲染完时商户节点可能还没出来，导航类节点不能当收款方（真实样本"回首页"曾被当商户）
            "首页", "客服", "更多")

    private val stepperNode = Regex("""^x\d+$""")

    /** 金额：优先"金额标签"同行，其次独立金额节点（成功页大字），最后任意 ¥ 金额 */
    private val labeledAmount = Regex("""(?:付款金额|支付金额|交易金额|金额)\s*[：:]?\s*[￥¥]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val standaloneAmount = Regex("""^[￥¥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)$""")
    private val anyAmount = Regex("""[￥¥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")

    /** 时间：2026-10-06 12:34[:56] / 2026.10.6 12:34 / 2026年10月6日 12:34 */
    private val timePattern = Regex("""(\d{4})[-/.年]\s*(\d{1,2})[-/.月]\s*(\d{1,2})日?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")

    /** 交易时间与查看时刻的最大允许偏差：超过视为在翻旧账单，不入队 */
    private const val MAX_AGE_MS = 24 * 3_600_000L

    private const val MAX_TEXTS = 200

    data class ParsedPage(
        val type: TxType,
        val amountCents: Long,
        /** 收款方/商家（入账进 counterparty 字段） */
        val counterparty: String,
        /** 支付方式（零钱/银行卡/余额宝…），进入 note 供确认时参考 */
        val paymentMethod: String?,
        /** 页面上的交易时间；无则由调用方用捕获时刻 */
        val txTime: Long?,
        val sourceLabel: String,
        val ruleName: String,
        val note: String,
        /** 与通知通道同构：包名|TYPE|金额分|分钟（txTime 优先，跨通道同一笔可对齐去重） */
        val dedupKey: String,
    )

    /**
     * 解析页面文本快照。返回 null = 不感兴趣/无法安全解析（方向不明、无金额、退款/收入页、旧账单）。
     */
    fun parse(packageName: String, texts: List<String>, capturedAt: Long): ParsedPage? {
        if (packageName !in NotificationRules.knownPackages) return null
        val nodes = texts.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_TEXTS).toList()
        if (nodes.isEmpty()) return null
        val joined = nodes.joinToString(" ")

        // 收银台页（还没付）：含"确认付款/使用密码/请验证"，直接跳过
        if (cashierKeywords.any { joined.contains(it) }) {
            AppLog.i("Page", "skip (cashier page, payment not completed) pkg=$packageName")
            return null
        }

        val isBillDetail = billDetailKeywords.any { joined.contains(it) }
        val isSuccess = successKeywords.any { joined.contains(it) }
        if (!isBillDetail && !isSuccess) {
            AppLog.d("Page", "skip (not a payment page) pkg=$packageName head=${joined.take(120)}")
            return null
        }
        val kind = if (isBillDetail) "账单详情" else "支付页"

        // 退款页跳过：退款到账由通知通道与账单导入覆盖，页面侧暂不识别（见维护指南，扩规则再加）
        if (joined.contains("退款") || joined.contains("退回")) {
            AppLog.d("Page", "skip (refund page, v1 not covered) pkg=$packageName kind=$kind")
            return null
        }

        // 方向判定。注意："对方已收钱"是微信转账支出的状态，其中的"已收钱"不代表收入；
        // "收款方"标签在转账支出页也出现，同样不能当收入依据。
        val transferOut = joined.contains("对方已收钱")
        val incomeHit = joined.contains("已存入") || joined.contains("收入") ||
            (joined.contains("已收钱") && !transferOut)
        val expenseHit = joined.contains("支出") || isSuccess || transferOut
        if (incomeHit || !expenseHit) {
            AppLog.d("Page", "skip (direction income/unclear, v1 expense-only) pkg=$packageName kind=$kind")
            return null
        }

        // 金额优先级：锚点节点里的实付金额（"支付成功￥12.90"，真实样本标题节点即含实付）
        // → "金额"标签行 → 独立 ¥ 节点 → 任意 ¥ 金额。
        // 锚点优先是因为成功页会同时出现订单金额/优惠金额等干扰节点。
        val anchorIdx = nodes.indexOfFirst { node -> (successKeywords + billDetailKeywords).any { node.contains(it) } }
        val amountText = (if (anchorIdx >= 0) paidAmountInAnchor(nodes[anchorIdx]) else null)
            ?: labeledAmount.find(joined)?.groupValues?.get(1)
            ?: nodes.firstNotNullOfOrNull { standaloneAmount.find(it)?.groupValues?.get(1) }
            ?: anyAmount.find(joined)?.groupValues?.get(1)
        if (amountText == null) {
            AppLog.d("Page", "rule amount not found pkg=$packageName kind=$kind head=${joined.take(160)}")
            return null
        }
        val amountCents = Money.parseCents(amountText)
        if (amountCents == null || amountCents <= 0) {
            // 实付 0 元（全额优惠）无需记账；此前把订单金额 ¥0.01 当实付就是这种场景
            AppLog.i("Page", "skip (zero amount, nothing to record) pkg=$packageName kind=$kind amount=$amountText")
            return null
        }

        val counterparty = valueAfterLabel(nodes, counterpartyLabels)
            ?: guessCounterparty(nodes)
            ?: ""
        val paymentMethod = valueAfterLabel(nodes, paymentMethodLabels)
        val txTimeText = valueAfterLabel(nodes, timeLabels) ?: joined
        val txTime = timePattern.find(txTimeText)?.let { m ->
            runCatching {
                LocalDateTime.of(
                    m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt(),
                    m.groupValues[6].ifEmpty { "0" }.toInt(),
                ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        }

        if (txTime != null && capturedAt - txTime > MAX_AGE_MS) {
            AppLog.d("Page", "skip (old bill detail) pkg=$packageName kind=$kind ageHours=${(capturedAt - txTime) / 3_600_000}")
            return null
        }

        val label = NotificationRules.sourceLabel(packageName)
        val note = paymentMethod?.let { "支付方式 $it" }.orEmpty()

        return ParsedPage(
            type = TxType.EXPENSE,
            amountCents = amountCents,
            counterparty = counterparty,
            paymentMethod = paymentMethod,
            txTime = txTime,
            sourceLabel = label,
            ruleName = "$label·$kind",
            note = note,
            dedupKey = "$packageName|${TxType.EXPENSE}|$amountCents|${(txTime ?: capturedAt) / 60_000}",
        )
    }

    /**
     * 页面是否值得存进捕获日志（未匹配样本池）：支付类关键词命中即存，
     * 聊天/朋友圈等无关页面不落库，避免捕获日志被无障碍事件刷爆。
     */
    fun looksInteresting(texts: List<String>): Boolean {
        val joined = texts.joinToString(" ")
        return (successKeywords + billDetailKeywords + counterpartyLabels + paymentMethodLabels +
            listOf("交易金额", "付款金额", "交易时间", "交易单号")).any { joined.contains(it) }
    }

    /**
     * 锚点节点里的实付金额：跳过优惠项（前缀为 -/减/惠 的 ¥ 金额，如"支付遇财神 -￥0.01"）。
     */
    private fun paidAmountInAnchor(node: String): String? =
        anyAmount.findAll(node).firstOrNull { m ->
            val prev = if (m.range.first > 0) node[m.range.first - 1] else '　'
            prev != '-' && prev != '－' && prev != '减' && prev != '惠'
        }?.groupValues?.get(1)

    /**
     * "标签→值"提取：同行值必须紧跟分隔符（冒号或空格）——
     * "点击给商家对账"这类标签嵌在长句里的节点不产生值（曾把"对账"误当收款方）；
     * 标签独占一个节点时取其后的第一个有效值节点。
     */
    private fun valueAfterLabel(nodes: List<String>, labels: List<String>): String? {
        for ((index, node) in nodes.withIndex()) {
            for (label in labels) {
                val idx = node.indexOf(label)
                if (idx < 0) continue

                val after = node.substring(idx + label.length)
                val rest = when {
                    after.startsWith("：") || after.startsWith(":") -> after.substring(1).trim()
                    after.startsWith(" ") || after.startsWith("　") -> after.trimStart(' ', '　').trim()
                    else -> null // 标签嵌在句中，不是键值对
                }
                if (rest != null && isUsableValue(rest, labels)) return rest.take(30)

                // 标签独占一个节点 → 顺序取下一个有效值节点
                if (node.trim() == label) {
                    for (j in index + 1 until nodes.size) {
                        val next = nodes[j]
                        if (isUsableValue(next, labels)) return next.take(30)
                    }
                }
            }
        }
        return null
    }

    private fun isUsableValue(text: String, labels: List<String>): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (statusPhrases.any { t.contains(it) }) return false
        if (labels.any { t == it }) return false
        if (t.all { it.isDigit() }) return false // 订单号/单号之类
        return true
    }

    /**
     * 无标签商户兜底：从页面锚点节点（"支付成功"等）向后扫最多 5 个节点，
     * 取第一个"像名称"的文本。排除金额/状态/按钮/标签/其他标签的值节点；
     * 只在标签提取失败时调用，填错的代价低（确认弹窗里可见可改）。
     */
    private fun guessCounterparty(nodes: List<String>): String? {
        val anchorIdx = nodes.indexOfFirst { node ->
            (successKeywords + billDetailKeywords).any { node.contains(it) }
        }
        if (anchorIdx < 0) return null
        val allLabels = counterpartyLabels + paymentMethodLabels + timeLabels
        val end = minOf(nodes.size, anchorIdx + 6)
        for (i in anchorIdx + 1 until end) {
            val t = nodes[i]
            if (t.isEmpty()) continue
            if (t.startsWith("￥") || t.startsWith("¥")) continue
            if (t.all { it.isDigit() }) continue
            if (stepperNode.matches(t)) continue
            if (statusPhrases.any { t.contains(it) }) continue
            if (fallbackExcluded.any { t.contains(it) }) continue
            // 上一节点是某个标签 → 本节点是该标签的值（如"付款方式→零钱"），不是商户
            if (i > 0 && allLabels.any { nodes[i - 1] == it }) continue
            return t.take(30)
        }
        return null
    }
}
