package com.accbook.app.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.time.ZoneId

/**
 * 账单页：成熟、标准、易扫描的记账列表（不做游戏化）。
 * 按日期分组；每条 = 分类徽标 + 商户/名称 + 分类 · 备注 + 金额（收入绿、支出墨色）。
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

    Column(Modifier.fillMaxSize()) {
        MonthHeader(
            month = month,
            onPrev = vm::prevMonth,
            onNext = vm::nextMonth,
            trailing = {
                if (pendingCount > 0) {
                    TextButton(onClick = onOpenPending) { Text("待确认 $pendingCount") }
                }
            },
        )
        SummaryRow(
            incomeCents = income,
            expenseCents = expense,
            cancelledCents = if (showRefunds) refundTotal else null,
        )
        HorizontalDivider(color = ab.hairline)

        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "本月暂无记录\n点右下角 + 记一笔，或在“设置”中导入账单",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val grouped: Map<LocalDate, List<TransactionRow>> = rows.groupBy {
                Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
            }
            LazyColumn(Modifier.weight(1f)) {
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
                        BillRow(row = row, onClick = { onEditTransaction(row.id) })
                        HorizontalDivider(color = ab.hairline, modifier = Modifier.padding(start = 64.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BillRow(row: TransactionRow, onClick: () -> Unit) {
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
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
