package com.controlexo.app.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controlexo.app.data.LedgerViewModel
import com.controlexo.app.data.db.AccountEntity
import com.controlexo.app.util.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val balances by vm.accountBalances.collectAsStateWithLifecycle(initialValue = emptyList())

    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账户管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新增账户")
            }
        },
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无账户，点右下角 + 新增", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(accounts, key = { it.id }) { a ->
                    val bal = balances.firstOrNull { it.accountId == a.id }
                    val current = (bal?.initialBalanceCents ?: a.initialBalanceCents) + (bal?.signedSumCents ?: 0L)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { editing = a; showEditor = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.bodyLarge)
                            if (a.note.isNotBlank()) {
                                Text(a.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(Money.formatYuan(current), style = MaterialTheme.typography.titleSmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showEditor) {
        AccountEditorDialog(
            initial = editing,
            onSave = { id, name, cents, note ->
                vm.saveAccount(id, name, cents, note) { err -> if (err == null) showEditor = false }
            },
            onDelete = editing?.let { entity ->
                {
                    vm.deleteAccount(entity.id) { err -> if (err == null) showEditor = false }
                }
            },
            onClose = { showEditor = false },
        )
    }
}

@Composable
private fun AccountEditorDialog(
    initial: AccountEntity?,
    onSave: (Long, String, Long, String) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var balanceText by remember {
        mutableStateOf(initial?.let { Money.formatCents(it.initialBalanceCents) } ?: "")
    }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "新增账户" else "编辑账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账户名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text("初始金额（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除该账户", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) {
                    error = "请输入名称"
                    return@TextButton
                }
                val cents = if (balanceText.isBlank()) 0L else Money.parseCents(balanceText)
                if (cents == null) {
                    error = "初始金额格式不正确"
                    return@TextButton
                }
                onSave(initial?.id ?: 0L, trimmed, cents, note.trim())
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}
