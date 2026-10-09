package com.accbook.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图标规格解析：`""` / `EMOJI:🍜` / `GLYPH:BURGER` / `GLYPH:BURGER:DC2626` / `IMAGE:xxx.jpg`。
 * 带自定义颜色时，图形名不能被颜色部分污染（否则内置图形会解析不出、回落到按名字取形）。
 */
class CategoryIconSpecTest {

    @Test
    fun `内置图形不带颜色时按名字取色`() {
        val spec = CategoryIcon.glyph("BURGER")
        assertEquals("GLYPH:BURGER", spec)
        assertEquals("BURGER", CategoryIcon.glyphOf(spec))
        assertNull(CategoryIcon.glyphColorOf(spec))
    }

    @Test
    fun `内置图形带颜色时名字与颜色都能解析`() {
        val spec = CategoryIcon.glyph("BURGER", 0xDC2626)
        assertEquals("GLYPH:BURGER:DC2626", spec)
        assertEquals("BURGER", CategoryIcon.glyphOf(spec))
        assertEquals(0xDC2626, CategoryIcon.glyphColorOf(spec))
    }

    @Test
    fun `颜色大小写与高位无关_只取低 24 位`() {
        assertEquals(0xA21CAF, CategoryIcon.glyphColorOf("GLYPH:MEAL:a21caf"))
        assertEquals(0xDC2626, CategoryIcon.glyphColorOf("GLYPH:MEAL:FFDC2626"))
        assertNull(CategoryIcon.glyphColorOf("GLYPH:MEAL:zzz"))
    }

    @Test
    fun `其它规格不受影响`() {
        assertNull(CategoryIcon.glyphOf("EMOJI:🍜"))
        assertNull(CategoryIcon.glyphColorOf("EMOJI:🍜"))
        assertNull(CategoryIcon.glyphOf("IMAGE:cat_icon_1.jpg"))
        assertNull(CategoryIcon.glyphOf(""))
        assertEquals("cat_icon_1.jpg", CategoryIcon.imageOf("IMAGE:cat_icon_1.jpg"))
        assertEquals("🍜", CategoryIcon.emojiOf("EMOJI:🍜"))
    }
}
