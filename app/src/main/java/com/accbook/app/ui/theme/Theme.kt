package com.accbook.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.accbook.app.data.budget.Budget

/**
 * 阶段四设计 token 体系。
 *
 * 设计基调：现代、极简、成熟的移动金融产品——
 * 白色背景 + 低饱和蓝主色（冷色调），深墨蓝灰文字；金额数字用等宽字形（tnum）保证纵向对齐。
 * 语义色（收入/警示/危险）经 [MaterialTheme.accBook] 下发，全项目禁止散落硬编码色。
 */

// ---------- 语义扩展色（收入 / 警示 / 危险 / 发丝线 / 分类调色板） ----------

@Immutable
data class AccBookColors(
    val income: Color,
    val warning: Color,
    /** 危险/超支红（饱和度高于常规提示色，用于超支状态） */
    val critical: Color,
    /** 警报红：超支越严重越向此色过渡（心跳警示用，饱和度最高） */
    val criticalAlert: Color,
    /** 悬浮"记账"按钮底色：比 primary 略高饱和，用于需要一眼看到的主操作 */
    val primaryStrong: Color,
    /** 发丝分隔线 */
    val hairline: Color,
    /** 圆环底轨：比 hairline 略深，保证在浅蓝页面底色上仍能看清未完成部分 */
    val ringTrack: Color,
    /** 红包等喜庆色（分类 logo 专用，明亮红） */
    val festiveRed: Color,
    /** 叠加在图片/照片上的前景色（图片编辑器参考框、角落标记用；深浅色主题都是白） */
    val overlayInk: Color,
    /** 结余卡浅底配色（浅色背景图/默认浅蓝卡面用） */
    val cardOnLight: CardTones,
    /** 结余卡深底配色（深色背景图/深色主题卡面用） */
    val cardOnDark: CardTones,
    /** 分类徽标调色板：按分类名稳定取色，避免 emoji/图标依赖 */
    val categoryPalette: List<Color>,
)

internal fun lightAccBookColors() = AccBookColors(
    income = Color(0xFF3D7A50),
    warning = Color(0xFFB47B10),
    critical = Color(0xFFC1352B),
    criticalAlert = Color(0xFFE01F1F),
    primaryStrong = Color(0xFF3C6CA8),
    hairline = Color(0xFFE3EAF1),
    ringTrack = Color(0xFFD6E1ED),
    festiveRed = Color(0xFFD2453C),
    overlayInk = Color(0xFFFFFFFF),
    cardOnLight = CardTones(
        ink = Color(0xFF14212E),
        sub = Color(0xFF4E6175),
        income = Color(0xFF2F6B45),
        expense = Color(0xFFC1352B),
        scrim = Color(0xFFFFFFFF),
        pillContainer = Color(0x263C6CA8),
        pillSelected = Color(0xFF3C6CA8),
        pillSelectedContent = Color(0xFFFFFFFF),
    ),
    cardOnDark = CardTones(
        ink = Color(0xFFFFFFFF),
        sub = Color(0xCCFFFFFF),
        income = Color(0xFF8FE3A6),
        expense = Color(0xFFFFA694),
        scrim = Color(0xFF000000),
        pillContainer = Color(0x33FFFFFF),
        pillSelected = Color(0xFFFFFFFF),
        pillSelectedContent = Color(0xFF16202B),
    ),
    // 分类调色板：高饱和 + 16 色（暖→绿→青→蓝→紫→玫红），色相之外再拉开明度，
    // 让同一张饼图里的分类尽量不撞色也不近似；配色表在 Theme 内集中维护。
    // 明度整体取"中亮"档（比首版提亮一档）：圆环上是成片的纯色块，偏暗会显脏。
    categoryPalette = listOf(
        Color(0xFFEF4444), // 红
        Color(0xFFF97316), // 橙
        Color(0xFFEAB308), // 琥珀
        Color(0xFFCA8A04), // 棕黄
        Color(0xFF84CC16), // 黄绿
        Color(0xFF22C55E), // 绿
        Color(0xFF16A34A), // 深绿
        Color(0xFF14B8A6), // 深青绿
        Color(0xFF06B6D4), // 青
        Color(0xFF3B82F6), // 蓝
        Color(0xFF2563EB), // 深蓝
        Color(0xFF6366F1), // 靛蓝
        Color(0xFF8B5CF6), // 紫罗兰
        Color(0xFFD946EF), // 紫
        Color(0xFFEC4899), // 玫红
        Color(0xFFF43F5E), // 深玫红
    ),
)

