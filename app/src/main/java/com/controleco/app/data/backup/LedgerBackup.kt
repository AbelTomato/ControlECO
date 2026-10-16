package com.controleco.app.data.backup

import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.MerchantRuleEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import java.util.Base64 as JvmBase64

data class LedgerBackup(
    val formatVersion: Int,
    val exportedAt: Long,
    val accounts: List<AccountEntity>,
    val categories: List<CategoryEntity>,
    val transactions: List<TransactionEntity>,
    val merchantRules: List<MerchantRuleEntity>,
    val imageFiles: Map<String, String>,
)

object LedgerBackupCodec {
    const val CURRENT_FORMAT_VERSION = 1
    const val MAX_JSON_BYTES = 4 * 1024 * 1024
    const val MAX_STRING_LENGTH = 256 * 1024
    const val MAX_LIST_ITEMS = 100_000
    const val MAX_ATTACHMENT_BYTES = 16 * 1024 * 1024

    fun encode(backup: LedgerBackup): String {
        val errors = LedgerBackupValidator.validate(backup)
        require(errors.isEmpty()) { "Invalid backup fields: ${errors.joinToString()}" }
        val root = linkedMapOf<String, Any?>(
            "formatVersion" to backup.formatVersion,
            "exportedAt" to backup.exportedAt,
            "accounts" to backup.accounts.mapTo(mutableListOf<Any?>()) { account -> account.asBackupJson() },
            "categories" to backup.categories.mapTo(mutableListOf<Any?>()) { category -> category.asBackupJson() },
            "transactions" to backup.transactions.mapTo(mutableListOf<Any?>()) { transaction -> transaction.asBackupJson() },
            "merchantRules" to backup.merchantRules.mapTo(mutableListOf<Any?>()) { rule -> rule.asBackupJson() },
            "imageFiles" to backup.imageFiles,
        )
        return JsonWriter.write(root).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Backup is too large" }
        }
    }

    fun decode(json: String): LedgerBackup {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Backup is too large" }
        val root = JsonParser(json).parse() as? Map<*, *> ?: throw IllegalArgumentException("Invalid backup root")
        val version = root.requiredInt("formatVersion")
        require(version == CURRENT_FORMAT_VERSION) { "Unsupported backup format version" }
        val backup = LedgerBackup(
            formatVersion = version,
            exportedAt = root.requiredLong("exportedAt"),
            accounts = root.requiredArray("accounts").map { it.asObject().toAccount() },
            categories = root.requiredArray("categories").map { it.asObject().toCategory() },
            transactions = root.requiredArray("transactions").map { it.asObject().toTransaction() },
            merchantRules = root.requiredArray("merchantRules").map { it.asObject().toMerchantRule() },
            imageFiles = root.requiredObject("imageFiles").let { obj ->
                require(obj.size <= MAX_LIST_ITEMS) { "Too many image attachments" }
                obj.entries.associate { (key, value) ->
                    (key as? String ?: throw IllegalArgumentException("Invalid image name")) to
                        (value as? String ?: throw IllegalArgumentException("Invalid image attachment"))
                }
            },
        )
        val errors = LedgerBackupValidator.validate(backup)
        require(errors.isEmpty()) { "Invalid backup fields: ${errors.distinct().joinToString()}" }
        return backup
    }

    private fun AccountEntity.asBackupJson() = linkedMapOf<String, Any?>(
        "id" to id, "name" to name, "initialBalanceCents" to initialBalanceCents,
        "note" to note, "createdAt" to createdAt,
    )

    private fun CategoryEntity.asBackupJson() = linkedMapOf<String, Any?>(
        "id" to id, "name" to name, "type" to type.name, "sortOrder" to sortOrder, "icon" to icon,
    )

    private fun TransactionEntity.asBackupJson() = linkedMapOf<String, Any?>(
        "id" to id, "type" to type.name, "amountCents" to amountCents,
        "categoryId" to categoryId, "accountId" to accountId, "timestamp" to timestamp,
        "note" to note, "counterparty" to counterparty, "source" to source.name,
        "externalId" to externalId, "refundMark" to refundMark.name, "createdAt" to createdAt,
    )

    private fun MerchantRuleEntity.asBackupJson() = linkedMapOf<String, Any?>(
        "id" to id, "matchKey" to matchKey, "type" to type.name, "categoryId" to categoryId,
        "accountId" to accountId, "hits" to hits, "updatedAt" to updatedAt,
    )

    private fun Map<*, *>.toAccount() = AccountEntity(
        id = requiredLong("id"), name = requiredString("name"),
        initialBalanceCents = requiredLong("initialBalanceCents"), note = requiredString("note"),
        createdAt = requiredLong("createdAt"),
    )

    private fun Map<*, *>.toCategory() = CategoryEntity(
        id = requiredLong("id"), name = requiredString("name"), type = TxType.valueOf(requiredString("type")),
        sortOrder = requiredInt("sortOrder"), icon = requiredString("icon"),
    )

    private fun Map<*, *>.toTransaction() = TransactionEntity(
        id = requiredLong("id"), type = TxType.valueOf(requiredString("type")),
        amountCents = requiredLong("amountCents"), categoryId = requiredLong("categoryId"),
        accountId = requiredLong("accountId"), timestamp = requiredLong("timestamp"),
        note = requiredString("note"), counterparty = requiredString("counterparty"),
        source = TxSource.valueOf(requiredString("source")), externalId = optionalString("externalId"),
        refundMark = RefundMark.valueOf(requiredString("refundMark")), createdAt = requiredLong("createdAt"),
    )

    private fun Map<*, *>.toMerchantRule() = MerchantRuleEntity(
        id = requiredLong("id"), matchKey = requiredString("matchKey"),
        type = TxType.valueOf(requiredString("type")), categoryId = requiredLong("categoryId"),
        accountId = optionalLong("accountId"), hits = requiredInt("hits"), updatedAt = requiredLong("updatedAt"),
    )

    private fun Map<*, *>.requiredLong(key: String): Long = get(key).let {
        require(it is Number && it.toDouble().isFinite() && it.toDouble() == it.toLong().toDouble()) { "Invalid $key" }
        it.toLong()
    }

    private fun Map<*, *>.requiredInt(key: String): Int = requiredLong(key).also {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid $key" }
    }.toInt()

    private fun Map<*, *>.requiredString(key: String): String = get(key).let {
        require(it is String && it.length <= MAX_STRING_LENGTH) { "Invalid $key" }
        it
    }

    private fun Map<*, *>.optionalString(key: String): String? = get(key).let {
        if (it == null) null else (it as? String)?.also { value ->
            require(value.length <= MAX_STRING_LENGTH) { "Invalid $key" }
        } ?: throw IllegalArgumentException("Invalid $key")
    }

    private fun Map<*, *>.optionalLong(key: String): Long? = get(key).let {
        if (it == null) null else requiredLong(key)
    }

    private fun Map<*, *>.requiredArray(key: String): List<*> = get(key) as? List<*>
        ?: throw IllegalArgumentException("Invalid $key")

    private fun Map<*, *>.requiredObject(key: String): Map<*, *> = get(key) as? Map<*, *>
        ?: throw IllegalArgumentException("Invalid $key")

    private inline fun <T> List<*>.map(convert: (Any?) -> T): List<T> {
        require(size <= MAX_LIST_ITEMS) { "Too many records" }
        return indices.map { index ->
            convert(this[index])
        }
    }

    private fun Any?.asObject(): Map<*, *> = this as? Map<*, *> ?: throw IllegalArgumentException("Invalid record")
}

