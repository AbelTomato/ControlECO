package com.accbook.app.ui.home

import android.content.Context
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.budget.Budget
import com.accbook.app.data.budget.Denomination
import com.accbook.app.data.budget.HomeSettlement
import com.accbook.app.data.budget.MonthCashFlow
import com.accbook.app.ui.common.BudgetEditDialog
import com.accbook.app.ui.theme.AccBookColors
import com.accbook.app.ui.theme.NoteColor
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页：纸币堆资源 HUD。
 *
 * 纸币堆 = 剩余预算（比例映射到有限张数，绝不与金额 1:1；大额用堆叠的 ¥100 表示）；
 * 堆正下方为第一视觉信息"剩余预算"，其下是权重更低的"本月已花"；
 * 右上角为资源条式的"今日可用"纸票。
 *
 * 动画状态机（支出/收入严格独立、先支出后收入）：
 *   Idle → PileEntrance → [ExpenseSettlement → IncomeSettlement] → FinalSettled
 * 支出 = 从堆顶抽出纸币后消散；收入 = 纸币在堆中直接出现；settle 后强制显示数据库真实值，
 * 动画永不作为数据真源。
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
    val todayState = remember { mutableStateOf(LocalDate.now()) }
    val monthState = remember { mutableStateOf(YearMonth.now()) }
    val today = todayState.value
    val currentMonth = monthState.value
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                todayState.value = LocalDate.now()
                monthState.value = YearMonth.now()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val ab = MaterialTheme.accBook
    val noteColors = ab.noteColors
    var showBudgetDialog by remember { mutableStateOf(false) }

    if (budgetCents <= 0L) {
        NoBudgetContent(
            cash = cash,
            today = today,
            pendingCount = pendingCount,
            noteColors = noteColors,
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

    val fx = remember { CashFxController() }
    val remainingAnim = remember { Animatable(0f) }
    val expenseAnim = remember { Animatable(0f) }
    var stage by remember { mutableStateOf(HomeStage.Idle) }
    var banner by remember { mutableStateOf<DeltaBanner?>(null) }
    var session by remember { mutableStateOf<HomeSettlement.HomeSession?>(null) }
    var entranceKey by remember { mutableStateOf(1L) }
    var settlementConsumed by remember { mutableStateOf(false) }

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
        val pileLayout = remember(maxWidth) { CashStackLayout.build(maxWidth.value) }

        // 后台恢复时重建入口动画（entranceKey 由 ON_RESUME 递增）
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    entranceKey += 1L
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }

        fun pileCountFor(remainingCents: Long): Float =
            HomeSettlement.remainingToPileCount(remainingCents, budgetCents, CashPileSpecs.CAPACITY)

        // 每次 Home 进入/后台恢复先用真实纸币原地弹出重新形成当前资源堆。
        LaunchedEffect(entranceKey, session, animate) {
            val s = session ?: return@LaunchedEffect
            val targetRemaining = if (!settlementConsumed && s.hasDeltas) {
                s.from.remainingCents()
            } else {
                s.to.remainingCents()
            }
            val target = pileCountFor(targetRemaining).toInt()
            try {
                stage = HomeStage.PileEntrance
                fx.formPile(pileLayout, target)
                if (!settlementConsumed && s.hasDeltas) {
                    settlementConsumed = true
                    remainingAnim.snapTo(s.from.remainingCents().toFloat())
                    expenseAnim.snapTo(s.from.expenseCents.toFloat())
                    if (!animate) {
                        stage = HomeStage.FinalSettled
                        banner = reducedBanner(s, ab)
                        delay(1200)
                        banner = null
                        return@LaunchedEffect
                    }
                    if (s.expenseDelta > 0) {
                        stage = HomeStage.ExpenseSettlement
                        banner = DeltaBanner("− " + Money.formatYuanGrouped(s.expenseDelta), ab.critical)
                        val fromRemaining = s.from.remainingCents()
                        val newRemaining = fromRemaining - s.expenseDelta
                        val oldF = pileCountFor(fromRemaining)
                        val newF = pileCountFor(newRemaining)
                        val intensity = (s.expenseDelta.toFloat() / budgetCents).coerceIn(0.15f, 1f)
                        val (count, denom) = HomeSettlement.settlementNotePlan(s.expenseDelta)
                        coroutineScope {
                            launch { remainingAnim.animateTo(newRemaining.toFloat(), tween(560, easing = FastOutSlowInEasing)) }
                            launch { expenseAnim.animateTo((s.from.expenseCents + s.expenseDelta).toFloat(), tween(560, easing = FastOutSlowInEasing)) }
                            launch { fx.stackCount.animateTo(newF, tween(560, easing = FastOutSlowInEasing)) }
                            launch { fx.pullOutNotes(pileLayout, removedFillIndices(oldF, newF, count), denom, intensity) }
                        }
                    }
                    if (s.incomeDelta > 0) {
                        delay(140)
                        stage = HomeStage.IncomeSettlement
                        banner = DeltaBanner("+ " + Money.formatYuanGrouped(s.incomeDelta), ab.income)
                        val afterExpense = s.from.remainingCents() - s.expenseDelta
                        val newRemaining = afterExpense + s.incomeDelta
                        val oldF = if (s.expenseDelta > 0) pileCountFor(afterExpense) else fx.stackCount.value
                        val newF = pileCountFor(newRemaining)
                        val (count, denom) = HomeSettlement.settlementNotePlan(s.incomeDelta)
                        coroutineScope {
                            launch { remainingAnim.animateTo(newRemaining.toFloat(), tween(540, easing = FastOutSlowInEasing)) }
                            launch { fx.stackCount.animateTo(newF, tween(620, easing = FastOutSlowInEasing)) }
                            launch {
                                fx.popInNotes(
                                    layout = pileLayout,
                                    fillIndices = addedFillIndices(oldF, newF, count),
                                    denom = denom,
                                    holdMs = 460L, // 堆数量动画追平后再淡出粒子，避免闪断
                                )
                            }
                        }
                    }
                    banner = null
                }
                stage = HomeStage.FinalSettled
            } finally {
                fx.clearParticles()
            }
        }

        // 驻留期间数据变化（悬浮卡确认等）：settle 后堆平滑跟随真实值
        LaunchedEffect(realRemaining, stage) {
            if (stage == HomeStage.FinalSettled && session != null) {
                fx.stackCount.animateTo(pileCountFor(realRemaining), tween(400, easing = FastOutSlowInEasing))
            }
        }

        // 展示值：settle 阶段强制真实值（动画中断/浮点误差都不可能造成金额错误）
        val settled = stage == HomeStage.Idle || stage == HomeStage.FinalSettled
        val shownRemaining = if (settled) realRemaining else remainingAnim.value.roundToLong()
        val shownExpense = if (settled) realExpense else expenseAnim.value.roundToLong()
        val shownToday = (shownRemaining / daysLeft).coerceAtLeast(0L)

        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${today.monthValue}月", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                if (pendingCount > 0) {
                    PendingBadge(count = pendingCount, onClick = onOpenPending)
                }
                Spacer(Modifier.weight(1f))
                TodayHud(cents = shownToday, color = hudColor(status, ab), noteColors = noteColors)
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                CashPileView(
                    fx = fx,
                    layout = pileLayout,
                    noteColors = noteColors,
                    modifier = Modifier.fillMaxSize(),
                    visible = session != null,
                )
                DeltaBannerView(banner = banner, modifier = Modifier.align(Alignment.TopCenter))
            }

            Column(
                Modifier.fillMaxWidth().height(158.dp).alpha(if (session != null) 1f else 0f),
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
private fun TodayHud(cents: Long, color: Color, noteColors: List<NoteColor>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BanknoteMark(noteColors = noteColors, width = 24.dp)
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

/** 未设预算：纸币 + 本月已花 + 引导（资源堆无比例意义，不展示） */
@Composable
private fun NoBudgetContent(
    cash: MonthCashFlow?,
    today: LocalDate,
    pendingCount: Int,
    noteColors: List<NoteColor>,
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
        BanknoteMark(
            noteColors = noteColors,
            width = 150.dp,
            withLabel = true,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
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

/**
 * 支出抽出的填充序号：堆顶（最高填充序号 = 各叠顶层）向下取 n 张。
 * 数量变化不足一张时也抽一张，保证反馈。
 */
private fun removedFillIndices(oldF: Float, newF: Float, n: Int): List<Int> {
    if (n <= 0) return emptyList()
    val hi = ceil(oldF).toInt() - 1
    val lo = ceil(newF).toInt()
    val indices = ArrayList<Int>(n)
    var i = hi
    while (i >= lo && i >= 0 && indices.size < n) {
        indices += i
        i--
    }
    if (indices.isEmpty()) indices += hi.coerceAtLeast(0)
    return indices
}

/** 收入出现的填充序号：新增槽位自低向高；堆已满（比例封顶）时顶部示意性弹出后淡出 */
private fun addedFillIndices(oldF: Float, newF: Float, n: Int): List<Int> {
    if (n <= 0) return emptyList()
    val lo = ceil(oldF).toInt()
    val hi = minOf(ceil(newF).toInt(), CashPileSpecs.CAPACITY)
    val added = (lo until hi).toList()
    if (added.isNotEmpty()) return added.take(n)
    return ((CashPileSpecs.CAPACITY - 1) downTo (CashPileSpecs.CAPACITY - 3).coerceAtLeast(0)).toList().take(minOf(n, 3))
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
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var reducedMotion by remember { mutableStateOf(readReducedMotion(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reducedMotion = readReducedMotion(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return reducedMotion
}

private fun readReducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)
