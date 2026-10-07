package com.accbook.app.ui.transactions

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.RefundMark
import com.accbook.app.data.db.TransactionRow
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.CategoryTile
import com.accbook.app.ui.common.dayOfWeekZh
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** 范围/多选删除的二次确认（标题 + 说明 + 执行动作） */
private data class DeleteConfirm(val title: String, val body: String, val onConfirm: () -> Unit)

/** 收支类型筛选：全部 / 支出 / 收入 */
private enum class TxFilter(val label: String) {
    ALL("全部"),
    EXPENSE("支出"),
    INCOME("收入"),
}

/** 折叠状态下默认展示的最近天数；超出部分用页脚「查看本月全部账单」展开 */
private const val RECENT_DAYS = 3

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 账单页：成熟、标准、易扫描的记账列表（不做游戏化）。
 *
 * 页头自上而下：页标题 → 月份胶囊（可下拉跳月）→「本月支出」大字 →
 * 收入/结余（退款开关打开时加一列退款）→ 全部/支出/收入 筛选胶囊 → 数据范围提示。
 * 列表按日期分组（今天/昨天/日期 · 周几），先看最近 3 天，页脚一键展开整月；
 * 行的副标题是「分类 · 时间」，有备注时优先显示备注（不丢信息）。
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
    val ab = MaterialTheme.accBook

    val expense = totals.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L
    val income = totals.firstOrNull { it.type == TxType.INCOME }?.totalCents ?: 0L

    var filter by remember { mutableStateOf(TxFilter.ALL) }
    var showAllDays by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirm by remember { mutableStateOf<DeleteConfirm?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 切月即退出多选并回到"最近几天"视图，避免把状态误带到另一个月
    LaunchedEffect(month) {
        selecting = false
        selected = emptySet()
        showAllDays = false
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

    val filtered = remember(rows, filter) {
        when (filter) {
            TxFilter.ALL -> rows
            TxFilter.EXPENSE -> rows.filter { it.type == TxType.EXPENSE }
            TxFilter.INCOME -> rows.filter { it.type == TxType.INCOME }
        }
    }
    val grouped: Map<LocalDate, List<TransactionRow>> = remember(filtered) {
        filtered.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() }
    }
    val allDays = grouped.keys.toList()
    val shownDays = if (showAllDays || selecting) allDays else allDays.take(RECENT_DAYS)
    val shownCount = shownDays.sumOf { grouped[it]?.size ?: 0 }
    val hiddenDays = allDays.size - shownDays.size

    Column(Modifier.fillMaxSize()) {
        // ---- 页头：标题 + 单位 + 批量菜单 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = ab.primaryStrong, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("账单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "人民币 · 元",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        }

        // ---- 月份胶囊（可下拉跳月）+ 前后月快捷切换 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.prevMonth() }) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一月", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MonthPill(month = month, onSelect = { vm.setMonth(it) })
            IconButton(onClick = { vm.nextMonth() }) {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下一月", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- 本月支出大字 + 收入 / 结余 ----
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                "本月支出",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Money.formatYuanGroupedFixed(expense),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InlineAmount("收入", Money.formatYuanGroupedFixed(income))
                if (showRefunds && refundTotal > 0L) {
                    InlineAmount("退款", Money.formatYuanGroupedFixed(refundTotal))
                }
                InlineAmount("结余", Money.formatYuanGroupedFixed(income - expense))
            }
        }

        // ---- 类型筛选 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TxFilter.entries.forEach { f ->
                FilterPill(label = f.label, selected = filter == f) { filter = f }
            }
        }

        // ---- 数据范围提示 ----
        Text(
            when {
                rows.isEmpty() -> "本月暂无记录"
                hiddenDays > 0 -> "最近 $RECENT_DAYS 天的 $shownCount 笔记录 · 上方汇总为整月数据"
                else -> "本月共 $shownCount 笔记录 · 上方汇总为整月数据"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
        )

        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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

        when {
            rows.isEmpty() -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "本月暂无记录\n点右下角「记账」记一笔，或在「个人」页导入账单",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            filtered.isEmpty() -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "本月没有${filter.label}记录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(
                    Modifier.weight(1f),
                    // 底部给悬浮"记账"胶囊留出空间，最后一行不被遮挡
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    shownDays.forEach { day ->
                        stickyHeader(key = "d${day.toEpochDay()}") {
                            DayHeader(day = day, dayRows = grouped[day].orEmpty())
                        }
                        val dayRows = grouped[day].orEmpty()
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
                            HorizontalDivider(color = ab.hairline, modifier = Modifier.padding(start = 76.dp))
                        }
                    }
                    if (hiddenDays > 0) {
                        item(key = "expandAll") {
                            TextButton(
                                onClick = { showAllDays = true },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            ) {
                                Text(
                                    "查看本月全部账单",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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

/** 月份胶囊：白底描边 + ▾，点开是近两年月份列表，选中即跳月 */
@Composable
private fun MonthPill(month: YearMonth, onSelect: (YearMonth) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val months = remember(month) {
        val now = YearMonth.now()
        ((-23..1).map { now.plusMonths(it.toLong()) } + month).distinct().sortedDescending()
    }
    Box {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.accBook.hairline),
            modifier = Modifier.clickable { open = true },
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleSmall)
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            months.forEach { m ->
                DropdownMenuItem(
                    text = { Text("${m.year}年${m.monthValue}月") },
                    onClick = { onSelect(m); open = false },
                )
            }
        }
    }
}

/** 筛选胶囊：选中 = 浅蓝实底 + ✓，未选 = 白底描边 */
@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val ab = MaterialTheme.accBook
    val shape = RoundedCornerShape(50)
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tween(180),
        label = "pillBg",
    )
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(shape)
            .background(bg)
            .then(if (selected) Modifier else Modifier.border(1.dp, ab.hairline, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** 汇总行里的"标签 + 金额"（标签淡、金额墨色） */
@Composable
private fun InlineAmount(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/** 日期分组头：今天/昨天/日期 · 周几，右侧是当天小计（优先支出） */
@Composable
private fun DayHeader(day: LocalDate, dayRows: List<TransactionRow>) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> "今天 · ${day.monthValue}月${day.dayOfMonth}日"
        today.minusDays(1) -> "昨天 · ${day.monthValue}月${day.dayOfMonth}日"
        else -> "${day.monthValue}月${day.dayOfMonth}日 · ${dayOfWeekZh(day.dayOfWeek)}"
    }
    val dayExpense = dayRows.filter { it.type == TxType.EXPENSE }.sumOf { it.amountCents }
    val dayIncome = dayRows.filter { it.type == TxType.INCOME }.sumOf { it.amountCents }
    val subtotal = when {
        dayExpense > 0L -> "支出 ${Money.formatYuanGroupedFixed(dayExpense)}"
        dayIncome > 0L -> "收入 ${Money.formatYuanGroupedFixed(dayIncome)}"
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        subtotal?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    // 副标题：分类 · 时间 · 备注；标题已经是分类名时不再重复分类（时间始终在，备注给出上下文）
    val time = Instant.ofEpochMilli(row.timestamp).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormatter)
    val sub = buildList {
        if (title != row.categoryName) add(row.categoryName)
        add(time)
        if (row.note.isNotBlank()) add(row.note)
    }.joinToString(" · ")

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(4.dp))
        }
        CategoryTile(row.categoryName)
        Spacer(Modifier.width(14.dp))
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
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            Money.formatSignedPlain(if (isExpense) -row.amountCents else row.amountCents),
            style = MaterialTheme.typography.titleMedium,
            color = if (isExpense) MaterialTheme.colorScheme.onSurface else ab.income,
        )
    }
}