object LedgerBackupValidator {
    private val imageName = Regex("cat_icon_[A-Za-z0-9_-]+\\.jpg")

    fun validate(backup: LedgerBackup): List<String> {
        val errors = mutableListOf<String>()
        fun check(condition: Boolean, field: String) { if (!condition) errors += field }
        check(backup.formatVersion == LedgerBackupCodec.CURRENT_FORMAT_VERSION, "formatVersion")
        check(backup.exportedAt >= 0, "exportedAt")
        check(backup.accounts.size <= LedgerBackupCodec.MAX_LIST_ITEMS, "accounts")
        check(backup.categories.size <= LedgerBackupCodec.MAX_LIST_ITEMS, "categories")
        check(backup.transactions.size <= LedgerBackupCodec.MAX_LIST_ITEMS, "transactions")
        check(backup.merchantRules.size <= LedgerBackupCodec.MAX_LIST_ITEMS, "merchantRules")
        check(backup.accounts.map { it.id }.distinct().size == backup.accounts.size, "accounts.id")
        check(backup.categories.map { it.id }.distinct().size == backup.categories.size, "categories.id")
        check(backup.transactions.map { it.id }.distinct().size == backup.transactions.size, "transactions.id")
        check(backup.merchantRules.map { it.id }.distinct().size == backup.merchantRules.size, "merchantRules.id")
        val externalIds = backup.transactions.mapNotNull { it.externalId?.takeIf(String::isNotEmpty) }
        check(externalIds.distinct().size == externalIds.size, "transactions.externalId")
        val accountIds = backup.accounts.map { it.id }.toSet()
        val categoryIds = backup.categories.map { it.id }.toSet()
        backup.transactions.forEach {
            check(it.accountId in accountIds, "transactions.accountId")
            check(it.categoryId in categoryIds, "transactions.categoryId")
            check(it.amountCents >= 0, "transactions.amountCents")
            check(it.type in TxType.entries && it.source in TxSource.entries && it.refundMark in RefundMark.entries, "transactions.enum")
            check(stringsWithinLimit(it.type.name, it.note, it.counterparty, it.externalId.orEmpty()), "transactions.string")
        }
        backup.accounts.forEach { check(stringsWithinLimit(it.name, it.note), "accounts.string") }
        backup.categories.forEach {
            check(it.type in TxType.entries, "categories.type")
            check(stringsWithinLimit(it.name, it.icon), "categories.string")
        }
        val ruleKeys = backup.merchantRules.map { it.matchKey to it.type }
        check(ruleKeys.distinct().size == ruleKeys.size, "merchantRules.matchKey,type")
        backup.merchantRules.forEach {
            check(it.categoryId in categoryIds, "merchantRules.categoryId")
            check(it.accountId == null || it.accountId in accountIds, "merchantRules.accountId")
            check(it.hits > 0, "merchantRules.hits")
            check(stringsWithinLimit(it.matchKey), "merchantRules.matchKey")
        }
        val referencedImages = backup.categories.mapNotNull { icon ->
            if (!icon.icon.startsWith("IMAGE:")) null else icon.icon.removePrefix("IMAGE:")
        }
        check(referencedImages.all { imageName.matches(it) }, "categories.icon")
        check(referencedImages.distinct().size == referencedImages.size, "categories.icon")
        check(backup.imageFiles.keys.all(imageName::matches), "imageFiles.name")
        check(referencedImages.toSet() == backup.imageFiles.keys, "imageFiles.references")
        var totalBytes = 0L
        backup.imageFiles.forEach { (name, encoded) ->
            check(stringsWithinLimit(name, encoded), "imageFiles.string")
            if (encoded.length > LedgerBackupCodec.MAX_ATTACHMENT_BYTES * 4 / 3 + 4) {
                errors += "imageFiles.size"
                return@forEach
            }
            val bytes = try { JvmBase64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
                errors += "imageFiles.base64"
                byteArrayOf()
            }
            totalBytes += bytes.size
            check(totalBytes <= LedgerBackupCodec.MAX_ATTACHMENT_BYTES, "imageFiles.size")
            check(JvmBase64.getEncoder().encodeToString(bytes) == encoded, "imageFiles.base64")
        }
        return errors.distinct()
    }

