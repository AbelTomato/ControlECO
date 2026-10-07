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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
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
 * 页头紧凑（标题 + 月份胶囊 + 本月支出 / 收入 / 结余 + 筛选），纵向空间尽量留给列表；
 * 列表右侧常显滑动条；再次点击底部「账单」标签回到顶部。
 * 列表按日期分组（今天/昨天用浅蓝日期胶囊，其余白底描边），先看最近 3 天，
 * 页脚一键展开整月；行的副标题是「分类 · 时间 · 备注」（标题已是分类名时不重复）。
 * 右上角 ⋮ 菜单：批量管理（多选删除）/ 删除本月 / 删除本年 / 删除全部，范围删除均有计数确认。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    vm: LedgerViewModel,
    onEditTransaction: (Long) -> Unit,
    onOpenPending: () -> Unit,
    scrollToTopTick: Int = 0,
) {
    val rows by vm.monthRows.collectAsStateWithLifecycle()
    val totals by vm.monthTotals.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val showRefunds by vm.showRefunds.collectAsStateWithLifecycle()
    val refundTotal by vm.refundIncomeTotal.collectAsStateWithLifecycle()
    val ab = MaterialTheme.accBook

    val expense = totals.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L
    val income = totals.firstOrNull { it.type == TxType.INCOME }?.totalCents ?: 0L

    var filter by remember { mutableStateOf(TxFilter.ALL) }
    var showAllDays by remember { mutableStateOf(false) }
    var showMonthSheet by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirm by remember { mutableStateOf<DeleteConfirm?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 再次点击底部「账单」标签：回到顶部
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > 0) listState.animateScrollToItem(0)
    }

    // 切月即退出多选、回到"最近几天"视图并从顶部看起，避免把状态误带到另一个月
    LaunchedEffect(month) {
        selecting = false
        selected = emptySet()
        showAllDays = false
        listState.scrollToItem(0)
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
        // ---- 页头（紧凑）：标题 + 单位 + 批量菜单 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = ab.primaryStrong, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("账单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "人民币 · 元",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!selecting) {
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "批量操作", modifier = Modifier.size(20.dp))
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

        // ---- 月份胶囊（点开月份选择面板）+ 前后月快捷切换 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.prevMonth() }, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "上一月",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            MonthPill(month = month) { showMonthSheet = true }
            IconButton(onClick = { vm.nextMonth() }, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = "下一月",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    rows.isEmpty() -> "本月暂无记录"
                    hiddenDays > 0 -> "最近 $RECENT_DAYS 天 $shownCount 笔"
                    else -> "本月 $shownCount 笔"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 汇总：本月支出（大字）+ 右侧收入 / 结余（竖排，省下一行高度） ----
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp).padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "本月支出",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Money.formatYuanGroupedFixed(expense),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                InlineAmount("收入", Money.formatYuanGroupedFixed(income))
                Spacer(Modifier.height(2.dp))
                InlineAmount("结余", Money.formatYuanGroupedFixed(income - expense))
                if (showRefunds && refundTotal > 0L) {
                    Spacer(Modifier.height(2.dp))
                    InlineAmount("退款", Money.formatYuanGroupedFixed(refundTotal))
                }
            }
        }

        // ---- 类型筛选 + 数据范围提示 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TxFilter.entries.forEach { f ->
                FilterPill(label = f.label, selected = filter == f) { filter = f }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "汇总为整月数据",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
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
                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // 底部给悬浮"记账"胶囊留位，右侧给滑动条留位
                        contentPadding = PaddingValues(bottom = 96.dp, end = 8.dp),
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
                                        "查看本月全部账单（还有 $hiddenDays 天）",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    // 常显滑动条：可拖动快速跳转，静止时淡显、按住时加深
                    ListScrollbar(
                        state = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(18.dp)
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }

    if (showMonthSheet) {
        MonthPickerSheet(
            selectedMonth = month,
            onSelect = { vm.setMonth(it) },
            onDismiss = { showMonthSheet = false },
        )
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

/**
 * 列表滑动条（自绘，零依赖）：按"条目序号"估算位置——可见条目数决定拇指长度，
 * 首个可见条目决定拇指位置，首尾两端严格对齐；拇指可拖动跳转，静止淡显、按住加深。
 * 内容不足一屏时不显示。
 */
@Composable
private fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.26f)
    val activeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    val scope = rememberCoroutineScope()
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    val info = state.layoutInfo
    val total = info.totalItemsCount
    val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
    val visibleCount = (last - first + 1).coerceAtLeast(1)
    // 用 LazyListState 的"还能不能滚"判断，而不是条目数——最后一条只露出一半时也要显示
    val canScroll = state.canScrollForward || state.canScrollBackward

    val thumbFraction = (visibleCount.toFloat() / total.coerceAtLeast(1)).coerceIn(0.08f, 0.9f)
    val progress = if (total <= visibleCount) {
        // 条目数不足以判断位置：退回用首项被滚出的像素估算（少量溢出时的连续过渡）
        val overflow = info.visibleItemsInfo.firstOrNull()?.let { info.viewportStartOffset - it.offset } ?: 0
        (overflow.toFloat() / trackHeightPx.coerceAtLeast(1)).coerceIn(0f, 1f)
    } else {
        (first.toFloat() / (total - visibleCount).toFloat()).coerceIn(0f, 1f)
    }
    val shownProgress = if (dragging) dragProgress else progress
    val travelPx = (trackHeightPx * (1f - thumbFraction)).coerceAtLeast(1f)
    val thumbHeight = with(density) { (trackHeightPx * thumbFraction).toDp() }

    // 注意：测量用的 onSizeChanged 必须挂在始终存在的节点上（不能提前 return），否则轨道高度永远为 0
    Box(
        modifier
            .onSizeChanged { trackHeightPx = it.height }
            .pointerInput(canScroll, total, visibleCount) {
                if (canScroll) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragProgress = progress
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dragAmount ->
                        change.consume()
                        dragProgress = (dragProgress + dragAmount / travelPx).coerceIn(0f, 1f)
                        val target = (dragProgress * (total - visibleCount).coerceAtLeast(0)).roundToInt()
                        // 拖动中不断重定目标：取消上一个定位协程，避免堆积
                        scrollJob?.cancel()
                        scrollJob = scope.launch { state.scrollToItem(target.coerceIn(0, total - 1)) }
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (canScroll && trackHeightPx > 0) {
            // 拇指（居中 5dp 宽；外层 18dp 是留给手指的拖动热区）
            Box(
                Modifier
                    .offset { IntOffset(0, (shownProgress * travelPx).roundToInt()) }
                    .width(5.dp)
                    .height(thumbHeight)
                    .clip(CircleShape)
                    .background(if (dragging) activeColor else idleColor),
            )
        }
    }
}

