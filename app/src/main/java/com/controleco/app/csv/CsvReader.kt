package com.controleco.app.csv

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 通用 CSV 读取：支持双引号包裹、引号内逗号/换行/转义引号、CRLF/LF。
 * 编码自动识别：先按 UTF-8 严格解码，失败回退 GBK（支付宝老版导出为 GBK）。
 */
object CsvReader {

    fun decode(bytes: ByteArray): String {
        val utf8Text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            null
        }
        // UTF-8 严格解码成功且含账单关键词 → 直接使用；
        // 否则按 GBK 解码（支付宝老版导出），GBK 字节序列偶发能被 UTF-8 解码成乱码，需关键词校验兜底
        if (utf8Text != null && (isAsciiOnly(utf8Text) || utf8Text.contains("交易"))) {
            return utf8Text
        }
        val gbkText = String(bytes, Charset.forName("GBK"))
        return if (utf8Text == null || gbkText.contains("交易")) gbkText else utf8Text
    }

    private fun isAsciiOnly(text: String) = text.none { it.code > 0x7F }

    fun parse(text: String): List<List<String>> {
        val body = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        val field = StringBuilder()
        val current = mutableListOf<String>()
        var inQuotes = false

        fun endRow() {
            current.add(field.toString())
            field.clear()
            rows.add(current.toList())
            current.clear()
        }

        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                inQuotes -> when (c) {
                    '"' -> {
                        if (i + 1 < body.length && body[i + 1] == '"') {
                            field.append('"'); i++
                        } else {
                            inQuotes = false
                        }
                    }
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    current.add(field.toString()); field.clear()
                }
                c == '\r' -> {
                    if (i + 1 < body.length && body[i + 1] == '\n') i++
                    endRow()
                }
                c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || current.isNotEmpty()) endRow()
        return rows
    }
}
