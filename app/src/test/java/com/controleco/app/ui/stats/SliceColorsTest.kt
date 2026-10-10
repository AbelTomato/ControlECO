package com.controleco.app.ui.stats

import androidx.compose.ui.graphics.Color
import com.controleco.app.ui.theme.categoryColor
import com.controleco.app.ui.theme.colorDistance
import com.controleco.app.ui.theme.lightAccBookColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圆环配色与分类图标**同步**：
 * - 图标自定义过颜色 → 圆环用那个颜色；
 * - 内置图形（含默认取形）→ 用按分类名的自动取色（= 列表里图标的颜色）；
 * - emoji / 相册图片（没有颜色）→ 系统从调色板里挑离已用色最远的。
 */
class SliceColorsTest {

    private val palette = lightAccBookColors().categoryPalette

    private fun slice(name: String) = CategorySlice(name = name, actualCents = 100, budgetCents = 0)

    @Test
    fun `自定义过颜色的分类用图标色`() {
        val colors = sliceColorsOf(
            palette,
            listOf(slice("餐饮"), slice("交通")),
            mapOf("餐饮" to "GLYPH::EF4444", "交通" to "GLYPH:BURGER:1E3A8A"),
        )
        assertEquals(Color(0xFFEF4444), colors["餐饮"])
        assertEquals(Color(0xFF1E3A8A), colors["交通"])
    }

    @Test
    fun `内置图形用按名字的自动取色_与列表图标一致`() {
        val colors = sliceColorsOf(
            palette,
            listOf(slice("餐饮"), slice("交通")),
            mapOf("餐饮" to "", "交通" to "GLYPH:BURGER"),
        )
        assertEquals(categoryColor(palette, "餐饮"), colors["餐饮"])
        assertEquals(categoryColor(palette, "交通"), colors["交通"])
    }

    @Test
    fun `emoji 与相册图片没有颜色_由系统分配且离已用色够远`() {
        val colors = sliceColorsOf(
            palette,
            listOf(slice("餐饮"), slice("外卖"), slice("网购")),
            mapOf("餐饮" to "GLYPH::123456", "外卖" to "EMOJI:🍜", "网购" to "IMAGE:cat_icon_1.jpg"),
        )
        val fixed = Color(0xFF123456)
        listOf("外卖", "网购").forEach { name ->
            val c = colors[name]!!
            assertTrue("$name 应拿到调色板里的颜色", c in palette)
            assertTrue("$name 离已用色太近", colorDistance(c, fixed) >= 26f)
        }
        // 两个系统分配的颜色彼此也拉开
        assertTrue(colorDistance(colors["外卖"]!!, colors["网购"]!!) >= 26f)
    }

    @Test
    fun `结果与切片顺序无关`() {
        val icons = mapOf("餐饮" to "GLYPH::EF4444", "外卖" to "EMOJI:🍜", "交通" to "")
        val names = listOf("餐饮", "外卖", "交通")
        val a = sliceColorsOf(palette, names.map { slice(it) }, icons)
        val b = sliceColorsOf(palette, names.reversed().map { slice(it) }, icons)
        names.forEach { assertEquals(a[it], b[it]) }
    }

    @Test
    fun `分类表里没有的分类也能拿到颜色`() {
        val colors = sliceColorsOf(palette, listOf(slice("临时分类")), emptyMap())
        assertTrue(colors["临时分类"]!! in palette)
    }
}
