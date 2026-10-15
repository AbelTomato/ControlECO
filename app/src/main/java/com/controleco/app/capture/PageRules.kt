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

    /**
     * 失败/取消短语（自测整理的拒绝表，见 docs/自动记账捕获机制.md）：
     * 出现即跳过——失败与取消的页面绝不能记成账。
     */
    private val failureKeywords = listOf(
        "支付失败", "付款失败", "交易失败", "扣款失败",
        "交易已取消", "交易已关闭", "订单已取消", "订单已关闭", "已撤销", "支付已取消",
    )

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
            // 金额类节点的整行（"付款金额：¥20.06"/"实付 ¥20.06"）不是商户名
            "金额", "实付",
            // 页面尚未渲染完时商户节点可能还没出来，导航类节点不能当收款方（真实样本"回首页"曾被当商户）
            "首页", "客服", "更多")

    private val stepperNode = Regex("""^x\d+$""")

    /**
     * 纯金额节点（可带 ¥ 前缀/负号/千分位/小数/尾随"元"）：绝不能当商户名或标签值。
     * 真实样本（2026-10-13 支付宝碰一碰支付成功页）：页面把"￥"与"20.06"渲染成两个独立节点，
     * "20.06" 通过了原先的"全数字"检查（小数点不是数字字符），被兜底扫描当成商户记进了账。
     */
    private val amountLikeNode = Regex("""^[-−－]?\s*[￥¥]?\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?\s*元?$""")

    /** 文本是否只是一个金额/纯数字（商户与"标签→值"提取的通用排除项，见 [amountLikeNode]） */
    internal fun isAmountLikeNode(text: String): Boolean = amountLikeNode.matches(text.trim())

    /** 金额：优先"金额标签"同行，其次独立金额节点（成功页大字），最后任意 ¥ 金额 */
    private val labeledAmount = Regex("""(?:付款金额|支付金额|交易金额|金额)\s*[：:]?\s*[￥¥]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val standaloneAmount = Regex("""^[￥¥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)$""")
    private val anyAmount = Regex("""[￥¥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")

    /** 时间：2026-10-06 12:34[:56] / 2026.10.6 12:34 / 2026年10月6日 12:34 */
    private val timePattern = Regex("""(\d{4})[-/.年]\s*(\d{1,2})[-/.月]\s*(\d{1,2})日?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")

    /** 交易时间与查看时刻的最大允许偏差：超过视为在翻旧账单，不入队 */
    private const val MAX_AGE_MS = 24 * 3_600_000L

    private const val MAX_TEXTS = 200

    // ---------- 页面形状闸门（结果页 vs 应用主界面/消息流） ----------

    /**
     * 消息中心用语：出现即说明这不是交易结果页。
     * 实测样本（2026-10-14）：支付宝首页的"最近消息"卡片里挂着
     * "淘宝闪购极速版 付款成功￥21.00 1小时前"的历史预览，整页曾被误判成支付成功页记一笔。
     */
    private val messageStreamMarkers = listOf("最近消息", "条新消息", "消息盒子", "消息中心", "点击查看全部消息")

    /** 应用主界面入口词：同一页出现 ≥3 个即视为首页宫格，不是结果页 */
    private val appHomeMarkers = listOf(
        "扫一扫", "收付款", "卡包", "出行", "市民中心", "余额宝", "花呗", "蚂蚁森林", "我的快递", "转账",
    )

    /** 微信主界面底栏：两者同时出现说明是聊天列表/主界面壳，不是结果页 */
    private val wechatShellMarkers = listOf("通讯录", "发现")

    /** 消息预览的相对时间戳：锚点（或紧邻节点）带这类字样 → 是消息流里的历史预览 */
    private val relativeTimeMarkers = listOf("刚刚", "秒前", "分钟前", "小时前", "昨天", "天前")

    /** 富页面阈值：节点数达到该值且锚点仍埋得很深 → 判定为应用主界面（结果页信息少、锚点靠前） */
    private const val RICH_PAGE_NODE_COUNT = 30
    private const val ANCHOR_MAX_TOP_INDEX = 8

    /** 锚点前后看几个节点判"消息预览" */
    private const val PREVIEW_NEIGHBOR_WINDOW = 2

    /**
     * 页面形状闸门：拒掉"应用主界面 / 消息流"里出现的伪锚点（含历史账单预览）。
     * 返回 null = 形状像真正的交易结果页；否则返回拒绝原因（进日志）。
     * 判据（任一命中即拒）：
     * 1. 消息中心用语（最近消息/条新消息/…）；
     * 2. 首页宫格入口词 ≥3 个，或微信主界面底栏（通讯录+发现）；
     * 3. 锚点（或前后 2 个节点）带相对时间（"1小时前"）——消息预览的签名；
     * 4. 富页面 + 锚点埋深：节点 ≥30 且锚点下标 >8（支付完成页信息很少、锚点必然靠前）。
     */
    private fun nonResultPageReason(nodes: List<String>, anchorIdx: Int): String? {
        val joined = nodes.joinToString(" ")
        if (messageStreamMarkers.any { joined.contains(it) }) return "message center surface"
        if (appHomeMarkers.count { joined.contains(it) } >= 3) return "app home surface"
        if (wechatShellMarkers.all { joined.contains(it) }) return "wechat shell surface"
        if (anchorIdx >= 0) {
            val lo = maxOf(0, anchorIdx - PREVIEW_NEIGHBOR_WINDOW)
            val hi = minOf(nodes.size, anchorIdx + PREVIEW_NEIGHBOR_WINDOW + 1)
            if ((lo until hi).any { i -> relativeTimeMarkers.any { nodes[i].contains(it) } }) {
                return "anchor looks like message preview"
            }
            if (nodes.size >= RICH_PAGE_NODE_COUNT && anchorIdx > ANCHOR_MAX_TOP_INDEX) {
                return "rich page with buried anchor"
            }
        }
        return null
    }

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
        // 失败/取消页：绝不能记成账
        if (failureKeywords.any { joined.contains(it) }) {
            AppLog.i("Page", "skip (failure/cancelled page) pkg=$packageName")
            return null
        }

        val isBillDetail = billDetailKeywords.any { joined.contains(it) }
        val isSuccess = successKeywords.any { joined.contains(it) }
        if (!isBillDetail && !isSuccess) {
            AppLog.d("Page", "skip (not a payment page) pkg=$packageName head=${joined.take(120)}")
            return null
        }
        val kind = if (isBillDetail) "账单详情" else "支付页"

        // 页面形状闸门：主界面/消息流（含历史账单预览）不是交易结果页
        val anchorIdx = nodes.indexOfFirst { node -> (successKeywords + billDetailKeywords).any { node.contains(it) } }
        val shapeReject = nonResultPageReason(nodes, anchorIdx)
        if (shapeReject != null) {
            AppLog.i("Page", "skip ($shapeReject) pkg=$packageName kind=$kind nodes=${nodes.size} anchorIdx=$anchorIdx")
            return null
        }

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
            dedupKey = NotificationRules.dedupKey(packageName, TxType.EXPENSE, false, amountCents, txTime ?: capturedAt),
        )
    }

    /**
     * 页面是否值得存进捕获日志（未匹配样本池）：支付类关键词命中即存，
     * 聊天/朋友圈等无关页面不落库，避免捕获日志被无障碍事件刷爆。
     */
    fun looksInteresting(texts: List<String>): Boolean {
        val joined = texts.joinToString(" ")
        return (successKeywords + billDetailKeywords + incomeAnchors + counterpartyLabels + paymentMethodLabels +
            listOf("交易金额", "付款金额", "收款金额", "交易时间", "交易单号")).any { joined.contains(it) }
    }

    // ---------- 收入方向（转账收款 / 红包 / 收款到账） ----------

    /**
     * 收入页锚点：出现即说明这是一笔"到账"页。通知通道覆盖不到这些场景
     * （微信转账收款、拆红包只有页面没有通知），页面通道是唯一线索。
     * "待确认收款"是**收款方**的待收款态（与付款方的"等待对方确认收款"不同，后者在排除项里）。
     */
    private val incomeAnchors = listOf("已收钱", "收款到账", "收款成功", "已存入零钱", "已转入", "待确认收款")

    /**
     * 收入页排除项：**"对方已收钱"是付款方的支出页状态**（付款完成后显示），
     * 不是收入信号——自测曾把它当收入信号，被这里显式排除；
     * 退款与"等待对方确认"（付款方的待收款态）同样排除；
     * 明细/列表类页面（零钱明细等）不是某笔交易的结果页，也不能记。
     */
    private val incomeExcludes = listOf(
        "对方已收钱", "待收钱", "待对方确认", "等待对方", "退款", "退回", "已退还",
        "零钱明细", "交易明细", "账单列表", "收支统计",
    )

    /** 付款方提取（收入页的"对方"）：来自张三 / 张三向你转账 / 领取了王五的红包 */
    private val payerPatterns = listOf(
        Regex("""来自\s*([^\s，,。;；]{1,12})"""),
        Regex("""([^\s，,。;；]{1,12})\s*向你转账"""),
        Regex("""领取了\s*([^\s，,。;；]{1,12})\s*的红包"""),
        Regex("""([^\s，,。;；]{1,12})\s*(?:的红包|发的红包)"""),
    )

    /** 收入页的付款方标签（"付款方/付款人/来自"这类键值对写法） */
    private val incomePayerLabels = listOf("付款方", "付款人", "转账方", "来自") + counterpartyLabels

    /** 兜底商户的通用词：这些词出现在收入页但不是付款方（转账/红包本身） */
    private val incomeGenericWords = listOf("转账", "红包", "微信红包", "收款", "零钱", "微信支付", "支付宝", "支付")

    /**
     * 收入方向页面解析（转账收款 / 红包 / 收款到账）。
     * 与支出路径同为"宁漏勿错"：方向互斥（含"对方已收钱"一律跳过）、金额提不到就放弃、
     * 只看 24 小时内的新鲜页面。返回 null = 不是可安全记账的收入页。
     */
    fun parseIncome(packageName: String, texts: List<String>, capturedAt: Long): ParsedPage? {
        if (packageName !in NotificationRules.knownPackages) return null
        val nodes = texts.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_TEXTS).toList()
        if (nodes.isEmpty()) return null
        val joined = nodes.joinToString(" ")

        if (cashierKeywords.any { joined.contains(it) }) return null
        if (failureKeywords.any { joined.contains(it) }) {
            AppLog.i("Page", "skip income (failure/cancelled page) pkg=$packageName")
            return null
        }
        if (incomeExcludes.any { joined.contains(it) }) {
            // 转账支出页（对方已收钱）/待收款/退款页：不是收入
            AppLog.d("Page", "skip income (excluded phrase) pkg=$packageName head=${joined.take(120)}")
            return null
        }
        val anchor = incomeAnchors.firstOrNull { joined.contains(it) }
        if (anchor == null) {
            AppLog.d("Page", "skip income (no income anchor) pkg=$packageName head=${joined.take(120)}")
            return null
        }

        // 页面形状闸门与支出路径共用：消息流/主界面里的"已收钱"预览同样不能记成收入
        val anchorIdx = nodes.indexOfFirst { node -> incomeAnchors.any { node.contains(it) } }
        val shapeReject = nonResultPageReason(nodes, anchorIdx)
        if (shapeReject != null) {
            AppLog.i("Page", "skip income ($shapeReject) pkg=$packageName nodes=${nodes.size} anchorIdx=$anchorIdx")
            return null
        }

        // 金额优先级与支出路径一致：锚点节点内的实付/实收金额 → "金额"标签行 → 独立 ¥ 节点 → 任意 ¥
        val amountText = (if (anchorIdx >= 0) paidAmountInAnchor(nodes[anchorIdx]) else null)
            ?: labeledAmount.find(joined)?.groupValues?.get(1)
            ?: nodes.firstNotNullOfOrNull { standaloneAmount.find(it)?.groupValues?.get(1) }
            ?: anyAmount.find(joined)?.groupValues?.get(1)
        if (amountText == null) {
            AppLog.d("Page", "skip income (amount not found) pkg=$packageName anchor=$anchor head=${joined.take(160)}")
            return null
        }
        val amountCents = Money.parseCents(amountText)
        if (amountCents == null || amountCents <= 0) {
            AppLog.i("Page", "skip income (zero amount) pkg=$packageName amount=$amountText")
            return null
        }

        val counterparty = payerFromPage(nodes)
            ?: valueAfterLabel(nodes, incomePayerLabels)
            ?: guessCounterparty(nodes)?.takeIf { guess -> incomeGenericWords.none { guess == it } }
            ?: ""
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
            AppLog.d("Page", "skip income (old page) pkg=$packageName ageHours=${(capturedAt - txTime) / 3_600_000}")
            return null
        }

        val label = NotificationRules.sourceLabel(packageName)
        // 红包与普通收款分开命名（"微信·红包" / "微信·收款"），待确认与溯源页据此显示
        val kind = if (joined.contains("红包")) "红包" else "收款"
        return ParsedPage(
            type = TxType.INCOME,
            amountCents = amountCents,
            counterparty = counterparty,
            paymentMethod = null,
            txTime = txTime,
            sourceLabel = label,
            ruleName = "$label·$kind",
            note = "",
            dedupKey = NotificationRules.dedupKey(packageName, TxType.INCOME, false, amountCents, txTime ?: capturedAt),
        )
    }

    /** 付款方提取：按常见句式找"来自 X / X 向你转账 / 领取了 X 的红包"，找不到返回 null */
    private fun payerFromPage(nodes: List<String>): String? {
        for (node in nodes) {
            for (pattern in payerPatterns) {
                val v = pattern.find(node)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                if (v.isEmpty()) continue
                if (isAmountLikeNode(v)) continue // "转账 20.06 向你转账"式的误抓（金额不是人名）
                if (incomeGenericWords.any { v == it }) continue
                return v.take(20)
            }
        }
        return null
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
        if (isAmountLikeNode(t)) return false // 金额不是任何标签的值（收款方/支付方式/时间都不是）
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
            if (isAmountLikeNode(t)) continue // 金额拆成独立节点（碰一碰样本的"20.06"）也不是商户
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
