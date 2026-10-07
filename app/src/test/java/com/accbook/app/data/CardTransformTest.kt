package com.accbook.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CardTransformTest {

    @Test
    fun `序列化与解析往返一致`() {
        val t = CardTransform(scale = 1.35f, offsetX = 0.08f, offsetY = -0.2f, rotation = 12f)
        assertEquals(t, CardTransform.parse(t.serialize()))
    }

    @Test
    fun `空值与脏数据回落到默认`() {
        assertEquals(CardTransform.DEFAULT, CardTransform.parse(null))
        assertEquals(CardTransform.DEFAULT, CardTransform.parse(""))
        assertEquals(CardTransform.DEFAULT, CardTransform.parse("abc"))
        assertEquals(CardTransform.DEFAULT, CardTransform.parse("1.2|0.1"))
        assertEquals(CardTransform.DEFAULT, CardTransform.parse("x|y|z|w"))
    }

    @Test
    fun `越界参数被夹到合理区间`() {
        val big = CardTransform(scale = 99f, offsetX = 9f, offsetY = -9f, rotation = 750f).normalized()
        assertEquals(CardTransform.MAX_SCALE, big.scale, 1e-4f)
        assertEquals(1.5f, big.offsetX, 1e-4f)
        assertEquals(-1.5f, big.offsetY, 1e-4f)
        // 750° 归一化到 0..360
        assertEquals(30f, big.rotation, 1e-3f)

        val small = CardTransform(scale = 0.01f, rotation = -30f).normalized()
        assertEquals(CardTransform.MIN_SCALE, small.scale, 1e-4f)
        assertEquals(330f, small.rotation, 1e-3f)
    }

    @Test
    fun `解析时也会夹取越界值`() {
        val parsed = CardTransform.parse("9|5|5|400")
        assertEquals(CardTransform.MAX_SCALE, parsed.scale, 1e-4f)
        assertEquals(1.5f, parsed.offsetX, 1e-4f)
        assertEquals(40f, parsed.rotation, 1e-3f)
    }
}
