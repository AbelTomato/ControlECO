package com.accbook.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.TransactionRow
import com.accbook.app.data.db.TxType
import com.accbook.app.data.PeriodMode
import com.accbook.app.ui.common.PeriodPickerSheet
import com.accbook.app.ui.common.PeriodStepper
import com.accbook.app.ui.common.PeriodSwitch
import com.accbook.app.ui.common.SummaryRow
import com.accbook.app.ui.common.periodScopeLabel
import com.accbook.app.ui.theme.accBook
import com.accbook.app.ui.theme.categoryColor
import com.accbook.app.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 统计页：结论 > 趋势 > 分类细节（信息层级自上而下）。
 * 不引入图表库：趋势为自绘 Canvas 柱状图，分类为条形占比。
 */
@Composable
fun StatsScreen(vm: LedgerViewModel, scrollToTopTick: Int = 0) {
    val month by vm.month.collectAsStateWithLifecycle()
    val mode by vm.periodMode.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    val totals by vm.periodTotals.collectAsStateWithLifecycle()
    val byCategory by vm.periodCategorySums.collectAsStateWithLifecycle()
    val rows by vm.periodRows.collectAsStateWithLifecycle()
    val showRefunds by vm.showRefunds.collectAsStateWithLifecycle()
    val refundTotal by vm.periodRefundTotal.collectAsStateWithLifecycle()

    // 数据未到位前整页不画（避免先显示 0 / 空图再跳数）
    val rowsReady = rows ?: return
    val totalsReady = totals ?: return
    val byCategoryReady = byCategory ?: return
    val refundReady = refundTotal ?: return

    val expense = totalsReady.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L
    val income = totalsReady.firstOrNull { it.type == TxType.INCOME }?.totalCents ?: 0L

    // 日均：当前周期用"截至今天"，历史周期用满周期天数
    val now = YearMonth.now()
    val isCurrentPeriod = if (mode == PeriodMode.MONTH) month == now else month.year == now.year
    val daysElapsed = when {
        mode == PeriodMode.MONTH && isCurrentPeriod -> LocalDate.now().dayOfMonth
        mode == PeriodMode.MONTH -> month.lengthOfMonth()
        isCurrentPeriod -> LocalDate.now().dayOfYear
        else -> if (YearMonth.of(month.year, 2).lengthOfMonth() == 29) 366 else 365
    }
    val dailyAverage = expense / daysElapsed.coerceAtLeast(1)
    val top = byCategoryReady.firstOrNull()
    val topShare = if (top != null && expense > 0) (top.totalCents * 100 / expense) else 0L

    val pageScroll = rememberScrollState()
    // 再次点击底部「统计」标签：回到顶部
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > 0) pageScroll.animateScrollTo(0)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(pageScroll),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
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
            PeriodSwitch(mode = mode, onChange = { vm.setPeriodMode(it) })
        }
        SummaryRow(
            incomeCents = income,
            expenseCents = expense,
            cancelledCents = if (showRefunds) refundReady else null,
        )
        HorizontalDivider(color = MaterialTheme.accBook.hairline)

        // 第一层级：结论
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (expense == 0L) {
                Text(
                    "${periodScopeLabel(mode)}暂无支出记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${periodScopeLabel(mode)}支出 ${Money.formatYuanGrouped(expense)} · 日均 ${Money.formatYuanGrouped(dailyAverage)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (top != null) "花得最多的是「${top.categoryName}」，占 $topShare%" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 第二层级：每日支出趋势（自绘）
        SectionLabel(if (mode == PeriodMode.MONTH) "每日支出" else "月度支出")
        ExpenseChart(
            rows = rowsReady,
            month = month,
            mode = mode,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // 第三层级：分类细节
        SectionLabel("分类占比")
        if (byCategoryReady.isEmpty()) {
            Text(
                "暂无支出分类数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            Column(Modifier.padding(horizontal = 16.dp)) {
                byCategoryReady.forEach { c ->
                    val fraction = if (expense > 0) c.totalCents.toFloat() / expense else 0f
                    val share = if (expense > 0) c.totalCents * 100 / expense else 0L
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(c.categoryName, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${Money.formatYuanGrouped(c.totalCents)} · $share%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.accBook.ringTrack),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        categoryColor(MaterialTheme.accBook.categoryPalette, c.categoryName),
                                    ),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
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
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 支出柱状图：月模式按天、年模式按月；零花销的画发丝线刻度，保持节奏感 */
@Composable
private fun ExpenseChart(
    rows: List<TransactionRow>,
    month: YearMonth,
    mode: PeriodMode,
    modifier: Modifier = Modifier,
) {
    val ab = MaterialTheme.accBook
    val barColor = MaterialTheme.colorScheme.primary
    val daily = remember(rows, month, mode) {
        if (mode == PeriodMode.MONTH) {
            FloatArray(month.lengthOfMonth()).also { arr ->
                rows.filter { it.type == TxType.EXPENSE }.forEach { r ->
                    val d = Instant.ofEpochMilli(r.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                    if (d.year == month.year && d.month == month.month) {
                        arr[d.dayOfMonth - 1] += r.amountCents / 100f
                    }
                }
            }
        } else {
            FloatArray(12).also { arr ->
                rows.filter { it.type == TxType.EXPENSE }.forEach { r ->
                    val d = Instant.ofEpochMilli(r.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                    if (d.year == month.year) arr[d.monthValue - 1] += r.amountCents / 100f
                }
            }
        }
    }
    Canvas(modifier.fillMaxWidth().height(110.dp)) {
        val n = daily.size.coerceAtLeast(1)
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val max = daily.max().coerceAtLeast(1f)
        daily.forEachIndexed { i, v ->
            val x = i * (barWidth + gap)
            if (v > 0f) {
                val h = (v / max) * (size.height - 4.dp.toPx())
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h.coerceAtLeast(2.dp.toPx())),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            } else {
                drawLine(
                    color = ab.hairline,
                    start = Offset(x, size.height - 1.dp.toPx()),
                    end = Offset(x + barWidth, size.height - 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}
