package com.accbook.app.ui.transactions

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.RefundMark
import com.accbook.app.data.db.TransactionRow
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.CategoryBadge
import com.accbook.app.ui.common.MonthHeader
import com.accbook.app.ui.common.SummaryRow
import com.accbook.app.ui.common.dayOfWeekZh
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch

/** 范围/多选删除的二次确认（标题 + 说明 + 执行动作） */
private data class DeleteConfirm(val title: String, val body: String, val onConfirm: () -> Unit)

/**
 * 账单页：成熟、标准、易扫描的记账列表（不做游戏化）。
 * 按日期分组；每条 = 分类徽标 + 商户/名称 + 分类 · 备注 + 金额（收入绿、支出墨色）。
 * 右上角 ⋮ 菜单：批量管理（多选删除）/ 删除本月 / 删除本年 / 删除全部，范围删除均有计数确认。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionListScreen(vm: LedgerViewModel, onEditTransaction: (Long) -> Unit, onOpenPending: () -> Unit) {
    val rows by vm.monthRows.collectAsStateWithLifecycle(initialValue = emptyList())
    val totals by vm.monthTotals.collectAsStateWithLifecycle(initialValue = emptyList())
    val month by vm.month.collectAsStateWithLifecycle()
    val showRefunds by vm.showRefunds.collectAsStateWithLifecycle()
    val refundTotal by vm.refundIncomeTotal.collectAsStateWithLifecycle(initialValue = 0L)
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
    val ab = MaterialTheme.accBook

    val expense = totals.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L
    val income = totals.firstOrNull { it.type == TxType.INCOME }?.totalCents ?: 0L

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirm by remember { mutableStateOf<DeleteConfirm?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 切月即退出多选，避免把选择集误带到另一个月
    LaunchedEffect(month) {
        selecting = false
        selected = emptySet()
    }

    val report: (String?) -> Unit = { err ->
        if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
    }

    fun monthRange(m: YearMonth): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    fun yearRange(m: YearMonth): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(m.year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(m.year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    // 范围删除先查条数再弹确认，让用户明确将删掉多少条
    fun confirmRange(title: String, range: Pair<Long, Long>) {
        scope.launch {
            val n = vm.countTransactionsInRange(range.first, range.second)
            confirm = DeleteConfirm(title, "将删除 $n 条记录，删除后不可恢复。") {
                vm.deleteTransactionsInRange(range.first, range.second, report)
            }
        }
    }

    fun confirmDeleteAll() {
        scope.launch {
            val n = vm.countAllTransactions()
            confirm = DeleteConfirm("删除全部记录", "将删除全部 $n 条记录（所有月份），删除后不可恢复。") {
                vm.deleteAllTransactions(report)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        MonthHeader(
            month = month,
            onPrev = vm::prevMonth,
            onNext = vm::nextMonth,
            trailing = {
                if (!selecting) {
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "批量操作")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("批量管理") },
                                onClick = { menuOpen = false; selecting = true; selected = emptySet() },
                            )
                            DropdownMenuItem(
                                text = { Text("删除本月全部记录…") },
                                onClick = { menuOpen = false; confirmRange("删除本月记录", monthRange(month)) },
                            )
                            DropdownMenuItem(
                                text = { Text("删除本年全部记录…") },
                                onClick = { menuOpen = false; confirmRange("删除本年记录", yearRange(month)) },
                            )
                            DropdownMenuItem(
                                text = { Text("删除全部记录…") },
                                onClick = { menuOpen = false; confirmDeleteAll() },
                            )
                        }
                    }
                }
            },
        )
        SummaryRow(
            incomeCents = income,
            expenseCents = expense,
            cancelledCents = if (showRefunds) refundTotal else null,
        )

        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已选 ${selected.size}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selected = rows.map { it.id }.toSet() }) { Text("全选") }
                TextButton(
                    onClick = {
                        if (selected.isNotEmpty()) {
                            confirm = DeleteConfirm("删除已选记录", "将删除 ${selected.size} 条记录，删除后不可恢复。") {
                                vm.deleteTransactionsByIds(selected) { err ->
                                    report(err)
                                    if (err == null) {
                                        selecting = false
                                        selected = emptySet()
                                    }
                                }
                            }
                        }
                    },
                ) { Text("删除", color = ab.critical) }
                TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("取消") }
            }
        }
        HorizontalDivider(color = ab.hairline)

        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "本月暂无记录\n点右下角「记账」记一笔，或在“设置”中导入账单",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val grouped: Map<LocalDate, List<TransactionRow>> = rows.groupBy {
                Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
            }
            LazyColumn(
                Modifier.weight(1f),
                // 底部给悬浮"记一笔"胶囊留出空间，最后一行不被遮挡
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                grouped.forEach { (day, dayRows) ->
                    stickyHeader(key = "d${day.toEpochDay()}") {
                        val dayExpense = dayRows.filter { it.type == TxType.EXPENSE }.sumOf { it.amountCents }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${day.monthValue}月${day.dayOfMonth}日 ${dayOfWeekZh(day.dayOfWeek)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (dayExpense > 0) {
                                Text(
                                    "支出 ${Money.formatYuan(dayExpense)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(dayRows, key = { it.id }) { row ->
                        BillRow(
                            row = row,
                            selecting = selecting,
                            selected = row.id in selected,
                            onClick = {
                                if (selecting) {
                                    selected = if (row.id in selected) selected - row.id else selected + row.id
                                } else {
                                    onEditTransaction(row.id)
                                }
                            },
                            onLongClick = {
                                if (!selecting) {
                                    selecting = true
                                    selected = setOf(row.id)
                                }
                            },
                        )
                        HorizontalDivider(color = ab.hairline, modifier = Modifier.padding(start = 64.dp))
                    }
                }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.body) },
            confirmButton = {
                TextButton(onClick = { confirm = null; c.onConfirm() }) {
                    Text("删除", color = MaterialTheme.accBook.critical)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BillRow(
    row: TransactionRow,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ab = MaterialTheme.accBook
    val isExpense = row.type == TxType.EXPENSE
    val title = row.counterparty.ifBlank { row.categoryName }
    val sub = buildList {
        if (title != row.categoryName) add(row.categoryName)
        if (row.note.isNotBlank()) add(row.note)
    }.joinToString(" · ")

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(4.dp))
        }
        CategoryBadge(row.categoryName)
        Spacer(Modifier.padding(start = 12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.refundMark != RefundMark.NONE) {
                    Spacer(Modifier.padding(start = 6.dp))
                    Text(
                        if (row.refundMark == RefundMark.REFUND_INCOME) "退款收入" else "已退款",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.refundMark == RefundMark.REFUND_INCOME) ab.income else ab.warning,
                    )
                }
            }
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.padding(start = 8.dp))
        Text(
            (if (isExpense) "-" else "+") + Money.formatYuanGrouped(row.amountCents),
            style = MaterialTheme.typography.titleMedium,
            color = if (isExpense) MaterialTheme.colorScheme.onSurface else ab.income,
        )
    }
}
