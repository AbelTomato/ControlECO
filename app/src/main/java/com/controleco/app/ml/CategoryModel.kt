package com.controleco.app.ml

import java.io.InputStream
import java.text.Normalizer
import kotlin.math.exp

data class Prediction(val label: String, val confidence: Double, val margin: Double)

class CategoryModel private constructor(
    private val labels: List<String>,
    private val vocabulary: Map<String, Int>,
    private val weights: List<DoubleArray>,
    private val intercepts: DoubleArray,
    private val ngramMin: Int,
    private val ngramMax: Int,
    private val confidenceThreshold: Double,
    private val marginThreshold: Double,
) {
    fun predictExpenseCategory(text: String, allowedLabels: Set<String>): Prediction? {
        val normalized = normalize(text)
        if (normalized.isEmpty() || allowedLabels.isEmpty()) return null

        val rawScores = DoubleArray(weights.size) { index ->
            var score = intercepts[index]
            for (size in ngramMin..ngramMax) {
                for (start in 0..normalized.length - size) {
                    val featureIndex = vocabulary[normalized.substring(start, start + size)] ?: continue
                    score += weights[index][featureIndex]
                }
            }
            score
        }
        val scores = if (labels.size == 2 && rawScores.size == 1) {
            doubleArrayOf(-rawScores[0] / 2.0, rawScores[0] / 2.0)
        } else {
            rawScores
        }
        val maximum = scores.maxOrNull() ?: return null
        val probabilities = DoubleArray(scores.size) { index ->
            exp((scores[index] - maximum).coerceAtLeast(-700.0))
        }
        val total = probabilities.sum()
        if (!total.isFinite() || total <= 0.0) return null
        for (index in probabilities.indices) probabilities[index] /= total

        val ranked = labels.indices.sortedByDescending { probabilities[it] }
        val bestIndex = ranked.firstOrNull() ?: return null
        val bestLabel = labels[bestIndex]
        if (bestLabel !in allowedLabels) return null
        val confidence = probabilities[bestIndex]
        val margin = confidence - (ranked.getOrNull(1)?.let { probabilities[it] } ?: 0.0)
        if (confidence < confidenceThreshold || margin < marginThreshold) return null
        return Prediction(bestLabel, confidence, margin)
    }

    companion object {
        private const val MAX_LABELS = 256
        private const val MAX_FEATURES = 20_000
        private const val MAX_WEIGHT_CELLS = 1_000_000
        private const val MAX_NGRAM_SIZE = 8
        private const val MAX_MODEL_BYTES = 16 * 1024 * 1024

        fun loadOrNull(openAsset: () -> InputStream): CategoryModel? =
            runCatching { load(openAsset()) }.getOrNull()

        fun load(input: InputStream): CategoryModel {
            val bytes = input.use { it.readNBytes(MAX_MODEL_BYTES + 1) }
            require(bytes.size <= MAX_MODEL_BYTES) { "Model file is too large" }
            val root = JsonParser(String(bytes, Charsets.UTF_8)).parse() as? Map<*, *>
                ?: error("Model root must be an object")
            require(root.int("schema_version") == 1) { "Unsupported category model schema" }

            val labelArray = root.array("labels")
            require(labelArray.size in 2..MAX_LABELS) { "Invalid label count" }
            val labels = labelArray.map { it as? String ?: error("Invalid label") }
            require(labels.all { it.isNotBlank() } && labels.toSet().size == labels.size) {
                "Invalid labels"
            }

            val vocabularyObject = root.obj("vocabulary")
            require(vocabularyObject.size in 1..MAX_FEATURES) { "Invalid vocabulary size" }
            val vocabulary = HashMap<String, Int>(vocabularyObject.size)
            val indices = HashSet<Int>(vocabularyObject.size)
            for ((key, rawIndex) in vocabularyObject) {
                val feature = key as? String ?: error("Invalid vocabulary feature")
                val index = rawIndex as? Int ?: error("Invalid vocabulary index")
                require(feature.isNotEmpty() && index in 0 until vocabularyObject.size) {
                    "Invalid vocabulary entry"
                }
                require(indices.add(index)) { "Duplicate vocabulary index" }
                vocabulary[feature] = index
            }
            require(indices.size == vocabulary.size) { "Incomplete vocabulary indices" }

            val ngramMin = root.int("ngram_min")
            val ngramMax = root.int("ngram_max")
            require(ngramMin in 1..MAX_NGRAM_SIZE && ngramMax in ngramMin..MAX_NGRAM_SIZE) {
                "Invalid n-gram range"
            }

            val weightArray = root.array("weights")
            val interceptArray = root.array("intercepts")
            val expectedRows = if (labels.size == 2) 1 else labels.size
            require(weightArray.size == expectedRows && interceptArray.size == expectedRows) {
                "Invalid weight rows"
            }
            require(expectedRows.toLong() * vocabulary.size <= MAX_WEIGHT_CELLS) {
                "Model exceeds weight limit"
            }
            val weights = (0 until weightArray.size).map { row ->
                val values = weightArray[row] as? List<*> ?: error("Invalid weight row")
                require(values.size == vocabulary.size) { "Invalid weight width" }
                DoubleArray(values.size) { column -> values[column].number().also {
                    require(it.isFinite() && kotlin.math.abs(it) <= 1_000_000.0) {
                        "Invalid model weight"
                    }
                } }
            }
            val intercepts = DoubleArray(interceptArray.size) { index ->
                interceptArray[index].number().also {
                    require(it.isFinite() && kotlin.math.abs(it) <= 1_000_000.0) {
                        "Invalid model intercept"
                    }
                }
            }

            val thresholds = root.obj("thresholds")
            val confidenceThreshold = thresholds.number("confidence")
            val marginThreshold = thresholds.number("margin")
            require(confidenceThreshold in 0.0..1.0 && marginThreshold in 0.0..1.0) {
                "Invalid confidence thresholds"
            }

            return CategoryModel(labels, vocabulary, weights, intercepts, ngramMin, ngramMax,
                confidenceThreshold, marginThreshold)
        }

        private fun Map<*, *>.value(key: String): Any = get(key) ?: error("Missing $key")
        private fun Map<*, *>.int(key: String): Int = value(key) as? Int ?: error("Invalid $key")
        private fun Map<*, *>.obj(key: String): Map<*, *> = value(key) as? Map<*, *>
            ?: error("Invalid $key")
        private fun Map<*, *>.array(key: String): List<*> = value(key) as? List<*>
            ?: error("Invalid $key")
        private fun Map<*, *>.number(key: String): Double = value(key).number()
        private fun Any?.number(): Double = (this as? Number)?.toDouble()
            ?: error("Invalid number")

        private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("\\d+"), "#")
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
            .joinToString(" ")
    }
}