    private fun stringsWithinLimit(vararg values: String): Boolean =
        values.all { it.length <= LedgerBackupCodec.MAX_STRING_LENGTH }
}

private object JsonWriter {
    fun write(value: Any?): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendString(value)
            is Number, is Boolean -> append(value.toString())
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendString(entry.key as? String ?: error("JSON object key must be a string"))
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            else -> error("Unsupported JSON value")
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}

private class JsonParser(private val input: String) {
    private var index = 0

    fun parse(): Any? {
        skipWhitespace()
        val result = value(0)
        skipWhitespace()
        require(index == input.length) { "Trailing JSON data" }
        return result
    }

    private fun value(depth: Int): Any? {
        require(depth <= 64) { "JSON nesting too deep" }
        skipWhitespace()
        require(index < input.length) { "Unexpected end of JSON" }
        return when (input[index]) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> numberValue()
            else -> throw IllegalArgumentException("Invalid JSON token")
        }
    }

    private fun objectValue(depth: Int): Map<String, Any?> {
        index++
        skipWhitespace()
        val result = linkedMapOf<String, Any?>()
        if (consume('}')) return result
        while (true) {
            skipWhitespace()
            require(index < input.length && input[index] == '"') { "Invalid JSON object key" }
            val key = stringValue()
            skipWhitespace()
            require(consume(':')) { "Missing JSON colon" }
            require(!result.containsKey(key)) { "Duplicate JSON key" }
            result[key] = value(depth)
            skipWhitespace()
            if (consume('}')) return result
            require(consume(',')) { "Invalid JSON object" }
        }
    }

    private fun arrayValue(depth: Int): List<Any?> {
        index++
        skipWhitespace()
        val result = mutableListOf<Any?>()
        if (consume(']')) return result
        while (true) {
            require(result.size < LedgerBackupCodec.MAX_LIST_ITEMS) { "Too many JSON items" }
            result += value(depth)
            skipWhitespace()
            if (consume(']')) return result
            require(consume(',')) { "Invalid JSON array" }
        }
    }

    private fun stringValue(): String {
        index++
        val result = StringBuilder()
        while (index < input.length) {
            val char = input[index++]
            when {
                char == '"' -> return result.toString().also {
                    require(it.length <= LedgerBackupCodec.MAX_STRING_LENGTH) { "JSON string too long" }
                }
                char == '\\' -> {
                    require(index < input.length) { "Invalid JSON escape" }
                    when (val escaped = input[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(index + 4 <= input.length) { "Invalid unicode escape" }
                            result.append(input.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("Invalid JSON escape")
                    }
                }
                char.code < 0x20 -> throw IllegalArgumentException("Invalid JSON string")
                else -> result.append(char)
            }
            require(result.length <= LedgerBackupCodec.MAX_STRING_LENGTH) { "JSON string too long" }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun numberValue(): Number {
        val start = index
        if (consume('-')) require(index < input.length) { "Invalid JSON number" }
        if (consume('0')) {
            require(index >= input.length || input[index] !in '0'..'9') { "Invalid JSON number" }
        } else {
            require(index < input.length && input[index] in '1'..'9') { "Invalid JSON number" }
            while (index < input.length && input[index] in '0'..'9') index++
        }
        require(index >= input.length || input[index] !in listOf('.', 'e', 'E')) { "Only integer JSON numbers are accepted" }
        return input.substring(start, index).toLongOrNull() ?: throw IllegalArgumentException("Invalid JSON integer")
    }

    private fun <T> literal(text: String, result: T): T {
        require(input.startsWith(text, index)) { "Invalid JSON literal" }
        index += text.length
        return result
    }

    private fun consume(char: Char): Boolean {
        if (index < input.length && input[index] == char) {
            index++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index] in listOf(' ', '\n', '\r', '\t')) index++
    }
}