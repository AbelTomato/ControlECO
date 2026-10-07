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

    /**
     * 分 → 紧凑金额（首页圆环等窄空间用）：
     * 整数部分 ≤ 4 位（< 1 万元）正常分组显示（如 ¥1,234.12）；
     * 超过 4 位进位到 K（千）/ M（百万）/ B（十亿），保留两位小数并去除尾零
     * （如 ¥32.31K、¥10K、¥1.23M）。
     */
    fun formatYuanCompact(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val yuan = abs / 100.0
        val sign = if (negative) "-¥" else "¥"
        return when {
            yuan >= 1_000_000_000.0 -> sign + trimNumber(yuan / 1_000_000_000.0) + "B"
            yuan >= 1_000_000.0 -> sign + trimNumber(yuan / 1_000_000.0) + "M"
            yuan >= 10_000.0 -> sign + trimNumber(yuan / 1_000.0) + "K"
            else -> formatYuanGrouped(cents)
        }
    }

    /** 保留两位小数（四舍五入）并去除尾零：32.31232 → "32.31"，10.0 → "10" */
    private fun trimNumber(value: Double): String =
        BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