internal fun darkAccBookColors() = AccBookColors(
    income = Color(0xFF8FC79E),
    warning = Color(0xFFE0B35C),
    critical = Color(0xFFF07C64),
    criticalAlert = Color(0xFFFF5844),
    primaryStrong = Color(0xFF5B93D4),
    hairline = Color(0xFF2A323B),
    ringTrack = Color(0xFF333C47),
    festiveRed = Color(0xFFE88272),
    overlayInk = Color(0xFFFFFFFF),
    cardOnLight = CardTones(
        ink = Color(0xFF14212E),
        sub = Color(0xFF4E6175),
        income = Color(0xFF2F6B45),
        expense = Color(0xFFC1352B),
        scrim = Color(0xFFFFFFFF),
        pillContainer = Color(0x263C6CA8),
        pillSelected = Color(0xFF3C6CA8),
        pillSelectedContent = Color(0xFFFFFFFF),
    ),
    cardOnDark = CardTones(
        ink = Color(0xFFFFFFFF),
        sub = Color(0xCCFFFFFF),
        income = Color(0xFF8FE3A6),
        expense = Color(0xFFFFA694),
        scrim = Color(0xFF000000),
        pillContainer = Color(0x33FFFFFF),
        pillSelected = Color(0xFFFFFFFF),
        pillSelectedContent = Color(0xFF16202B),
    ),
    categoryPalette = listOf(
        Color(0xFFF87171), // 红
        Color(0xFFFB923C), // 橙
        Color(0xFFFCD34D), // 琥珀
        Color(0xFFCA8A04), // 棕黄
        Color(0xFFA3E635), // 黄绿
        Color(0xFF4ADE80), // 绿
        Color(0xFF22C55E), // 深绿
        Color(0xFF2DD4BF), // 深青绿
        Color(0xFF22D3EE), // 青
        Color(0xFF60A5FA), // 蓝
        Color(0xFF3B82F6), // 深蓝
        Color(0xFF818CF8), // 靛蓝
        Color(0xFFA78BFA), // 紫罗兰
        Color(0xFFE879F9), // 紫
        Color(0xFFF472B6), // 玫红
        Color(0xFFFB7185), // 深玫红
    ),
)

val LocalAccBookColors = staticCompositionLocalOf { lightAccBookColors() }

val MaterialTheme.accBook: AccBookColors
    @Composable get() = LocalAccBookColors.current

/**
 * 结余卡的用色方案：浅底/深底各一套，由背景亮度自动选择，
 * 对比度不足时再叠一层 [scrim]（透明度按 WCAG 对比度 ≥ 3 反算）。
 */
@Immutable
data class CardTones(
    val ink: Color,
    val sub: Color,
    val income: Color,
    val expense: Color,
    val scrim: Color,
    val pillContainer: Color,
    val pillSelected: Color,
    val pillSelectedContent: Color,
)

/** 分类名 → 调色板稳定取色（同一分类全 App 同色，跨会话不变） */
fun categoryColor(palette: List<Color>, categoryName: String): Color {
    if (palette.isEmpty()) return Color.Unspecified
    return palette[preferredSlot(palette.size, categoryName)]
}

/**
 * 一组分类名 → 一组**互不重复**的颜色（饼图/图例这类"同屏并列"的场景用）。
 *
 * 每个分类先按名字哈希取首选位，被占则线性探测下一个空位；分配顺序按名字排序，
 * 因此结果只取决于**名字集合**（与传入顺序、金额排序无关），换月换年也不会跳色。
 * 分类数超过调色板容量时才会出现重复（16 色对普通账本足够）。
 */
fun categoryColors(palette: List<Color>, names: List<String>): Map<String, Color> {
    if (palette.isEmpty()) return emptyMap()
    val used = BooleanArray(palette.size)
    val out = LinkedHashMap<String, Color>()
    names.filter { it.isNotEmpty() }.distinct().sorted().forEach { name ->
        val start = preferredSlot(palette.size, name)
        // 注意：firstOrNull 返回的是"探测步数"，槽位还得再按起点绕回去
        val step = (0 until palette.size).firstOrNull { !used[(start + it) % palette.size] } ?: 0
        val slot = (start + step) % palette.size
        used[slot] = true
        out[name] = palette[slot]
    }
    return out
}

/** 名字哈希 → 调色板首选位（同一名字永远从同一位开始探测） */
private fun preferredSlot(size: Int, name: String): Int {
    if (size <= 0) return 0
    var h = 0
    for (ch in name) h = (h * 31 + ch.code) and 0x7FFFFFFF
    return h % size
}

/**
 * 剩余额度档位 → 语义色（绿 / 黄 / 红）。首页（含超支渐强）与个人页预算卡共用这套映射，
 * 避免同一档位在两处出现不同颜色。
 */
fun toneColor(tone: Budget.Tone, colors: AccBookColors): Color = when (tone) {
    Budget.Tone.OK -> colors.income
    Budget.Tone.MID -> colors.warning
    Budget.Tone.LOW -> colors.critical
}

