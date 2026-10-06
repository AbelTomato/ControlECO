package com.accbook.app.ui.home

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.budget.Budget
import com.accbook.app.data.budget.HomeSettlement
import com.accbook.app.data.budget.MonthCashFlow
import com.accbook.app.ui.common.BudgetEditDialog
import com.accbook.app.ui.theme.AccBookColors
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页：金币堆资源 HUD。
 *
 * 金币堆 = 剩余预算（比例映射到有限金币数，绝不与金额 1:1）；堆正下方为第一视觉信息
 * "剩余预算"，其下是权重更低的"本月已花"；右上角为游戏资源条式的"今日可用"。
 *
 * 动画状态机（支出/收入严格独立、先支出后收入）：
 *   Idle → PileEntrance → [ExpenseSettlement → IncomeSettlement] → FinalSettled
 * 不存在的阶段自动跳过；settle 后强制显示数据库真实值，动画永不作为数据真源。
 */
private enum class HomeStage { Idle, PileEntrance, ExpenseSettlement, IncomeSettlement, FinalSettled }

private data class DeltaBanner(val text: String, val color: Color)

@Composable
fun HomeScreen(
    vm: LedgerViewModel,
    onQuickAdd: () -> Unit,
    onOpenPending: () -> Unit,
) {
    val cash by vm.homeCashFlow.collectAsStateWithLifecycle()
    val budgetCents by vm.monthlyBudgetCents.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val animEnabled by vm.homeAnimationsEnabled.collectAsStateWithLifecycle()
    val reducedMotion = rememberReducedMotion()
    val animate = animEnabled && !reducedMotion
    val today = remember { LocalDate.now() }
    val currentMonth = remember { YearMonth.now() }
    val ab = MaterialTheme.accBook
    val palette = remember(ab) { coinPalette(ab) }
    var showBudgetDialog by remember { mutableStateOf(false) }

    if (budgetCents <= 0L) {
        NoBudgetContent(
            cash = cash,
            today = today,
            pendingCount = pendingCount,
            palette = palette,
            onSetBudget = { showBudgetDialog = true },
            onQuickAdd = onQuickAdd,
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

    val fx = remember { CoinFxController() }
    val remainingAnim = remember { Animatable(0f) }
    val expenseAnim = remember { Animatable(0f) }
    var stage by remember { mutableStateOf(HomeStage.Idle) }
    var banner by remember { mutableStateOf<DeltaBanner?>(null) }
    var session by remember { mutableStateOf<HomeSettlement.HomeSession?>(null) }

    val realRemaining = cash?.let { budgetCents + it.incomeCents - it.expenseCents } ?: 0L
    val realExpense = cash?.expenseCents ?: 0L
    val status = cash?.let { Budget.compute(budgetCents, it.expenseCents, it.incomeCents, today) }
    val daysLeft = status?.daysLeft ?: 1

    // 进入 Home：创建一次结算会话（快照消费即记录，幂等——旋转屏/重组/进程恢复都不会重播事件）
    LaunchedEffect(cash) {
        val c = cash ?: return@LaunchedEffect
        if (session == null) {
            session = vm.beginHomeSession(budgetCents, c.expenseCents, c.incomeCents, currentMonth)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        val pileLayout = remember(maxWidth) { CoinPileLayout.build(maxWidth.value, CoinPileSpecs.COIN_RADIUS_DP) }
        // 粒子出生高度 = 画布顶部附近（实测画布尺寸，避免估算偏差）；cy 坐标系原点在堆底基线
        val density = LocalDensity.current
        var canvasHeightDp by remember { mutableStateOf(maxHeight.value) }
        val spawnY = -(canvasHeightDp - CoinPileSpecs.GROUND_PAD_DP) + 12f

        fun pileCountFor(remainingCents: Long): Float =
            (remainingCents.toFloat() / budgetCents).coerceIn(0f, 1f) * pileLayout.size

        // 动画状态机：按数据跳过不存在的阶段；支出与收入不同时播放
        LaunchedEffect(session, animate) {
            val s = session ?: return@LaunchedEffect
            remainingAnim.snapTo(s.from.remainingCents().toFloat())
            expenseAnim.snapTo(s.from.expenseCents.toFloat())
            fx.pileCount.snapTo(pileCountFor(s.from.remainingCents()))

            if (!animate) {
                // Reduced Motion / 用户关闭：金币堆轻微 reveal，数字快速到位，±¥ 简短出现
                fx.reveal(240)
                stage = HomeStage.FinalSettled
                if (s.hasDeltas) {
                    banner = reducedBanner(s, ab)
                    delay(1600)
                    banner = null
                }
                return@LaunchedEffect
            }

            if (!s.hasDeltas) {
                stage = HomeStage.PileEntrance
                fx.reveal()
                val target = pileCountFor(s.to.remainingCents()).toInt()
                if (target > 0) {
                    val n = minOf(14, maxOf(6, target / 3))
                    val indices = ((target - 1) downTo (target - n).coerceAtLeast(0)).toList()
                    fx.fallOntoSlots(pileLayout, indices, spawnY)
                }
                stage = HomeStage.FinalSettled
                return@LaunchedEffect
            }

            stage = HomeStage.PileEntrance
            fx.reveal(220)

            if (s.expenseDelta > 0) {
                stage = HomeStage.ExpenseSettlement
                banner = DeltaBanner("− " + Money.formatYuanGrouped(s.expenseDelta), ab.critical)
                val fromRemaining = s.from.remainingCents()
                val newRemaining = fromRemaining - s.expenseDelta
                val oldF = pileCountFor(fromRemaining)
                val newF = pileCountFor(newRemaining)
                val intensity = (s.expenseDelta.toFloat() / budgetCents).coerceIn(0.15f, 1f)
                val origins = burstOrigins(pileLayout, oldF, newF, HomeSettlement.moneyToVisualCoinCount(s.expenseDelta))
                coroutineScope {
                    launch { remainingAnim.animateTo(newRemaining.toFloat(), tween(650, easing = FastOutSlowInEasing)) }
                    launch { expenseAnim.animateTo((s.from.expenseCents + s.expenseDelta).toFloat(), tween(650, easing = FastOutSlowInEasing)) }
                    launch { fx.pileCount.animateTo(newF, tween(650, easing = FastOutSlowInEasing)) }
                    launch { fx.burstFrom(origins, intensity) }
                }
            }

            if (s.incomeDelta > 0) {
                delay(200) // 支出 → 极短停顿 → 收入
                stage = HomeStage.IncomeSettlement
                banner = DeltaBanner("+ " + Money.formatYuanGrouped(s.incomeDelta), ab.income)
                val afterExpense = s.from.remainingCents() - s.expenseDelta
                val newRemaining = afterExpense + s.incomeDelta
                val oldF = if (s.expenseDelta > 0) pileCountFor(afterExpense) else fx.pileCount.value
                val newF = pileCountFor(newRemaining)
                coroutineScope {
                    launch { remainingAnim.animateTo(newRemaining.toFloat(), tween(600, easing = FastOutSlowInEasing)) }
                    launch { fx.pileCount.animateTo(newF, tween(750, easing = FastOutSlowInEasing)) }
                    launch {
                        fx.fallOntoSlots(
                            layout = pileLayout,
                            slotIndices = incomeSlotIndices(
                                capacity = pileLayout.size,
                                oldF = oldF,
                                newF = newF,
                                n = HomeSettlement.moneyToVisualCoinCount(s.incomeDelta),
                            ),
                            spawnYDp = spawnY,
                        )
                    }
                }
            }

            banner = null
            stage = HomeStage.FinalSettled
        }

        // 驻留期间数据变化（悬浮卡确认等）：settle 后堆平滑跟随真实值
        LaunchedEffect(realRemaining, stage) {
            if (stage == HomeStage.FinalSettled && session != null) {
                fx.pileCount.animateTo(pileCountFor(realRemaining), tween(400, easing = FastOutSlowInEasing))
            }
        }

        // 展示值：settle 阶段强制真实值（动画中断/浮点误差都不可能造成金额错误）
        val settled = stage == HomeStage.Idle || stage == HomeStage.FinalSettled
        val shownRemaining = if (settled) realRemaining else remainingAnim.value.roundToLong()
        val shownExpense = if (settled) realExpense else expenseAnim.value.roundToLong()
        val shownToday = (shownRemaining / daysLeft).coerceAtLeast(0L)

        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${today.monthValue}月", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                if (pendingCount > 0) {
                    PendingBadge(count = pendingCount, onClick = onOpenPending)
                }
                Spacer(Modifier.weight(1f))
                TodayHud(cents = shownToday, color = hudColor(status, ab), palette = palette)
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { canvasHeightDp = it.height / density.density },
            ) {
                CoinPileView(
                    fx = fx,
                    layout = pileLayout,
                    palette = palette,
                    modifier = Modifier.fillMaxSize(),
                    visible = session != null,
                )
                DeltaBannerView(banner = banner, modifier = Modifier.align(Alignment.TopCenter))
            }

            Column(
                Modifier.fillMaxWidth().alpha(if (session != null) 1f else 0f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    Money.formatYuanGrouped(shownRemaining),
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                    color = if (status?.state == Budget.State.CRITICAL) {
                        ab.critical
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                )
                Text(
                    "剩余预算",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "本月已花 ${Money.formatYuanGrouped(shownExpense)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))
            }

            Button(
                onClick = onQuickAdd,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("记一笔", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(24.dp))
        }

        if (showBudgetDialog) {
            BudgetEditDialog(
                currentCents = budgetCents,
                onSave = { vm.setMonthlyBudget(it); showBudgetDialog = false },
                onDismiss = { showBudgetDialog = false },
            )
        }
    }
}

// ---------------- 子组件 ----------------

@Composable
private fun TodayHud(cents: Long, color: Color, palette: CoinPalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(18.dp)) {
            val r = 8.dp.toPx()
            drawCoin(size.width / 2f, size.height / 2f, r, r, 0f, palette)
        }
        Spacer(Modifier.width(7.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Money.formatYuanGrouped(cents), style = MaterialTheme.typography.titleSmall, color = color)
            Text(
                "今日可用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeltaBannerView(banner: DeltaBanner?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(banner) }
    if (banner != null) shown = banner
    AnimatedVisibility(
        visible = banner != null,
        enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { -it / 2 },
        exit = fadeOut(tween(260)),
        modifier = modifier,
    ) {
        shown?.let { b ->
            Text(
                b.text,
                style = MaterialTheme.typography.titleMedium,
                color = b.color,
                modifier = Modifier.padding(top = 6.dp),
            )
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

/** 未设预算：金币 + 本月已花 + 引导（金币堆无比例意义，不展示） */
@Composable
private fun NoBudgetContent(
    cash: MonthCashFlow?,
    today: LocalDate,
    pendingCount: Int,
    palette: CoinPalette,
    onSetBudget: () -> Unit,
    onQuickAdd: () -> Unit,
    onOpenPending: () -> Unit,
) {
    val spent = cash?.expenseCents ?: 0L
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${today.monthValue}月", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            if (pendingCount > 0) {
                PendingBadge(count = pendingCount, onClick = onOpenPending)
            }
        }
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.size(110.dp).align(Alignment.CenterHorizontally)) {
            val r = 44.dp.toPx()
            drawCoin(size.width / 2f, size.height / 2f, r, r, 0f, palette)
        }
        Spacer(Modifier.height(28.dp))
        Text(
            Money.formatYuanGrouped(spent),
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
        Button(
            onClick = onQuickAdd,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("记一笔", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- 纯辅助 ----------------

private fun hudColor(status: Budget.Snapshot?, ab: AccBookColors): Color = when (status?.state) {
    Budget.State.CRITICAL -> ab.critical
    Budget.State.WARNING -> ab.warning
    else -> ab.goldDeep
}

/** 支出弹出金币的出发槽位：从堆顶被移除的 coins 中自顶向下取 */
private fun burstOrigins(layout: List<PileCoinSpec>, oldF: Float, newF: Float, n: Int): List<PileCoinSpec> {
    if (layout.isEmpty() || n <= 0) return emptyList()
    val hi = (floor(oldF).toInt() - 1).coerceAtLeast(0)
    val lo = ceil(newF).toInt().coerceIn(0, layout.size - 1)
    val indices = ArrayList<Int>(n)
    var i = hi
    while (i >= lo && indices.size < n) {
        indices += i
        i--
    }
    if (indices.isEmpty()) indices += hi // 变化不足一枚时也从顶部弹一枚，保证反馈
    return indices.map { layout[it % layout.size] }
}

/** 收入落币的目标槽位：堆新增的槽位；堆已满（比例封顶）时落到顶部装饰位 */
private fun incomeSlotIndices(capacity: Int, oldF: Float, newF: Float, n: Int): List<Int> {
    if (n <= 0 || capacity <= 0) return emptyList()
    val from = floor(oldF).toInt()
    val to = ceil(newF).toInt()
    val added = (from until minOf(to, capacity)).toList()
    if (added.isNotEmpty()) return added.take(n)
    val top = ((capacity - 1) downTo (capacity - 3).coerceAtLeast(0)).toList()
    return top.take(n.coerceAtMost(3))
}

private fun reducedBanner(s: HomeSettlement.HomeSession, ab: AccBookColors): DeltaBanner {
    val parts = buildList {
        if (s.expenseDelta > 0) add("−" + Money.formatYuanGrouped(s.expenseDelta))
        if (s.incomeDelta > 0) add("+" + Money.formatYuanGrouped(s.incomeDelta))
    }
    val color = when {
        s.expenseDelta > 0 && s.incomeDelta == 0L -> ab.critical
        s.incomeDelta > 0 && s.expenseDelta == 0L -> ab.income
        else -> ab.goldDeep
    }
    return DeltaBanner(parts.joinToString("   "), color)
}

/** 系统减少动画设置（动画时长缩放为 0）时走极简路径 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