private class JsonParser(private val source: String) {
    private var position = 0

    fun parse(): Any? {
        val value = readValue()
        skipWhitespace()
        require(position == source.length) { "Trailing JSON content" }
        return value
    }

    private fun readValue(): Any? {
        skipWhitespace()
        require(position < source.length) { "Unexpected end of JSON" }
        return when (source[position]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            'n' -> readLiteral("null", null)
            '-', in '0'..'9' -> readNumber()
            else -> error("Invalid JSON value")
        }
    }

    private fun readObject(): Map<String, Any?> {
        position++
        skipWhitespace()
        val result = LinkedHashMap<String, Any?>()
        if (consume('}')) return result
        while (true) {
            skipWhitespace()
            require(position < source.length && source[position] == '"') { "Invalid object key" }
            val key = readString()
            skipWhitespace()
            require(consume(':')) { "Missing object separator" }
            require(!result.containsKey(key)) { "Duplicate object key" }
            result[key] = readValue()
            skipWhitespace()
            if (consume('}')) return result
            require(consume(',')) { "Missing object delimiter" }
        }
    }

    private fun readArray(): List<Any?> {
        position++
        skipWhitespace()
        val result = ArrayList<Any?>()
        if (consume(']')) return result
        while (true) {
            result.add(readValue())
            skipWhitespace()
            if (consume(']')) return result
            require(consume(',')) { "Missing array delimiter" }
        }
    }

    private fun readString(): String {
        require(consume('"')) { "Invalid string" }
        val result = StringBuilder()
        while (position < source.length) {
            val char = source[position++]
            when {
                char == '"' -> return result.toString()
                char == '\\' -> {
                    require(position < source.length) { "Invalid escape" }
                    when (val escaped = source[position++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(position + 4 <= source.length) { "Invalid unicode escape" }
                            result.append(source.substring(position, position + 4).toInt(16).toChar())
                            position += 4
                        }
                        else -> error("Invalid escape")
                    }
                }
                char.code < 0x20 -> error("Invalid control character")
                else -> result.append(char)
            }
        }
        error("Unterminated string")
    }

    private fun readNumber(): Number {
        val start = position
        if (source[position] == '-') position++
        require(position < source.length) { "Invalid number" }
        if (source[position] == '0') {
            position++
        } else {
            require(source[position] in '1'..'9') { "Invalid number" }
            while (position < source.length && source[position].isDigit()) position++
        }
        var integral = true
        if (position < source.length && source[position] == '.') {
            integral = false
            position++
            require(position < source.length && source[position].isDigit()) { "Invalid fraction" }
            while (position < source.length && source[position].isDigit()) position++
        }
        if (position < source.length && source[position] in "eE") {
            integral = false
            position++
            if (position < source.length && source[position] in "+-") position++
            require(position < source.length && source[position].isDigit()) { "Invalid exponent" }
            while (position < source.length && source[position].isDigit()) position++
        }
        val raw = source.substring(start, position)
        return if (integral) raw.toIntOrNull() ?: raw.toDouble() else raw.toDouble()
    }

    private fun <T> readLiteral(literal: String, value: T): T {
        require(source.startsWith(literal, position)) { "Invalid literal" }
        position += literal.length
        return value
    }

    private fun consume(expected: Char): Boolean {
        if (position < source.length && source[position] == expected) {
            position++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (position < source.length && source[position] in " \t\r\n") position++
    }
}