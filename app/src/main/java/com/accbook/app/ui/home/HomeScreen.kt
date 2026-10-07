package com.accbook.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 首页：三个预览模式，左右滑动或点击中心切换（无自动播放，一切动画由交互触发）。
 *   1. 总剩余预算 —— 中心大圆环（弧长 = 剩余比例，绿/蓝/红随额度变化），环内"本月还可以花"，
 *      环下方大字"本日剩余"；
 *   2. 分类剩余预算 —— 多个细环，每个支出分类一个（带分类预算的），分类名与剩余额在环内；
 *   3. 已花费与总存款 —— 无圆环，中央大字（本月已花 / 全部账户余额合计）。
 *
 * 切换动效：点击 = 收缩-回弹的"弹动"+ 滑动；滑动 = 跟手横移；圆点指示当前模式。
 * 颜色随数据变化做过渡（绿-蓝-红额度档位切换时可见的颜色提醒）。数值直接读数据库真实值。
 * 系统"减少动画"时全部降级为瞬时切换。
 */
private const val MODE_COUNT = 3
private val CategoryRingSize: Dp = 118.dp

/** 分类环数据：分类预算（分类管理设置）+ 当月该分类支出 → 剩余与额度档位 */
internal data class CategoryRingData(
    val name: String,
    val remainingCents: Long,
    val ratio: Float,
    val tone: Budget.Tone,
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

    if (budgetCents <= 0L) {
        NoBudgetContent(
            spentCents = spentCents,
            today = today,
            pendingCount = pendingCount,
            onSetBudget = { showBudgetDialog = true },
            onOpenPending = onOpenPending,
        )
        if (showBudgetDialog) {
            BudgetEditDialog(
                currentCents = 0L,
                onSave = { vm.setMonthlyBudget(it); showBudgetDialog = false },
                onDismiss = { showBudgetDialog = false },
            )
        }
        return
    }

    // budget > 0 时 compute 必返回非空，此分支仅为编译器收窄
    val s = snapshot ?: return

    val reducedMotion = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { MODE_COUNT }
    val pop = remember { Animatable(1f) }

    // 点击中心：切到下一模式——先收缩再回弹（"弹动"），页面同时横移到位
    val advance: () -> Unit = {
        if (!pagerState.isScrollInProgress) {
            val next = (pagerState.currentPage + 1) % MODE_COUNT
            scope.launch {
                if (reducedMotion) {
                    pagerState.scrollToPage(next)
                } else {
                    launch {
                        pagerState.animateScrollToPage(
                            next,
                            animationSpec = tween(340, easing = FastOutSlowInEasing),
                        )
                    }
                    launch {
                        pop.animateTo(0.93f, tween(90))
                        pop.animateTo(
                            1f,
                            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                        )
                    }
                }
            }
        }
    }
    val latestAdvance by rememberUpdatedState(advance)

    val rings = remember(categoryBudgets, categorySums, categories) {
        buildCategoryRings(categoryBudgets, categorySums, categories)
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

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer {
                    // 点击弹动的缩放只在 draw 阶段读取，不触发重组
                    scaleX = pop.value
                    scaleY = pop.value
                },
        ) { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { latestAdvance() } },
                contentAlignment = Alignment.Center,
            ) {
                when (page) {
                    0 -> ModeRemaining(s)
                    1 -> ModeCategories(rings)
                    else -> ModeSpentAndDeposit(s, totalDepositCents)
                }
            }
        }

        ModeIndicator(
            current = pagerState.currentPage,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
        )
    }
}

// ---------------- 模式 1：总剩余预算（大圆环 + 本日剩余） ----------------

@Composable
private fun ModeRemaining(s: Budget.Snapshot) {
    val ab = MaterialTheme.accBook
    val trackColor = ab.hairline
    val ringColor by animateColorAsState(toneColor(s.tone), tween(420), label = "mode1Ring")
    val ratio = s.remainingRatio.coerceIn(0f, 1f)
    val percent = (s.remainingRatio * 100).roundToInt().coerceIn(0, 100)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(232.dp)) {
                val stroke = 25.dp.toPx()
                val radius = (size.minDimension - stroke) / 2f
                drawCircle(color = trackColor, radius = radius, style = Stroke(stroke))
                when {
                    s.remainingCents <= 0L -> drawCircle(color = ringColor, radius = radius, style = Stroke(stroke))
                    ratio > 0f -> drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * ratio,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "本月还可以花",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Money.formatYuanGrouped(s.remainingCents),
                    style = MaterialTheme.typography.displaySmall,
                    color = ringColor,
                )
                Text("剩余 $percent%", style = MaterialTheme.typography.titleSmall, color = ringColor)
            }
        }
        Spacer(Modifier.height(40.dp))
        if (s.remainingCents > 0L) {
            Text(
                "本日剩余",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Money.formatYuanGrouped(s.suggestedDailyCents),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        } else {
            Text("本月已超支", style = MaterialTheme.typography.bodyMedium, color = ab.critical)
            Text(
                Money.formatYuanGrouped(-s.remainingCents),
                style = MaterialTheme.typography.displayMedium,
                color = ab.critical,
            )
        }
    }
}

