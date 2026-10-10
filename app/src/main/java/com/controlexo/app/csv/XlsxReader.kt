package com.controlexo.app.csv

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 极简 xlsx 读取：只解析 sharedStrings 与第一个工作表，输出与 CsvReader 相同的"行/列字符串"结构。
 * 微信 2026 版起的账单导出为 xlsx。用正则解析而非完整 XML 库：保持零依赖，且可在 JVM 单元测试中运行。
 * 支持单元格类型：共享字符串(t="s")、内联字符串(t="inlineStr")、数值/公式(默认取 <v> 原文)。
 */
object XlsxReader {

    private val siRegex = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
    private val tRegex = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val rowRegex = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
    private val cellRegex = Regex("<c([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val vRegex = Regex("<v(?:\\s[^>]*)?>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
    private val typeRegex = Regex("t=\"(\\w+)\"")
    private val refRegex = Regex("r=\"([A-Z]+)\\d+\"")

    /** zip 文件魔数 PK */
    fun isXlsx(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    fun parse(bytes: ByteArray): List<List<String>> {
        val contents = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                contents[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        val shared = contents["xl/sharedStrings.xml"]
            ?.let { parseSharedStrings(it.toString(Charsets.UTF_8)) }
            ?: emptyList()
        val sheetName = contents.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()
            .firstOrNull() ?: return emptyList()
        return parseSheet(contents.getValue(sheetName).toString(Charsets.UTF_8), shared)
    }

    private fun parseSharedStrings(xml: String): List<String> =
        siRegex.findAll(xml).map { m ->
            tRegex.findAll(m.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
        }.toList()

    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> =
        rowRegex.findAll(xml).map { rowMatch ->
            val cells = mutableListOf<String>()
            for (cellMatch in cellRegex.findAll(rowMatch.groupValues[1])) {
                val attrs = cellMatch.groupValues[1]
                val body = cellMatch.groupValues[2]
                val col = refRegex.find(attrs)?.groupValues?.get(1)?.let { columnToIndex(it) } ?: cells.size
                while (cells.size <= col) cells.add("")
                cells[col] = when (typeRegex.find(attrs)?.groupValues?.get(1)) {
                    "s" -> {
                        val idx = vRegex.find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()
                        if (idx != null && idx in shared.indices) unescape(shared[idx]) else ""
                    }
                    "inlineStr" -> tRegex.findAll(body).joinToString("") { unescape(it.groupValues[1]) }
                    else -> vRegex.find(body)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
                }
            }
            cells.toList()
        }.toList()

    /** 列字母 → 下标：A=0, B=1 … Z=25, AA=26 … */
    private fun columnToIndex(letters: String): Int =
        letters.fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toInt().toChar().toString() }
        .replace("&amp;", "&")
}
