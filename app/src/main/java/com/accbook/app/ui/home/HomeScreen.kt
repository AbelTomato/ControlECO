package com.accbook.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.budget.Budget
import com.accbook.app.data.db.CategoryEntity
import com.accbook.app.data.db.CategorySumRow
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.BudgetEditDialog
import com.accbook.app.ui.common.rememberReducedMotion
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * 首页：三个预览模式，点击中心或圆点在 1→2→3→1 间**翻转切换**（绕 Y 轴翻转，不滑动）。
 *   1. 总剩余预算 —— 中心大环（弧长 = 剩余比例，绿/黄/红随额度变化），环内再套一条淡蓝细环
 *      表示本月**剩余**时间：与主环同口径（饱和度高的弧 = 还剩多少），两环直接比长短即可
 *      （蓝弧比绿弧短 = 花钱比时间快）；环内"本月还可以花"+ 剩余天数，环下方大字"本日剩余"；
 *   2. 分类剩余预算 —— 每个设了预算的分类一个环 + "其他"聚合环（未设预算分类的剩余与支出），
 *      数量自适应网格缩放以容纳全部，不足一行的行内居中；
 *   3. 已花费与总存款 —— 无圆环，中央大字。
 *
 * 金额小数点前超过 4 位时逐档进位 K/M/B；环内金额按可用宽度自动缩放，不与环重叠。
 * 翻转跟手：滑动 30% 宽度转满 90°，也可快速甩动触发；翻转途中相机拉近 + 缩小 + 变暗营造立体感。
 * 超支/临界的红色数字带心跳与轻微摇晃警示，强度随剩余比例加深（设上限），
 * 系统"减少动画"时全部降级（含切换改为瞬时）。
 */
private const val MODE_COUNT = 3

/** 滑动翻页阈值：跟手旋转超过该角度视为一次翻页，否则回弹 */
private const val DRAG_FLIP_THRESHOLD_DEG = 45f

/** 跟手灵敏度：横向划过卡片宽度的该比例即转满 90°（越小越跟手） */
private const val DRAG_FULL_TURN_WIDTH_FRACTION = 0.3f

/** 快速甩动阈值：速度达到每秒划过卡片宽度的该比例即翻页，不必达到角度阈值 */
private const val FLICK_WIDTHS_PER_SECOND = 1.1f

/** 分类环数据：分类预算 + 当月支出 → 剩余、额度档位；isOther = "其他"聚合环 */
internal data class CategoryRingData(
    val name: String,
    val remainingCents: Long,
    val ratio: Float,
    val tone: Budget.Tone,
    val isOther: Boolean = false,
)