/** 月份胶囊：白底描边 + ▾，点开月份选择面板 */
@Composable
private fun MonthPill(month: YearMonth, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.accBook.hairline),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
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
}

/**
 * 月份选择面板：年份左右切换 + 4×3 月份格子（选中浅蓝实底，本月描边）。
 * 取代原来一长串下拉列表，跳月更直观。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthPickerSheet(selectedMonth: YearMonth, onSelect: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val now = YearMonth.now()
    var year by remember { mutableIntStateOf(selectedMonth.year) }

    fun dismiss(after: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            after()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选择月份", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { dismiss { onSelect(now) } }) { Text("回到本月") }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { year -= 1 }) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一年")
                }
                Text(
                    "$year 年",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                IconButton(onClick = { year += 1 }) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下一年")
                }
            }
            Spacer(Modifier.height(6.dp))
            repeat(3) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(4) { col ->
                        val m = row * 4 + col + 1
                        val isSelected = year == selectedMonth.year && m == selectedMonth.monthValue
                        val isThisMonth = year == now.year && m == now.monthValue
                        Box(
                            Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                )
                                .then(
                                    if (isThisMonth && !isSelected) {
                                        Modifier.border(
                                            1.dp,
                                            MaterialTheme.accBook.primaryStrong,
                                            RoundedCornerShape(12.dp),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { dismiss { onSelect(YearMonth.of(year, m)) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$m 月",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
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
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** 汇总区里的"标签 + 金额"（标签淡、金额墨色） */
@Composable
private fun InlineAmount(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/** 日期分组头：今天/昨天用浅蓝胶囊，其余白底描边胶囊；右侧是当天小计（优先支出） */
@Composable
private fun DayHeader(day: LocalDate, dayRows: List<TransactionRow>) {
    val today = LocalDate.now()
    val relative = when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> null
    }
    val label = if (relative != null) {
        "$relative · ${day.monthValue}月${day.dayOfMonth}日"
    } else {
        "${day.monthValue}月${day.dayOfMonth}日 · ${dayOfWeekZh(day.dayOfWeek)}"
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
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (relative != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            border = if (relative != null) null else BorderStroke(1.dp, MaterialTheme.accBook.hairline),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (relative != null) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
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
