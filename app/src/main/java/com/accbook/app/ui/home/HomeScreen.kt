package com.accbook.app.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.budget.Budget
import com.accbook.app.ui.common.BudgetEditDialog
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * 首页：游戏 HUD 式预算视图（克制版）。
 * 第一层级：剩余可用金额（金币 + 大字）；第二层级：本月已花 + 预算消耗进度；
 * 第三层级：今天建议最多花。三态 Normal/Warning/Critical 只靠颜色与简短文案表达。
 */
@Composable
fun HomeScreen(
    vm: LedgerViewModel,
    onQuickAdd: () -> Unit,
    onOpenPending: () -> Unit,
) {
    val budgetCents by vm.monthlyBudgetCents.collectAsStateWithLifecycle()
    val spentCents by vm.homeNetSpent.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    var showBudgetDialog by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val snapshot = remember(budgetCents, spentCents, today) {
        Budget.compute(budgetCents, spentCents, today)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${today.monthValue}月", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (pendingCount > 0) {
                PendingBadge(count = pendingCount, onClick = onOpenPending)
            }
        }
        Spacer(Modifier.height(44.dp))

        if (snapshot != null) {
            BudgetHero(snapshot)
        } else {
            NoBudgetHero(spentCents, onSetBudget = { showBudgetDialog = true })
        }

        Spacer(Modifier.height(44.dp))
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

/** 消费状态 → 强调色（Normal 金 / Warning 琥珀 / Critical 克制的红） */
@Composable
fun budgetStateColor(state: Budget.State): Color = when (state) {
    Budget.State.NORMAL -> MaterialTheme.accBook.goldDeep
    Budget.State.WARNING -> MaterialTheme.accBook.warning
    Budget.State.CRITICAL -> MaterialTheme.accBook.critical
}

@Composable
private fun BudgetHero(s: Budget.Snapshot) {
    val ab = MaterialTheme.accBook
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoinMark(coinSize = 84.dp, progress = s.spentRatio, state = s.state)
            Spacer(Modifier.width(24.dp))
            Column {
                RemainingText(s.remainingCents, s.state)
                Spacer(Modifier.height(2.dp))
                Text(
                    "可用余额",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(36.dp))
        BudgetProgress(s)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(budgetStateColor(s.state)))
            Spacer(Modifier.width(8.dp))
            Text(
                if (s.remainingCents > 0) {
                    "今天建议最多花 ${Money.formatYuanGrouped(s.suggestedDailyCents)} · 还剩 ${s.daysLeft} 天"
                } else {
                    "本月预算已用完，超支 ${Money.formatYuanGrouped(-s.remainingCents)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 第二层级：预算消耗进度条 + 已花/预算数字 */
@Composable
private fun BudgetProgress(s: Budget.Snapshot) {
    val ab = MaterialTheme.accBook
    val fraction by animateFloatAsState(
        targetValue = s.spentRatio.coerceIn(0f, 1f),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "budgetProgress",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(ab.hairline),
    ) {
        if (s.spentRatio > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(budgetStateColor(s.state)),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("本月已花 ${Money.formatYuanGrouped(s.spentCents)}", style = MaterialTheme.typography.titleSmall)
        Text(
            "预算 ${Money.formatYuanGrouped(s.budgetCents)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 剩余金额：变化时轻微计数动画（余额被消耗的直观反馈），Critical 转红 */
@Composable
private fun RemainingText(cents: Long, state: Budget.State) {
    val animated by animateFloatAsState(
        targetValue = cents.toFloat(),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "remaining",
    )
    val shown = animated.roundToLong()
    Text(
        Money.formatYuanGrouped(shown),
        style = MaterialTheme.typography.displayMedium,
        color = if (state == Budget.State.CRITICAL) {
            MaterialTheme.accBook.critical
        } else {
            MaterialTheme.colorScheme.onBackground
        },
    )
}

/** 未设预算：本月已花为主 + 引导设置 */
@Composable
private fun NoBudgetHero(spentCents: Long, onSetBudget: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        CoinMark(coinSize = 84.dp, progress = 0f, state = Budget.State.NORMAL)
        Spacer(Modifier.height(24.dp))
        Text(Money.formatYuanGrouped(spentCents), style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "本月已花 · 设置预算后这里显示还能花多少",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onSetBudget) { Text("设置本月预算") }
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

/**
 * 金币/Token 视觉：低饱和暖金圆币 + 外圈预算消耗环形进度。
 * 扁平两色 + 细描边细节，无渐变；消耗越多环越满，颜色随状态转琥珀/红。
 */
@Composable
private fun CoinMark(coinSize: Dp, progress: Float, state: Budget.State, modifier: Modifier = Modifier) {
    val ab = MaterialTheme.accBook
    val ringColor = budgetStateColor(state)
    val swept by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "coinRing",
    )
    val measurer = rememberTextMeasurer()
    Canvas(modifier.size(coinSize)) {
        val stroke = 4.dp.toPx()
        val ringRadius = (this.size.minDimension - stroke) / 2f
        drawCircle(color = ab.hairline, radius = ringRadius, style = Stroke(stroke))
        drawArc(
            color = ringColor,
            startAngle = -90f,
            sweepAngle = 360f * swept,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val coinRadius = ringRadius - stroke - 3.dp.toPx()
        drawCircle(color = ab.gold, radius = coinRadius)
        drawCircle(color = ab.goldDeep.copy(alpha = 0.30f), radius = coinRadius, style = Stroke(1.5.dp.toPx()))
        drawCircle(color = ab.goldDeep.copy(alpha = 0.20f), radius = coinRadius * 0.70f, style = Stroke(1.dp.toPx()))
        val glyph = measurer.measure(
            "¥",
            TextStyle(
                fontSize = (coinRadius * 0.85f).toDp().toSp(),
                fontWeight = FontWeight.Bold,
                color = ab.goldDeep,
                fontFeatureSettings = "tnum",
            ),
        )
        drawText(
            glyph,
            topLeft = Offset(center.x - glyph.size.width / 2f, center.y - glyph.size.height / 2f),
        )
    }
}
