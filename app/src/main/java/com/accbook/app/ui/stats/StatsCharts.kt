package com.accbook.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.accbook.app.ui.common.AutoScaledText
import com.accbook.app.ui.theme.accBook
import com.accbook.app.ui.theme.categoryColor
import com.accbook.app.util.Money
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 统计页图表（自绘，不引第三方库）：
 * - [ExpenseBarChart] 可交互柱状图：横竖轴都标简单数字，点按/滑动实时读出手指所在柱的数据；
 * - [CategoryDonut] 双环饼图：外环 = 分类预算占比，内环 = 实际支出占比（同色同分类，便于对照），
 *   圆环左右两列分别是总花费与总预算；
 * - [DonutLegend] 图例：分类色条 + 实际金额/占比与预算金额/占比，点一行即在圆环上选中该分类。
 */

// 图表几何常量：绘制与手势命中共用同一套算法，避免两处对不齐
private val GUTTER_DP = 34.dp     // 左侧纵轴数字区宽度
private val BOTTOM_DP = 18.dp     // 底部横轴数字区高度
private val BAR_GAP_WIDE = 6.dp   // 柱少（按月）时的柱间距
private val BAR_GAP_NARROW = 2.dp // 柱多（按天）时的柱间距

/** 柱状图几何：绘图与命中共用（宽度相关的量在这里集中算一次） */
private class BarGeometry(
    val gutter: Float,
    val bottom: Float,
    val barWidth: Float,
    val pitch: Float,
    val count: Int,
) {
    fun indexAt(x: Float): Int = ((x - gutter) / pitch).toInt().coerceIn(0, count - 1)
    fun left(index: Int): Float = gutter + index * pitch
    fun center(index: Int): Float = left(index) + barWidth / 2f
}

private fun barGeometry(widthPx: Float, n: Int, density: Density): BarGeometry {
    val gutter = with(density) { GUTTER_DP.toPx() }
    val bottom = with(density) { BOTTOM_DP.toPx() }
    val gap = with(density) { (if (n > 16) BAR_GAP_NARROW else BAR_GAP_WIDE).toPx() }
    val plotW = (widthPx - gutter).coerceAtLeast(1f)
    val barW = ((plotW - gap * (n - 1)) / n).coerceAtLeast(1f)
    return BarGeometry(gutter, bottom, barW, barW + gap, n)
}

/**
 * 可交互柱状图：手指按下即选中该柱，滑动时读数跟着手指走（不是只能点）。
 * 上方固定一行显示选中柱的明细（未选中时留空，但保留行高让图表不跳动）；
 * 图上有一条虚线指示 + 金额气泡。
 */
