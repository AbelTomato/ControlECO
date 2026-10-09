package com.accbook.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.accbook.app.ui.theme.accBook
import com.accbook.app.ui.theme.categoryColor

/**
 * 分类标记：账单列表行首的小 logo，也是自定义图标的"内置图形"库。
 *
 * 图形来源两类：
 * 1. **用户提供的 6 个 SVG**（购物 / 餐饮 / 交通 / 工资 / 娱乐 / 日用，见 [GlyphPaths]）——
 *    按路径原样绘制，颜色由分类色板下发；
 * 2. 自绘几何图形（碗 / 车 / 袋 / 房 / 十字 / 星 / 书 / 信号 / 红包 / K 线 / ￥ …）。
 *
 * 常见分类按关键词命中语义图形，其余按分类名哈希稳定取形；同一分类在任何页面、
 * 任何会话都是同一形状 + 同一颜色，便于判别。
 */
enum class Glyph(val label: String) {
    // 用户提供的 SVG 图形
    SVG_BAG("购物袋"),
    SVG_MEAL("餐盘"),
    SVG_CAR("汽车"),
    SVG_SALARY("工资袋"),
    SVG_GAME("游戏手柄"),
    SVG_DAILY("日用瓶"),
    // 自绘几何图形
    BURGER("汉堡"),
    BOWL("碗"),
    CAR("小车"),
    BAG("袋子"),
    HOUSE("房子"),
    CROSS("医疗"),
    STAR("星星"),
    BOOK("书本"),
    SIGNAL("信号"),
    ARROW_UP("上行箭头"),
    WAVE("波动"),
    RED_PACKET("红包"),
    CANDLES("K 线"),
    YEN("￥"),
    RING("圆环"),
    DOTS("省略号"),
    TRIANGLE("三角"),
    DIAMOND("菱形"),
    BARS("柱状图"),
    GRID("方块组"),
    ;

    /** SVG 图形对应的路径数据；自绘图形为 null */
    val svgData: String?
        get() = when (this) {
            SVG_BAG -> GlyphPaths.BAG
            SVG_MEAL -> GlyphPaths.MEAL
            SVG_CAR -> GlyphPaths.CAR
            SVG_SALARY -> GlyphPaths.SALARY
            SVG_GAME -> GlyphPaths.GAME
            SVG_DAILY -> GlyphPaths.DAILY
            else -> null
        }
}

private val KEYWORDS: List<Pair<List<String>, Glyph>> = listOf(
    listOf("餐", "食", "饭", "菜", "汉堡", "快餐", "外卖", "宵夜", "烧烤", "饮料") to Glyph.SVG_MEAL,
    listOf("咖啡", "奶茶", "饮", "酒", "茶") to Glyph.BOWL,
    listOf("交通", "出行", "打车", "网约", "出租", "地铁", "公交", "加油", "停车", "高铁", "机票", "车") to Glyph.SVG_CAR,
    listOf("购物", "服饰", "衣", "鞋", "超市", "百货", "美妆", "数码") to Glyph.SVG_BAG,
    listOf("日用", "生活用品", "日用品", "家居日用") to Glyph.SVG_DAILY,
    listOf("住", "房", "租", "物业", "水电", "燃气", "装修") to Glyph.HOUSE,
    listOf("医", "药", "健康", "体检", "挂号") to Glyph.CROSS,
    listOf("游戏", "娱乐", "电玩", "网吧") to Glyph.SVG_GAME,
    listOf("电影", "旅行", "旅游", "景点", "演出", "会员") to Glyph.STAR,
    listOf("教育", "学习", "书", "培训", "课程", "考试") to Glyph.BOOK,
    listOf("通讯", "话费", "流量", "网络", "宽带", "电话") to Glyph.SIGNAL,
    listOf("红包", "压岁") to Glyph.RED_PACKET,
    listOf("工资", "薪", "奖金", "收入", "报酬", "补贴") to Glyph.SVG_SALARY,
    listOf("报销", "退款", "收款", "转入") to Glyph.ARROW_UP,
    listOf("转", "理财", "投资", "基金", "股票", "公积金", "收益") to Glyph.CANDLES,
    listOf("其他", "其它", "杂") to Glyph.DOTS,
)

