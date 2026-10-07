package com.accbook.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.budget.Budget
import com.accbook.app.ui.common.BudgetEditDialog
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 首页：极简预算视图（无动画——动画方案实测卡顿已整体回退）。
 *
 * 中心 = 当月剩余预算饼图（环形进度，随剩余额度变色：充足绿 / 中等蓝 / 紧张红）；
 * 饼图正下方为第一视觉信息"剩余预算"，其下是权重更低的"本月已花"；
 * 右上角为"今日可用"资源条。所有数值直接读数据库真实值，不经过任何展示层插值。
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
    val ab = MaterialTheme.accBook

    if (budgetCents <= 0L) {
        NoBudgetContent(
            spentCents = spentCents,
            today = today,
            pendingCount = pendingCount,
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

    // budget > 0 时 compute 必返回非空，此分支仅为编译器收窄
    val s = snapshot ?: return

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${today.monthValue}月",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            if (pendingCount > 0) {
                PendingBadge(count = pendingCount, onClick = onOpenPending)
            }
            Spacer(Modifier.weight(1f))
            TodayHud(cents = s.suggestedDailyCents, color = toneColor(s.tone))
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BudgetPie(snapshot = s)
        }

        Column(
            Modifier.fillMaxWidth().height(158.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                Money.formatYuanGrouped(s.remainingCents),
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
                color = if (s.state == Budget.State.CRITICAL) ab.critical else MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "剩余预算",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "本月已花 ${Money.formatYuanGrouped(s.spentCents)}",
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

// ---------------- 子组件 ----------------

/** 剩余额度档位 → 用色：充足绿（收入绿同源）/ 中等蓝（主色）/ 紧张红 */
@Composable
private fun toneColor(tone: Budget.Tone): Color = when (tone) {
    Budget.Tone.OK -> MaterialTheme.accBook.income
    Budget.Tone.MID -> MaterialTheme.colorScheme.primary
    Budget.Tone.LOW -> MaterialTheme.accBook.critical
}

@Composable
private fun TodayHud(cents: Long, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
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

/**
 * 剩余预算饼图：环形进度（饼图的环形式读法），弧长 = 剩余比例，
 * 颜色随剩余额度三档变化；环心为剩余百分比。静态绘制，无任何动画。
 * 剩余 ≤ 0（超支）时整环转红。
 */
@Composable
private fun BudgetPie(snapshot: Budget.Snapshot, modifier: Modifier = Modifier) {
    val ab = MaterialTheme.accBook
    val color = toneColor(snapshot.tone)
    val ratio = snapshot.remainingRatio.coerceIn(0f, 1f)
    val percent = (snapshot.remainingRatio * 100).roundToInt().coerceIn(0, 100)
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val percentLayout = remember(percent, color) {
        measurer.measure(
            "$percent%",
            TextStyle(
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                fontFeatureSettings = "tnum",
            ),
        )
    }
    val labelLayout = remember(labelColor) {
        measurer.measure("剩余", TextStyle(fontSize = 12.sp, color = labelColor))
    }
    Canvas(modifier.size(200.dp)) {
        val stroke = 24.dp.toPx()
        val radius = (size.minDimension - stroke) / 2f
        // 轨道 = 已消耗部分
        drawCircle(color = ab.hairline, radius = radius, style = Stroke(stroke))
        when {
            snapshot.remainingCents <= 0L -> drawCircle(color = color, radius = radius, style = Stroke(stroke))
            ratio > 0f -> drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * ratio,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        val gap = 6.dp.toPx()
        val blockHeight = percentLayout.size.height + gap + labelLayout.size.height
        var y = center.y - blockHeight / 2f
        drawText(percentLayout, topLeft = Offset(center.x - percentLayout.size.width / 2f, y))
        y += percentLayout.size.height + gap
        drawText(labelLayout, topLeft = Offset(center.x - labelLayout.size.width / 2f, y))
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

/** 未设预算：中性占位环 + 本月已花 + 引导设置（无预算时饼图无比例意义） */
@Composable
private fun NoBudgetContent(
    spentCents: Long,
    today: LocalDate,
    pendingCount: Int,
    onSetBudget: () -> Unit,
    onQuickAdd: () -> Unit,
    onOpenPending: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${today.monthValue}月", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
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

/** 未设预算时的中性占位环（与正式饼图同构，但不表达比例） */
@Composable
private fun BudgetRingPlaceholder(modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val glyphColor = MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val glyph = remember(glyphColor) {
        measurer.measure("¥", TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = glyphColor))
    }
    Canvas(modifier.size(120.dp)) {
        drawCircle(
            color = trackColor,
            radius = (size.minDimension - 12.dp.toPx()) / 2f,
            style = Stroke(12.dp.toPx()),
        )
        drawText(
            glyph,
            topLeft = Offset(center.x - glyph.size.width / 2f, center.y - glyph.size.height / 2f),
        )
    }
}
