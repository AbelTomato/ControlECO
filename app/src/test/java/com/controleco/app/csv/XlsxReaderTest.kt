package com.controleco.app.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxReaderTest {

    private fun buildXlsx(sheetXml: String, sharedXml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetXml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(sharedXml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `missing cells in between are padded with empty strings`() {
        // 第 1 行只有 A1 和 C1，B1 缺失；第 2 行只有 B2
        val sheetXml = "<worksheet><sheetData>" +
            "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\" t=\"s\"><v>2</v></c></row>" +
            "<row r=\"2\"><c r=\"B2\"><v>1.5</v></c></row>" +
            "</sheetData></worksheet>"
        val sharedXml = "<sst><si><t>甲</t></si><si><t>乙</t></si><si><t>丙</t></si></sst>"
        val rows = XlsxReader.parse(buildXlsx(sheetXml, sharedXml))
        assertEquals(listOf("甲", "", "丙"), rows[0])
        assertEquals(listOf("", "1.5"), rows[1])
    }

    @Test
    fun `column beyond z maps correctly`() {
        // AA = 第 27 列（下标 26）
        val sheetXml = "<worksheet><sheetData>" +
            "<row r=\"1\"><c r=\"AA1\" t=\"s\"><v>0</v></c></row>" +
            "</sheetData></worksheet>"
        val sharedXml = "<sst><si><t>x</t></si></sst>"
        val rows = XlsxReader.parse(buildXlsx(sheetXml, sharedXml))
        assertEquals(27, rows[0].size)
        assertEquals("x", rows[0][26])
    }

    @Test
    fun `xml entities are unescaped`() {
        val sheetXml = "<worksheet><sheetData>" +
            "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>a&amp;b&lt;c&gt;</t></is></c></row>" +
            "</sheetData></worksheet>"
        val sharedXml = "<sst/>"
        val rows = XlsxReader.parse(buildXlsx(sheetXml, sharedXml))
        assertEquals("a&b<c>", rows[0][0])
    }

    @Test
    fun `zip magic detection`() {
        assertTrue(XlsxReader.isXlsx(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(XlsxReader.isXlsx(byteArrayOf(0x31, 0x0A, 0x32)))
        assertFalse(XlsxReader.isXlsx(ByteArray(0)))
    }
}
