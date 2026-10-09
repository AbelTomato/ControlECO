package com.accbook.app.ui.stats

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 双环饼图命中测试：点按/滑动落在哪一环的哪个分类上（角度 → 切片映射）。
 * 与绘制共用同一套圆心/半径算法（[donutGeometry]），所以这里验证的就是手势选中的行为。
 */
class DonutGeometryTest {

    private val density = Density(1f)
    private val canvas = 176f          // 画布边长（px，密度 1 时 = 176dp）
    private val geometry = donutGeometry(canvas, density)
    private val center = Offset(canvas / 2f, canvas / 2f)

    /** 极坐标取点：角度按绘制约定（0° 在正上方，顺时针增大） */
    private fun pointAt(radius: Float, degrees: Float): Offset {
        val rad = Math.toRadians((degrees - 90.0))
        return Offset(
            center.x + radius * cos(rad).toFloat(),
            center.y + radius * sin(rad).toFloat(),
        )
    }

    private fun slices(vararg pairs: Pair<String, Long>) = pairs.map { CategorySlice(it.first, it.second, 0L) }

    /** 实际与预算相等的切片（内外环都可命中） */
    private fun bothRings(vararg pairs: Pair<String, Long>) =
        pairs.map { CategorySlice(it.first, actualCents = it.second, budgetCents = it.second) }

    @Test
    fun `两个等分切片_正上方与正下方分别命中_内外环一致`() {
        val data = bothRings("餐饮" to 50L, "交通" to 50L)
        // 外环半径 = 88 - 10 = 78；内环 = 78 - 10 - 8 - 8 = 52
        assertEquals("餐饮", geometry.sliceAt(pointAt(78f, 20f), data))
        assertEquals("交通", geometry.sliceAt(pointAt(78f, 200f), data))
        assertEquals("餐饮", geometry.sliceAt(pointAt(52f, 20f), data))
        assertEquals("交通", geometry.sliceAt(pointAt(52f, 200f), data))
    }

    @Test
    fun `四等分切片_四个象限各自命中`() {
        val data = slices("A" to 25L, "B" to 25L, "C" to 25L, "D" to 25L)
        assertEquals("A", geometry.sliceAt(pointAt(52f, 30f), data))
        assertEquals("B", geometry.sliceAt(pointAt(52f, 120f), data))
        assertEquals("C", geometry.sliceAt(pointAt(52f, 210f), data))
        assertEquals("D", geometry.sliceAt(pointAt(52f, 300f), data))
    }

    @Test
    fun `圆心附近与画布角落都不命中`() {
        val data = slices("餐饮" to 100L)
        assertNull(geometry.sliceAt(center, data))                       // 圆心里（空白）
        assertNull(geometry.sliceAt(Offset(4f, 4f), data))               // 圆外角落
        assertNull(geometry.sliceAt(pointAt(78f, 0f), emptyList()))      // 没有切片
    }

    @Test
    fun `外环只看预算_内环只看实际`() {
        // 预算只有「购物」，实际只有「餐饮」：外环命中的是购物，内环命中的是餐饮
        val data = listOf(
            CategorySlice("餐饮", actualCents = 100L, budgetCents = 0L),
            CategorySlice("购物", actualCents = 0L, budgetCents = 100L),
        )
        assertEquals("购物", geometry.sliceAt(pointAt(78f, 90f), data))
        assertEquals("餐饮", geometry.sliceAt(pointAt(52f, 90f), data))
    }
}
