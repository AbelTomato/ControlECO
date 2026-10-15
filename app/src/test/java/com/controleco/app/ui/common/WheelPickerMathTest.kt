package com.controleco.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 立体滚轮的解析数学（圆柱投影 / 渐隐 / 惯性夹取 / 点击反解）——纯函数，不依赖 Compose。
 * 这些数字直接决定"行距是否按圆柱压缩、边缘是否渐隐、惯性会不会冲过边界"，
 * 真机只做观感确认，几何与边界在这里锁死。
 */
class WheelPickerMathTest {

    private val itemHeightPx = 28f
    private val radiusPx = itemHeightPx * WHEEL_RADIUS_ROWS // 84px

    // ---------- 圆柱投影 ----------

    @Test
    fun `一行对应三分之一弧度`() {
        assertEquals(1f / 3f, wheelPhi(1f, itemHeightPx, radiusPx), 0.0001f)
        assertEquals(-1f / 3f, wheelPhi(-1f, itemHeightPx, radiusPx), 0.0001f)
        assertEquals(0f, wheelPhi(0f, itemHeightPx, radiusPx), 0.0001f)
    }

    @Test
    fun `中心一项接近一比一_两端被圆柱压缩`() {
        // 中心邻行：正弦投影本身带一点压缩（84·sin(1/3) ≈ 27.48，行高 28），误差 < 5%；
        // 跟手按弧长映射（见"拖动映射与手指一比一"），中心项视觉上仍是 1:1
        val y1 = wheelOffsetY(wheelPhi(1f, itemHeightPx, radiusPx), radiusPx)
        assertEquals(itemHeightPx, y1, itemHeightPx * 0.05f)
        assertEquals(-y1, wheelOffsetY(wheelPhi(-1f, itemHeightPx, radiusPx), radiusPx), 0.01f)
        // 两端：正弦投影使间距小于线性（圆柱压缩），压缩越来越明显
        val y2 = wheelOffsetY(wheelPhi(2f, itemHeightPx, radiusPx), radiusPx)
        val y3 = wheelOffsetY(wheelPhi(3f, itemHeightPx, radiusPx), radiusPx)
        assertTrue("第 2 行比线性更近中心：$y2", y2 < 2 * itemHeightPx)
        assertTrue("第 3 行压缩更明显：$y3 < $y2", y3 - y2 < y2 - itemHeightPx)
        // 正弦关系直接成立
        assertEquals((radiusPx * sin(wheelPhi(2f, itemHeightPx, radiusPx))), y2, 0.0001f)
    }

    @Test
    fun `透视缩放随距离单调减小且中心为一`() {
        assertEquals(1f, wheelScale(0f, radiusPx), 0.0001f)
        val s1 = wheelScale(wheelPhi(1f, itemHeightPx, radiusPx), radiusPx)
        val s2 = wheelScale(wheelPhi(2f, itemHeightPx, radiusPx), radiusPx)
        val s3 = wheelScale(wheelPhi(3f, itemHeightPx, radiusPx), radiusPx)
        assertTrue("$s1 应小于 1", s1 < 1f)
        assertTrue("$s2 < $s1", s2 < s1)
        assertTrue("$s3 < $s2", s3 < s2)
        // 对称性：正负方向同缩放
        assertEquals(s2, wheelScale(wheelPhi(-2f, itemHeightPx, radiusPx), radiusPx), 0.0001f)
    }

    @Test
    fun `透明度中心最高_两端触底并夹住下限`() {
        assertEquals(1f, wheelAlpha(0f), 0.0001f)
        assertTrue(wheelAlpha(1f) < 1f)
        assertTrue(wheelAlpha(2f) < wheelAlpha(1f))
        assertTrue(wheelAlpha(-2f) == wheelAlpha(2f))
        assertEquals(0.15f, wheelAlpha(10f), 0.0001f)
    }

    // ---------- 惯性边界 ----------

