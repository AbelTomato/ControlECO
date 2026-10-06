package com.accbook.app.ui.refund

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.TransactionRow
import com.accbook.app.ui.common.formatTimestamp
import com.accbook.app.util.Money

/**
 * 退款关联页：微信"退款到账通知"不带金额，无法自动入账——
 * 用户在此选择被退款的支出，生成退款收入（金额可改，支持部分退款）并把原支出标记已退款。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefundLinkScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    var rows by remember { mutableStateOf<List<TransactionRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var linking by remember { mutableStateOf<TransactionRow?>(null) }

    LaunchedEffect(Unit) {
        rows = vm.refundCandidates()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择被退款的账单") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "选择与这笔退款对应的支出记录：将生成一条退款收入（默认等于原支出金额，可修改以支持部分退款），" +
                    "并把原支出标记为「已退款」。仅显示近 60 天未退款的支出。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            when {
                loading -> {
                    Text("加载中…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
                }
                rows.isEmpty() -> {
                    Text(
                        "近 60 天没有可关联的支出记录",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                else -> {
                    LazyColumn {
                        items(rows, key = { it.id }) { row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { linking = row }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "-" + Money.formatYuan(row.amountCents),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    val desc = listOfNotNull(
                                        row.counterparty.ifBlank { null },
                                        row.note.ifBlank { null },
                                        "${row.categoryName} · ${row.accountName}",
                                    ).joinToString(" · ")
                                    Text(
                                        desc.ifBlank { " " },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    formatTimestamp(row.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    linking?.let { row ->
        LinkRefundDialog(
            row = row,
            onLink = { amountCents, onResult ->
                vm.linkRefund(row.id, amountCents) { error ->
                    onResult(error)
                    if (error == null) {
                        // 原支出已标记退款，从候选中移除
                        rows = rows.filter { it.id != row.id }
                    }
                }
            },
            onDismiss = { linking = null },
        )
    }
}

@Composable
private fun LinkRefundDialog(
    row: TransactionRow,
    onLink: (amountCents: Long, onResult: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var amountText by remember { mutableStateOf(Money.formatCents(row.amountCents)) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录退款收入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "原支出：${Money.formatYuan(row.amountCents)} · ${row.categoryName} · " +
                        "${row.accountName} · ${formatTimestamp(row.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    row.counterparty.ifBlank { row.note.ifBlank { "无备注" } },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("退款金额（部分退款可改小）") },
                    singleLine = true,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    val cents = Money.parseCents(amountText)
                    if (cents == null || cents <= 0) {
                        error = "请输入有效金额"
                        return@TextButton
                    }
                    if (cents > row.amountCents) {
                        error = "退款金额不能超过原支出"
                        return@TextButton
                    }
                    submitting = true
                    onLink(cents) { result ->
                        submitting = false
                        if (result == null) {
                            onDismiss()
                        } else {
                            error = result
                        }
                    }
                },
            ) { Text(if (submitting) "处理中…" else "确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
