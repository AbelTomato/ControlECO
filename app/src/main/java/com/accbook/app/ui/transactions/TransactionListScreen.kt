package com.accbook.app.ui.transactions

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.CardTransform
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.RefundMark
import com.accbook.app.data.db.TransactionRow
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.CategoryGlyph
import com.accbook.app.ui.common.PeriodPickerSheet
import com.accbook.app.ui.common.PeriodStepper
import com.accbook.app.ui.common.dayOfWeekZh
import com.accbook.app.ui.common.periodScopeLabel
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 范围/多选删除的二次确认（标题 + 说明 + 执行动作） */
private data class DeleteConfirm(val title: String, val body: String, val onConfirm: () -> Unit)

/** 收支类型筛选：单个按钮点击循环 全部 → 支出 → 收入 → 全部 */
private enum class TxFilter(val label: String) {
    ALL("全部"),
    EXPENSE("支出"),
    INCOME("收入");

    fun next(): TxFilter = entries[(ordinal + 1) % entries.size]
}

/** 折叠状态下默认展示的最近天数；超出部分用页脚「查看全部」展开 */
private const val RECENT_DAYS = 3

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 账单页：顶部一张悬浮结余卡（大字结余 + 右侧收入/支出，可换背景图），下面是周期步进与列表。
 * 周期支持月/年（PeriodMode），列表按日期分组（今天/昨天浅蓝胶囊，右侧当天结余 +绿 −红），
 * 先看最近 3 天、页脚一键展开；单按钮循环筛选；右侧常显可拖动滑动条；重按底部标签回到顶部。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    vm: LedgerViewModel,
    onEditTransaction: (Long) -> Unit,
    onOpenPending: () -> Unit,
    scrollToTopTick: Int = 0,
) {
    val rows by vm.periodRows.collectAsStateWithLifecycle()
    val totals by vm.periodTotals.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val mode by vm.periodMode.collectAsStateWithLifecycle()
    val showRefunds by vm.showRefunds.collectAsStateWithLifecycle()
    val refundTotal by vm.periodRefundTotal.collectAsStateWithLifecycle()
    val cardImageUri by vm.cardImageUri.collectAsStateWithLifecycle()
    val cardTransform by vm.cardTransform.collectAsStateWithLifecycle()
    val ab = MaterialTheme.accBook

    val expense = totals.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L
    val income = totals.firstOrNull { it.type == TxType.INCOME }?.totalCents ?: 0L

    var filter by remember { mutableStateOf(TxFilter.ALL) }
    var showAllDays by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showImageDialog by remember { mutableStateOf(false) }
    var showImageEditor by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirm by remember { mutableStateOf<DeleteConfirm?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // 相册选图 → 持久化读取权限，重启后仍能显示
    // 系统照片选择器（相册 UI，不是文件管理）：选完立刻拷进应用内部存储
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { importCardImage(context, uri) }
                if (saved != null) {
                    vm.setCardImage(saved)
                    vm.setCardTransform(CardTransform.DEFAULT)
                    showImageEditor = true
                } else {
                    Toast.makeText(context, "这张图片读取失败，换一张试试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 再次点击底部「账单」标签：回到顶部
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > 0) listState.animateScrollToItem(0)
    }

    // 切周期（月/年切换或换月换年）即退出多选、回到"最近几天"并从顶部看起
    LaunchedEffect(month, mode) {
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
        // ---- 悬浮结余卡（取代页眉与汇总行）----
        BalanceCard(
            mode = mode,
            balanceCents = income - expense,
            incomeCents = income,
            expenseCents = expense,
            imagePath = cardImageUri,
            transform = cardTransform,
            onModeChange = { vm.setPeriodMode(it) },
            onOpenImageSettings = { showImageDialog = true },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )

        // ---- 周期步进 + 批量菜单 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeriodStepper(
                yearMonth = month,
                mode = mode,
                onPrev = { vm.prevPeriod() },
                onNext = { vm.nextPeriod() },
                onOpenPicker = { showPicker = true },
            )
            Spacer(Modifier.weight(1f))
            if (!selecting) {
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(38.dp)) {
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

        // ---- 单按钮循环筛选 + 记录数 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CycleFilterButton(filter = filter, onCycle = { filter = filter.next() })
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    rows.isEmpty() -> "${periodScopeLabel(mode)}暂无记录"
                    hiddenDays > 0 -> "最近 $RECENT_DAYS 天 $shownCount 笔"
                    else -> "${periodScopeLabel(mode)} $shownCount 笔"
                },
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
                        "${periodScopeLabel(mode)}暂无记录\n点右下角「记账」记一笔，或在「个人」页导入账单",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            filtered.isEmpty() -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "${periodScopeLabel(mode)}没有${filter.label}记录",
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
                                        "查看${periodScopeLabel(mode)}全部账单（还有 $hiddenDays 天）",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
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

    if (showPicker) {
        PeriodPickerSheet(
            mode = mode,
            current = month,
            onSelectMonth = { vm.setMonth(it) },
            onSelectYear = { vm.setMonth(YearMonth.of(it, month.monthValue)) },
            onDismiss = { showPicker = false },
        )
    }

    if (showImageDialog) {
        val hasImage = !cardImageUri.isNullOrBlank()
        AlertDialog(
            onDismissRequest = { showImageDialog = false },
            title = { Text("结余卡背景") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "从相册选一张图片做结余卡背景（会自动压暗，保证卡上文字清晰）；" +
                            "不选则使用默认的几何背景。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (hasImage) {
                        TextButton(onClick = { showImageDialog = false; showImageEditor = true }) {
                            Text("调整位置 / 缩放 / 旋转")
                        }
                        TextButton(onClick = { vm.setCardImage(null); showImageDialog = false }) {
                            Text("恢复默认背景")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showImageDialog = false
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }) { Text("从相册选择") }
            },
            dismissButton = {
                TextButton(onClick = { showImageDialog = false }) { Text("取消") }
            },
        )
    }

    if (showImageEditor && !cardImageUri.isNullOrBlank()) {
        CardImageEditorDialog(
            initial = cardTransform,
            onApply = {
                vm.setCardTransform(it)
                showImageEditor = false
            },
            onDismiss = { showImageEditor = false },
            preview = { draft ->
                BalanceCard(
                    mode = mode,
                    balanceCents = income - expense,
                    incomeCents = income,
                    expenseCents = expense,
                    imagePath = cardImageUri,
                    transform = draft,
                    onModeChange = {},
                    onOpenImageSettings = {},
                )
            },
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

/** 单按钮筛选：显示当前口径，点一下循环到下一个（全部 → 支出 → 收入 → 全部） */
@Composable
private fun CycleFilterButton(filter: TxFilter, onCycle: () -> Unit) {
    val active = filter != TxFilter.ALL
    val shape = RoundedCornerShape(50)
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tween(180),
        label = "cycleFilterBg",
    )
    val fg = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(shape)
            .background(bg)
            .then(if (active) Modifier else Modifier.border(1.dp, MaterialTheme.accBook.hairline, shape))
            .clickable(onClick = onCycle)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(filter.label, style = MaterialTheme.typography.labelLarge, color = fg)
        Spacer(Modifier.width(5.dp))
        Icon(Icons.Filled.Refresh, contentDescription = "切换筛选", tint = fg, modifier = Modifier.size(14.dp))
    }
}

/**
 * 列表滑动条（自绘）：拇指长度 = 视口/内容估算比例，位置 = 已滚像素比例；
 * 拖动把位移换算成滚动像素直接交给列表（`dispatchRawDelta`），拖多少内容走多少。
 */
@Composable
private fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    val activeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visibleInfo = info.visibleItemsInfo
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val avgItem = if (visibleInfo.isEmpty()) 1f else visibleInfo.sumOf { it.size }.toFloat() / visibleInfo.size
    val contentPx = (avgItem * total).coerceAtLeast(viewport.toFloat())
    val scrollablePx = (contentPx - viewport).coerceAtLeast(1f)
    val canScroll = state.canScrollForward || state.canScrollBackward

    val first = visibleInfo.firstOrNull()
    val scrolledPx = if (first == null) 0f else (avgItem * first.index - first.offset).coerceIn(0f, scrollablePx)
    val progress = (scrolledPx / scrollablePx).coerceIn(0f, 1f)
    val thumbFraction = (viewport / contentPx).coerceIn(0.12f, 0.9f)
    val travelPx = (trackHeightPx * (1f - thumbFraction)).coerceAtLeast(1f)
    val thumbHeight = with(LocalDensity.current) { (trackHeightPx * thumbFraction).toDp() }
    // 拖动 1px → 内容滚多少：按"内容可滚量 / 拇指可走距离"换算，保底 0.2 让轻微溢出也能拖动
    val dragRatio = (scrollablePx / travelPx).coerceAtLeast(0.2f)

    // 测量用的 onSizeChanged 必须挂在始终存在的节点上（提前 return 会让轨道高度永远是 0）
    Box(
        modifier
            .onSizeChanged { trackHeightPx = it.height }
            .pointerInput(canScroll, total) {
                if (canScroll) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dragAmount ->
                        change.consume()
                        state.dispatchRawDelta(dragAmount * dragRatio)
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (canScroll && trackHeightPx > 0) {
            Box(
                Modifier
                    .offset { IntOffset(0, (progress * travelPx).roundToInt()) }
                    .width(6.dp)
                    .height(thumbHeight)
                    .clip(CircleShape)
                    .background(if (dragging) activeColor else idleColor),
            )
        }
    }
}

/** 日期分组头：今天/昨天浅蓝胶囊；右侧是当天结余（+ 绿 / − 红） */
@Composable
private fun DayHeader(day: LocalDate, dayRows: List<TransactionRow>) {
    val ab = MaterialTheme.accBook
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
    val dayBalance = dayIncome - dayExpense
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
            border = if (relative != null) null else BorderStroke(1.dp, ab.hairline),
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
        Text(
            Money.formatSignedPlain(dayBalance),
            style = MaterialTheme.typography.titleSmall,
            color = if (dayBalance >= 0L) ab.income else ab.critical,
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
    // 副标题：分类 · 时间 · 备注；标题已经是分类名时不再重复分类
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
        CategoryGlyph(row.categoryName)
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
