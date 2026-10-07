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

    @Test
    fun `compact keeps normal format up to 4 integer digits`() {
        assertEquals("¥1,234.12", Money.formatYuanCompact(123412))
        assertEquals("¥9,999.99", Money.formatYuanCompact(999999))
        assertEquals("¥2,884", Money.formatYuanCompact(288400))
        assertEquals("¥0.05", Money.formatYuanCompact(5))
    }

    @Test
    fun `compact carries unit only when digits before decimal point exceed 4`() {
        // 小数点前 4 位（含）不升档
        assertEquals("¥1,234.12", Money.formatYuanCompact(123412))
        assertEquals("¥9,999.99", Money.formatYuanCompact(999999))
        assertEquals("¥2,884", Money.formatYuanCompact(288400))
        assertEquals("¥0.05", Money.formatYuanCompact(5))
        // 32,312.32 → 32.31K（用户示例）
        assertEquals("¥32.31K", Money.formatYuanCompact(3_231_232))
        // 10,000 整 → 去尾零
        assertEquals("¥10K", Money.formatYuanCompact(1_000_000))
        assertEquals("¥12.5K", Money.formatYuanCompact(1_250_000))
        // K 档显示值仍在 4 位以内（1000K / 1234.57K）时不进 M
        assertEquals("¥1000K", Money.formatYuanCompact(100_000_000))
        assertEquals("¥1234.57K", Money.formatYuanCompact(123_456_789))
        // 进位到下一档：K 显示值超过 4 位才用 M
        assertEquals("¥10M", Money.formatYuanCompact(1_000_000_000))
        assertEquals("¥12.35M", Money.formatYuanCompact(1_234_567_890))
        // M 显示值超过 4 位才用 B
        assertEquals("¥12.35B", Money.formatYuanCompact(1_234_567_890_100))
        // 负数保留符号
        assertEquals("-¥32.31K", Money.formatYuanCompact(-3_231_232))
        assertEquals("-¥12.35M", Money.formatYuanCompact(-1_234_567_890))
    }

    @Test
    fun `compact rounds up at unit boundary keeping 4 digits`() {
        // 9,999,999.99 → K 档四舍五入后为 10000（5 位），再进一档
        assertEquals("¥10M", Money.formatYuanCompact(999_999_999))
        assertEquals("¥9999.99K", Money.formatYuanCompact(999_999_000))
    }

    @Test
    fun `grouped fixed keeps two decimals`() {
        assertEquals("¥3,480.00", Money.formatYuanGroupedFixed(348_000))
        assertEquals("¥8,520.00", Money.formatYuanGroupedFixed(852_000))
        assertEquals("¥0.05", Money.formatYuanGroupedFixed(5))
        assertEquals("-¥32.00", Money.formatYuanGroupedFixed(-3_200))
        assertEquals("¥1,234,567.89", Money.formatYuanGroupedFixed(123_456_789))
    }

    @Test
    fun `signed plain has no currency symbol`() {
        assertEquals("-32.00", Money.formatSignedPlain(-3_200))
        assertEquals("+12,000.00", Money.formatSignedPlain(1_200_000))
        assertEquals("+0.50", Money.formatSignedPlain(50))
    }
}