/** 分类名 → 形状：先关键词，再按名字哈希稳定取形 */
fun glyphForName(categoryName: String): Glyph {
    KEYWORDS.firstOrNull { (words, _) -> words.any { categoryName.contains(it) } }?.let { return it.second }
    val shapes = Glyph.entries.filter { it != Glyph.DOTS && it != Glyph.RED_PACKET }
    var h = 7
    for (ch in categoryName) h = (h * 31 + ch.code) and 0x7FFFFFFF
    return shapes[h % shapes.size]
}

/** 图标规格里的图形名 → 图形（未知名字返回 null，调用方回落到按名字取形） */
fun glyphByName(name: String): Glyph? = Glyph.entries.firstOrNull { it.name == name }

/** 图形的固定用色：红包用喜庆红，其余用分类稳定色 */
@Composable
fun glyphTint(glyph: Glyph, categoryName: String): Color =
    if (glyph == Glyph.RED_PACKET) {
        MaterialTheme.accBook.festiveRed
    } else {
        categoryColor(MaterialTheme.accBook.categoryPalette, categoryName)
    }

/** 默认分类标记（未自定义图标时）：按分类名取形 + 分类色 */
@Composable
fun CategoryGlyph(categoryName: String, size: Dp = 40.dp) {
    val glyph = remember(categoryName) { glyphForName(categoryName) }
    // 不画底板，只留图形本身
    GlyphIcon(glyph = glyph, tint = glyphTint(glyph, categoryName), size = size)
}

/** 指定图形的渲染（图标选择器、自定义图标、默认标记共用） */
@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.62f)) { drawGlyph(glyph, tint) }
    }
}

