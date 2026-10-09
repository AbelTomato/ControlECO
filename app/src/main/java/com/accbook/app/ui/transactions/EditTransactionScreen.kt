package com.accbook.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.TransactionEntity
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.CategoryLogo
import com.accbook.app.ui.common.DatePickerButton
import com.accbook.app.ui.common.DropdownSelector
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 记一笔/编辑（账单详情）：金额为第一视觉主体，信息结构保持标准金融 App 顺序——
 * 金额 → 类型/分类 → 账户 → 日期 → 备注；编辑态提供删除（操作效率优先）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditTransactionScreen(vm: LedgerViewModel, txId: Long, onBack: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    var existing by remember { mutableStateOf<TransactionEntity?>(null) }
    LaunchedEffect(txId) {
        if (txId > 0) existing = vm.transactionById(txId)
    }

    var type by remember { mutableStateOf(TxType.EXPENSE) }
    var amountText by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    // 编辑时保留原记录的时分（改日期不该把时间抹成 00:00）
    var timeOfDay by remember { mutableStateOf(java.time.LocalTime.now()) }
    var counterparty by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 编辑模式：加载完成后回填表单（仅一次）
    LaunchedEffect(existing) {
        existing?.let { t ->
            type = t.type
            amountText = Money.formatCents(t.amountCents)
            categoryId = t.categoryId
            accountId = t.accountId
            val local = Instant.ofEpochMilli(t.timestamp).atZone(ZoneId.systemDefault())
            date = local.toLocalDate()
            timeOfDay = local.toLocalTime()
            counterparty = t.counterparty
            note = t.note
        }
    }

    // 默认选中：第一个账户 + 当前类型的第一个分类
    LaunchedEffect(accounts, categories, type) {
        if (accountId == null) accountId = accounts.firstOrNull()?.id
        if (categories.firstOrNull { it.id == categoryId }?.type != type) {
            categoryId = categories.firstOrNull { it.type == type }?.id
        }
    }

    val catsOfType = categories.filter { it.type == type }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (txId > 0) "编辑记录" else "记一笔") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (txId > 0) {
                        TextButton(onClick = { showDeleteConfirm = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 金额主体
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                prefix = { Text("¥", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) },
                textStyle = MaterialTheme.typography.headlineLarge.copy(textAlign = TextAlign.Start),
                label = { Text("金额（元）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                FilterChip(
                    selected = type == TxType.EXPENSE,
                    onClick = { type = TxType.EXPENSE },
                    label = { Text("支出") },
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
                FilterChip(
                    selected = type == TxType.INCOME,
                    onClick = { type = TxType.INCOME },
                    label = { Text("收入") },
                )
            }

            Column {
                Text(
                    "分类",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    catsOfType.forEach { c ->
                        FilterChip(
                            selected = categoryId == c.id,
                            onClick = { categoryId = c.id },
                            leadingIcon = { CategoryLogo(categoryName = c.name, icon = c.icon, size = 22.dp) },
                            label = { Text(c.name) },
                        )
                    }
                }
            }

            DropdownSelector(
                label = "账户",
                options = accounts.map { it.id to it.name },
                selectedId = accountId,
                onSelect = { accountId = it },
            )

            DatePickerButton(date = date, onPick = { date = it })

            OutlinedTextField(
                value = counterparty,
                onValueChange = { counterparty = it },
                label = { Text("商户") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    val cents = Money.parseCents(amountText)
                    when {
                        cents == null || cents <= 0L -> error = "请输入有效金额"
                        categoryId == null -> error = "请选择分类"
                        accountId == null -> error = "请选择账户"
                        else -> {
                            val ts = java.time.LocalDateTime.of(date, timeOfDay)
                                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            vm.saveTransaction(
                                txId, type, cents, categoryId!!, accountId!!, ts, note.trim(), counterparty.trim(),
                            )
                            onBack()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(if (txId > 0) "保存修改" else "保存", style = MaterialTheme.typography.titleMedium) }
        }
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除记录") },
            text = { Text("确定删除这笔交易吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTransaction(existing!!.id) { _ -> onBack() }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}
