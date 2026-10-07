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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.accbook.app.ui.common.MonthHeader
import com.accbook.app.ui.common.SummaryRow
import com.accbook.app.ui.theme.accBook
import com.accbook.app.ui.theme.categoryColor
import com.accbook.app.util.Money
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * 统计页：结论 > 趋势 > 分类细节（信息层级自上而下）。
 * 不引入图表库：趋势为自绘 Canvas 柱状图，分类为条形占比。
 */
@Composable
fun StatsScreen(vm: LedgerViewModel) {
    val month by vm.month.collectAsStateWithLifecycle()
    val totals by vm.monthTotals.collectAsStateWithLifecycle(initialValue = emptyList())
    val byCategory by vm.expenseByCategory.collectAsStateWithLifecycle(initialValue = emptyList())
    val rows by vm.monthRows.collectAsStateWithLifecycle(initialValue = emptyList())
    val showRefunds by vm.showRefunds.collectAsStateWithLifecycle()
    val refundTotal by vm.refundIncomeTotal.collectAsStateWithLifecycle(initialValue = 0L)

    val expense = totals.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L
    val income = totals.firstOrNull { it.type == TxType.INCOME }?.totalCents ?: 0L

    // 日均：当月用"截至今天"，历史月份用整月天数
    val isCurrentMonth = month == YearMonth.now()
    val daysElapsed = if (isCurrentMonth) java.time.LocalDate.now().dayOfMonth else month.lengthOfMonth()
    val dailyAverage = expense / daysElapsed.coerceAtLeast(1)
    val top = byCategory.firstOrNull()
    val topShare = if (top != null && expense > 0) (top.totalCents * 100 / expense) else 0L

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        MonthHeader(month = month, onPrev = vm::prevMonth, onNext = vm::nextMonth)
        SummaryRow(
            incomeCents = income,
            expenseCents = expense,
            cancelledCents = if (showRefunds) refundTotal else null,
        )
        HorizontalDivider(color = MaterialTheme.accBook.hairline)

        // 第一层级：结论
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (expense == 0L) {
                Text(
                    "本月暂无支出记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "本月支出 ${Money.formatYuanGrouped(expense)} · 日均 ${Money.formatYuanGrouped(dailyAverage)}",
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
        SectionLabel("每日支出")
        DailyExpenseChart(rows = rows, month = month, modifier = Modifier.padding(horizontal = 16.dp))

        // 第三层级：分类细节
        SectionLabel("分类占比")
        if (byCategory.isEmpty()) {
            Text(
                "暂无支出分类数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            Column(Modifier.padding(horizontal = 16.dp)) {
                byCategory.forEach { c ->
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

/** 每日支出柱状图：零花销日画发丝线刻度，保持节奏感；主色柱 = 有支出 */
@Composable
private fun DailyExpenseChart(rows: List<TransactionRow>, month: YearMonth, modifier: Modifier = Modifier) {
    val ab = MaterialTheme.accBook
    val barColor = MaterialTheme.colorScheme.primary
    val daily = remember(rows, month) {
        FloatArray(month.lengthOfMonth()).also { arr ->
            rows.filter { it.type == TxType.EXPENSE }.forEach { r ->
                val d = Instant.ofEpochMilli(r.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                if (d.year == month.year && d.month == month.month) {
                    arr[d.dayOfMonth - 1] += r.amountCents / 100f
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