@Composable
fun HomeScreen(vm: LedgerViewModel, onOpenPending: () -> Unit) {
    val budgetCents by vm.monthlyBudgetCents.collectAsStateWithLifecycle()
    val spentCents by vm.homeNetSpent.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val categoryBudgets by vm.categoryBudgets.collectAsStateWithLifecycle()
    val categorySums by vm.homeCategorySums.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val totalDepositCents by vm.totalDepositCents.collectAsStateWithLifecycle()

    var showBudgetDialog by remember { mutableStateOf(false) }
    var todayState by remember { mutableStateOf(LocalDate.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) todayState = LocalDate.now()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val today = todayState
    val snapshot = remember(budgetCents, spentCents, today) {
        Budget.compute(budgetCents, spentCents, today)
    }

    // budget > 0 时 compute 必返回非空
    val s = snapshot

    val reducedMotion = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    val flip = remember { Animatable(0f) }
    val density = LocalDensity.current.density
    var dragging by remember { mutableStateOf(false) }
    var dragRotation by remember { mutableFloatStateOf(0f) }

    fun wrap(target: Int): Int = ((target % MODE_COUNT) + MODE_COUNT) % MODE_COUNT

    // 翻转方向约定：下一个 = 负向旋转（视觉上向左翻，与左滑一致）；上一个 = 正向
    // 点击/圆点：切到目标模式；翻转进行中防重入；减少动画时瞬时
    val flipTo: (Int) -> Unit = { target ->
        val t = wrap(target)
        if (!flip.isRunning && t != mode) {
            scope.launch {
                if (reducedMotion) {
                    mode = t
                } else {
                    val forward = wrap(t - mode)
                    val sign = if (forward == MODE_COUNT - 1) 1f else -1f
                    flip.animateTo(90f * sign, tween(150, easing = FastOutSlowInEasing))
                    mode = t
                    flip.snapTo(-90f * sign)
                    flip.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
                }
            }
        }
    }
    val latestFlipTo by rememberUpdatedState(flipTo)
    val advance: () -> Unit = { latestFlipTo(mode + 1) }

    // 滑动翻转：跟手旋转，越过角度阈值或快速甩动即翻页（快甩时角度可能还很小），
    // 未触发则回弹；翻页方向始终跟随手指，与跟手旋转同向
    val onDragEnd: (rotation: Float, velocityX: Float, widthPx: Float) -> Unit = { rotation, velocityX, widthPx ->
        if (dragging) {
            scope.launch {
                flip.snapTo(rotation)
                dragging = false
                val flicked = abs(velocityX) >= widthPx * FLICK_WIDTHS_PER_SECOND
                val turned = abs(rotation) >= DRAG_FLIP_THRESHOLD_DEG
                val next = if (flicked && !turned) velocityX < 0f else rotation < 0f
                if (reducedMotion) {
                    if (flicked || turned) mode = wrap(mode + if (next) 1 else -1)
                    flip.snapTo(0f)
                } else if (flicked || turned) {
                    val sign = if (next) -1f else 1f
                    flip.animateTo(90f * sign, tween(130, easing = FastOutSlowInEasing))
                    mode = wrap(mode + if (next) 1 else -1)
                    flip.snapTo(-90f * sign)
                    flip.animateTo(0f, tween(130, easing = FastOutSlowInEasing))
                } else {
                    flip.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    val latestOnDragEnd by rememberUpdatedState(onDragEnd)

    val rings = remember(categoryBudgets, categorySums, categories, budgetCents, spentCents) {
        buildCategoryRings(categoryBudgets, categorySums, categories, budgetCents, spentCents)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            MonthChip("${today.year}年${today.monthValue}月")
            Spacer(Modifier.weight(1f))
            if (pendingCount > 0) {
                PendingBadge(count = pendingCount, onClick = onOpenPending)
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer {
                    // 旋转角度只在 draw 阶段读取，不触发重组
                    val rot = if (dragging) dragRotation else flip.value
                    rotationY = rot
                    // 立体感：越接近侧立，相机越近（透视越强）+ 轻微后退缩小 + 变暗，
                    // 读起来像一张卡片翻过去，而不是平面瞬间换内容
                    val p = (abs(rot) / 90f).coerceIn(0f, 1f)
                    cameraDistance = (14f - 8f * p) * density
                    val s = 1f - 0.06f * p
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.2f * p
                }
                .pointerInput(Unit) { detectTapGestures { advance() } }
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            if (!flip.isRunning) {
                                dragging = true
                                dragRotation = 0f
                                tracker.resetTracking()
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (dragging) {
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val k = 90f / (size.width * DRAG_FULL_TURN_WIDTH_FRACTION)
                                dragRotation = (dragRotation + dragAmount * k).coerceIn(-90f, 90f)
                            }
                        },
                        onDragEnd = {
                            latestOnDragEnd(dragRotation, tracker.calculateVelocity().x, size.width.toFloat())
                        },
                        onDragCancel = {
                            if (dragging) {
                                dragging = false
                                dragRotation = 0f
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            when (mode) {
                0 -> if (s != null) {
                    ModeRemaining(s, today)
                } else {
                    ModeNeedsBudget(mode = 1, onSetBudget = { showBudgetDialog = true })
                }
                1 -> if (s != null) {
                    ModeCategories(rings)
                } else {
                    ModeNeedsBudget(mode = 2, onSetBudget = { showBudgetDialog = true })
                }
                else -> ModeSpentAndDeposit(snapshot = s, spentCents = spentCents, totalDepositCents = totalDepositCents)
            }
        }

        ModeIndicator(
            current = mode,
            onSelect = { index -> flipTo(index) },
        )
    }

    if (showBudgetDialog) {
        BudgetEditDialog(
            currentCents = budgetCents,
            onSave = { vm.setMonthlyBudget(it); showBudgetDialog = false },
            onDismiss = { showBudgetDialog = false },
        )
    }
}

// ---------------- 模式 1：总剩余预算（大环 + 时间细环 + 本日剩余） ----------------

@Composable
private fun ModeRemaining(s: Budget.Snapshot, today: LocalDate) {
    val ab = MaterialTheme.accBook
    val tone = s.tone
    val severity = warningSeverity(s.remainingRatio)
    val alarming = tone == Budget.Tone.LOW
    val mainColor by animateColorAsState(toneColor(tone, severity), tween(420), label = "mode1Ring")
    val ratio = s.remainingRatio.coerceIn(0f, 1f)
    val percent = (s.remainingRatio * 100).roundToInt().coerceIn(0, 100)
    val timeRatio = (today.dayOfMonth.toFloat() / today.lengthOfMonth()).coerceIn(0f, 1f)
    // 月份细环与主环同口径：饱和度高的弧 = 还剩多少，所以画"本月剩余"而非"已过"。
    // 此前画"已过"（8 号 = 1/4）会被读成"只剩 1/4"，且与主环方向相反、无法直接对比。
    val monthLeftRatio = (1f - timeRatio).coerceIn(0f, 1f)
    val daysLeftInMonth = (today.lengthOfMonth() - today.dayOfMonth).coerceAtLeast(0)
    // 底轨用比发丝线略深的 ringTrack：页面底色是浅蓝，hairline 会糊在背景里
    val trackColor = ab.ringTrack
    // 月份环（本月剩余时间）：饱和蓝，与预算主环明显区分（此前灰调不起眼）
    val timeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val timeColor = ab.primaryStrong
    val ink = MaterialTheme.colorScheme.onBackground
    val overColor = toneColor(Budget.Tone.LOW, severity)

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ringSize = minOf(maxWidth * 0.84f, maxHeight * 0.58f).coerceIn(200.dp, 290.dp)
        val stroke = ringSize * 0.105f
        val timeStroke = 4.dp
        val amountMaxWidth = ringSize * 0.62f
        val bottomMaxWidth = maxWidth - 32.dp

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(ringSize)) {
                    val strokePx = stroke.toPx()
                    val mainR = (size.minDimension - strokePx) / 2f
                    // 轨道与弧用同一半径/同一包围盒，避免弧相对轨道偏移
                    drawCircle(color = trackColor, radius = mainR, style = Stroke(strokePx))
                    if (s.remainingCents <= 0L) {
                        drawCircle(color = mainColor, radius = mainR, style = Stroke(strokePx))
                    } else if (ratio > 0f) {
                        drawArc(
                            color = mainColor,
                            startAngle = -90f,
                            sweepAngle = 360f * ratio,
                            useCenter = false,
                            topLeft = Offset(center.x - mainR, center.y - mainR),
                            size = Size(mainR * 2f, mainR * 2f),
                            style = Stroke(strokePx),
                        )
                    }
                    // 时间细环：本月剩余比例（与预算主环同口径，直接比长短；不表达金额）
                    val timeR = mainR - strokePx / 2f - 10.dp.toPx() - timeStroke.toPx() / 2f
                    drawCircle(color = timeTrackColor, radius = timeR, style = Stroke(timeStroke.toPx()))
                    if (monthLeftRatio > 0f) {
                        drawArc(
                            color = timeColor,
                            startAngle = -90f,
                            sweepAngle = 360f * monthLeftRatio,
                            useCenter = false,
                            topLeft = Offset(center.x - timeR, center.y - timeR),
                            size = Size(timeR * 2f, timeR * 2f),
                            style = Stroke(timeStroke.toPx()),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "本月还可以花",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.warningHeartbeat(alarming, severity),
                    ) {
                        AutoScaledText(
                            text = Money.formatYuanCompact(s.remainingCents),
                            style = MaterialTheme.typography.displaySmall,
                            color = mainColor,
                            maxWidth = amountMaxWidth,
                        )
                        Text("$percent%", style = MaterialTheme.typography.titleSmall, color = mainColor)
                    }
                    // 细环口径说明：避免"这条蓝环还剩多少"再被读反
                    Text(
                        "本月剩余 $daysLeftInMonth 天",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            if (s.remainingCents > 0L) {
                Text(
                    "本日剩余",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AutoScaledText(
                    text = Money.formatYuanCompact(s.suggestedDailyCents),
                    style = MaterialTheme.typography.displayMedium,
                    color = ink,
                    maxWidth = bottomMaxWidth,
                )
            } else {
                Text("本月已超支", style = MaterialTheme.typography.bodyMedium, color = overColor)
                AutoScaledText(
                    text = Money.formatYuanCompact(-s.remainingCents),
                    style = MaterialTheme.typography.displayMedium,
                    color = overColor,
                    maxWidth = bottomMaxWidth,
                    modifier = Modifier.warningHeartbeat(true, severity),
                )
            }
        }
    }
}

// ---------------- 模式 2：分类剩余预算（含"其他"聚合环，弹性网格） ----------------

@Composable
private fun ModeCategories(items: List<CategoryRingData>) {
    if (items.isEmpty()) return
    val trackColor = MaterialTheme.accBook.ringTrack
    val showHint = items.all { it.isOther }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val n = items.size
        val cols = when {
            n <= 2 -> n
            n <= 4 -> 2
            n <= 9 -> 3
            else -> 4
        }
        val rows = ceil(n / cols.toFloat()).toInt()
        val gap = 16.dp
        val hintSpace = if (showHint) 54.dp else 0.dp
        val sizeByWidth = (maxWidth - gap * (cols - 1)) / cols
        val sizeByHeight = (maxHeight - gap * (rows - 1) - hintSpace) / rows
        val ringSize = minOf(sizeByWidth, sizeByHeight, 124.dp).coerceAtLeast(68.dp)
        val stroke = (ringSize * 0.09f).coerceAtLeast(5.dp)
        // 仅内容溢出时启用纵向滚动（避免与横向翻转滑动争手势）
        val contentHeight = ringSize * rows + gap * (rows - 1) + hintSpace
        val needsScroll = contentHeight > maxHeight

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = if (needsScroll) Modifier.verticalScroll(rememberScrollState()) else Modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items.chunked(cols).forEach { rowItems ->
                    // 不足一行的行内居中（fillMaxWidth + CenterHorizontally），不靠左/右对齐
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rowItems.forEach { CategoryRing(it, ringSize, stroke, trackColor) }
                    }
                    Spacer(Modifier.height(gap))
                }
                if (showHint) {
                    Text(
                        "在「设置 → 分类管理」可为分类设定预算",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRing(data: CategoryRingData, ringSize: Dp, stroke: Dp, trackColor: Color) {
    val severity = warningSeverity(data.ratio)
    val alarming = data.tone == Budget.Tone.LOW
    val color by animateColorAsState(toneColor(data.tone, severity), tween(420), label = "categoryRing")
    val textMaxWidth = ringSize - stroke * 2 - 14.dp

    Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val r = (size.minDimension - strokePx) / 2f
            // 浅灰轨道 + 颜色弧同半径同包围盒（修复此前弧相对轨道外偏的问题）
            drawCircle(color = trackColor, radius = r, style = Stroke(strokePx))
            if (data.remainingCents <= 0L) {
                drawCircle(color = color, radius = r, style = Stroke(strokePx))
            } else if (data.ratio > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = (360f * data.ratio).coerceAtLeast(12f),
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(strokePx),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AutoScaledText(
                text = data.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxWidth = textMaxWidth,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.warningHeartbeat(alarming, severity),
            ) {
                AutoScaledText(
                    text = Money.formatYuanCompact(data.remainingCents),
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    maxWidth = textMaxWidth,
                )
            }
        }
    }
}

// ---------------- 模式 3：已花费与总存款（中央大字，无圆环） ----------------

@Composable
private fun ModeSpentAndDeposit(snapshot: Budget.Snapshot?, spentCents: Long, totalDepositCents: Long) {
    val ab = MaterialTheme.accBook
    val severity = snapshot?.let { warningSeverity(it.remainingRatio) } ?: 0f
    val spentAlarm = snapshot?.tone == Budget.Tone.LOW
    val spentTarget = if (snapshot != null) {
        toneColor(snapshot.tone, severity)
    } else {
        MaterialTheme.colorScheme.onBackground
    }
    val spentColor by animateColorAsState(spentTarget, tween(420), label = "mode3Spent")
    val depositNegative = totalDepositCents < 0L
    val depositColor by animateColorAsState(
        if (depositNegative) ab.criticalAlert else MaterialTheme.colorScheme.primary,
        tween(420),
        label = "mode3Deposit",
    )
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val textMaxWidth = maxWidth - 32.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "本月已花",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AutoScaledText(
                text = Money.formatYuanCompact(snapshot?.spentCents ?: spentCents),
                style = MaterialTheme.typography.displayMedium,
                color = spentColor,
                maxWidth = textMaxWidth,
                modifier = Modifier.warningHeartbeat(spentAlarm, severity),
            )
            Spacer(Modifier.height(46.dp))
            Text(
                "总存款",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AutoScaledText(
                text = Money.formatYuanCompact(totalDepositCents),
                style = MaterialTheme.typography.displayMedium,
                color = depositColor,
                maxWidth = textMaxWidth,
                modifier = Modifier.warningHeartbeat(depositNegative, if (depositNegative) 0.5f else 0f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "全部账户余额合计",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------- 子组件 ----------------

/**
 * 剩余额度档位 → 用色：充足绿 / 中等黄（warning）/ 紧张红；
 * 红档随 severity（超支越重）向饱和度更高的警报红过渡。
 */
@Composable
private fun toneColor(tone: Budget.Tone, severity: Float = 0f): Color {
    val ab = MaterialTheme.accBook
    return when (tone) {
        Budget.Tone.OK -> ab.income
        Budget.Tone.MID -> ab.warning
        Budget.Tone.LOW -> if (severity > 0f) {
            lerp(ab.critical, ab.criticalAlert, severity.coerceIn(0f, 1f))
        } else {
            ab.critical
        }
    }
}

/**
 * 超支警示强度 0..1：剩余比例 20%（红档起点）为 0，超支 30%（比例 -0.3）封顶 1。
 * 用于心跳/摇晃幅度与红色饱和度（上限即"防止过度动画"的封顶）。
 */
internal fun warningSeverity(remainingRatio: Float): Float =
    ((Budget.TONE_LOW_RATIO - remainingRatio) / 0.5f).coerceIn(0f, 1f)

/**
 * 红色数字警示动效：心跳缩放（幅度/频率随 severity 增强，封顶 1.10 倍、周期不短于 650ms）
 * + 间隔性的快速轻微摇晃（幅度封顶 2.2°，每 2.6s 只摇晃一小段）。
 * 只在 draw 阶段读取动画状态，不触发重组；系统"减少动画"或非告警状态时原样返回。
 */
@Composable
private fun Modifier.warningHeartbeat(active: Boolean, severity: Float): Modifier {
    if (!active || severity <= 0.01f) return this
    val reduced = rememberReducedMotion()
    if (reduced) return this
    val peak = 1f + 0.10f * severity
    val pulseMs = (1400 - 750 * severity).roundToInt().coerceAtLeast(650)
    val shakeDeg = 2.2f * severity

    val transition = rememberInfiniteTransition(label = "warning")
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(pulseMs, easing = LinearEasing)),
        label = "warningPulse",
    )
    val shake = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "warningShake",
    )
    return this.graphicsLayer {
        val p = pulse.value
        val beat = if (p < 0.34f) sin(p / 0.34f * PI).toFloat() else 0f
        val scale = 1f + (peak - 1f) * beat
        scaleX = scale
        scaleY = scale
        val q = shake.value
        rotationZ = if (q < 0.18f) (shakeDeg * sin(q / 0.18f * PI * 3.0)).toFloat() else 0f
    }
}

/**
 * 单行金额文本：超出可用宽度时按比例缩小字号（下限 0.55 倍），
 * 保证长数字不与圆环重叠；配合 K/M/B 紧凑格式，通常无需缩放。
 */
@Composable
private fun AutoScaledText(
    text: String,
    style: TextStyle,
    color: Color,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val maxPx = with(density) { maxWidth.toPx() }
    val fitted = remember(text, style, maxPx) {
        val measured = measurer.measure(text, style, maxLines = 1, softWrap = false)
        val width = measured.size.width.toFloat()
        if (maxPx > 0f && width > maxPx) {
            style.copy(fontSize = style.fontSize * (maxPx / width).coerceAtLeast(0.55f))
        } else {
            style
        }
    }
    Text(
        text = text,
        style = fitted,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 月份胶囊：与账单页月份浏览解耦（首页固定当前自然月） */
@Composable
private fun MonthChip(text: String) {
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 模式指示圆点：当前项为拉长的胶囊；点击可直接切换 */
@Composable
private fun ModeIndicator(current: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(78.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(MODE_COUNT) { i ->
            val active = i == current
            val width by animateDpAsState(if (active) 20.dp else 8.dp, tween(220), label = "modeDotWidth")
            val color by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                tween(220),
                label = "modeDotColor",
            )
            Box(
                Modifier
                    .clickable(enabled = !active) { onSelect(i) }
                    .padding(horizontal = 5.dp, vertical = 14.dp),
            ) {
                Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
            }
        }
    }
}

@Composable
private fun PendingBadge(count: Int, onClick: () -> Unit) {
    val ab = MaterialTheme.accBook
    Box(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(ab.warning.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("待确认 $count", style = MaterialTheme.typography.labelMedium, color = ab.warning)
    }
}

/** 未设预算时的模式内提示（模式①/②保留可切换，只是引导先设置预算） */
@Composable
private fun ModeNeedsBudget(mode: Int, onSetBudget: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 28.dp),
    ) {
        BudgetRingPlaceholder()
        Spacer(Modifier.height(20.dp))
        Text(
            if (mode == 1) "还没有设置月预算" else "还没有分类预算",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (mode == 1) {
                "设置月预算后，这里显示本月还可以花与本日剩余"
            } else {
                "设置月预算后到「设置 → 分类管理」为分类设定预算，这里显示各分类剩余"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onSetBudget) { Text("设置本月预算") }
    }
}

/** 未设预算时的中性占位环（与正式圆环同构，不表达比例） */
@Composable
private fun BudgetRingPlaceholder(modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.accBook.ringTrack
    Canvas(modifier.size(132.dp)) {
        drawCircle(
            color = trackColor,
            radius = (size.minDimension - 14.dp.toPx()) / 2f,
            style = Stroke(14.dp.toPx()),
        )
    }
}

// ---------------- 纯逻辑 ----------------

/**
 * 分类预算 + 当月分类支出 + 总预算 → 分类环数据（按剩余比例升序 = 最紧张的在前，不截断）。
 *
 * 除设了预算的分类外，追加一个"其他"聚合环：预算 = 月总预算 − 已分配分类预算，
 * 支出 = 当月总支出 − 已计入分类环的支出——使 Σ分类剩余 + 其他剩余 = 月总剩余（模式①口径）。
 * 仅当"其他"预算 > 0 时出现（全部分类预算超过总预算时无处归属，跳过）。
 * 仅支出分类与仍存在的分类参与；分类预算未设置的分类不计入已分配。
 */
internal fun buildCategoryRings(
    budgets: Map<Long, Long>,
    sums: List<CategorySumRow>,
    categories: List<CategoryEntity>,
    monthlyBudgetCents: Long,
    totalSpentCents: Long,
): List<CategoryRingData> {
    val byId = categories.associateBy { it.id }
    val spentById = sums.associate { it.categoryId to it.totalCents }
    val budgeted = budgets.entries.mapNotNull { (id, budget) ->
        if (budget <= 0L) return@mapNotNull null
        val category = byId[id] ?: return@mapNotNull null
        if (category.type != TxType.EXPENSE) return@mapNotNull null
        category to budget
    }

    val rings = budgeted.map { (category, budget) ->
        ringOf(category.name, budget, spentById[category.id] ?: 0L, isOther = false)
    }.toMutableList()

    val allocatedBudget = budgeted.sumOf { it.second }
    val allocatedSpent = budgeted.sumOf { (category, _) -> spentById[category.id] ?: 0L }
    val otherBudget = monthlyBudgetCents - allocatedBudget
    if (monthlyBudgetCents > 0L && otherBudget > 0L) {
        rings += ringOf("其他", otherBudget, totalSpentCents - allocatedSpent, isOther = true)
    }
    return rings.sortedBy { it.ratio }
}

private fun ringOf(name: String, budgetCents: Long, spentCents: Long, isOther: Boolean): CategoryRingData {
    val remaining = budgetCents - spentCents
    val ratio = remaining.toDouble() / budgetCents.toDouble()
    return CategoryRingData(
        name = name,
        remainingCents = remaining,
        ratio = ratio.toFloat(),
        tone = Budget.remainingTone(ratio.toFloat()),
        isOther = isOther,
    )
}
