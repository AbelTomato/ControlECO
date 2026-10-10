package com.controlexo.app.capture

import com.controlexo.app.data.db.AccountEntity
import com.controlexo.app.data.db.CategoryEntity
import com.controlexo.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 自动入账/待确认弹窗共用的默认分类与账户推测。 */
class CaptureDefaultsTest {

    private fun cat(id: Long, name: String, type: TxType) = CategoryEntity(id = id, name = name, type = type)
    private fun acc(id: Long, name: String) = AccountEntity(id = id, name = name)

    private val categories = listOf(
        cat(1, "餐饮", TxType.EXPENSE),
        cat(2, "购物", TxType.EXPENSE),
        cat(3, "其他", TxType.EXPENSE),
        cat(4, "工资", TxType.INCOME),
        cat(5, "其他", TxType.INCOME),
    )

    private val accounts = listOf(
        acc(1, "现金"),
        acc(2, "微信零钱"),
        acc(3, "支付宝"),
        acc(4, "中国银行储蓄卡"),
    )

    @Test
    fun `分类优先取该类型的其他`() {
        assertEquals(3L, CaptureDefaults.categoryId(categories, TxType.EXPENSE))
        assertEquals(5L, CaptureDefaults.categoryId(categories, TxType.INCOME))
    }

    @Test
    fun `没有其他分类时取该类型第一个_并区分方向`() {
        val only = listOf(cat(1, "餐饮", TxType.EXPENSE), cat(2, "工资", TxType.INCOME))
        assertEquals(1L, CaptureDefaults.categoryId(only, TxType.EXPENSE))
        assertEquals(2L, CaptureDefaults.categoryId(only, TxType.INCOME))
    }

    @Test
    fun `没有可用分类返回空`() {
        assertNull(CaptureDefaults.categoryId(emptyList(), TxType.EXPENSE))
        assertNull(CaptureDefaults.categoryId(listOf(cat(1, "工资", TxType.INCOME)), TxType.EXPENSE))
    }

    @Test
    fun `账户按来源包名推测`() {
        assertEquals(2L, CaptureDefaults.accountId(accounts, NotificationRules.WECHAT))
        assertEquals(3L, CaptureDefaults.accountId(accounts, NotificationRules.ALIPAY))
        // 云闪付等：走"银行"关键字
        assertEquals(4L, CaptureDefaults.accountId(accounts, NotificationRules.UNIONPAY))
    }

    @Test
    fun `推测不到关键字时取第一个账户`() {
        val plain = listOf(acc(1, "现金"), acc(2, "备用金"))
        assertEquals(1L, CaptureDefaults.accountId(plain, NotificationRules.ALIPAY))
        assertNull(CaptureDefaults.accountId(emptyList(), NotificationRules.ALIPAY))
    }
}
