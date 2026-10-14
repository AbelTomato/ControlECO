package com.controleco.app.capture

import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 页面规则引擎测试。输入是无障碍服务收集的页面文本快照（按控件树顺序）。
 * 当前用例基于常见版式的推演样本；真机采样后按捕获日志的真实快照补充/修正（流程见 docs/通知规则维护指南.md）。
 */
class PageRulesTest {

    /** 固定"查看时刻"：2026-10-06 12:35:00 本地时区 */
    private val capturedAt: Long = LocalDateTime.of(2026, 10, 6, 12, 35, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun parse(pkg: String, texts: List<String>, at: Long = capturedAt) =
        PageRules.parse(pkg, texts, at)

    // ---------- 支付成功页 ----------

    @Test
    fun `wechat payment success with separate label nodes`() {
        val r = parse(NotificationRules.WECHAT, listOf("支付成功", "¥12.80", "收款方", "老王奶茶店", "支付方式", "零钱"))!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1280L, r.amountCents)
        assertEquals("老王奶茶店", r.counterparty)
        assertEquals("零钱", r.paymentMethod)
        assertEquals("支付方式 零钱", r.note)
        assertEquals("微信·支付页", r.ruleName)
        // 无交易时间 → 分钟桶用捕获时刻，与通知通道同构
        assertEquals("com.tencent.mm|EXPENSE|1280|${capturedAt / 60_000}", r.dedupKey)
    }

