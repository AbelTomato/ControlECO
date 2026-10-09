package com.accbook.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.accbook.app.data.PeriodMode
import com.accbook.app.ui.theme.accBook
import java.time.YearMonth
import kotlinx.coroutines.launch

/** 选择面板覆盖的年份范围（月视图一年一页，年视图一页 12 年） */
private const val PICKER_YEAR_MIN = 1970
private const val PICKER_YEAR_MAX = 2099
private const val YEARS_PER_PAGE = 12

/** 周期文案：月模式 "2026年10月"，年模式 "2026年" */
fun periodLabel(yearMonth: YearMonth, mode: PeriodMode): String = when (mode) {
    PeriodMode.MONTH -> "${yearMonth.year}年${yearMonth.monthValue}月"
    PeriodMode.YEAR -> "${yearMonth.year}年"
}

/** 结余文案前缀：本月结余 / 本年结余 */
fun periodBalanceLabel(mode: PeriodMode): String = when (mode) {
    PeriodMode.MONTH -> "本月结余"
    PeriodMode.YEAR -> "本年结余"
}

/** 周期范围文案（用于空态与提示）：本月 / 本年 */
fun periodScopeLabel(mode: PeriodMode): String = when (mode) {
    PeriodMode.MONTH -> "本月"
    PeriodMode.YEAR -> "本年"
}

/**
 * 通用分段切换（月/年切换、账单"全部/支出/收入"筛选共用同一套外观）：
 * 外层浅主色胶囊，选中段主色实底 + 白字加粗，未选中透明——开着就有"能点、点哪个"的直观感。
 * [compact] = 账单页那种"一屏塞更多信息"的小一号（文字小一档、内边距收窄）。
 */
