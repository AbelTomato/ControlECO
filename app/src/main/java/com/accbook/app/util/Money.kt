package com.accbook.app.util

import java.math.BigDecimal

/**
 * 金额工具：内部一律以“分”（Long）存储，避免浮点误差。
 */
object Money {

    private val pattern = Regex("""^-?\d+(\.\d{1,2})?$""")

    /**
     * 解析金额文本为分。支持：¥/￥ 前缀、千分位逗号、正负号、前后空白。
     * 仅接受最多两位小数；非法输入返回 null。
     * 注意：逗号只在符合千分位格式（如 1,234.56）时移除，"1,2.3" 这类脏输入返回 null。
     */
    fun parseCents(text: String): Long? {
        var cleaned = text.replace("¥", "").replace("￥", "").replace(" ", "").trim()
        if (cleaned.contains(',')) {
            val thousandSeparated = Regex("""^-?\d{1,3}(,\d{3})+(\.\d{1,2})?$""")
            cleaned = if (thousandSeparated.matches(cleaned)) cleaned.replace(",", "") else cleaned
        }
        if (cleaned.isEmpty() || !pattern.matches(cleaned)) return null
        return BigDecimal(cleaned).movePointRight(2).longValueExact()
    }

    /** 分 → "1234.56"（负数带 -，不含货币符号） */
    fun formatCents(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val whole = abs / 100
        val frac = (abs % 100).toString().padStart(2, '0')
        return (if (negative) "-" else "") + whole + "." + frac
    }

    /** 分 → "¥1234.56"（负数形如 -¥12.34） */
    fun formatYuan(cents: Long): String {
        val negative = cents < 0
        return (if (negative) "-" else "") + "¥" + formatCents(if (negative) -cents else cents)
    }

    /**
     * 分 → "¥2,884"（整数金额不带小数、千分位分组；有小数时保留两位，如 ¥32.50）。
     * 首页/汇总等大金额展示用，短且易读。
     */
    fun formatYuanGrouped(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val whole = abs / 100
        val frac = abs % 100
        val grouped = java.text.DecimalFormat("#,##0").format(whole)
        val body = if (frac == 0L) grouped else "$grouped." + frac.toString().padStart(2, '0')
        return (if (negative) "-" else "") + "¥" + body
    }
}
