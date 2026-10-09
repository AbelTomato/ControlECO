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

private fun lightAccBookColors() = AccBookColors(
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
    // 分类调色板：高饱和、色相按 36° 均匀分布（红→橙→橄榄→绿→青绿→青→蓝→紫→洋红→玫红），
    // 保证饼图/圆环里相邻分类也能一眼区分；不再用低饱和灰调。
    categoryPalette = listOf(
        Color(0xFFDC2626), // 红
        Color(0xFFEA580C), // 橙
        Color(0xFF65A30D), // 橄榄
        Color(0xFF059669), // 绿
        Color(0xFF0D9488), // 青绿
        Color(0xFF0891B2), // 青
        Color(0xFF2563EB), // 蓝
        Color(0xFF6D28D9), // 紫
        Color(0xFFC026D3), // 洋红
        Color(0xFFDB2777), // 玫红
    ),
)

private fun darkAccBookColors() = AccBookColors(
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
        Color(0xFFA3E635), // 橄榄
        Color(0xFF34D399), // 绿
        Color(0xFF2DD4BF), // 青绿
        Color(0xFF22D3EE), // 青
        Color(0xFF60A5FA), // 蓝
        Color(0xFFA78BFA), // 紫
        Color(0xFFE879F9), // 洋红
        Color(0xFFF472B6), // 玫红
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
    if (categoryName.isEmpty()) return palette.first()
    var h = 0
    for (ch in categoryName) h = (h * 31 + ch.code) and 0x7FFFFFFF
    return palette[(h % palette.size).coerceAtLeast(0)]
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