@Composable
fun <T> SegmentedSwitch(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    containerColor: Color = MaterialTheme.accBook.primaryStrong.copy(alpha = 0.16f),
    selectedColor: Color = MaterialTheme.accBook.primaryStrong,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(containerColor)
            .padding(if (compact) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .clip(shape)
                    .then(if (isSelected) Modifier.background(selectedColor) else Modifier)
                    .clickable { onSelect(option) }
                    .padding(
                        horizontal = if (compact) 12.dp else 14.dp,
                        vertical = if (compact) 3.dp else 5.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = if (compact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    color = if (isSelected) selectedContentColor else contentColor,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 月 / 年 两段式切换：选中段主色实底，未选中透明。
 * 账单页结余卡与统计页顶部共用，两处永远同步（同一份 ViewModel 状态）。
 */
@Composable
fun PeriodSwitch(
    mode: PeriodMode,
    onChange: (PeriodMode) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.accBook.primaryStrong.copy(alpha = 0.16f),
    selectedColor: Color = MaterialTheme.accBook.primaryStrong,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    SegmentedSwitch(
        options = PeriodMode.entries,
        selected = mode,
        label = { it.label },
        onSelect = onChange,
        modifier = modifier,
        containerColor = containerColor,
        selectedColor = selectedColor,
        selectedContentColor = selectedContentColor,
        contentColor = contentColor,
    )
}

/**
 * 周期步进条：[‹] [2026年10月] [›]。
 * 两侧箭头是浅蓝圆钮（唯一的"可点"图形提示），中间日期本身就是选择入口——
 * 不加方框、不加下拉小三角，文字自己站住；[compact] 供账单页用小一号（一屏多放几行）。
 */
@Composable
fun PeriodStepper(
    yearMonth: YearMonth,
    mode: PeriodMode,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StepButton(
            icon = Icons.Filled.KeyboardArrowLeft,
            desc = "上一${mode.label}",
            onClick = onPrev,
            size = if (compact) 30.dp else 36.dp,
        )
        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
        Text(
            periodLabel(yearMonth, mode),
            style = if (compact) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpenPicker() }
                .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp),
        )
        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
        StepButton(
            icon = Icons.Filled.KeyboardArrowRight,
            desc = "下一${mode.label}",
            onClick = onNext,
            size = if (compact) 30.dp else 36.dp,
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, desc: String, onClick: () -> Unit, size: Dp = 36.dp) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(size).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = desc,
                tint = MaterialTheme.accBook.primaryStrong,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/**
 * 周期选择面板（账单/统计共用）：
 * 月模式 = 一年一页的 4×3 月份格子，**左右滑动切换年份**；年模式 = 一页 12 年的年份格子。
 * 选中项浅蓝实底，当前月/今年描边，「回到本月/今年」一键回到现在。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodPickerSheet(
    mode: PeriodMode,
    current: YearMonth,
    onSelectMonth: (YearMonth) -> Unit,
    onSelectYear: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val now = YearMonth.now()

    fun dismiss(after: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            after()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (mode == PeriodMode.MONTH) "选择月份" else "选择年份",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        dismiss {
                            if (mode == PeriodMode.MONTH) onSelectMonth(now) else onSelectYear(now.year)
                        }
                    },
                ) { Text(if (mode == PeriodMode.MONTH) "回到本月" else "回到今年") }
            }

            if (mode == PeriodMode.MONTH) {
                val pageCount = PICKER_YEAR_MAX - PICKER_YEAR_MIN + 1
                val pager = rememberPagerState(
                    initialPage = (current.year - PICKER_YEAR_MIN).coerceIn(0, pageCount - 1),
                ) { pageCount }
                PickerStepper(
                    label = "${PICKER_YEAR_MIN + pager.currentPage} 年",
                    onPrev = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } },
                    onNext = {
                        scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(pageCount - 1)) }
                    },
                )
                HorizontalPager(state = pager) { page ->
                    val year = PICKER_YEAR_MIN + page
                    Column(Modifier.fillMaxWidth()) {
                        repeat(3) { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                repeat(4) { col ->
                                    val month = row * 4 + col + 1
                                    PickerCell(
                                        label = "$month 月",
                                        selected = year == current.year && month == current.monthValue,
                                        highlighted = year == now.year && month == now.monthValue,
                                        onClick = { dismiss { onSelectMonth(YearMonth.of(year, month)) } },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val pageCount = (PICKER_YEAR_MAX - PICKER_YEAR_MIN + 1 + YEARS_PER_PAGE - 1) / YEARS_PER_PAGE
                val pager = rememberPagerState(
                    initialPage = ((current.year - PICKER_YEAR_MIN) / YEARS_PER_PAGE).coerceIn(0, pageCount - 1),
                ) { pageCount }
                val firstYear = PICKER_YEAR_MIN + pager.currentPage * YEARS_PER_PAGE
                PickerStepper(
                    label = "$firstYear – ${firstYear + YEARS_PER_PAGE - 1}",
                    onPrev = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } },
                    onNext = {
                        scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(pageCount - 1)) }
                    },
                )
                HorizontalPager(state = pager) { page ->
                    Column(Modifier.fillMaxWidth()) {
                        repeat(3) { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                repeat(4) { col ->
                                    val year = PICKER_YEAR_MIN + page * YEARS_PER_PAGE + row * 4 + col
                                    if (year > PICKER_YEAR_MAX) {
                                        Spacer(Modifier.weight(1f).height(46.dp))
                                    } else {
                                        PickerCell(
                                            label = "$year",
                                            selected = year == current.year,
                                            highlighted = year == now.year,
                                            onClick = { dismiss { onSelectYear(year) } },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 选择面板里的年份步进：‹ 2026 年 ›，点箭头翻页（与左右滑动同步） */
@Composable
private fun PickerStepper(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(Icons.Filled.KeyboardArrowLeft, "上一个", onPrev)
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        StepButton(Icons.Filled.KeyboardArrowRight, "下一个", onNext)
    }
}

@Composable
private fun RowScope.PickerCell(label: String, selected: Boolean, highlighted: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            )
            .then(
                if (highlighted && !selected) {
                    Modifier.border(1.dp, MaterialTheme.accBook.primaryStrong, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
