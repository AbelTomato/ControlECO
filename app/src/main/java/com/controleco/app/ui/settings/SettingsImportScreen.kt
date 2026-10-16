package com.controleco.app.ui.settings

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.backup.LedgerBackupOperation
import com.controleco.app.ui.common.InfoRow
import com.controleco.app.ui.common.PaneCard
import com.controleco.app.ui.common.SectionTitle
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SwitchRow

/**
 * 账单导入（个人页「账本」→ 账单导入）：导入入口 + 微信/支付宝导出账单的路径教程 + 退款记录显示开关。
 */
@Composable
fun SettingsImportScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val backupState by vm.backupRestoreState.collectAsStateWithLifecycle()
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) vm.exportBackup(uri) }
    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.readBackup(uri) }

    SettingsPage(title = "账单导入", onBack = onBack) {
        SectionTitle("导入")
        PaneCard {
            InfoRow(title = "选择账单文件", onClick = onImport)
        }

        SectionTitle("本地账本备份")
        PaneCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "备份包含账户、分类、交易、商户学习规则及自定义分类图片；不包含待确认条目、捕获记录、设置和月预算。文件含财务隐私，请妥善保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { exportPicker.launch("controleco-ledger-backup-v1.json") },
                    enabled = backupState.operation != LedgerBackupOperation.PROCESSING,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("导出备份") }
                OutlinedButton(
                    onClick = { restorePicker.launch(arrayOf("application/json", "application/octet-stream")) },
                    enabled = backupState.operation != LedgerBackupOperation.PROCESSING,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("恢复备份") }
                if (backupState.operation == LedgerBackupOperation.PROCESSING) {
                    CircularProgressIndicator()
                }
                backupState.message?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionTitle("导出账单（微信 / 支付宝）")
        PaneCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                TutorialStep(
                    "1. 微信",
                    "我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单，用途选「用于个人对账」，xlsx 会发送到邮箱。",
                )
                Spacer(Modifier.height(10.dp))
                TutorialStep(
                    "2. 支付宝",
                    "我的 → 账单 → 右上角「…」 → 开具交易流水证明，用途选「用于个人对账」，CSV 会发送到邮箱。",
                )
            }
        }

        SectionTitle("显示")
        PaneCard {
            RefundDisplaySetting(vm)
        }
    }

    backupState.pendingRestore?.let { backup ->
        val timestamp = remember(backup.exportedAt) {
            DateFormat.getLongDateFormat(context).format(backup.exportedAt) + " " +
                DateFormat.getTimeFormat(context).format(backup.exportedAt)
        }
        AlertDialog(
            onDismissRequest = { vm.cancelRestore() },
            title = { Text("确认恢复账本？") },
            text = {
                Column {
                    Text("备份时间：$timestamp")
                    Text("账户 ${backup.accounts.size} 项 · 分类 ${backup.categories.size} 项")
                    Text("交易 ${backup.transactions.size} 项 · 商户规则 ${backup.merchantRules.size} 项")
                    Spacer(Modifier.height(8.dp))
                    Text("将整体替换当前账户、分类、交易和商户学习规则。")
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.confirmRestore() },
                    enabled = backupState.operation != LedgerBackupOperation.PROCESSING,
                ) { Text("确认替换") }
            },
            dismissButton = {
                OutlinedButton(onClick = { vm.cancelRestore() }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TutorialStep(title: String, detail: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun RefundDisplaySetting(vm: LedgerViewModel) {
    val showRefunds by vm.showRefunds.collectAsStateWithLifecycle()
    SwitchRow(
        title = "显示退款记录",
        checked = showRefunds,
        onCheckedChange = { vm.setShowRefunds(it) },
    )
}
