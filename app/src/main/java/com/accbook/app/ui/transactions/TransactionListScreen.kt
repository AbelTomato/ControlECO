package com.accbook.app.ui.transactions

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.accbook.app.data.importCardImage
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.RefundMark
import com.accbook.app.data.db.TransactionRow
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.CategoryLogo
import com.accbook.app.ui.common.PeriodPickerSheet
import com.accbook.app.ui.common.PeriodStepper
import com.accbook.app.ui.common.SegmentedSwitch
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

/** 收支类型筛选：三段式（全部 / 支出 / 收入），选中段主色实底 */
private enum class TxFilter(val label: String) {
    ALL("全部"),
    EXPENSE("支出"),
    INCOME("收入"),
}

/** 折叠状态下默认展示的最近天数；超出部分用页脚「查看全部」展开 */
private const val RECENT_DAYS = 3

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 账单行文案：标题 + 小行第一行 + 备注行（备注为空时不占位） */
internal data class BillRowText(val title: String, val subLine1: String, val note: String)

/**
 * 账单行的文案规则（纯函数，`BillRowTextTest` 覆盖）：
 * - 有商户：名称显示**商户**，小行第一行「时间 · 类别」，备注在小行第二行；
 * - 无商户：名称显示**类别**，小行第一行只留时间，备注在小行第二行。
 */
