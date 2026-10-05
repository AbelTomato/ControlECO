package com.accbook.app.ui.pending

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.capture.NotificationRules
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.AccountEntity
import com.accbook.app.data.db.CategoryEntity
import com.accbook.app.data.db.PendingTransactionEntity
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.DropdownSelector
import com.accbook.app.ui.common.formatTimestamp
import com.accbook.app.util.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val pending by vm.pendingList.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    var confirming by remember { mutableStateOf<PendingTransactionEntity?>(null) }
    var confirmAllAsk by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("待确认账单") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (pending.isNotEmpty()) {
                        TextButton(onClick = { confirmAllAsk = true }) { Text("全部确认") }
                    }
                },
            )
        },
    ) { padding ->
        if (pending.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无待确认账单\n开启通知监听后，收款/动账通知会出现在这里",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(pending, key = { it.id }) { p ->
                    PendingRow(
                        item = p,
                        onConfirm = { confirming = p },
                        onDiscard = { vm.discardPending(p.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    confirming?.let { p ->
        ConfirmPendingDialog(
            item = p,
            accounts = accounts,
            categories = categories,
            defaultCategoryId = vm.guessCategoryId(p.type),
            defaultAccountId = vm.guessAccountId(p.sourcePackage),
            onConfirm = { categoryId, accountId ->
                vm.confirmPending(p, categoryId, accountId)
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }

    if (confirmAllAsk) {
        AlertDialog(
            onDismissRequest = { confirmAllAsk = false },
            title = { Text("全部确认") },
            text = {
                Text("将把 ${pending.size} 条待确认按默认分类（其他）与推测账户入账，之后可在流水中修改。")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.confirmAllPending()
                    confirmAllAsk = false
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { confirmAllAsk = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PendingRow(item: PendingTransactionEntity, onConfirm: () -> Unit, onDiscard: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onConfirm)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (item.type == TxType.EXPENSE) "-" else "+") + Money.formatYuan(item.amountCents),
                style = MaterialTheme.typography.titleSmall,
                color = if (item.type == TxType.INCOME) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${NotificationRules.sourceLabel(item.sourcePackage)} · ${item.ruleName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (item.note.isNotBlank()) {
                Text(
                    item.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                formatTimestamp(item.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        TextButton(onClick = onConfirm) { Text("确认") }
        TextButton(onClick = onDiscard) { Text("丢弃") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmPendingDialog(
    item: PendingTransactionEntity,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    defaultCategoryId: Long?,
    defaultAccountId: Long?,
    onConfirm: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var categoryId by remember(item.id) { mutableStateOf(defaultCategoryId) }
    var accountId by remember(item.id) { mutableStateOf(defaultAccountId) }
    var error by remember { mutableStateOf<String?>(null) }
    val catsOfType = categories.filter { it.type == item.type }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((if (item.type == TxType.EXPENSE) "支出 " else "收入 ") + Money.formatYuan(item.amountCents)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${NotificationRules.sourceLabel(item.sourcePackage)} · ${item.ruleName} · ${formatTimestamp(item.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (item.note.isNotBlank()) {
                    Text(
                        item.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("分类", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    catsOfType.forEach { c ->
                        FilterChip(
                            selected = categoryId == c.id,
                            onClick = { categoryId = c.id },
                            label = { Text(c.name) },
                        )
                    }
                }
                DropdownSelector(
                    label = "账户",
                    options = accounts.map { it.id to it.name },
                    selectedId = accountId,
                    onSelect = { accountId = it },
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (categoryId == null) {
                    error = "请选择分类"
                    return@TextButton
                }
                if (accountId == null) {
                    error = "请选择账户"
                    return@TextButton
                }
                onConfirm(categoryId!!, accountId!!)
            }) { Text("入账") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
