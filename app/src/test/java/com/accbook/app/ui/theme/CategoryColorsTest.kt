package com.accbook.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 圆环配色：**同屏不重复** + **彼此尽量远**（最远点贪心）+ 与传入顺序无关 + 超容量不崩。
 * 距离用 CIELAB ΔE；圆环化会把饱和度抬到下限、明度压进亮带（纯色块互相好分辨）。
 */
class CategoryColorsTest {

    /** 用**主题真实调色板**，算法在真实输入上的表现才算数 */
    private val palette = lightAccBookColors().categoryPalette

    private val eightNames = listOf("餐饮", "交通", "购物", "日用", "娱乐", "医疗", "居住", "其他")

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
        val names = eightNames + listOf("通讯", "学习", "旅行", "人情", "宠物", "运动", "数码", "美妆")
        val colors = ringColors(palette, names)
        assertEquals(names.size, colors.size)
        assertEquals("同屏取色不应重复", names.size, colors.values.toSet().size)
    }

    @Test
    fun `取色与传入顺序无关`() {
        val a = ringColors(palette, eightNames)
        val b = ringColors(palette, eightNames.reversed())
        eightNames.forEach { assertEquals(a[it], b[it]) }
    }

    @Test
    fun `分配结果彼此尽量远_任意两色的 ΔE 都够大`() {
        val list = ringColors(palette, eightNames).values.toList()
        val minDelta = minPairDistance(list)
        // 圆环化 + 撞色就换"最远点"：最差的一对也应该能一眼分开
        assertTrue("最小色差过小：$minDelta", minDelta >= RING_MIN_DELTA - 0.5f)
    }

    @Test
    fun `与徽标同色优先_撞色才换`() {
        val ringPalette = palette.map { ringBoost(it) }
        val assigned = ringColors(palette, eightNames)
        // 至少有一半分类保留了自己的哈希取色（与账单列表的图标/徽标同色）
        val kept = eightNames.count { name ->
            assigned[name] == ringPalette[Math.floorMod(nameHash(name), ringPalette.size)]
        }
        assertTrue("保住哈希色的分类数：$kept/${eightNames.size}", kept >= eightNames.size / 2)
        // 无论怎么换，最小色差都不低于阈值
        assertTrue(minPairDistance(assigned.values.toList()) >= RING_MIN_DELTA - 0.5f)
    }

    @Test
    fun `圆环化把饱和度与明度抬进可分辨区间_色相不变`() {
        palette.forEach { c ->
            val before = rgbToHsv(c.red, c.green, c.blue)
            val boosted = ringBoost(c)
            val after = rgbToHsv(boosted.red, boosted.green, boosted.blue)
            val dump = "color=${c.value.toString(16)} before=${before.toList()} after=${after.toList()}"
            // 容差 0.005：HSV→RGB→HSV 往返 + Color 通道量化会掉千分之几
            assertTrue("饱和度应不低于下限 $dump", after[1] >= RING_MIN_SATURATION - 0.005f)
            assertTrue("饱和度不应下降", after[1] >= before[1] - 0.005f)
            assertTrue("明度应进入亮带", after[2] >= RING_MIN_VALUE - 0.001f && after[2] <= RING_MAX_VALUE + 0.001f)
            assertTrue("色相应保持", abs(before[0] - after[0]) < 1.5f)
            assertEquals(c.alpha, boosted.alpha, 0.001f)
        }
    }

    @Test
    fun `重复名字不会拿到两个颜色`() {
        val colors = ringColors(palette, listOf("餐饮", "餐饮", "交通"))
        assertEquals(2, colors.size)
    }

    @Test
    fun `分类数超过调色板容量时仍全部有值`() {
        val names = List(20) { "分类$it" }
        val colors = ringColors(palette, names)
        assertEquals(names.size, colors.size)
        val ringPalette = palette.map { ringBoost(it) }
        assertTrue(colors.values.all { it in ringPalette })
    }

    @Test
    fun `空名字被跳过_空调色板返回空表`() {
        assertTrue(ringColors(palette, listOf("", "")).isEmpty())
        assertTrue(ringColors(emptyList(), listOf("餐饮", "交通")).isEmpty())
    }

    @Test
    fun `单名字取色与 categoryColor 落在同一槽位`() {
        // 单个分类时用哈希首选位（与徽标/图标取色同位），只是圆环化后更艳
        val single = ringColors(palette, listOf("餐饮"))["餐饮"]!!
        // 同一槽位（同一色相族），只是圆环化后更艳
        assertEquals(ringBoost(categoryColor(palette, "餐饮")), single)
        assertNotEquals(Color.Unspecified, single)
        assertTrue(single in palette.map { ringBoost(it) })
    }

    private fun nameHash(name: String): Int {
        var h = 0
        for (ch in name) h = (h * 31 + ch.code) and 0x7FFFFFFF
        return h
    }

    private fun minPairDistance(colors: List<Color>): Float {
        var min = Float.MAX_VALUE
        for (i in colors.indices) {
            for (j in i + 1 until colors.size) min = minOf(min, colorDistance(colors[i], colors[j]))
        }
        return min
    }
}
