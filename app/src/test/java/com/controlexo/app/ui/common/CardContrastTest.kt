package com.controlexo.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardContrastTest {

    @Test
    fun `相对亮度端点正确`() {
        assertEquals(0f, relativeLuminance(0f, 0f, 0f), 1e-4f)
        assertEquals(1f, relativeLuminance(1f, 1f, 1f), 1e-4f)
        // 中灰的线性亮度远低于 0.5（sRGB 非线性）
        assertTrue(relativeLuminance(0.5f, 0.5f, 0.5f) < 0.25f)
    }

    @Test
    fun `对比度上下限`() {
        assertEquals(21f, contrastRatio(1f, 0f), 1e-3f)
        assertEquals(1f, contrastRatio(0.4f, 0.4f), 1e-3f)
    }

    @Test
    fun `足够对比时不需要遮罩`() {
        // 很亮的底 + 深墨字：对比度远大于 3
        assertEquals(0f, scrimAlphaFor(0.95f, 0.015f), 1e-3f)
        // 很暗的底 + 白字
        assertEquals(0f, scrimAlphaFor(0.05f, 1f), 1e-3f)
    }

    @Test
    fun `对比不足时按遮罩推算后达标`() {
        // 中灰底(0.5) + 白字：对比度仅 1.9 → 需叠黑遮罩
        val dark = scrimAlphaFor(0.5f, 1f)
        assertTrue("白字应有黑遮罩 ($dark)", dark > 0f)
        val afterDark = 0.5f * (1f - dark)
        assertTrue("遮罩后对比度需 ≥3", contrastRatio(afterDark, 1f) >= 3f - 1e-3f)

        // 暗底(0.05) + 墨字：对比度仅 1.5 → 需叠白遮罩
        val light = scrimAlphaFor(0.05f, 0.015f)
        assertTrue("墨字应有白遮罩 ($light)", light > 0f)
        val afterLight = 0.05f + light * (1f - 0.05f)
        assertTrue("遮罩后对比度需 ≥3", contrastRatio(afterLight, 0.015f) >= 3f - 1e-3f)
    }
}
