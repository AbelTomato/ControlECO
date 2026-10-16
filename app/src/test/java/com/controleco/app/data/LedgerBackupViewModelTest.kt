package com.controleco.app.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.controleco.app.data.backup.LedgerBackup
import com.controleco.app.data.backup.LedgerBackupCodec
import com.controleco.app.data.backup.LedgerBackupController
import com.controleco.app.data.backup.LedgerBackupOperation
import com.controleco.app.data.backup.LedgerBackupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LedgerBackupViewModelTest {
    private lateinit var scope: CoroutineScope
    private lateinit var files: FakeBackupFiles
    private lateinit var repository: FakeBackupRepository
    private lateinit var controller: LedgerBackupController

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        files = FakeBackupFiles()
        repository = FakeBackupRepository()
        controller = LedgerBackupController(
            ApplicationProvider.getApplicationContext<Context>(), repository, files, scope, Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `cancel clears pending backup without restoring`() {
        files.input = LedgerBackupCodec.encode(backup()).toByteArray()
        controller.read(Uri.parse("content://backup/file"))
        assertTrue(controller.state.value.pendingRestore != null)

        controller.cancelRestore()

        assertEquals(0, repository.restoreCalls)
        assertNull(controller.state.value.pendingRestore)
        assertEquals(LedgerBackupOperation.IDLE, controller.state.value.operation)
    }

    @Test
    fun `invalid file reports error and never success`() {
        files.input = "not a backup".toByteArray()

        controller.read(Uri.parse("content://backup/file"))

        val state = controller.state.value
        assertEquals(LedgerBackupOperation.ERROR, state.operation)
        assertFalse(state.message.orEmpty().contains("not a backup"))
        assertNull(state.pendingRestore)
        assertEquals(0, repository.restoreCalls)
    }

    @Test
    fun `restore starts only after explicit confirmation`() {
        files.input = LedgerBackupCodec.encode(backup()).toByteArray()
        controller.read(Uri.parse("content://backup/file"))
        assertEquals(0, repository.restoreCalls)

        controller.confirmRestore()

        assertEquals(1, repository.restoreCalls)
        assertEquals(LedgerBackupOperation.SUCCESS, controller.state.value.operation)
    }

    @Test
    fun `export error is visible without ledger details`() {
        files.outputFailure = IllegalStateException("private transaction counterparty")

        controller.export(Uri.parse("content://backup/file"))

        val state = controller.state.value
        assertEquals(LedgerBackupOperation.ERROR, state.operation)
        assertTrue(state.message.orEmpty().contains("导出失败"))
        assertFalse(state.message.orEmpty().contains("private transaction counterparty"))
        assertFalse(state.message.orEmpty().contains("counterparty"))
    }

    private fun backup() = LedgerBackup(
        formatVersion = 1, exportedAt = 1,
        accounts = emptyList(), categories = emptyList(), transactions = emptyList(),
        merchantRules = emptyList(), imageFiles = emptyMap(),
    )

    private class FakeBackupRepository : LedgerBackupRepository.Access {
        var restoreCalls = 0

        override suspend fun createBackup(context: Context, exportedAt: Long): LedgerBackup = LedgerBackup(
            formatVersion = 1, exportedAt = exportedAt,
            accounts = emptyList(), categories = emptyList(), transactions = emptyList(),
            merchantRules = emptyList(), imageFiles = emptyMap(),
        )

        override suspend fun restoreBackup(context: Context, backup: LedgerBackup) {
            restoreCalls++
        }
    }

    private class FakeBackupFiles : LedgerBackupController.Files {
        var input = ByteArray(0)
        var outputFailure: Throwable? = null

        override fun openInput(uri: Uri): InputStream = ByteArrayInputStream(input)

        override fun openOutput(uri: Uri): OutputStream {
            outputFailure?.let { throw it }
            return ByteArrayOutputStream()
        }
    }
}