internal fun billRowText(counterparty: String, categoryName: String, time: String, note: String): BillRowText {
    val merchant = counterparty.trim()
    return if (merchant.isNotEmpty()) {
        BillRowText(title = merchant, subLine1 = "$time · $categoryName", note = note)
    } else {
        BillRowText(title = categoryName, subLine1 = time, note = note)
    }
}

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
    val cardImage by vm.cardImage.collectAsStateWithLifecycle()
    val cardImageColor by vm.cardImageColor.collectAsStateWithLifecycle()
    val cardImageLuminance by vm.cardImageLuminance.collectAsStateWithLifecycle()
    val ab = MaterialTheme.accBook

    // 数据未到位前整页不画：不显示空列表、也不显示默认卡面
    val rowsReady = rows ?: return
    val totalsReady = totals ?: return
    val refundReady = refundTotal ?: return

    val expense = totalsReady.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L
    val income = totalsReady.firstOrNull { it.type == TxType.INCOME }?.totalCents ?: 0L

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
                    vm.setCardImage(saved.path)
                    vm.setCardImageStats(saved.colorArgb, saved.luminance)
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

    val filtered = remember(rowsReady, filter) {
        when (filter) {
            TxFilter.ALL -> rowsReady
            TxFilter.EXPENSE -> rowsReady.filter { it.type == TxType.EXPENSE }
            TxFilter.INCOME -> rowsReady.filter { it.type == TxType.INCOME }
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
            image = cardImage,
            transform = cardTransform,
            placeholderColor = cardImageColor,
            placeholderLuminance = cardImageLuminance,
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

        // ---- 收支筛选（三段式）+ 记录数 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentedSwitch(
                options = TxFilter.entries,
                selected = filter,
                label = { it.label },
                onSelect = { filter = it },
            )
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    rowsReady.isEmpty() -> "${periodScopeLabel(mode)}暂无记录"
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
                TextButton(onClick = { selected = rowsReady.map { it.id }.toSet() }) { Text("全选") }
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
            rowsReady.isEmpty() -> {
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
            image = cardImage?.drawable,
            initial = cardTransform,
            onApply = {
                vm.setCardTransform(it)
                showImageEditor = false
            },
            onDismiss = { showImageEditor = false },
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
 * 列表滑动条（自绘）：拇指长度 = 视口/内容比例，位置 = 已滚像素比例；
 * 拖动把位移换算成滚动像素直接交给列表（`dispatchRawDelta`），拖多少内容走多少。
 *
 * 实机排查后改掉的三处（原来是"闪动 + 不跟手"）：
 * 1. **位置改用最后一个可见项算**：列表带 sticky 日期头，被钉住的那一项会被当作
 *    "第一个可见项"报出来（实测 `vis=2:o0 6:o0 ...`），用它算位置会让拇指一直卡在顶部；
 *    从最后一个可见项倒推（最后一项永远不会是钉住的头）才拿到真实滚动量。
 * 2. **高度按 index 缓存**：见过的是实测值，没见过的按已测平均补；没测到任何一项时
 *    干脆先不画拇指（硬猜平均高度会画出一根长度离谱的拇指，下一帧又跳回正常 = 闪动）。
 * 3. **拖动比例按下时定死**：拖动中列表在滚、比例若每帧重算，拇指就追不上手指。
 */
@Composable
private fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    val activeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    val info = state.layoutInfo
    val total = info.totalItemsCount
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val visible = info.visibleItemsInfo

    // 实测高度缓存：按 index 记（组合期间只读，写入放到 SideEffect）。
    // 键绑总条目数：换月/展开/筛选后条目集合变了，旧高度不能复用
    val seen = remember(total) { HashMap<Int, Int>() }
    // 首帧还没测到任何项时拇指不画；SideEffect 补上高度后靠版本号触发一次重组把拇指补出来
    // （否则"静止在列表顶部"时永远等不到下一次重组，拇指干脆不见了）
    var measuredVersion by remember(total) { mutableIntStateOf(0) }
    SideEffect {
        var changed = false
        state.layoutInfo.visibleItemsInfo.forEach { item ->
            if (seen[item.index] != item.size) {
                seen[item.index] = item.size
                changed = true
            }
        }
        if (changed) measuredVersion++
    }
    val avg = remember(measuredVersion) {
        if (seen.isEmpty()) 0f else seen.values.sum().toFloat() / seen.size
    }

    /** 已测到的用实测高度，没测到的按已测平均补：跑几帧就逼近真值，拇指长度不会再变 */
    fun approxHeight(index: Int): Float = (seen[index] ?: avg).toFloat()

    fun sumHeights(before: Int): Float {
        var acc = 0f
        for (i in 0 until before) acc += approxHeight(i)
        return acc
    }

    val measured = avg > 0f
    val contentPx = (if (measured) sumHeights(total) else 0f).coerceAtLeast(viewport.toFloat())
    val scrollablePx = (contentPx - viewport).coerceAtLeast(1f)
    // 从最后一个可见项倒推滚动量：它上面所有项的高度和 − 它在视口内的偏移
    val last = visible.lastOrNull()
    val scrolledPx = if (measured && last != null) {
        (sumHeights(last.index) - last.offset).coerceIn(0f, scrollablePx)
    } else {
        0f
    }
    val progress = (scrolledPx / scrollablePx).coerceIn(0f, 1f)
    val thumbFraction = (viewport / contentPx).coerceIn(0.12f, 0.9f)
    val travelPx = (trackHeightPx * (1f - thumbFraction)).coerceAtLeast(1f)
    val thumbHeight = with(LocalDensity.current) { (trackHeightPx * thumbFraction).toDp() }
    // 拖动 1px → 内容滚多少：按"内容可滚量 / 拇指可走距离"换算，保底 0.2 让轻微溢出也能拖动
    val ratioNow = (scrollablePx / travelPx).coerceAtLeast(0.2f)
    val latestRatio by rememberUpdatedState(ratioNow)
    val canScroll = state.canScrollForward || state.canScrollBackward

    // 测量用的 onSizeChanged 必须挂在始终存在的节点上（提前 return 会让轨道高度永远是 0）
    Box(
        modifier
            .onSizeChanged { trackHeightPx = it.height }
            .pointerInput(canScroll, total) {
                if (canScroll) {
                    var dragRatio = 1f
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            // 按下时定死比例：整个手势都用它，拇指才会贴着手指走
                            dragRatio = latestRatio
                        },
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
        if (canScroll && measured && trackHeightPx > 0) {
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

/** 日期分组头：**统一样式**（浅蓝底、日期在前，今天/昨天/周几在后，不用描边框住）+ 当天结余（+ 绿 / − 红） */
@Composable
private fun DayHeader(day: LocalDate, dayRows: List<TransactionRow>) {
    val ab = MaterialTheme.accBook
    val today = LocalDate.now()
    val relative = when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> dayOfWeekZh(day.dayOfWeek)
    }
    val label = "${day.monthValue}月${day.dayOfMonth}日 · $relative"
    // 日期头随滚动反复进出视野，汇总只在数据变化时算一次
    val dayExpense = remember(dayRows) { dayRows.filter { it.type == TxType.EXPENSE }.sumOf { it.amountCents } }
    val dayIncome = remember(dayRows) { dayRows.filter { it.type == TxType.INCOME }.sumOf { it.amountCents } }
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
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
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
    // 时间字符串按时间戳缓存：滚动时行会被反复组合，日期转换 + 格式化不必每帧重来
    val time = remember(row.timestamp) {
        Instant.ofEpochMilli(row.timestamp).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormatter)
    }
    val texts = billRowText(row.counterparty, row.categoryName, time, row.note)
    val subStyle = MaterialTheme.typography.bodySmall
    val subColor = MaterialTheme.colorScheme.onSurfaceVariant
    val subModifier = Modifier.fillMaxWidth()

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
        CategoryLogo(categoryName = row.categoryName, icon = row.categoryIcon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    texts.title,
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
                texts.subLine1,
                style = subStyle,
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = subModifier,
            )
            if (texts.note.isNotBlank()) {
                Text(
                    texts.note,
                    style = subStyle,
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = subModifier,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            Money.formatSignedPlain(if (isExpense) -row.amountCents else row.amountCents),
            style = MaterialTheme.typography.titleMedium,
            color = if (isExpense) MaterialTheme.colorScheme.onSurface else ab.income,
        )
    }
}