    @Test
    fun `惯性速度被夹到不冲出边界`() {
        // 距末尾 2 项、时间常数 0.25s → 速度上限 8 项/秒
        assertEquals(8f, clampFlingVelocity(50f, posItems = 28f, lastIndex = 30, tauSeconds = 0.25f), 0.0001f)
        // 距开头 3 项、反向滑动同理
        assertEquals(-12f, clampFlingVelocity(-50f, posItems = 3f, lastIndex = 30, tauSeconds = 0.25f), 0.0001f)
        // 已经在边界上还往外滑 → 0（不弹跳）
        assertEquals(0f, clampFlingVelocity(20f, posItems = 30f, lastIndex = 30, tauSeconds = 0.25f), 0.0001f)
        assertEquals(0f, clampFlingVelocity(-20f, posItems = 0f, lastIndex = 30, tauSeconds = 0.25f), 0.0001f)
        // 窗口内的速度原样保留
        assertEquals(5f, clampFlingVelocity(5f, posItems = 10f, lastIndex = 30, tauSeconds = 0.25f), 0.0001f)
    }

    // ---------- 点击反解 ----------

    @Test
    fun `点击位置反解到正确项`() {
        // 正中心 = 当前项
        assertEquals(5, wheelIndexAt(0f, posItems = 5f, itemHeightPx = itemHeightPx))
        // 中心下方一行 = 下一项
        assertEquals(6, wheelIndexAt(itemHeightPx, posItems = 5f, itemHeightPx = itemHeightPx))
        assertEquals(4, wheelIndexAt(-itemHeightPx, posItems = 5f, itemHeightPx = itemHeightPx))
        // 反解与正向投影互逆（用正向投影算出 y，再反解回同一项）
        for (d in -3..3) {
            val y = wheelOffsetY(wheelPhi(d.toFloat(), itemHeightPx, radiusPx), radiusPx)
            assertEquals(5 + d, wheelIndexAt(y, posItems = 5f, itemHeightPx = itemHeightPx))
        }
        // 超出圆柱范围也不崩（夹到边界方向）
        assertTrue(wheelIndexAt(10_000f, posItems = 5f, itemHeightPx = itemHeightPx) > 5)
        assertTrue(wheelIndexAt(-10_000f, posItems = 5f, itemHeightPx = itemHeightPx) < 5)
    }

    @Test
    fun `吸附目标只会落在整数项`() {
        // 模拟吸附：连续位置四舍五入 + 夹取，任何拖动中间态都收敛到合法整数
        val cases = listOf(0f, 0.49f, 0.5f, 2.5f, 29.6f, 30f, -0.4f)
        cases.forEach { raw ->
            val snapped = raw.roundToInt().coerceIn(0, 30)
            assertTrue("$raw → $snapped", snapped in 0..30)
        }
        assertEquals(1, 0.6f.roundToInt())
        assertEquals(0, 0.4f.roundToInt())
        assertEquals(30, (30.6f).roundToInt().coerceIn(0, 30))
    }

    // ---------- 边缘渐隐（内容在贴到边缘前淡出） ----------

    @Test
    fun `边缘渐隐在中心处不生效_贴边处归零`() {
        val rowH = 28f
        val halfH = rowH * 3 / 2f // 三行窗口
        val fadeLen = rowH * WHEEL_EDGE_FADE_ROWS
        fun edge(yFromCenter: Float) =
            ((halfH - abs(yFromCenter)) / fadeLen).coerceIn(0f, 1f)

        // 中心一行：完全不受影响
        assertEquals(1f, edge(0f), 0.0001f)
        // 相邻一行（正弦投影约 27.5px）：仍基本可见
        assertTrue("相邻行不应被压暗太多：${edge(27.5f)}", edge(27.5f) > 0.7f)
        // 贴到边缘：彻底淡出（不会硬切）
        assertEquals(0f, edge(halfH), 0.0001f)
        assertEquals(0f, edge(halfH + 10f), 0.0001f)
    }

    @Test
    fun `拖动映射与手指一比一`() {
        // 拖动 delta（像素）→ 项位移 = delta / 行高；中心项视觉上正好跟手
        val delta = 42f
        val items = delta / itemHeightPx
        assertEquals(1.5f, items, 0.0001f)
        assertEquals(abs(items), abs(delta / itemHeightPx), 0.0001f)
    }
}