// ---------- 明暗两套 Material 配色（白 + 浅蓝 / 冷墨蓝） ----------

private fun lightScheme() = lightColorScheme(
    // 页面底色用带蓝的白（卡片为纯白）——全 App 冷色调，不再有暖黄感
    background = Color(0xFFF1F5FB),
    onBackground = Color(0xFF1B2430),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2430),
    surfaceVariant = Color(0xFFE9EFF7),
    onSurfaceVariant = Color(0xFF5B6B7C),
    surfaceDim = Color(0xFFE3EAF3),
    surfaceBright = Color(0xFFFCFDFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7FAFD),
    surfaceContainer = Color(0xFFF4F8FC),
    surfaceContainerHigh = Color(0xFFEAF0F8),
    surfaceContainerHighest = Color(0xFFE2EAF4),
    // 海拔叠加色跟随主色（默认是 M3 基线的暖紫，会在浅底上透出暖调）
    surfaceTint = Color(0xFF4E6E8E),
    primary = Color(0xFF4E6E8E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8F5),
    onPrimaryContainer = Color(0xFF0F2740),
    secondary = Color(0xFF5F6B77),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E9F0),
    onSecondaryContainer = Color(0xFF1E262E),
    tertiary = Color(0xFF456B63),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD6E8E4),
    onTertiaryContainer = Color(0xFF0E211D),
    error = Color(0xFFC1352B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9D2),
    onErrorContainer = Color(0xFF410001),
    outline = Color(0xFF8B97A3),
    outlineVariant = Color(0xFFE1E8EF),
    inverseSurface = Color(0xFF2A3139),
    inverseOnSurface = Color(0xFFF0F4F8),
    inversePrimary = Color(0xFFA5C6E8),
)

private fun darkScheme() = darkColorScheme(
    background = Color(0xFF101419),
    onBackground = Color(0xFFE4EAF1),
    surface = Color(0xFF171C23),
    onSurface = Color(0xFFE4EAF1),
    surfaceVariant = Color(0xFF212832),
    onSurfaceVariant = Color(0xFFA3AFBC),
    surfaceDim = Color(0xFF101419),
    surfaceBright = Color(0xFF333C47),
    surfaceContainerLowest = Color(0xFF0C1015),
    surfaceContainerLow = Color(0xFF151A21),
    surfaceContainer = Color(0xFF191F27),
    surfaceContainerHigh = Color(0xFF232A34),
    surfaceContainerHighest = Color(0xFF2D353F),
    surfaceTint = Color(0xFFA5C6E8),
    primary = Color(0xFFA5C6E8),
    onPrimary = Color(0xFF12283E),
    primaryContainer = Color(0xFF2C4A69),
    onPrimaryContainer = Color(0xFFD3E5F6),
    secondary = Color(0xFFAAB6C2),
    onSecondary = Color(0xFF1A2029),
    secondaryContainer = Color(0xFF2A323C),
    onSecondaryContainer = Color(0xFFD5DEE7),
    tertiary = Color(0xFFA9CFC7),
    onTertiary = Color(0xFF14302B),
    tertiaryContainer = Color(0xFF27453F),
    onTertiaryContainer = Color(0xFFCDE9E3),
    error = Color(0xFFF07C64),
    onError = Color(0xFF4A1002),
    errorContainer = Color(0xFF6A2A18),
    onErrorContainer = Color(0xFFFFDAD2),
    outline = Color(0xFF828E9B),
    outlineVariant = Color(0xFF333B45),
    inverseSurface = Color(0xFFE4EAF1),
    inverseOnSurface = Color(0xFF2A3139),
    inversePrimary = Color(0xFF4E6E8E),
)

// ---------- 字体：金额用等宽数字（tnum），层级靠字号与留白 ----------

private fun tabular(style: TextStyle) = style.copy(fontFeatureSettings = "tnum")

private fun appTypography(): Typography {
    val base = Typography()
    return base.copy(
        // 首页"剩余可用金额"——全页视觉层级最高的信息
        displayMedium = tabular(
            base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        ),
        displaySmall = tabular(base.displaySmall.copy(fontWeight = FontWeight.SemiBold)),
        // 快速记账金额输入
        headlineLarge = tabular(base.headlineLarge.copy(fontWeight = FontWeight.SemiBold)),
        // 列表/汇总里的金额列
        titleMedium = tabular(base.titleMedium),
        titleSmall = tabular(base.titleSmall),
    )
}

private val appShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun AccBookTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) darkScheme() else lightScheme()
    val extended = if (darkTheme) darkAccBookColors() else lightAccBookColors()
    androidx.compose.runtime.CompositionLocalProvider(LocalAccBookColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography(),
            shapes = appShapes,
            content = content,
        )
    }
}
