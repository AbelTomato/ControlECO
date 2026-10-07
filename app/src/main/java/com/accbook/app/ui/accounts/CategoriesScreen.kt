package com.accbook.app.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.CategoryEntity
import com.accbook.app.data.db.TxType
import com.accbook.app.util.Money

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val budgets by vm.categoryBudgets.collectAsStateWithLifecycle()

    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新增分类")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "hint") {
                Text(
                    "为支出分类设置「月预算」后，首页「分类剩余预算」预览会用圆环显示各分类剩余。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            listOf(TxType.EXPENSE to "支出分类", TxType.INCOME to "收入分类").forEach { (type, label) ->
                val list = categories.filter { it.type == type }
                if (list.isNotEmpty()) {
                    item(key = "header-$type") {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(list, key = { it.id }) { c ->
                        val budget = budgets[c.id] ?: 0L
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { editing = c; showEditor = true }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (c.type == TxType.EXPENSE && budget > 0L) {
                                Text(
                                    "月预算 ${Money.formatYuanGrouped(budget)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showEditor) {
        CategoryEditorDialog(
            initial = editing,
            initialBudgetCents = editing?.let { budgets[it.id] } ?: 0L,
            onSave = { id, name, type, budgetCents ->
                vm.saveCategory(id, name, type) { savedId, err ->
                    if (err == null) {
                        vm.setCategoryBudget(savedId, if (type == TxType.EXPENSE) budgetCents else 0L)
                        showEditor = false
                    }
                }
            },
            onDelete = editing?.let { entity ->
                {
                    vm.deleteCategory(entity.id) { err -> if (err == null) showEditor = false }
                }
            },
            onClose = { showEditor = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorDialog(
    initial: CategoryEntity?,
    initialBudgetCents: Long,
    onSave: (Long, String, TxType, Long) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: TxType.EXPENSE) }
    var budgetText by remember {
        mutableStateOf(if (initialBudgetCents > 0L) Money.formatCents(initialBudgetCents) else "")
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "新增分类" else "编辑分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名称") },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = type == TxType.EXPENSE,
                        onClick = { type = TxType.EXPENSE },
                        label = { Text("支出") },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = type == TxType.INCOME,
                        onClick = { type = TxType.INCOME },
                        label = { Text("收入") },
                    )
                }
                if (type == TxType.EXPENSE) {
                    OutlinedTextField(
                        value = budgetText,
                        onValueChange = { budgetText = it },
                        label = { Text("分类月预算（元，可留空）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    Text(
                        "用于首页「分类剩余预算」预览；留空或 0 表示不跟踪该分类",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除该分类", color = MaterialTheme.colorScheme.error)
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
                val raw = budgetText.trim()
                val budgetCents = if (raw.isEmpty()) {
                    0L
                } else {
                    val cents = Money.parseCents(raw)
                    if (cents == null || cents < 0) {
                        error = "分类月预算请输入有效金额（或留空）"
                        return@TextButton
                    }
                    cents
                }
                onSave(initial?.id ?: 0L, trimmed, type, budgetCents)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}
