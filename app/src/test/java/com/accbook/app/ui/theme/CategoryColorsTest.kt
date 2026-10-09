package com.accbook.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类配色：同一屏内**不重复**（哈希首选位 + 线性探测空位）、与传入顺序无关、
 * 超容量时不崩且有值可取。
 */
class CategoryColorsTest {

    /** 与主题同名同序的 16 色（只用来验证算法本身；内置调色板的去重在下方单独断言） */
    private val palette = listOf(
        Color(0xFFDC2626), Color(0xFFEA580C), Color(0xFFCA8A04), Color(0xFFA16207),
        Color(0xFF65A30D), Color(0xFF16A34A), Color(0xFF166534), Color(0xFF0F766E),
        Color(0xFF0891B2), Color(0xFF2563EB), Color(0xFF1E3A8A), Color(0xFF4F46E5),
        Color(0xFF7C3AED), Color(0xFFA21CAF), Color(0xFFDB2777), Color(0xFF9F1239),
    )

    @Test
    fun `内置调色板 16 色明暗两套都不重复`() {
        listOf(lightAccBookColors(), darkAccBookColors()).forEach { colors ->
            val p = colors.categoryPalette
            assertEquals(16, p.size)
            assertEquals("调色板内不应有重复颜色", 16, p.toSet().size)
        }
    }

    @Test
    fun `同屏分类取色互不重复`() {
        val names = listOf(
            "餐饮", "交通", "购物", "日用", "娱乐", "医疗", "居住", "通讯",
            "学习", "旅行", "人情", "宠物", "运动", "数码", "美妆", "其他",
        )
        val colors = categoryColors(palette, names)
        assertEquals(names.size, colors.size)
        assertEquals("同屏取色不应重复", names.size, colors.values.toSet().size)
    }

    @Test
    fun `取色与传入顺序无关`() {
        val names = listOf("餐饮", "交通", "购物", "日用", "娱乐")
        val a = categoryColors(palette, names)
        val b = categoryColors(palette, names.reversed())
        names.forEach { assertEquals(a[it], b[it]) }
    }

    @Test
    fun `重复名字不会拿到两个颜色`() {
        val colors = categoryColors(palette, listOf("餐饮", "餐饮", "交通"))
        assertEquals(2, colors.size)
    }

    @Test
    fun `分类数超过调色板容量时仍全部有值`() {
        val names = List(20) { "分类$it" }
        val colors = categoryColors(palette, names)
        assertEquals(names.size, colors.size)
        assertTrue(colors.values.all { it in palette })
    }

    @Test
    fun `空名字被跳过_空调色板返回空表`() {
        assertTrue(categoryColors(palette, listOf("", "")).isEmpty())
        assertTrue(categoryColors(emptyList(), listOf("餐饮", "交通")).isEmpty())
    }

    @Test
    fun `小集合里单名字取色与 categoryColor 一致`() {
        val single = categoryColor(palette, "餐饮")
        assertEquals(single, categoryColors(palette, listOf("餐饮"))["餐饮"])
        // 单名字取色落在调色板内（不是构造出来的占位色）
        assertTrue(single in palette)
        assertNotEquals(Color.Unspecified, single)
    }
}
