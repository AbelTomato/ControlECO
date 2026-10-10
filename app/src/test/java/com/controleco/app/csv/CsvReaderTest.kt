package com.controleco.app.csv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class CsvReaderTest {

    @Test
    fun `parse simple rows`() {
        val rows = CsvReader.parse("a,b,c\n1,2,3\n")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows)
    }

    @Test
    fun `quoted fields with comma newline and escaped quotes`() {
        val text = "a,\"b,c\",\"d\ne\",\"f\"\"g\"\n"
        val rows = CsvReader.parse(text)
        assertEquals(1, rows.size)
        assertEquals(listOf("a", "b,c", "d\ne", "f\"g"), rows[0])
    }

    @Test
    fun `crlf line endings`() {
        val rows = CsvReader.parse("a,b\r\n1,2\r\n")
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows)
    }

    @Test
    fun `strip utf8 bom`() {
        val rows = CsvReader.parse("\uFEFFa,b\n")
        assertEquals(listOf("a", "b"), rows[0])
    }

    @Test
    fun `decode utf8`() {
        val text = "交易时间,金额\n2024-01-01,1.00"
        assertEquals(text, CsvReader.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `decode gbk fallback`() {
        val text = "交易时间,金额,备注"
        assertEquals(text, CsvReader.decode(text.toByteArray(Charset.forName("GBK"))))
    }
}
