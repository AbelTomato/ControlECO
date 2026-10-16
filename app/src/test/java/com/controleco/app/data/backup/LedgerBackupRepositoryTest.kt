package com.controleco.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.controleco.app.data.db.AccBookDatabase
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CaptureChannel
import com.controleco.app.data.db.CaptureTraceEntity
import com.controleco.app.data.db.CapturedNotificationEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.MerchantRuleEntity
import com.controleco.app.data.db.PendingStatus
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TraceOutcome
import com.controleco.app.data.db.TransactionEntity
import com.controleco.app.data.db.TxSource
import com.controleco.app.data.db.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LedgerBackupRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: AccBookDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AccBookDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

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

    @Test
    fun `restore unique transaction conflict rolls back ledger and leaves capture tables unchanged`() = runBlocking {
        seedCurrentData()
        val oldAccount = db.accountDao().getAll().single()
        val oldCategory = db.categoryDao().getAll().single()
        val oldTransaction = transaction(id = 3, externalId = "old")
        db.transactionDao().insert(oldTransaction)
        val backup = validBackup().copy(
            accounts = listOf(oldAccount.copy(name = "Replaced")),
            categories = listOf(oldCategory.copy(name = "Replaced")),
            transactions = listOf(
                transaction(id = 30, externalId = "duplicate"),
                transaction(id = 31, externalId = "duplicate"),
            ),
        )
        val pendingBefore = tableSnapshot("pending_transactions", "dedupKey")
        val capturedBefore = tableSnapshot("captured_notifications", "packageName")
        val tracesBefore = tableSnapshot("capture_traces", "dedupKey")

        assertTrue(runCatching { LedgerBackupRepository.restoreBackup(context, db, backup) }.isFailure)

        assertEquals(listOf(oldAccount), db.accountDao().getAll())
        assertEquals(listOf(oldCategory), db.categoryDao().getAll())
        assertEquals(listOf(oldTransaction), db.transactionDao().getAllForBackup())
        assertEquals(pendingBefore, tableSnapshot("pending_transactions", "dedupKey"))
        assertEquals(capturedBefore, tableSnapshot("captured_notifications", "packageName"))
        assertEquals(tracesBefore, tableSnapshot("capture_traces", "dedupKey"))
    }

    @Test
    fun `successful restore replaces only core ledger tables`() = runBlocking {
        seedCurrentData()
        val backup = validBackup().copy(
            accounts = listOf(AccountEntity(id = 41, name = "Backup account")),
            categories = listOf(CategoryEntity(id = 42, name = "Backup category", type = TxType.EXPENSE)),
            transactions = listOf(transaction(id = 43, accountId = 41, categoryId = 42, externalId = "backup")),
        )
        val pendingBefore = tableSnapshot("pending_transactions", "dedupKey")
        val capturedBefore = tableSnapshot("captured_notifications", "packageName")
        val tracesBefore = tableSnapshot("capture_traces", "dedupKey")

        LedgerBackupRepository.restoreBackup(context, db, backup)

        assertEquals(backup.accounts, db.accountDao().getAll())
        assertEquals(backup.categories, db.categoryDao().getAll())
        assertEquals(backup.transactions, db.transactionDao().getAllForBackup())
        assertEquals(pendingBefore, tableSnapshot("pending_transactions", "dedupKey"))
        assertEquals(capturedBefore, tableSnapshot("captured_notifications", "packageName"))
        assertEquals(tracesBefore, tableSnapshot("capture_traces", "dedupKey"))
    }

    private suspend fun seedCurrentData() {
        val account = AccountEntity(id = 1, name = "Existing")
        val category = CategoryEntity(id = 2, name = "Existing category", type = TxType.EXPENSE)
        db.accountDao().insert(account)
        db.categoryDao().insert(category)
        db.pendingDao().insert(
            PendingTransactionEntity(type = TxType.EXPENSE, amountCents = 10, timestamp = 10,
                sourcePackage = "pkg", ruleName = "rule", dedupKey = "pending", status = PendingStatus.PENDING),
        )
        db.captureDao().insert(
            CapturedNotificationEntity(packageName = "pkg", postTime = 11),
        )
        db.traceDao().insert(
            CaptureTraceEntity(channel = CaptureChannel.NOTIFICATION, packageName = "pkg", capturedAt = 12,
                dedupKey = "trace", outcome = TraceOutcome.PENDING),
        )
    }

    private fun transaction(
        id: Long,
        accountId: Long = 1,
        categoryId: Long = 2,
        externalId: String,
    ) = TransactionEntity(
        id = id, type = TxType.EXPENSE, amountCents = 100, categoryId = categoryId,
        accountId = accountId, timestamp = 100, externalId = externalId,
    )

    private fun validBackup() = LedgerBackup(
        formatVersion = 1, exportedAt = 1,
        accounts = listOf(AccountEntity(id = 1, name = "Backup")),
        categories = listOf(CategoryEntity(id = 2, name = "Backup", type = TxType.EXPENSE)),
        transactions = listOf(transaction(id = 3, externalId = "backup")),
        merchantRules = emptyList(), imageFiles = emptyMap(),
    )

    private fun tableSnapshot(table: String, column: String): List<String> =
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT $column FROM $table ORDER BY id")).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
}