package com.accbook.app.ui.home

import com.accbook.app.data.budget.Budget
import com.accbook.app.data.db.CategoryEntity
import com.accbook.app.data.db.CategorySumRow
import com.accbook.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryRingsTest {

    private fun category(id: Long, name: String, type: TxType = TxType.EXPENSE) =
        CategoryEntity(id = id, name = name, type = type)

    private fun sum(id: Long, name: String, cents: Long) =
        CategorySumRow(categoryId = id, categoryName = name, totalCents = cents)

    @Test
    fun `无分类预算时为空`() {
        val rings = buildCategoryRings(
            budgets = emptyMap(),
            sums = listOf(sum(1, "餐饮", 100_00)),
            categories = listOf(category(1, "餐饮")),
        )
        assertTrue(rings.isEmpty())
    }

    @Test
    fun `剩余与档位按分类预算计算`() {
        val rings = buildCategoryRings(
            budgets = mapOf(1L to 1_000_00),
            sums = listOf(sum(1, "餐饮", 400_00)),
            categories = listOf(category(1, "餐饮")),
        )
        assertEquals(1, rings.size)
        assertEquals("餐饮", rings[0].name)
        assertEquals(600_00, rings[0].remainingCents)
        assertEquals(0.6f, rings[0].ratio, 0.0001f)
        assertEquals(Budget.Tone.OK, rings[0].tone)
    }

    @Test
    fun `未花过的分类视为全额剩余`() {
        val rings = buildCategoryRings(
            budgets = mapOf(1L to 500_00),
            sums = emptyList(),
            categories = listOf(category(1, "交通")),
        )
        assertEquals(500_00, rings[0].remainingCents)
        assertEquals(1f, rings[0].ratio, 0.0001f)
    }

    @Test
    fun `超支分类剩余为负且档位为紧张`() {
        val rings = buildCategoryRings(
            budgets = mapOf(1L to 300_00),
            sums = listOf(sum(1, "购物", 450_00)),
            categories = listOf(category(1, "购物")),
        )
        assertEquals(-150_00, rings[0].remainingCents)
        assertEquals(Budget.Tone.LOW, rings[0].tone)
    }

    @Test
    fun `按剩余比例升序取前四_最紧张的在前`() {
        val budgets: Map<Long, Long> =
            mapOf(1L to 100_00, 2L to 100_00, 3L to 100_00, 4L to 100_00, 5L to 100_00)
        val sums = listOf(
            sum(1, "甲", 10_00),  // 剩 90%
            sum(2, "乙", 90_00),  // 剩 10% ← 最紧张
            sum(3, "丙", 50_00),  // 剩 50%
            sum(4, "丁", 70_00),  // 剩 30%
            sum(5, "戊", 95_00),  // 剩 5% ← 第二紧张，但被截断前四
        )
        val categories = listOf(
            category(1, "甲"), category(2, "乙"), category(3, "丙"), category(4, "丁"), category(5, "戊"),
        )
        val rings = buildCategoryRings(budgets, sums, categories)
        assertEquals(4, rings.size)
        assertEquals(listOf("戊", "乙", "丁", "丙"), rings.map { it.name })
    }

    @Test
    fun `收入分类与已删除分类不参与`() {
        val rings = buildCategoryRings(
            budgets = mapOf(1L to 100_00, 2L to 200_00, 9L to 300_00),
            sums = listOf(sum(1, "工资", 50_00)),
            categories = listOf(
                category(1, "工资", TxType.INCOME),
                category(2, "餐饮", TxType.EXPENSE),
                // 9L 已删除：不在 categories 中
            ),
        )
        assertEquals(listOf("餐饮"), rings.map { it.name })
    }
}
