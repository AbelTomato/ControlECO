package com.accbook.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 圆环/图例配色：**已经定好颜色的分类原样使用**（与图标同色），
 * 只有"图标没有颜色"的分类（emoji / 相册图片）才由 [ringColors] 从调色板里
 * 挑"离已用色最远"的一个；与传入顺序无关，超容量不崩。
 */
class CategoryColorsTest {

    /** 用**主题真实调色板**，算法在真实输入上的表现才算数 */
    private val palette = lightAccBookColors().categoryPalette

    private val eightNames = listOf("餐饮", "交通", "购物", "日用", "娱乐", "医疗", "居住", "其他")

    @Test
    fun `内置调色板 8 色明暗两套都不重复`() {
        listOf(lightAccBookColors(), darkAccBookColors()).forEach { colors ->
            val p = colors.categoryPalette
            assertEquals(8, p.size)
            assertEquals("调色板内不应有重复颜色", 8, p.toSet().size)
        }
    }

    @Test
    fun `调色板任意两色的 ΔE 都够大`() {
        var min = Float.MAX_VALUE
        for (i in palette.indices) {
            for (j in i + 1 until palette.size) min = minOf(min, colorDistance(palette[i], palette[j]))
        }
        // 8 色版把最小 ΔE 从 16 色时的 ~23 拉到 30 以上：一眼能分
        assertTrue("调色板最小色差过小：$min", min >= 30f)
    }

    @Test
    fun `已定好颜色的分类原样使用_不打乱`() {
        val fixed = mapOf("餐饮" to Color(0xFF123456), "交通" to Color(0xFF00FF00))
        val colors = ringColors(palette, fixed, emptyList())
        assertEquals(Color(0xFF123456), colors["餐饮"])
        assertEquals(Color(0xFF00FF00), colors["交通"])
    }

    @Test
    fun `没有颜色的分类分到离已用色最远的一个`() {
        val fixed = mapOf("餐饮" to Color(0xFFEF4444), "交通" to Color(0xFF3B82F6))
        val colors = ringColors(palette, fixed, listOf("外卖", "网购"))
        assertEquals(4, colors.size)
        colors.filterKeys { it == "外卖" || it == "网购" }.values.forEach { c ->
            val nearest = fixed.values.minOf { colorDistance(c, it) }
            assertTrue("自动分配的颜色离已用色太近：$nearest", nearest >= RING_MIN_DELTA)
        }
    }

    @Test
    fun `同屏分类取色互不重复`() {
        val colors = ringColors(palette, emptyMap(), eightNames)
        assertEquals(eightNames.size, colors.size)
        assertEquals("同屏取色不应重复", eightNames.size, colors.values.toSet().size)
    }

    @Test
    fun `取色与传入顺序无关`() {
        val a = ringColors(palette, emptyMap(), eightNames)
        val b = ringColors(palette, emptyMap(), eightNames.reversed())
        eightNames.forEach { assertEquals(a[it], b[it]) }
    }

    @Test
    fun `空名字被跳过_空调色板不打乱已定颜色`() {
        assertTrue(ringColors(palette, emptyMap(), listOf("", "")).isEmpty())
        val fixed = mapOf("餐饮" to Color(0xFFEF4444))
        assertEquals(fixed, ringColors(emptyList(), fixed, listOf("交通")))
    }

    @Test
    fun `分类数超过调色板容量时仍全部有值`() {
        val names = List(20) { "分类$it" }
        val colors = ringColors(palette, emptyMap(), names)
        assertEquals(names.size, colors.size)
        assertTrue(colors.values.all { it in palette })
    }

    @Test
    fun `色差函数与 HSV 往返可用`() {
        // 同色 0、红绿之间很远（自动分配的判据依赖它）
        assertEquals(0f, colorDistance(palette[0], palette[0]), 0.001f)
        assertTrue(colorDistance(palette[0], palette[3]) > 50f)
        // 色环弹窗依赖 HSV 往返：RGB → HSV → RGB 应回到原色（±1/255）
        palette.forEach { c ->
            val hsv = rgbToHsv(c.red, c.green, c.blue)
            val back = hsvToRgb(hsv[0], hsv[1], hsv[2])
            assertTrue(abs(back[0] - c.red) < 0.005f)
            assertTrue(abs(back[1] - c.green) < 0.005f)
            assertTrue(abs(back[2] - c.blue) < 0.005f)
        }
    }
}
