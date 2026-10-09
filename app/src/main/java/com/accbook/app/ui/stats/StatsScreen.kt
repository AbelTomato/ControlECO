package com.accbook.app.ui.stats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.PeriodMode
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.PeriodPickerSheet
import com.accbook.app.ui.common.PeriodStepper
import com.accbook.app.ui.common.PeriodSwitch
import com.accbook.app.ui.common.periodBalanceLabel
import com.accbook.app.ui.common.periodScopeLabel
import com.accbook.app.ui.theme.accBook
import com.accbook.app.ui.theme.categoryColors
import com.accbook.app.util.Money
import java.time.LocalDate
import java.time.YearMonth

/**
 * 统计页：结论 → 趋势（可交互柱状图）→ 分类（预算 vs 实际双环饼图）。
 * 月视角每日一柱、年视角每月一柱；横竖轴标简单数字，点按/滑动柱子实时读数。
 * 图表全部自绘（Canvas），不引入第三方图表库。
 */
@OptIn(ExperimentalFoundationApi::class)
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

    // 日均结余：净结余 ÷ 天数（当前周期用"截至今天"，历史周期用满周期天数）；
    // 结余为负时带上负号（说明这个周期平均每天在净流出）
    val now = YearMonth.now()
    val isCurrentPeriod = if (mode == PeriodMode.MONTH) month == now else month.year == now.year
    val daysElapsed = when {
        mode == PeriodMode.MONTH && isCurrentPeriod -> LocalDate.now().dayOfMonth
        mode == PeriodMode.MONTH -> month.lengthOfMonth()
        isCurrentPeriod -> LocalDate.now().dayOfYear
        else -> if (YearMonth.of(month.year, 2).lengthOfMonth() == 29) 366 else 365
    }
    val dailyBalance = (income - expense) / daysElapsed.coerceAtLeast(1)

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
    // 圆环选中态与配色：配色按"分类名集合"分配（同屏不重复），选中态点空白处取消
    var selectedSlice by remember(slices) { mutableStateOf<String?>(null) }
    val palette = MaterialTheme.accBook.categoryPalette
    val sliceColors = remember(slices, palette) { categoryColors(palette, slices.map { it.name }) }
    val listState = rememberLazyListState()
    // 再次点击底部「统计」标签：回到顶部
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > 0) listState.animateScrollToItem(0)
    }
    // 用 LazyColumn 而不是普通 Column：分类圆环要当 stickyHeader 常驻屏幕上方，
    // 往下翻图例时圆环仍看得见（点图例 = 点圆环，两边必须同时在场）
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            // 点页面上任意空白处 = 取消圆环选中（点环 / 点图例行会被它们自己接住）
            .pointerInput(Unit) { detectTapGestures { selectedSlice = null } },
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "period") {
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
        }

        // ---- 结论卡：与账单悬浮卡同排版（左：结余大字 + 日均；右：收入 / 支出，+绿 −红，无文字标签） ----
        item(key = "conclusion") {
            StatsCard(Modifier.padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            periodBalanceLabel(mode),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            Money.formatYuanGroupedFixed(income - expense),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${Money.formatYuanGrouped(dailyBalance)} / day",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        if (showRefunds && refundReady > 0L) {
                            Text(
                                "退款到账 ${Money.formatYuanGrouped(refundReady)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            Money.formatSignedPlain(income),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.accBook.income,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            Money.formatSignedPlain(-expense),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.accBook.critical,
                        )
                    }
                }
            }
        }

        // ---- 趋势卡：柱状图（可点按/滑动读数） ----
        item(key = "trendLabel") {
            SectionLabel(if (mode == PeriodMode.MONTH) "每日支出" else "月度支出")
        }
        item(key = "trend") {
            StatsCard {
                if (expense == 0L) {
                    EmptyNote("${periodScopeLabel(mode)}暂无支出记录，记一笔后这里会出现柱状图")
                } else {
                    ExpenseBarChart(
                        bars = bars,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
            }
        }

        // ---- 分类卡：预算 vs 实际 双环饼图（两侧数字跟着选中项走），常驻屏幕上方 ----
        item(key = "categoryLabel") { SectionLabel("分类预算与占比") }
        stickyHeader(key = "categoryDonut") {
            // 卡片下方会有图例滚过，所以整块先铺一层页面底色，内容不会从圆角外透出来
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                StatsCard(Modifier.padding(top = 4.dp)) {
                    if (slices.isEmpty()) {
                        EmptyNote("暂无分类数据：记账或设置分类预算后这里会出现饼图")
                    } else {
                        CategoryDonut(
                            slices = slices,
                            totalActualCents = expense,
                            colors = sliceColors,
                            selected = selectedSlice,
                            onSelect = { selectedSlice = it },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
        if (slices.isNotEmpty()) {
            item(key = "categoryCaption") {
                Text(
                    buildString {
                        append("外环 预算占比　内环 实际占比")
                        if (mode == PeriodMode.YEAR) append("（年视角预算按 12 个月计）")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 12.dp, bottom = 8.dp),
                )
            }
            item(key = "categoryLegend") {
                StatsCard {
                    DonutLegend(
                        slices = slices,
                        totalActualCents = expense,
                        colors = sliceColors,
                        selected = selectedSlice,
                        // 点图例 = 点圆环上该分类（同一份选中态，两边同时高亮）
                        onSelect = { selectedSlice = it },
                        modifier = Modifier.padding(vertical = 4.dp),
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
