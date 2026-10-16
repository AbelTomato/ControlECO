package com.controleco.app.data.backup

import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.MerchantRuleEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerBackupRepositoryTest {
    @Test
    fun `backup DTO retains explicit ids refund icon source and merchant rules`() {
        val snapshot = LedgerBackup(
            formatVersion = 1,
            exportedAt = 123,
            accounts = listOf(AccountEntity(id = 7, name = "Cash", initialBalanceCents = 1200, note = "n", createdAt = 11)),
            categories = listOf(CategoryEntity(id = 8, name = "Food", type = TxType.EXPENSE, sortOrder = 3, icon = "IMAGE:cat_icon_1.jpg")),
            transactions = listOf(
                TransactionEntity(
                    id = 9, type = TxType.EXPENSE, amountCents = 450, categoryId = 8, accountId = 7,
                    timestamp = 99, source = TxSource.WECHAT, externalId = "bill", refundMark = RefundMark.REFUNDED_EXPENSE,
                ),
            ),
            merchantRules = listOf(MerchantRuleEntity(id = 10, matchKey = "shop", type = TxType.EXPENSE, categoryId = 8, accountId = 7)),
            imageFiles = mapOf("cat_icon_1.jpg" to "AQ=="),
        )

        assertEquals(snapshot, LedgerBackupCodec.decode(LedgerBackupCodec.encode(snapshot)))
        assertTrue(LedgerBackupValidator.validate(snapshot).isEmpty())
    }

    @Test
    fun `invalid references are rejected before repository database operations`() {
        val valid = LedgerBackup(
            formatVersion = 1, exportedAt = 1,
            accounts = listOf(AccountEntity(id = 1, name = "Cash")),
            categories = listOf(CategoryEntity(id = 2, name = "Food", type = TxType.EXPENSE)),
            transactions = listOf(TransactionEntity(id = 3, type = TxType.EXPENSE, amountCents = 1, categoryId = 2, accountId = 1, timestamp = 1)),
            merchantRules = emptyList(), imageFiles = emptyMap(),
        )

        assertTrue(LedgerBackupValidator.validate(valid).isEmpty())
        assertTrue(LedgerBackupValidator.validate(valid.copy(transactions = listOf(valid.transactions.single().copy(accountId = 99)))).isNotEmpty())
    }
}