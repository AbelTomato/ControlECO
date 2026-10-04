package com.accbook.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `parse basic amounts`() {
        assertEquals(123456L, Money.parseCents("¥1,234.56"))
        assertEquals(123456L, Money.parseCents("1234.56"))
        assertEquals(8850L, Money.parseCents(" 88.5 "))
        assertEquals(500L, Money.parseCents("¥5"))
        assertEquals(0L, Money.parseCents("0.00"))
    }

    @Test
    fun `parse signed amounts`() {
        assertEquals(-320L, Money.parseCents("-3.20"))
        assertEquals(320L, Money.parseCents("3.20"))
    }

    @Test
    fun `parse invalid returns null`() {
        assertNull(Money.parseCents("abc"))
        assertNull(Money.parseCents(""))
        assertNull(Money.parseCents("1.234"))
        assertNull(Money.parseCents("--1.0"))
        assertNull(Money.parseCents("1,2.3"))
    }

    @Test
    fun `format cents`() {
        assertEquals("1234.56", Money.formatCents(123456))
        assertEquals("-3.20", Money.formatCents(-320))
        assertEquals("0.05", Money.formatCents(5))
        assertEquals("0.00", Money.formatCents(0))
    }

    @Test
    fun `format yuan`() {
        assertEquals("¥1234.56", Money.formatYuan(123456))
        assertEquals("-¥3.20", Money.formatYuan(-320))
        assertEquals("¥0.05", Money.formatYuan(5))
    }
}
