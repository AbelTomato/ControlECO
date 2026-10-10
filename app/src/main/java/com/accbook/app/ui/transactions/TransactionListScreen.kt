package com.accbook.app.ui.transactions

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.CardTransform
import com.accbook.app.data.PeriodMode
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

/** 分页：首屏先给这么多条，滑到底再追加一页（年账单几千条不会一次性排完） */
private const val FIRST_PAGE_ROWS = 30
private const val PAGE_ROWS = 30

/** 滑动条拇指长度占比的上下限：太短不好抓，太长看着不像滑动条 */
private const val MIN_THUMB_FRACTION = 0.12f
private const val MAX_THUMB_FRACTION = 0.9f

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 账单行文案：标题 + 小行第一行 + 备注行（备注为空时不占位） */
internal data class BillRowText(val title: String, val subLine1: String, val note: String)

/**
 * 账单行的文案规则（纯函数，`BillRowTextTest` 覆盖）：
 * - 有商户：名称显示**商户**，小行第一行「时间　类别」（空白间隔，不用「·」），备注在小行第二行；
 * - 无商户：名称显示**类别**，小行第一行只留时间，备注在小行第二行。
 */
internal fun billRowText(counterparty: String, categoryName: String, time: String, note: String): BillRowText {
    val merchant = counterparty.trim()
    return if (merchant.isNotEmpty()) {
        BillRowText(title = merchant, subLine1 = "$time  $categoryName", note = note)
    } else {
        BillRowText(title = categoryName, subLine1 = time, note = note)
    }
}

/**
 * 分页取天（纯函数，`BillPagingTest` 覆盖）：从最近的一天开始往回取，累计到 [limit] 条为止；
 * **整天推进**（不切开某一天），至少给一天。返回实际显示的天与条数。
 */
internal fun pageDays(
    days: List<LocalDate>,
    grouped: Map<LocalDate, List<TransactionRow>>,
    limit: Int,
): Pair<List<LocalDate>, Int> {
    if (days.isEmpty()) return emptyList<LocalDate>() to 0
    val out = ArrayList<LocalDate>(minOf(days.size, 16))
    var rows = 0
    for (d in days) {
        out += d
        rows += grouped[d]?.size ?: 0
        if (rows >= limit) break
    }
    return out to rows
}

/** 要显示到 [day] 那天，至少得加载多少条（这天不在列表里时返回全部条数 = 全加载） */
internal fun rowsUpToDay(
    days: List<LocalDate>,
    grouped: Map<LocalDate, List<TransactionRow>>,
    day: LocalDate,
): Int {
    var acc = 0
    for (d in days) {
        acc += grouped[d]?.size ?: 0
        if (d == day) return acc
    }
    return acc
}

