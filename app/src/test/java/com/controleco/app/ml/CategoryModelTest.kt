package com.controleco.app.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class CategoryModelTest {
    @Test
    fun `loadOrNull safely handles missing or corrupt model`() {
        assertNull(CategoryModel.loadOrNull { error("missing asset") })
        assertNull(CategoryModel.loadOrNull { stream("not json") })
    }

    @Test
    fun `loads python format and predicts allowed label`() {
        val model = CategoryModel.load(fixture())

        assertEquals("餐饮", model.predictExpenseCategory("咖啡123", setOf("餐饮"))?.label)
    }

    @Test
    fun `numeric redaction matches python training normalization`() {
        val model = CategoryModel.load(fixture())

        assertEquals("餐饮", model.predictExpenseCategory("咖啡9999", setOf("餐饮"))?.label)
    }

    @Test
    fun `rejects labels outside current expense allow list`() {
        val model = CategoryModel.load(fixture())

        assertNull(model.predictExpenseCategory("咖啡123", setOf("交通")))
    }

    @Test
    fun `rejects invalid schema`() {
        val invalid = fixtureJson().replace("\"schema_version\": 1", "\"schema_version\": 99")

        assertTrue(runCatching { CategoryModel.load(stream(invalid)) }.isFailure)
    }

    @Test
    fun `rejects oversized model dimensions`() {
        val invalid = fixtureJson().replace("\"ngram_max\": 1", "\"ngram_max\": 99")

        assertTrue(runCatching { CategoryModel.load(stream(invalid)) }.isFailure)
    }

    @Test
    fun `empty text and low confidence return no prediction`() {
        val model = CategoryModel.load(fixture())

        assertNull(model.predictExpenseCategory("  ", setOf("餐饮", "交通")))
        assertNull(model.predictExpenseCategory("未知文本", setOf("餐饮", "交通")))
    }

    private fun fixture() = stream(fixtureJson())

    private fun stream(value: String) = ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))

    private fun fixtureJson() = """
        {
          "schema_version": 1,
          "labels": ["交通", "餐饮"],
          "vocabulary": {"咖": 0, "啡": 1},
          "weights": [[4.0, 4.0]],
          "intercepts": [0.0],
          "ngram_min": 1,
          "ngram_max": 1,
          "thresholds": {"confidence": 0.6, "margin": 0.15}
        }
    """.trimIndent()
}