// ---------- 各形状画法：统一画在 s×s 画布里 ----------

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val svg = glyph.svgData
    if (svg != null) {
        drawSvgGlyph(svg, color)
        return
    }
    val s = size.minDimension
    val w = s * 0.15f
    val stroke = Stroke(width = w, cap = StrokeCap.Round)
    when (glyph) {
        Glyph.BURGER -> {
            // 三层汉堡：上层面包（半圆）+ 中间夹层 + 下层面包
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(s * 0.06f, s * 0.14f),
                size = Size(s * 0.88f, s * 0.88f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.04f, s * 0.52f),
                size = Size(s * 0.92f, s * 0.16f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.10f, s * 0.74f),
                size = Size(s * 0.80f, s * 0.18f),
                cornerRadius = CornerRadius(s * 0.09f),
            )
        }
        Glyph.RED_PACKET -> {
            // 红包：上方倒三角（最长边 = 长方形宽）+ 下方长方形顶部扣去倒三角。
            // 三角的两条斜边与长方形缺口的斜边**平行**，只留一条均匀窄缝（0.10s 的纵向错位），
            // 而不是把长方形整体下移留出一大片空隙。
            val flap = Path().apply {
                moveTo(s * 0.16f, s * 0.08f)
                lineTo(s * 0.84f, s * 0.08f)
                lineTo(s * 0.50f, s * 0.42f)
                close()
            }
            drawPath(flap, color)
            val body = Path().apply {
                moveTo(s * 0.16f, s * 0.18f)
                lineTo(s * 0.50f, s * 0.52f)
                lineTo(s * 0.84f, s * 0.18f)
                lineTo(s * 0.84f, s * 0.94f)
                lineTo(s * 0.16f, s * 0.94f)
                close()
            }
            drawPath(body, color)
        }
        Glyph.CANDLES -> {
            // 三根 K 线：实体矩形 + 中间穿插竖线，高度/长短各不相同
            listOf(
                Triple(0.16f, 0.44f, 0.34f),
                Triple(0.42f, 0.18f, 0.50f),
                Triple(0.68f, 0.32f, 0.38f),
            ).forEach { (x, top, height) ->
                val cx = s * (x + 0.08f)
                drawLine(
                    color = color,
                    start = Offset(cx, s * (top - 0.12f).coerceAtLeast(0f)),
                    end = Offset(cx, s * (top + height + 0.12f).coerceAtMost(s)),
                    strokeWidth = s * 0.05f,
                    cap = StrokeCap.Round,
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * x, s * top),
                    size = Size(s * 0.16f, s * height),
                    cornerRadius = CornerRadius(s * 0.04f),
                )
            }
        }
        Glyph.YEN -> {
            // ￥：两撇 + 竖干 + 两横
            drawLine(color, Offset(s * 0.20f, s * 0.10f), Offset(s * 0.50f, s * 0.46f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.80f, s * 0.10f), Offset(s * 0.50f, s * 0.46f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.50f, s * 0.42f), Offset(s * 0.50f, s * 0.92f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.22f, s * 0.58f), Offset(s * 0.78f, s * 0.58f), w, StrokeCap.Round)
            drawLine(color, Offset(s * 0.22f, s * 0.78f), Offset(s * 0.78f, s * 0.78f), w, StrokeCap.Round)
        }
        Glyph.BOWL -> {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.06f, s * 0.30f),
                size = Size(s * 0.88f, s * 0.88f),
                style = stroke,
            )
            drawLine(
                color = color,
                start = Offset(s * 0.06f, s * 0.30f),
                end = Offset(s * 0.94f, s * 0.30f),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
        }
        Glyph.CAR -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.06f, s * 0.38f),
                size = Size(s * 0.88f, s * 0.30f),
                cornerRadius = CornerRadius(s * 0.10f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.24f, s * 0.16f),
                size = Size(s * 0.52f, s * 0.22f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawCircle(color = color, radius = s * 0.10f, center = Offset(s * 0.26f, s * 0.76f))
            drawCircle(color = color, radius = s * 0.10f, center = Offset(s * 0.74f, s * 0.76f))
        }
        Glyph.BAG -> {
            val body = Path().apply {
                moveTo(s * 0.16f, s * 0.34f)
                lineTo(s * 0.84f, s * 0.34f)
                lineTo(s * 0.74f, s * 0.90f)
                lineTo(s * 0.26f, s * 0.90f)
                close()
            }
            drawPath(body, color)
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(s * 0.32f, s * 0.08f),
                size = Size(s * 0.36f, s * 0.36f),
                style = stroke,
            )
        }
        Glyph.HOUSE -> {
            val roof = Path().apply {
                moveTo(s * 0.50f, s * 0.08f)
                lineTo(s * 0.94f, s * 0.46f)
                lineTo(s * 0.06f, s * 0.46f)
                close()
            }
            drawPath(roof, color)
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.20f, s * 0.50f),
                size = Size(s * 0.60f, s * 0.40f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
        }
        Glyph.CROSS -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.40f, s * 0.10f),
                size = Size(s * 0.20f, s * 0.80f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.10f, s * 0.40f),
                size = Size(s * 0.80f, s * 0.20f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
        }
        Glyph.STAR -> {
            listOf(0f, 90f).forEach { deg ->
                rotate(deg, pivot = Offset(s / 2f, s / 2f)) {
                    val p = Path().apply {
                        moveTo(s * 0.50f, s * 0.04f)
                        lineTo(s * 0.70f, s * 0.50f)
                        lineTo(s * 0.50f, s * 0.96f)
                        lineTo(s * 0.30f, s * 0.50f)
                        close()
                    }
                    drawPath(p, color)
                }
            }
        }
        Glyph.BOOK -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.06f, s * 0.24f),
                size = Size(s * 0.38f, s * 0.52f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.56f, s * 0.24f),
                size = Size(s * 0.38f, s * 0.52f),
                cornerRadius = CornerRadius(s * 0.08f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.46f, s * 0.16f),
                size = Size(s * 0.08f, s * 0.68f),
                cornerRadius = CornerRadius(s * 0.04f),
            )
        }
        Glyph.SIGNAL -> {
            listOf(0.42f, 0.70f, 0.98f).forEach { f ->
                drawArc(
                    color = color,
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(s * (0.5f - f / 2f), s * 0.86f - f * s * 0.72f),
                    size = Size(s * f, s * f),
                    style = stroke,
                )
            }
            drawCircle(color = color, radius = s * 0.11f, center = Offset(s * 0.50f, s * 0.86f))
        }
        Glyph.ARROW_UP -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.42f, s * 0.32f),
                size = Size(s * 0.16f, s * 0.60f),
                cornerRadius = CornerRadius(s * 0.06f),
            )
            val head = Path().apply {
                moveTo(s * 0.50f, s * 0.06f)
                lineTo(s * 0.84f, s * 0.42f)
                lineTo(s * 0.16f, s * 0.42f)
                close()
            }
            drawPath(head, color)
        }
        Glyph.WAVE -> {
            val p = Path().apply {
                moveTo(s * 0.06f, s * 0.62f)
                cubicTo(s * 0.26f, s * 0.14f, s * 0.48f, s * 0.94f, s * 0.94f, s * 0.38f)
            }
            drawPath(p, color, style = stroke)
        }
        Glyph.RING -> {
            drawCircle(color = color, radius = s * 0.34f, center = Offset(s / 2f, s / 2f), style = Stroke(w))
            drawCircle(color = color, radius = s * 0.10f, center = Offset(s * 0.74f, s * 0.74f))
        }
        Glyph.DOTS -> {
            drawCircle(color = color, radius = s * 0.13f, center = Offset(s * 0.22f, s * 0.50f))
            drawCircle(color = color, radius = s * 0.13f, center = Offset(s * 0.50f, s * 0.50f))
            drawCircle(color = color, radius = s * 0.13f, center = Offset(s * 0.78f, s * 0.50f))
        }
        Glyph.TRIANGLE -> {
            val p = Path().apply {
                moveTo(s * 0.50f, s * 0.10f)
                lineTo(s * 0.92f, s * 0.86f)
                lineTo(s * 0.08f, s * 0.86f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.DIAMOND -> {
            val p = Path().apply {
                moveTo(s * 0.50f, s * 0.06f)
                lineTo(s * 0.92f, s * 0.50f)
                lineTo(s * 0.50f, s * 0.94f)
                lineTo(s * 0.08f, s * 0.50f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.BARS -> {
            listOf(0.16f to 0.44f, 0.42f to 0.68f, 0.68f to 0.90f).forEach { (x, h) ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * x, s * (0.92f - h)),
                    size = Size(s * 0.16f, s * h),
                    cornerRadius = CornerRadius(s * 0.06f),
                )
            }
        }
        Glyph.GRID -> {
            listOf(0.10f to 0.10f, 0.54f to 0.10f, 0.10f to 0.54f, 0.54f to 0.54f).forEach { (x, y) ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * x, s * y),
                    size = Size(s * 0.36f, s * 0.36f),
                    cornerRadius = CornerRadius(s * 0.10f),
                )
            }
        }
        // 六个 SVG 图形在上面已经提前 return
        else -> Unit
    }
}

// ---------- 用户提供的 SVG 图形：按路径原样绘制 ----------

/** 解析结果进程内缓存（只在绘制线程访问）：同一图形反复画不再重复解析 */
private val svgPathCache = HashMap<String, Path>()

private fun svgPathOf(data: String): Path = svgPathCache.getOrPut(data) {
    runCatching { PathParser().parsePathString(data).toPath() }.getOrElse { Path() }
}

/** 把 960×960 的 viewBox（x∈[0,960]，y∈[-960,0]）等比缩放到当前画布并居中 */
private fun DrawScope.drawSvgGlyph(data: String, color: Color) {
    val s = size.minDimension
    val k = s / GlyphPaths.VIEWPORT
    val half = GlyphPaths.VIEWPORT / 2f
    withTransform({
        translate(size.width / 2f, size.height / 2f)
        scale(k, k, pivot = Offset.Zero)
        // 该坐标系原点在左上、y 向下为负：先把中心 (480,-480) 平移到原点
        translate(-half, half)
    }) {
        drawPath(svgPathOf(data), color)
    }
}