    @Test
    fun `alipay payment success with inline labels`() {
        val r = parse(NotificationRules.ALIPAY, listOf("付款成功", "¥14.90", "收款方：某某百货", "付款方式：余额宝"))!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1490L, r.amountCents)
        assertEquals("某某百货", r.counterparty)
        assertEquals("余额宝", r.paymentMethod)
        assertNull(r.txTime)
    }

    @Test
    fun `labeled amount with thousand separator`() {
        val r = parse(NotificationRules.WECHAT, listOf("支付成功", "付款金额：¥1,280.00", "收款方", "某店"))!!
        assertEquals(128_000L, r.amountCents)
    }

    // ---------- 账单详情页 ----------

    @Test
    fun `wechat bill detail extracts all fields`() {
        val r = parse(
            NotificationRules.WECHAT,
            listOf(
                "账单详情", "¥128.00", "当前状态", "支付成功", "商户全称", "某某超市有限公司",
                "支付方式", "招商银行(1234)", "交易时间", "2026-10-06 12:34:56",
                "交易单号", "4200182202610061234567890",
            ),
        )!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(128_00L, r.amountCents)
        assertEquals("某某超市有限公司", r.counterparty)
        assertEquals("招商银行(1234)", r.paymentMethod)
        val expected = LocalDateTime.of(2026, 10, 6, 12, 34, 56)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, r.txTime)
        assertEquals("微信·账单详情", r.ruleName)
        // 有交易时间 → 分钟桶对齐交易时间，与该笔支付通知天然去重
        assertEquals("com.tencent.mm|EXPENSE|12800|${expected / 60_000}", r.dedupKey)
    }

    @Test
    fun `alipay bill detail with expense tag`() {
        val r = parse(
            NotificationRules.ALIPAY,
            listOf("账单详情", "支出", "¥14.90", "对方", "某某百货商行", "付款方式", "余额宝", "交易时间：2026-10-06 20:30"),
        )!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1490L, r.amountCents)
        assertEquals("某某百货商行", r.counterparty)
        assertEquals("余额宝", r.paymentMethod)
        val expected = LocalDateTime.of(2026, 10, 6, 20, 30, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, r.txTime)
    }

    @Test
    fun `wechat transfer out bill detail keeps expense direction`() {
        // 转账支出页同时出现"对方已收钱"与"收款方"标签——"收款方"不能把方向判成收入，
        // "对方已收钱"不能被当成收款方名称
        val r = parse(
            NotificationRules.WECHAT,
            listOf("账单详情", "¥100.00", "当前状态", "对方已收钱", "收款方", "张三", "支付方式", "零钱", "交易时间", "2026-10-06 09:00"),
        )!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(10_000L, r.amountCents)
        assertEquals("张三", r.counterparty)
        assertEquals("零钱", r.paymentMethod)
    }

    // ---------- 跳过场景（宁漏勿错） ----------

    @Test
    fun `income bill detail skipped`() {
        assertNull(parse(NotificationRules.WECHAT, listOf("账单详情", "¥50.00", "当前状态", "已存入零钱", "付款方", "李四")))
    }

    @Test
    fun `refund page skipped`() {
        assertNull(parse(NotificationRules.ALIPAY, listOf("退款成功", "¥14.90", "对方", "某某百货")))
    }

    @Test
    fun `chat page skipped`() {
        assertNull(parse(NotificationRules.WECHAT, listOf("张三", "哈哈好的", "明天见", "[图片]")))
    }

    @Test
    fun `payment page without amount skipped`() {
        assertNull(parse(NotificationRules.WECHAT, listOf("支付成功", "完成")))
    }

    @Test
    fun `old bill detail skipped`() {
        val threeDaysLater = capturedAt + 3 * 24 * 3_600_000L
        assertNull(
            parse(
                NotificationRules.WECHAT,
                listOf("账单详情", "¥20.00", "当前状态", "支付成功", "支付方式", "零钱", "交易时间", "2026-10-06 12:34"),
                at = threeDaysLater,
            ),
        )
    }

    @Test
    fun `unknown package skipped`() {
        assertNull(parse("com.example.other", listOf("支付成功", "¥12.00")))
    }

    // ---------- 真实样本（真机采集，2026-10） ----------

    @Test
    fun `alipay real success page with unlabeled merchant node`() {
        // 用户真机样本：商户"拼多多平台商户"是独立节点、无标签，靠锚点兜底提取
        val r = parse(
            NotificationRules.ALIPAY,
            listOf(
                "支付成功￥12.90", "拼多多平台商户", "￥12.90", "付款方式",
                "中国银行储蓄卡(8920)", "x3", "完成", "完成", "完成",
            ),
        )!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(1290L, r.amountCents)
        assertEquals("拼多多平台商户", r.counterparty)
        assertEquals("中国银行储蓄卡(8920)", r.paymentMethod)
        assertEquals("支付宝·支付页", r.ruleName)
        assertEquals("支付方式 中国银行储蓄卡(8920)", r.note)
        assertEquals("com.eg.android.AlipayGphone|EXPENSE|1290|${capturedAt / 60_000}", r.dedupKey)
    }

    @Test
    fun `alipay real success page deepseek merchant`() {
        // 用户真机样本（2026-10-11）：商户"杭州深度求索"是独立节点、无标签；
        // 页面比拼多多样本多了"回首页/返回商家"，少了 x3 步进器
        val r = parse(
            NotificationRules.ALIPAY,
            listOf(
                "支付成功￥5.00", "杭州深度求索", "￥5.00", "付款方式",
                "中国银行储蓄卡(8920)", "回首页", "返回商家", "完成", "完成",
            ),
        )!!
        assertEquals(TxType.EXPENSE, r.type)
        assertEquals(500L, r.amountCents)
        assertEquals("杭州深度求索", r.counterparty)
        assertEquals("中国银行储蓄卡(8920)", r.paymentMethod)
    }

    @Test
    fun `partial snapshot without merchant node does not pick nav buttons`() {
        // 页面分阶段渲染：商户节点未就绪的半截快照，导航节点（回首页）不能被兜底当成收款方
        val r = parse(
            NotificationRules.ALIPAY,
            listOf("支付成功￥5.00", "￥5.00", "付款方式", "中国银行储蓄卡(8920)", "回首页", "返回商家", "完成", "完成"),
        )!!
        assertEquals(500L, r.amountCents)
        assertEquals("", r.counterparty)
    }

    @Test
    fun `alipay real cashier page skipped`() {
        // 真机样本：收银台页（还没付款），"支付成功得xx券"营销文案会让它命中"支付成功"
        assertNull(
            parse(
                NotificationRules.ALIPAY,
                listOf(
                    "关闭", "使用密码", "付款给陈自有(**有)", "支付金额0.00元", "支付成功得公交地铁1.5元多次券",
                    "订单金额,¥ 0.01", "订单金额", "¥ 0.01", "支付遇财神,-¥ 0.01", "支付遇财神", "-¥ 0.01",
                    "付款,确认付款，付款给陈自有(**有)，付款金额0.00元人民币", "付款",
                ),
            ),
        )
    }

    @Test
    fun `alipay real zero-paid success page skipped`() {
        // 真机样本：遇财神全额优惠，实付￥0.00（标题节点）；订单金额￥0.01 不是实付，不能入队
        assertNull(
            parse(
                NotificationRules.ALIPAY,
                listOf(
                    "支付成功￥0.00", "陈自有", "￥0.01", "支付遇财神", "-￥0.01",
                    "点击给商家对账", "完成", "回首页", "回首页",
                ),
            ),
        )
    }

    @Test
    fun `embedded label sentence does not produce a value`() {
        // "点击给商家对账"里嵌着标签"商家"，不能把"对账"当收款方
        val r = parse(
            NotificationRules.ALIPAY,
            listOf("支付成功", "￥12.90", "点击给商家对账", "付款方式", "零钱"),
        )!!
        assertEquals("", r.counterparty)
        assertEquals("零钱", r.paymentMethod)
        assertEquals(1290L, r.amountCents)
    }

    @Test
    fun `space separated same line label value`() {
        val r = parse(NotificationRules.ALIPAY, listOf("付款成功", "¥9.90", "收款方 某某水果店"))!!
        assertEquals("某某水果店", r.counterparty)
    }

    @Test
    fun `discount amount in anchor is not taken as paid`() {
        // 优惠行紧邻实付时不能抓错：-￥0.01 是优惠，￥12.90 才是实付
        val r = parse(
            NotificationRules.ALIPAY,
            listOf("支付成功￥12.90", "支付遇财神", "-￥0.01", "付款方式", "零钱"),
        )!!
        assertEquals(1290L, r.amountCents)
    }

    // ---------- 页面形状闸门：主界面/消息流里的伪锚点（2026-10 实测） ----------

    @Test
    fun `alipay home with recent-message payment preview is skipped`() {
        // 用户实测（2026-10-14）：支付宝首页的"最近消息"卡片里有
        // "淘宝闪购极速版 付款成功￥21.00  1小时前"的历史预览，整页曾被误记一笔
        val home = listOf(
            "松开刷新", "厦门", "多云 28℃", "搜索框", "疯狂碰友节 全民领红包",
            "扫一扫", "收付款", "出行", "无限骑", "卡包", "市民中心", "飞猪旅行", "淘票票",
            "转账", "蚂蚁森林", "我的信用卡", "手机营业厅", "余额宝", "商家服务", "花呗",
            "我的快递", "海底捞火锅", "全部",
            "消息盒子 蚂蚁森林 你有即将过期的绿色能量，领5-20g能量 18分钟前",
            "淘宝闪购极速版 付款成功￥21.00  1小时前", "点击查看全部消息", "最近消息", "238条新消息",
            "为你推荐", "某推荐内容 1010", "某推荐内容 108", "首页", "理财", "视频", "消息", "我的",
        )
        assertNull(parse(NotificationRules.ALIPAY, home))
    }

    @Test
    fun `message preview anchor with relative time is skipped`() {
        // 即便页面信息不多，锚点带"1小时前"这类相对时间也说明是消息预览，不是结果页
        assertNull(parse(NotificationRules.ALIPAY, listOf("付款成功￥21.00 1小时前", "最近消息")))
    }

    @Test
    fun `rich page with buried anchor is skipped`() {
        // 富页面 + 锚点埋深：支付完成页信息很少、锚点必然靠前（用户实测结论）
        val nodes = mutableListOf<String>()
        repeat(30) { nodes.add("无关内容$it") }
        nodes.add("支付成功￥66.00")
        nodes.add("某商户")
        assertNull(parse(NotificationRules.ALIPAY, nodes))
    }

    @Test
    fun `rich page with top anchor still parses`() {
        // 反面样本：节点不少但锚点靠前（结果页下方挂了推荐位），仍然正常解析
        val nodes = mutableListOf("支付成功￥12.90", "拼多多平台商户", "￥12.90", "付款方式", "零钱")
        repeat(30) { nodes.add("推荐内容$it") }
        val r = parse(NotificationRules.ALIPAY, nodes)!!
        assertEquals(1290L, r.amountCents)
        assertEquals("拼多多平台商户", r.counterparty)
    }

    @Test
    fun `wechat shell page with transfer preview is skipped`() {
        // 微信主界面（通讯录+发现 底栏）里挂着转账预览，不能记成账
        assertNull(
            parse(
                NotificationRules.WECHAT,
                listOf("微信", "通讯录", "发现", "我", "张三 转账 ￥100.00 昨天", "李四 [转账] 已收钱"),
            ),
        )
    }

    @Test
    fun `alipay home markers alone do not block a real success page`() {
        // 支付方式出现"余额宝"这类首页入口词不算首页（阈值 ≥3 个）：结果页照常解析
        val r = parse(
            NotificationRules.ALIPAY,
            listOf("支付成功￥30.00", "某商户", "￥30.00", "付款方式", "余额宝"),
        )!!
        assertEquals(3000L, r.amountCents)
        assertEquals("余额宝", r.paymentMethod)
    }

    // ---------- 捕获日志入库判定 ----------

    @Test
    fun `looksInteresting distinguishes payment pages from noise`() {
        assertTrue(PageRules.looksInteresting(listOf("支付成功", "¥12.80")))
        assertTrue(PageRules.looksInteresting(listOf("账单详情", "交易单号", "4200182...")))
        assertFalse(PageRules.looksInteresting(listOf("张三", "哈哈好的", "明天见")))
    }
}
