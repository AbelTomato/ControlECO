package com.controleco.app.data.backup

import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.MerchantRuleEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerBackupTest {
    @Test
    fun `v1 round trip preserves all ledger fields and image attachment`() {
        val backup = validBackup()

        val decoded = LedgerBackupCodec.decode(LedgerBackupCodec.encode(backup))

        assertEquals(backup, decoded)
        assertTrue(LedgerBackupValidator.validate(decoded).isEmpty())
    }

    @Test
    fun `encode rejects invalid backup`() {
        val invalid = validBackup().copy(transactions = listOf(validBackup().transactions.first().copy(categoryId = 404)))

        assertTrue(runCatching { LedgerBackupCodec.encode(invalid) }.isFailure)
    }

    @Test
    fun `decode rejects unknown format version`() {
        val json = LedgerBackupCodec.encode(validBackup()).replace("\"formatVersion\":1", "\"formatVersion\":99")

        assertDecodeRejected(json)
    }

    @Test
    fun `decode rejects missing required field`() {
        val json = LedgerBackupCodec.encode(validBackup()).replace(Regex("\\\"exportedAt\\\":\\d+,?"), "")

        assertDecodeRejected(json)
    }

    @Test
    fun `decode rejects invalid enum`() {
        val json = LedgerBackupCodec.encode(validBackup()).replace("\"source\":\"WECHAT\"", "\"source\":\"ALIEN\"")

        assertDecodeRejected(json)
    }

    @Test
    fun `validator rejects duplicate primary and external ids`() {
        val backup = validBackup()
        val duplicateAccount = backup.copy(accounts = backup.accounts + backup.accounts.first())
        val duplicateCategory = backup.copy(categories = backup.categories + backup.categories.first())
        val duplicateTransaction = backup.copy(transactions = backup.transactions + backup.transactions.first())
        val duplicateExternal = backup.copy(
            transactions = backup.transactions + backup.transactions.first().copy(id = 21, externalId = "bill-1"),
        )

        assertInvalid(duplicateAccount)
        assertInvalid(duplicateCategory)
        assertInvalid(duplicateTransaction)
        assertInvalid(duplicateExternal)
    }

    @Test
    fun `validator allows repeated empty external ids`() {
        val backup = validBackup().copy(transactions = listOf(
            validBackup().transactions.last().copy(externalId = ""),
            validBackup().transactions.last().copy(id = 21, externalId = ""),
        ))

        assertTrue(LedgerBackupValidator.validate(backup).isEmpty())
    }

    @Test
    fun `validator rejects missing transaction and merchant references`() {
        val backup = validBackup()

        assertInvalid(backup.copy(transactions = listOf(backup.transactions.first().copy(accountId = 404))))
        assertInvalid(backup.copy(transactions = listOf(backup.transactions.first().copy(categoryId = 404))))
        assertInvalid(backup.copy(merchantRules = listOf(backup.merchantRules.first().copy(categoryId = 404))))
        assertInvalid(backup.copy(merchantRules = listOf(backup.merchantRules.first().copy(accountId = 404))))
    }

    @Test
    fun `validator rejects duplicate merchant rule key and type`() {
        val backup = validBackup()

        assertInvalid(backup.copy(merchantRules = backup.merchantRules + backup.merchantRules.first().copy(id = 31)))
    }

    @Test
    fun `validator rejects unsafe image paths and missing or unreferenced attachments`() {
        val backup = validBackup()
        val icon = backup.categories.first()

        assertInvalid(backup.copy(categories = listOf(icon.copy(icon = "IMAGE:../cat_icon_1.jpg"))))
        assertInvalid(backup.copy(imageFiles = emptyMap()))
        assertInvalid(backup.copy(imageFiles = backup.imageFiles + ("cat_icon_unused.jpg" to "aGVsbG8=")))
        assertInvalid(backup.copy(imageFiles = mapOf("../cat_icon_1.jpg" to "aGVsbG8=")))
    }

    @Test
    fun `validator rejects malformed base64`() {
        assertInvalid(validBackup().copy(imageFiles = mapOf("cat_icon_1.jpg" to "%%%")))
    }

    @Test
    fun `validator rejects oversized decoded attachments`() {
        val large = ByteArray(LedgerBackupCodec.MAX_ATTACHMENT_BYTES + 1).let {
            java.util.Base64.getEncoder().encodeToString(it)
        }
        assertInvalid(validBackup().copy(imageFiles = mapOf("cat_icon_1.jpg" to large)))
    }

    @Test
    fun `decode rejects oversized input`() {
        assertDecodeRejected(" ".repeat(LedgerBackupCodec.MAX_JSON_BYTES + 1))
    }

    private fun assertDecodeRejected(json: String) {
        val rejected = runCatching { LedgerBackupCodec.decode(json) }.isFailure
        assertTrue("Expected malformed backup to be rejected", rejected)
    }

    private fun assertInvalid(backup: LedgerBackup) {
        val errors = LedgerBackupValidator.validate(backup)
        assertFalse("Expected validation errors", errors.isEmpty())
        assertTrue(errors.all { it.isNotBlank() && !it.contains("bill-1") && !it.contains("Lunch") })
    }

    private fun validBackup() = LedgerBackup(
        formatVersion = LedgerBackupCodec.CURRENT_FORMAT_VERSION,
        exportedAt = 1_735_689_600_000,
        accounts = listOf(AccountEntity(id = 1, name = "Daily", initialBalanceCents = 12345, note = "cash", createdAt = 10)),
        categories = listOf(CategoryEntity(id = 2, name = "Lunch", type = TxType.EXPENSE, sortOrder = 3, icon = "IMAGE:cat_icon_1.jpg")),
        transactions = listOf(
            TransactionEntity(
                id = 3,
                type = TxType.INCOME,
                amountCents = 2500,
                categoryId = 2,
                accountId = 1,
                timestamp = 20,
                note = "refund",
                counterparty = "Cafe",
                source = TxSource.WECHAT,
                externalId = "bill-1",
                refundMark = RefundMark.REFUND_INCOME,
                createdAt = 30,
            ),
            TransactionEntity(
                id = 4,
                type = TxType.EXPENSE,
                amountCents = 100,
                categoryId = 2,
                accountId = 1,
                timestamp = 21,
                source = TxSource.MANUAL,
                externalId = "",
            ),
        ),
        merchantRules = listOf(MerchantRuleEntity(id = 5, matchKey = "cafe", type = TxType.EXPENSE, categoryId = 2, accountId = 1, hits = 2, updatedAt = 40)),
        imageFiles = mapOf("cat_icon_1.jpg" to "aGVsbG8="),
    )
}