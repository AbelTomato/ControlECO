package com.accbook.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.PeriodMode
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.PeriodPickerSheet
import com.accbook.app.ui.common.PeriodStepper
import com.accbook.app.ui.common.PeriodSwitch
import com.accbook.app.ui.common.periodScopeLabel
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.time.LocalDate
import java.time.YearMonth

/**
 * 统计页：结论 → 趋势（可交互柱状图）→ 分类（预算 vs 实际双环饼图）。
 * 月视角每日一柱、年视角每月一柱；横竖轴标简单数字，点按/滑动柱子实时读数。
 * 图表全部自绘（Canvas），不引入第三方图表库。
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
    val categoryBudgets by vm.categoryBudgets.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

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

    // 柱状图数据：月视角每日一根、年视角每月一根
    val bars = remember(rowsReady, month, mode) {
        if (mode == PeriodMode.MONTH) {
            ChartMath.monthBars(rowsReady, month)
        } else {
            ChartMath.yearBars(rowsReady, month)
        }
    }

    // 分类切片：实际支出（本期）+ 分类预算（年视角按 12 个月合计）
    val slices = remember(byCategoryReady, categoryBudgets, categories, mode) {
        val actual = byCategoryReady.map { it.categoryName to it.totalCents }
        val budget = categoryBudgets.entries.mapNotNull { (id, cents) ->
            val category = categories.firstOrNull { it.id == id }
            if (category == null || category.type != TxType.EXPENSE || cents <= 0L) {
                null
            } else {
                category.name to if (mode == PeriodMode.YEAR) cents * 12 else cents
            }
        }
        ChartMath.mergeSlices(actual, budget)
    }
    val top = slices.firstOrNull()?.takeIf { it.actualCents > 0 }
    val topShare = top?.let { ChartMath.sharePercent(it.actualCents, expense).toInt() } ?: 0

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

        // ---- 结论卡：本期支出大字 + 收入/结余/退款 ----
        StatsCard(Modifier.padding(top = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${periodScopeLabel(mode)}支出",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        Money.formatYuanGrouped(expense),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "日均 ${Money.formatYuanGrouped(dailyAverage)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetricRow("收入", "+${Money.formatYuanGrouped(income)}", MaterialTheme.accBook.income)
                    MetricRow("结余", Money.formatYuanGrouped(income - expense), MaterialTheme.colorScheme.onSurface)
                    if (showRefunds && refundReady > 0L) {
                        MetricRow("退款到账", Money.formatYuanGrouped(refundReady), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (expense > 0L) {
                HorizontalDivider(color = MaterialTheme.accBook.hairline)
                Text(
                    if (top != null) "花得最多的是「${top.name}」，占 $topShare%" else "本期暂无支出",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        // ---- 趋势卡：柱状图（可点按/滑动读数） ----
        SectionLabel(if (mode == PeriodMode.MONTH) "每日支出" else "月度支出")
        StatsCard {
            if (expense == 0L) {
                EmptyNote("${periodScopeLabel(mode)}暂无支出记录，记一笔后这里会出现柱状图")
            } else {
                ExpenseBarChart(
                    bars = bars,
                    hint = if (mode == PeriodMode.MONTH) "点按或滑动柱子查看每日明细" else "点按或滑动柱子查看每月明细",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
        }

        // ---- 分类卡：预算 vs 实际 双环饼图 + 图例 ----
        SectionLabel("分类预算与占比")
        StatsCard {
            if (slices.isEmpty()) {
                EmptyNote("暂无分类数据：记账或设置分类预算后这里会出现饼图")
            } else {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    CategoryDonut(
                        slices = slices,
                        totalActualCents = expense,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text(
                        buildString {
                            append("外环 预算占比 · 内环 实际占比")
                            if (mode == PeriodMode.YEAR) append("（年视角预算按 12 个月计）")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                    )
                    DonutLegend(slices = slices, totalActualCents = expense)
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

/** 结论卡右侧的一行小指标：标签 + 取值（取值可带语义色） */
@Composable
private fun MetricRow(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** 白色卡片：与账单/设置页同一套视觉（16dp 圆角 + 发丝描边） */
@Composable
private fun StatsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.accBook.hairline, RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
