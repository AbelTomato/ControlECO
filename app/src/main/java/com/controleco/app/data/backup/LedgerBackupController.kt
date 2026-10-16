package com.controleco.app.data.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

enum class LedgerBackupOperation { IDLE, PROCESSING, SUCCESS, ERROR }

data class LedgerBackupRestoreState(
    val operation: LedgerBackupOperation = LedgerBackupOperation.IDLE,
    val message: String? = null,
    val pendingRestore: LedgerBackup? = null,
)

class LedgerBackupController(
    private val context: Context,
    private val repository: LedgerBackupRepository.Access,
    private val files: Files,
    private val scope: CoroutineScope,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    interface Files {
        fun openInput(uri: Uri): InputStream?
        fun openOutput(uri: Uri): OutputStream?
    }

    private val _state = MutableStateFlow(LedgerBackupRestoreState())
    val state: StateFlow<LedgerBackupRestoreState> = _state.asStateFlow()

    fun clear() {
        _state.value = LedgerBackupRestoreState()
    }

    fun export(uri: Uri?) {
        if (uri == null || _state.value.operation == LedgerBackupOperation.PROCESSING) return
        scope.launch {
            _state.value = LedgerBackupRestoreState(operation = LedgerBackupOperation.PROCESSING)
            try {
                withContext(ioDispatcher) {
                    val json = LedgerBackupCodec.encode(repository.createBackup(context, System.currentTimeMillis()))
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    require(bytes.isNotEmpty() && bytes.size <= LedgerBackupCodec.MAX_JSON_BYTES)
                    val output = files.openOutput(uri) ?: error("Unable to open destination")
                    output.use { it.write(bytes) }
                }
                _state.value = LedgerBackupRestoreState(LedgerBackupOperation.SUCCESS, "备份导出成功")
            } catch (_: Exception) {
                _state.value = LedgerBackupRestoreState(LedgerBackupOperation.ERROR, "导出失败，请检查目标文件后重试")
            }
        }
    }

    fun read(uri: Uri?) {
        if (uri == null || _state.value.operation == LedgerBackupOperation.PROCESSING) return
        scope.launch {
            _state.value = LedgerBackupRestoreState(operation = LedgerBackupOperation.PROCESSING)
            try {
                val backup = withContext(ioDispatcher) {
                    val input = files.openInput(uri) ?: error("Unable to open backup")
                    val bytes = input.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= LedgerBackupCodec.MAX_JSON_BYTES) { "Backup is too large" }
                            output.write(buffer, 0, count)
                        }
                        require(total > 0) { "Backup is empty" }
                        output.toByteArray()
                    }
                    LedgerBackupCodec.decode(bytes.toString(Charsets.UTF_8))
                }
                _state.value = LedgerBackupRestoreState(pendingRestore = backup)
            } catch (_: Exception) {
                _state.value = LedgerBackupRestoreState(LedgerBackupOperation.ERROR, "备份读取或校验失败，请选择有效的账本备份文件")
            }
        }
    }

    fun confirmRestore() {
        val backup = _state.value.pendingRestore ?: return
        if (_state.value.operation == LedgerBackupOperation.PROCESSING) return
        scope.launch {
            _state.value = LedgerBackupRestoreState(LedgerBackupOperation.PROCESSING, pendingRestore = backup)
            try {
                withContext(ioDispatcher) { repository.restoreBackup(context, backup) }
                _state.value = LedgerBackupRestoreState(LedgerBackupOperation.SUCCESS, "账本恢复成功")
            } catch (_: Exception) {
                _state.value = LedgerBackupRestoreState(LedgerBackupOperation.ERROR, "恢复失败，当前账本未能完成替换")
            }
        }
    }

    fun cancelRestore() {
        if (_state.value.operation == LedgerBackupOperation.PROCESSING) return
        clear()
    }
}