// ---------------- 模式 2：分类剩余预算（多环，无轨道无底色） ----------------

@Composable
private fun ModeCategories(items: List<CategoryRingData>) {
    if (items.isEmpty()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Text("还没有分类预算", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "到「设置 → 分类管理」为常用支出分类设置每月预算，这里会用圆环显示各分类剩余",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items.chunked(2).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.size == 1) Spacer(Modifier.size(CategoryRingSize))
                row.forEach { CategoryRing(it) }
                if (row.size == 1) Spacer(Modifier.size(CategoryRingSize))
            }
        }
    }
}

@Composable
private fun CategoryRing(data: CategoryRingData) {
    val color by animateColorAsState(toneColor(data.tone), tween(420), label = "categoryRing")
    val percent = (data.ratio * 100).roundToInt().coerceIn(0, 100)
    Box(Modifier.size(CategoryRingSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 11.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f
            when {
                data.remainingCents <= 0L -> drawCircle(color = color, radius = radius, style = Stroke(stroke))
                data.ratio > 0f -> drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = (360f * data.ratio).coerceAtLeast(12f),
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                data.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 84.dp),
            )
            Text(
                Money.formatYuanGrouped(data.remainingCents),
                style = MaterialTheme.typography.titleMedium,
                color = color,
                maxLines = 1,
            )
            Text("$percent%", style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

// ---------------- 模式 3：已花费与总存款（中央大字，无圆环） ----------------

@Composable
private fun ModeSpentAndDeposit(s: Budget.Snapshot, totalDepositCents: Long) {
    val ab = MaterialTheme.accBook
    val spentColor by animateColorAsState(toneColor(s.tone), tween(420), label = "mode3Spent")
    val depositColor by animateColorAsState(
        if (totalDepositCents < 0L) ab.critical else MaterialTheme.colorScheme.primary,
        tween(420),
        label = "mode3Deposit",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "本月已花",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            Money.formatYuanGrouped(s.spentCents),
            style = MaterialTheme.typography.displayMedium,
            color = spentColor,
        )
        Spacer(Modifier.height(46.dp))
        Text(
            "总存款",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            Money.formatYuanGrouped(totalDepositCents),
            style = MaterialTheme.typography.displayMedium,
            color = depositColor,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "全部账户余额合计",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------- 子组件 ----------------

/** 剩余额度档位 → 用色：充足绿（收入绿同源）/ 中等蓝（主色）/ 紧张红 */
@Composable
private fun toneColor(tone: Budget.Tone): Color = when (tone) {
    Budget.Tone.OK -> MaterialTheme.accBook.income
    Budget.Tone.MID -> MaterialTheme.colorScheme.primary
    Budget.Tone.LOW -> MaterialTheme.accBook.critical
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

/** 未设预算：中性占位环 + 本月已花 + 引导设置（无预算时比例无意义） */
@Composable
private fun NoBudgetContent(
    spentCents: Long,
    today: LocalDate,
    pendingCount: Int,
    onSetBudget: () -> Unit,
    onOpenPending: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            MonthChip("${today.year}年${today.monthValue}月")
            Spacer(Modifier.weight(1f))
            if (pendingCount > 0) {
                PendingBadge(count = pendingCount, onClick = onOpenPending)
            }
        }
        Spacer(Modifier.weight(1f))
        BudgetRingPlaceholder(modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(28.dp))
        Text(
            Money.formatYuanGrouped(spentCents),
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "本月已花 · 设置预算后这里显示还能花多少",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onSetBudget, modifier = Modifier.fillMaxWidth()) { Text("设置本月预算") }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(78.dp))
    }
}

/** 未设预算时的中性占位环（与正式圆环同构，不表达比例） */
@Composable
private fun BudgetRingPlaceholder(modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
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
 * 分类预算 + 当月分类支出 → 分类环数据（按剩余比例升序 = 最紧张的在前），最多 4 个。
 * 仅支出分类；分类预算未设置或分类已删除的跳过。
 */
internal fun buildCategoryRings(
    budgets: Map<Long, Long>,
    sums: List<CategorySumRow>,
    categories: List<CategoryEntity>,
): List<CategoryRingData> {
    if (budgets.isEmpty()) return emptyList()
    val byId = categories.associateBy { it.id }
    val spentById = sums.associate { it.categoryId to it.totalCents }
    return budgets.entries
        .mapNotNull { (id, budget) ->
            if (budget <= 0L) return@mapNotNull null
            val category = byId[id] ?: return@mapNotNull null
            if (category.type != TxType.EXPENSE) return@mapNotNull null
            val spent = spentById[id] ?: 0L
            val remaining = budget - spent
            val ratio = remaining.toDouble() / budget.toDouble()
            CategoryRingData(
                name = category.name,
                remainingCents = remaining,
                ratio = ratio.toFloat(),
                tone = Budget.remainingTone(ratio.toFloat()),
            )
        }
        .sortedBy { it.ratio }
        .take(4)
}