/** 第 [index] 天的日期头在列表里的位置（每天 = 1 个日期头 + 当天的账单行） */
internal fun headerIndexForDay(
    days: List<LocalDate>,
    grouped: Map<LocalDate, List<TransactionRow>>,
    index: Int,
): Int {
    var acc = 0
    for (i in 0 until index) acc += 1 + (grouped[days[i]]?.size ?: 0)
    return acc
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
    var loadedLimit by remember { mutableIntStateOf(FIRST_PAGE_ROWS) }  // 已排到多少条（滑到底自动加一页）
    var jumpTarget by remember { mutableStateOf<LocalDate?>(null) }     // 点日期头 → 挑日期跳过去
    var pendingJump by remember { mutableStateOf<LocalDate?>(null) }    // 选好日期，等这天排出来再滚
    var showPicker by remember { mutableStateOf(false) }
    var showImageDialog by remember { mutableStateOf(false) }
    var showImageEditor by remember { mutableStateOf(false) }
    // 本次选图还没「应用」：取消时要把上一张卡面原样放回去（图片文件在应用前不会删）
    var pendingImage by remember { mutableStateOf<String?>(null) }
    var imageSnapshot by remember { mutableStateOf<CardImageSnapshot?>(null) }
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
                    // 先把当前卡面记下来：编辑器里点「取消」要回到选图前，而不是把新图留在卡上
                    imageSnapshot = CardImageSnapshot(cardImageUri, cardImageColor, cardImageLuminance, cardTransform)
                    pendingImage = saved.path
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

    // 切周期（月/年切换或换月换年）即退出多选、回到第一页并从顶部看起
    LaunchedEffect(month, mode) {
        selecting = false
        selected = emptySet()
        loadedLimit = FIRST_PAGE_ROWS
        listState.scrollToItem(0)
    }

    // 换筛选：列表内容整体换了一批，同样回到第一页顶部
    LaunchedEffect(filter) {
        loadedLimit = FIRST_PAGE_ROWS
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
    // 分页：先排一页，滑到底自动追加（整天推进，不把某一天切成两半）
    val (shownDays, shownCount) = remember(allDays, grouped, loadedLimit) {
        pageDays(allDays, grouped, loadedLimit)
    }
    val hasMoreDays = shownDays.size < allDays.size

    // 滑到离底部 3 项以内就再排一页（替代原来底部的「查看全部账单」按钮）
    val loadMore by rememberUpdatedState(hasMoreDays)
    LaunchedEffect(listState, filtered) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, totalItems) ->
                if (loadMore && totalItems > 0 && lastVisible >= totalItems - 3) {
                    loadedLimit += PAGE_ROWS
                }
            }
    }

    // 选完日期：先保证这天已经排出来，再滚到它的日期头
    LaunchedEffect(pendingJump, shownDays) {
        val target = pendingJump ?: return@LaunchedEffect
        if (allDays.isEmpty()) return@LaunchedEffect
        // 那天没有记录时退到"它之前最近的一天"：能看到这天该在的位置，而不是没反应
        val day = allDays.firstOrNull { it <= target } ?: allDays.last()
        val index = shownDays.indexOf(day)
        if (index < 0) {
            loadedLimit = maxOf(loadedLimit, rowsUpToDay(allDays, grouped, day))
            return@LaunchedEffect
        }
        val headerIndex = headerIndexForDay(shownDays, grouped, index)
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > headerIndex }
        listState.scrollToItem(headerIndex)
        pendingJump = null
    }

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
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeriodStepper(
                yearMonth = month,
                mode = mode,
                onPrev = { vm.prevPeriod() },
                onNext = { vm.nextPeriod() },
                onOpenPicker = { showPicker = true },
                compact = true,
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
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentedSwitch(
                options = TxFilter.entries,
                selected = filter,
                label = { it.label },
                onSelect = { filter = it },
                compact = true,
            )
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    rowsReady.isEmpty() -> "${periodScopeLabel(mode)}暂无记录"
                    hasMoreDays -> "已显示 $shownCount / ${filtered.size} 笔"
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
                            val dayRows = grouped[day].orEmpty()
                            stickyHeader(key = "d${day.toEpochDay()}") {
                                DayHeader(day = day, dayRows = dayRows, onClickDay = { jumpTarget = day })
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
                // 真正落地了才收尾：清掉这回选图留下的其它图片文件
                vm.pruneCardImages()
                pendingImage = null
                imageSnapshot = null
                showImageEditor = false
            },
            onDismiss = {
                // 「取消」= 这次选图整条作废：卡面回到选图前（含位移/缩放/旋转），新图删掉
                val snapshot = imageSnapshot
                if (pendingImage != null && snapshot != null) {
                    vm.setCardImage(snapshot.path)
                    if (snapshot.path != null && snapshot.colorArgb != null && snapshot.luminance != null) {
                        vm.setCardImageStats(snapshot.colorArgb, snapshot.luminance)
                    }
                    vm.setCardTransform(snapshot.transform)
                    vm.pruneCardImages()
                }
                pendingImage = null
                imageSnapshot = null
                showImageEditor = false
            },
        )
    }

    jumpTarget?.let { day ->
        DayJumpDialog(
            current = day,
            onPick = { picked ->
                // 挑到别的月/年：先把周期切过去（数据到位后由 pendingJump 负责滚过去）
                when {
                    mode == PeriodMode.MONTH && YearMonth.from(picked) != month ->
                        vm.setMonth(YearMonth.from(picked))
                    mode == PeriodMode.YEAR && picked.year != month.year ->
                        vm.setMonth(YearMonth.of(picked.year, month.monthValue))
                }
                pendingJump = picked
                jumpTarget = null
            },
            onDismiss = { jumpTarget = null },
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
 *
 * 卡顿排查（实机 gfxinfo 对比）：位置 / 长度原来是**每帧**把全部条目高度加一遍，
 * 年账单上千条时就是每帧 O(n) 的装箱哈希查找；现在高度表 → 前缀和只在"测到新高度"时
 * 重算一次（见 [buildScrollMetrics]），滚动帧全部落在 draw 阶段做 O(1) 查表——
 * 滚列表不再触发这个组件的重组与重新布局。
 */
@Composable
private fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    val activeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val thumbWidthPx = with(density) { 6.dp.toPx() }

    // 高度表 + 前缀和：只在"测到新条目高度"时更新一次（组合/绘制期间不写状态）
    var metrics by remember(state) { mutableStateOf<ScrollMetrics?>(null) }
    LaunchedEffect(state) {
        var heights = HashMap<Int, Int>()
        var knownTotal = -1
        snapshotFlow { state.layoutInfo }.collect { info ->
            if (info.totalItemsCount != knownTotal) {
                // 换周期 / 换筛选 / 追加分页后条目集合变了，旧高度不能复用
                heights = HashMap()
                knownTotal = info.totalItemsCount
            }
            var changed = false
            info.visibleItemsInfo.forEach { item ->
                if (heights[item.index] != item.size) {
                    heights[item.index] = item.size
                    changed = true
                }
            }
            if (changed || metrics == null) metrics = buildScrollMetrics(heights, knownTotal)
        }
    }

    Box(
        modifier
            .pointerInput(state) {
                var dragRatio = 1f
                var active = false
                detectVerticalDragGestures(
                    onDragStart = {
                        active = metrics != null && (state.canScrollForward || state.canScrollBackward)
                        dragging = active
                        // 按下时定死比例：整个手势都用它，拇指才会贴着手指走
                        val m = metrics
                        if (active && m != null) {
                            val ratio = scrollbarGeometry(state.layoutInfo, m, size.height.toFloat())
                            dragRatio = (ratio.scrollablePx / ratio.travelPx).coerceAtLeast(0.2f)
                        }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    if (active) {
                        change.consume()
                        state.dispatchRawDelta(dragAmount * dragRatio)
                    }
                }
            }
            // 拇指的位移/长度都在 draw 阶段算：滚动帧只需要重画，不用重组
            .drawBehind {
                val m = metrics ?: return@drawBehind
                val info = state.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull() ?: return@drawBehind
                val geo = scrollbarGeometry(info, m, size.height)
                if (geo.scrollablePx <= 0f) return@drawBehind
                // 从最后一个可见项倒推滚动量（上面第 1 条）
                val scrolled = (m.offsetOf(last.index) - last.offset).coerceIn(0f, geo.scrollablePx)
                val thumbHeight = size.height * geo.thumbFraction
                val top = (scrolled / geo.scrollablePx) * (size.height - thumbHeight)
                drawRoundRect(
                    color = if (dragging) activeColor else idleColor,
                    topLeft = Offset((size.width - thumbWidthPx) / 2f, top),
                    size = Size(thumbWidthPx, thumbHeight),
                    cornerRadius = CornerRadius(thumbWidthPx / 2f),
                )
            },
    )
}

/** 滑动条几何：内容高 / 可滚量 / 拇指占比 / 拇指可走距离 */
private class ScrollbarGeometry(
    val scrollablePx: Float,
    val thumbFraction: Float,
    val travelPx: Float,
)

private fun scrollbarGeometry(info: LazyListLayoutInfo, m: ScrollMetrics, trackHeight: Float): ScrollbarGeometry {
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val content = m.contentPx.coerceAtLeast(viewport.toFloat())
    val fraction = (viewport / content).coerceIn(MIN_THUMB_FRACTION, MAX_THUMB_FRACTION)
    return ScrollbarGeometry(
        scrollablePx = content - viewport,
        thumbFraction = fraction,
        travelPx = ((trackHeight * (1f - fraction)).coerceAtLeast(1f)),
    )
}

/**
 * 高度表 → 前缀和（未测到的按"已测平均"补）+ 内容总高。
 * 只在测到新高度时构建一次；滚动帧用 [ScrollMetrics.offsetOf] 做 O(1) 查表。
 */
private class ScrollMetrics(val prefix: LongArray, val contentPx: Float) {
    fun offsetOf(index: Int): Float = prefix[index.coerceIn(0, prefix.lastIndex)].toFloat()
}

private fun buildScrollMetrics(heights: HashMap<Int, Int>, total: Int): ScrollMetrics {
    var measuredSum = 0L
    var measuredCount = 0
    heights.forEach { (index, h) ->
        if (index in 0 until total) {
            measuredSum += h
            measuredCount++
        }
    }
    val avg = if (measuredCount == 0) 0f else measuredSum.toFloat() / measuredCount
    val prefix = LongArray(total + 1)
    for (i in 0 until total) {
        prefix[i + 1] = prefix[i] + (heights[i] ?: avg.toInt()).toLong()
    }
    return ScrollMetrics(prefix, prefix[total].toFloat())
}

/** 日期分组头：**统一样式**（浅蓝底、日期在前，今天/昨天/周几在后，不用描边框住）+ 当天结余（+ 绿 / − 红）；
 *  点日期胶囊可以挑日期直接跳过去看（见 [DayJumpDialog]） */
@Composable
private fun DayHeader(day: LocalDate, dayRows: List<TransactionRow>, onClickDay: () -> Unit) {
    val ab = MaterialTheme.accBook
    val today = LocalDate.now()
    val relative = when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> dayOfWeekZh(day.dayOfWeek)
    }
    val label = "${day.monthValue}月${day.dayOfMonth}日  $relative"
    // 日期头随滚动反复进出视野，汇总只在数据变化时算一次
    val dayExpense = remember(dayRows) { dayRows.filter { it.type == TxType.EXPENSE }.sumOf { it.amountCents } }
    val dayIncome = remember(dayRows) { dayRows.filter { it.type == TxType.INCOME }.sumOf { it.amountCents } }
    val dayBalance = dayIncome - dayExpense
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClickDay),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(
            Money.formatSignedPlain(dayBalance),
            style = MaterialTheme.typography.titleSmall,
            color = if (dayBalance >= 0L) ab.income else ab.critical,
        )
    }
}

/** 从日期头跳转：选一天（支持跨月/跨年），落到那天的账 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayJumpDialog(current: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = current.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }) { Text("跳转") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}

/** 选图前的卡面状态：编辑器点「取消」时按这份快照恢复 */
private data class CardImageSnapshot(
    val path: String?,
    val colorArgb: Int?,
    val luminance: Float?,
    val transform: CardTransform,
)

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
    val subStyle = MaterialTheme.typography.labelSmall
    val subColor = MaterialTheme.colorScheme.onSurfaceVariant
    val subModifier = Modifier.fillMaxWidth()

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(4.dp))
        }
        CategoryLogo(categoryName = row.categoryName, icon = row.categoryIcon, size = 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    texts.title,
                    style = MaterialTheme.typography.bodyMedium,
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
            style = MaterialTheme.typography.titleSmall,
            color = if (isExpense) MaterialTheme.colorScheme.onSurface else ab.income,
        )
    }
}