@Composable
fun ExpenseBarChart(bars: List<BarPoint>, modifier: Modifier = Modifier) {
    val ab = MaterialTheme.accBook
    val barColor = MaterialTheme.colorScheme.primary
    val highlightColor = ab.primaryStrong
    val gridColor = ab.hairline
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bubbleBg = ab.primaryStrong
    val bubbleInk = MaterialTheme.colorScheme.onPrimary

    val measurer = rememberTextMeasurer()
    val axisStyle = remember(axisColor) {
        TextStyle(color = axisColor, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
    val bubbleStyle = remember(bubbleInk) {
        TextStyle(color = bubbleInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
    // 文字量测缓存：拖动手势每帧都会重画，避免重复量同一串文字
    val axisCache = remember(measurer, axisStyle) { mutableMapOf<String, TextLayoutResult>() }
    val bubbleCache = remember(measurer, bubbleStyle) { mutableMapOf<String, TextLayoutResult>() }

    var selected by remember(bars) { mutableIntStateOf(-1) }
    val selectedBar = bars.getOrNull(selected)

    Column(modifier) {
        // 读数行：未选中时留空（操作提示文字已删），用不换行空格占住行高，图表不跳动
        Text(
            text = selectedBar?.tip ?: "\u00A0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                // 点按选柱；横向拖动跟手读数。用 detectHorizontalDragGestures 而非全量 drag：
                // 纵向滑动交给外层滚动容器，图表不会"吃掉"页面滚动
                .pointerInput(bars) {
                    detectTapGestures { offset ->
                        selected = barGeometry(size.width.toFloat(), bars.size, this).indexAt(offset.x)
                    }
                }
                .pointerInput(bars) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            selected = barGeometry(size.width.toFloat(), bars.size, this).indexAt(offset.x)
                        },
                        onHorizontalDrag = { change, _ ->
                            selected = barGeometry(size.width.toFloat(), bars.size, this).indexAt(change.position.x)
                            change.consume()
                        },
                    )
                },
        ) {
            if (bars.isEmpty()) return@Canvas
            val geom = barGeometry(size.width, bars.size, this)
            val plotTop = 6.dp.toPx()
            val plotBottom = size.height - geom.bottom
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
            val maxYuan = ChartMath.niceCeil(bars.maxOf { it.cents / 100f })

            fun yAt(cents: Long): Float =
                plotBottom - (cents / 100f / maxYuan).coerceIn(0f, 1f) * plotH

            fun axisLayout(text: String): TextLayoutResult =
                axisCache.getOrPut(text) { measurer.measure(text, axisStyle) }

            fun drawAxisText(text: String, x: Float, top: Float, alignRight: Boolean) {
                val l = axisLayout(text)
                val tx = if (alignRight) x - l.size.width else x - l.size.width / 2f
                drawText(l, topLeft = Offset(tx, top))
            }

            // 1) 横向网格线 + 纵轴数字（0 / 一半 / 上限）
            listOf(0f, maxYuan / 2f, maxYuan).forEach { v ->
                val y = plotBottom - (v / maxYuan) * plotH
                drawLine(gridColor, Offset(geom.gutter, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                val text = ChartMath.axisAmount(v)
                val l = axisLayout(text)
                drawAxisText(text, geom.gutter - 6.dp.toPx(), y - l.size.height / 2f, alignRight = true)
            }
            // 2) 纵轴轴线
            drawLine(gridColor, Offset(geom.gutter, plotTop), Offset(geom.gutter, plotBottom), strokeWidth = 1.dp.toPx())

            // 3) 柱子：有支出的实心柱，零支出画一根发丝刻度保持"每一天都在"的节奏
            bars.forEachIndexed { i, bar ->
                val left = geom.left(i)
                if (bar.cents > 0L) {
                    val top = yAt(bar.cents)
                    val h = (plotBottom - top).coerceAtLeast(2.dp.toPx())
                    val color = when {
                        i == selected -> highlightColor
                        selected >= 0 -> barColor.copy(alpha = 0.42f)
                        else -> barColor
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left, plotBottom - h),
                        size = Size(geom.barWidth, h),
                        cornerRadius = CornerRadius(min(3.dp.toPx(), geom.barWidth / 2f)),
                    )
                } else {
                    drawLine(
                        color = gridColor,
                        start = Offset(left, plotBottom - 1.dp.toPx()),
                        end = Offset(left + geom.barWidth, plotBottom - 1.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }

            // 4) 选中：虚线指示 + 金额气泡（顶部放不下时挪到柱内）
            if (selected in bars.indices) {
                val bar = bars[selected]
                val centerX = geom.center(selected)
                drawLine(
                    color = highlightColor.copy(alpha = 0.35f),
                    start = Offset(centerX, plotTop),
                    end = Offset(centerX, plotBottom),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                val label = if (bar.cents > 0L) Money.formatYuanCompact(bar.cents) else "无支出"
                val l = bubbleCache.getOrPut(label) { measurer.measure(label, bubbleStyle) }
                val padH = 7.dp.toPx()
                val padV = 3.dp.toPx()
                val bw = l.size.width + padH * 2
                val bh = l.size.height + padV * 2
                val bubbleX = (centerX - bw / 2f).coerceIn(geom.gutter, (size.width - bw).coerceAtLeast(geom.gutter))
                var bubbleY = yAt(bar.cents) - bh - 6.dp.toPx()
                if (bubbleY < plotTop) bubbleY = yAt(bar.cents) + 6.dp.toPx()
                drawRoundRect(
                    color = bubbleBg,
                    topLeft = Offset(bubbleX, bubbleY),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
                drawText(l, topLeft = Offset(bubbleX + padH, bubbleY + padV))
            }

            // 5) 横轴数字（简单数字：天/月编号）
            bars.forEachIndexed { i, bar ->
                val label = bar.axisLabel ?: return@forEachIndexed
                drawAxisText(label, geom.center(i), plotBottom + 3.dp.toPx(), alignRight = false)
            }
        }
    }
}

/**
 * 双环饼图：外环 = 分类预算占比（42% 透明），内环 = 实际支出占比（实色）。
 * 中心不写字：圆环两侧各站一列总览——左「花费」、右「预算」，列内第一行金额、第二行占比
 * （花费列的占比 = 花费占预算，超预算转红；预算列是基准所以恒 100%）。
 * 点按 / 横向滑动任一环选中分类，上方读数行显示「类别 · 金额 · 占比 · 预算」；
 * 选中后其他分类压暗到 22%。**取消选中交给外层**（点页面空白处即取消）。
 */
@Composable
fun CategoryDonut(
    slices: List<CategorySlice>,
    totalActualCents: Long,
    colors: Map<String, Color>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ab = MaterialTheme.accBook
    val palette = ab.categoryPalette
    val totalBudget = slices.sumOf { it.budgetCents }
    val selectedSlice = slices.firstOrNull { it.name == selected }

    val readout = if (selectedSlice != null) {
        buildString {
            append(selectedSlice.name)
            append(" · ¥")
            append(Money.formatYuanGrouped(selectedSlice.actualCents).removePrefix("¥"))
            append(" · ")
            append(ChartMath.sharePercent(selectedSlice.actualCents, totalActualCents).roundToInt())
            append('%')
            if (selectedSlice.budgetCents > 0L) {
                append(" · 预算 ¥")
                append(Money.formatYuanGrouped(selectedSlice.budgetCents).removePrefix("¥"))
                append(" · ")
                append(ChartMath.sharePercent(selectedSlice.budgetCents, totalBudget).roundToInt())
                append('%')
            }
        }
    } else {
        "\u00A0" // 未选中：留一行空位（不换行空格，看不见但占高），选中时数字出现不顶动圆环
    }

    Column(modifier) {
        // 读数行：未选中时留空（提示文字已删），保留行高避免图表跳动
        Text(
            readout,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val ringSize = minOf(152.dp, maxWidth * 0.46f).coerceAtLeast(96.dp)
            val columnMax = ((maxWidth - ringSize) / 2f - 6.dp).coerceAtLeast(44.dp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SideStat(
                    label = "花费",
                    amountCents = totalActualCents,
                    percent = if (totalBudget > 0L) {
                        ChartMath.sharePercent(totalActualCents, totalBudget)
                    } else {
                        null
                    },
                    overBudget = totalBudget > 0L && totalActualCents > totalBudget,
                    maxWidth = columnMax,
                    modifier = Modifier.weight(1f),
                )
                Canvas(
                    Modifier
                        .size(ringSize)
                        .pointerInput(slices) {
                            val scope = this
                            fun pick(pos: Offset) {
                                onSelect(donutGeometry(scope.size.width.toFloat(), scope).sliceAt(pos, slices))
                            }
                            detectTapGestures { pos -> pick(pos) }
                        }
                        .pointerInput(slices) {
                            val scope = this
                            fun pick(pos: Offset) {
                                onSelect(donutGeometry(scope.size.width.toFloat(), scope).sliceAt(pos, slices))
                            }
                            detectHorizontalDragGestures(
                                onDragStart = { pos -> pick(pos) },
                                onHorizontalDrag = { change, _ ->
                                    pick(change.position)
                                    change.consume()
                                },
                            )
                        },
                ) {
                    val strokeOuter = 20.dp.toPx()
                    val strokeInner = 16.dp.toPx()
                    val ringGap = 8.dp.toPx()
                    val c = center
                    val rOuter = size.minDimension / 2f - strokeOuter / 2f
                    val rInner = rOuter - strokeOuter / 2f - ringGap - strokeInner / 2f

                    // 底轨
                    drawCircle(ab.ringTrack, radius = rOuter, center = c, style = Stroke(strokeOuter))
                    drawCircle(ab.ringTrack, radius = rInner, center = c, style = Stroke(strokeInner))

                    val totalBudgetRing = slices.sumOf { it.budgetCents }
                    val totalActualRing = slices.sumOf { it.actualCents }

                    fun ring(radius: Float, width: Float, alpha: Float, value: (CategorySlice) -> Long, total: Long) {
                        if (total <= 0L) return
                        var start = -90f
                        slices.forEach { s ->
                            val v = value(s)
                            val sweep = v * 360f / total
                            if (v > 0L) {
                                val dim = selected != null && selected != s.name
                                val color = colors[s.name] ?: categoryColor(palette, s.name)
                                val pad = min(1.2f, sweep * 0.06f)
                                drawArc(
                                    color = color.copy(alpha = if (dim) alpha * 0.22f else alpha),
                                    startAngle = start + pad,
                                    sweepAngle = (sweep - pad * 2).coerceAtLeast(0.8f),
                                    useCenter = false,
                                    topLeft = Offset(c.x - radius, c.y - radius),
                                    size = Size(radius * 2, radius * 2),
                                    style = Stroke(width),
                                )
                            }
                            start += sweep
                        }
                    }
                    ring(rOuter, strokeOuter, alpha = 0.42f, value = { it.budgetCents }, total = totalBudgetRing)
                    ring(rInner, strokeInner, alpha = 1f, value = { it.actualCents }, total = totalActualRing)
                }
                SideStat(
                    label = "预算",
                    amountCents = totalBudget,
                    percent = if (totalBudget > 0L) 100f else null,
                    overBudget = false,
                    alignEnd = true,
                    maxWidth = columnMax,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 圆环两侧的总览列：标签（花费 / 预算）+ 金额 + 占比。
 * 标签用 titleSmall + onSurface（比原来的说明小字更大更深），金额超宽时自动缩放，不压到圆环。
 */
@Composable
private fun SideStat(
    label: String,
    amountCents: Long,
    percent: Float?,
    overBudget: Boolean,
    alignEnd: Boolean = false,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val ab = MaterialTheme.accBook
    Column(
        modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        AutoScaledText(
            text = Money.formatYuanGrouped(amountCents),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxWidth = maxWidth,
        )
        Text(
            percent?.let { "${it.roundToInt()}%" } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = if (overBudget) ab.critical else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 饼图几何：绘制与命中共用（半径按画布最小边推导，与绘制逻辑一致） */
internal class DonutGeometry(
    private val center: Offset,
    private val rOuter: Float,
    private val rInner: Float,
    private val strokeOuter: Float,
    private val strokeInner: Float,
) {
    /** 触点落在哪一环的哪个分类上（外环优先；都不中返回 null 表示清除选中） */
    fun sliceAt(pos: Offset, slices: List<CategorySlice>): String? {
        val dx = pos.x - center.x
        val dy = pos.y - center.y
        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val onOuter = kotlin.math.abs(dist - rOuter) <= strokeOuter * 0.9f
        val onInner = kotlin.math.abs(dist - rInner) <= strokeInner * 0.9f
        if (!onOuter && !onInner) return null
        var deg = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (deg < 0f) deg += 360f
        val list = if (onOuter) slices.filter { it.budgetCents > 0L } else slices.filter { it.actualCents > 0L }
        val total = list.sumOf { if (onOuter) it.budgetCents else it.actualCents }
        if (total <= 0L) return null
        var acc = 0f
        list.forEach { s ->
            val sweep = (if (onOuter) s.budgetCents else s.actualCents) * 360f / total
            if (deg >= acc && deg < acc + sweep) return s.name
            acc += sweep
        }
        return list.lastOrNull()?.name
    }
}

internal fun donutGeometry(widthPx: Float, density: Density): DonutGeometry {
    val strokeOuter = with(density) { 20.dp.toPx() }
    val strokeInner = with(density) { 16.dp.toPx() }
    val ringGap = with(density) { 8.dp.toPx() }
    val rOuter = widthPx / 2f - strokeOuter / 2f
    val rInner = rOuter - strokeOuter / 2f - ringGap - strokeInner / 2f
    return DonutGeometry(Offset(widthPx / 2f, widthPx / 2f), rOuter, rInner, strokeOuter, strokeInner)
}

/**
 * 图例：行首一条**分类色条**（粗与旧圆点相同 = 10dp，长 = 行高），分类名 + 右侧两列数字（金额 / 占比）。
 * 每项两排——上排实际花费、下排预算，**金额与金额、占比与占比纵向对齐**，方便逐项对比；
 * 未设预算的分类下排标注「未设」。点一行 = 在圆环上选中该分类（与点环等价），选中行加浅蓝底。
 */
@Composable
fun DonutLegend(
    slices: List<CategorySlice>,
    totalActualCents: Long,
    colors: Map<String, Color>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalBudget = slices.sumOf { it.budgetCents }
    // 两列定宽 + 右对齐：跨分类、上下两排的数字都能对齐成表
    val amountWidth = 96.dp
    val percentWidth = 42.dp

    @Composable
    fun NumberCell(text: String, width: Dp, muted: Boolean = false) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.width(width),
        )
    }

    Column(modifier.fillMaxWidth()) {
        slices.forEachIndexed { index, s ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.accBook.hairline)
            val isSelected = s.name == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    // 色条要撑满整项高度：整项取最小固有高度，色条再 fillMaxHeight
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onSelect(s.name) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(10.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors[s.name] ?: MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    // 上排：实际花费 + 占比
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        NumberCell(Money.formatYuanGrouped(s.actualCents), amountWidth)
                        Spacer(Modifier.width(6.dp))
                        NumberCell(
                            "${ChartMath.sharePercent(s.actualCents, totalActualCents).roundToInt()}%",
                            percentWidth,
                        )
                    }
                    // 下排：预算（与上排同一列，纵向对齐）
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "预算",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (s.budgetCents > 0L) {
                            NumberCell(Money.formatYuanGrouped(s.budgetCents), amountWidth, muted = true)
                            Spacer(Modifier.width(6.dp))
                            NumberCell(
                                "${ChartMath.sharePercent(s.budgetCents, totalBudget).roundToInt()}%",
                                percentWidth,
                                muted = true,
                            )
                        } else {
                            NumberCell("未设", amountWidth, muted = true)
                            Spacer(Modifier.width(6.dp))
                            NumberCell("", percentWidth)
                        }
                    }
                }
            }
        }
    }
}
