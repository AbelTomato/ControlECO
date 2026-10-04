package com.accbook.app.csv

import com.accbook.app.data.db.TxType
import com.accbook.app.data.model.BillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.ZoneId

class BillParsersTest {

    private val wechatCsv = """
        微信支付账单明细
        ---------------------
        微信昵称：测试用户
        起始时间：2024-01-01 00:00:00
        终止时间：2024-01-31 23:59:59
        ---------------------
        导出类型：全部
        导出时间：2024-02-01 10:00:00
        共 4 笔记录
        ---------------------
        交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
        2024-01-02 12:30:00,商户消费,"沙县小吃(XX店)",沙县小吃,支出,¥25.00,零钱,支付成功,4200012345,M10001,
        2024-01-03 09:00:00,微信红包,张三,红包,收入,¥8.88,零钱,已存入零钱,4200067890,M10002,新年红包
        2024-01-04 10:00:00,转账,李四,转账-扫码,/,¥100.00,零钱,已转账,4200099999,M10003,
        2024-01-05 20:00:00,商户消费,某电商,商品订单,支出,¥59.90,招商银行(1234),已全额退款,4200111111,M10004,
    """.trimIndent().trim()

    private val alipayCsv = """
        支付宝交易记录明细查询
        账号:[test@example.com]
        起始日期:[2024-01-01 00:00:00]
        终止日期:[2024-01-31 23:59:59]
        ---------------------------------
        交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,服务费（元）,成功退款（元）,备注
        202401022188880001,TB100,2024-01-02 18:00:00,2024-01-02 18:00:01,2024-01-02 18:00:01,浙江省杭州市,交易,淘宝网,秋冬外套,129.90,支出,交易成功,0.00,0.00,
        202401032188880002,,2024-01-03 08:00:00,2024-01-03 08:00:00,2024-01-03 08:00:00,上海市,转账,王五,转账,500.00,收入,交易成功,0.00,0.00,饭钱
        202401042188880003,,2024-01-04 08:00:00,,2024-01-04 08:00:00,杭州市,交易,某商家,商品A,99.00,支出,等待付款,0.00,0.00,
    """.trimIndent().trim()

    private fun millis(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int): Long =
        LocalDateTime.of(y, m, d, h, min, s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `detect wechat`() {
        assertEquals(BillSource.WECHAT, BillParsers.detect(CsvReader.parse(wechatCsv)))
    }

    @Test
    fun `detect alipay`() {
        assertEquals(BillSource.ALIPAY, BillParsers.detect(CsvReader.parse(alipayCsv)))
    }

    @Test
    fun `parse wechat bill`() {
        val bill = BillParsers.parse(wechatCsv.toByteArray(Charsets.UTF_8))!!
        assertEquals(BillSource.WECHAT, bill.source)
        assertEquals(1, bill.skippedOtherType)   // "/" 不计收支
        assertEquals(1, bill.skippedStatus)      // 已全额退款
        assertEquals(2, bill.rows.size)

        val r0 = bill.rows[0]
        assertEquals(TxType.EXPENSE, r0.type)
        assertEquals(2500L, r0.amountCents)
        assertEquals("沙县小吃(XX店)", r0.counterparty)
        assertEquals(millis(2024, 1, 2, 12, 30, 0), r0.timestamp)
        assertEquals("4200012345", r0.externalId)
        assertEquals("零钱", r0.payMethod)
        assertEquals("沙县小吃", r0.note)
        assertEquals("餐饮", r0.guessedCategoryName)

        val r1 = bill.rows[1]
        assertEquals(TxType.INCOME, r1.type)
        assertEquals(888L, r1.amountCents)
        assertEquals("张三", r1.counterparty)
        assertEquals("新年红包", r1.note)
        assertEquals("红包", r1.guessedCategoryName)
    }

    @Test
    fun `parse alipay bill gbk`() {
        val bill = BillParsers.parse(alipayCsv.toByteArray(Charset.forName("GBK")))!!
        assertEquals(BillSource.ALIPAY, bill.source)
        assertEquals(0, bill.skippedOtherType)
        assertEquals(1, bill.skippedStatus)      // 等待付款
        assertEquals(2, bill.rows.size)

        val r0 = bill.rows[0]
        assertEquals(TxType.EXPENSE, r0.type)
        assertEquals(12990L, r0.amountCents)
        assertEquals("淘宝网", r0.counterparty)
        assertEquals(millis(2024, 1, 2, 18, 0, 1), r0.timestamp)   // 取付款时间
        assertEquals("202401022188880001", r0.externalId)
        assertEquals("购物", r0.guessedCategoryName)

        val r1 = bill.rows[1]
        assertEquals(TxType.INCOME, r1.type)
        assertEquals(50000L, r1.amountCents)
        assertEquals("王五", r1.counterparty)
        assertEquals("饭钱", r1.note)
    }

    @Test
    fun `unrecognized format returns null`() {
        assertNull(BillParsers.parse("hello,world\n1,2\n".toByteArray()))
    }
}
