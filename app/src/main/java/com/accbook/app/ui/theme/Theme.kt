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

/**
 * 阶段四设计 token 体系。
 *
 * 设计基调：现代、极简、成熟的移动金融产品（Financial App × Subtle Game Economy）——
 * 暖白背景、深墨文字、低饱和暖金强调；金额数字用等宽字形（tnum）保证纵向对齐。
 * 语义色（收入/警示/危险）经 [MaterialTheme.accBook] 下发，全项目禁止散落硬编码色。
 */

// ---------- 语义扩展色（收入 / 警示 / 危险 / 金币 / 发丝线 / 分类调色板） ----------

@Immutable
data class AccBookColors(
    val income: Color,
    val warning: Color,
    val critical: Color,
    /** 金币/Token 主视觉色（低饱和暖金） */
    val gold: Color,
    /** 金币描边、金色系深色文字 */
    val goldDeep: Color,
    /** 发丝分隔线 */
    val hairline: Color,
    /** 分类徽标调色板：按分类名稳定取色，避免 emoji/图标依赖 */
    val categoryPalette: List<Color>,
)

private fun lightAccBookColors() = AccBookColors(
    income = Color(0xFF3D7A50),
    warning = Color(0xFFB47B10),
    critical = Color(0xFFA5402C),
    gold = Color(0xFFC79A3D),
    goldDeep = Color(0xFF8C6A1D),
    hairline = Color(0xFFE7E1D3),
    categoryPalette = listOf(
        Color(0xFF8A6D3B), // 金褐
        Color(0xFF75855A), // 橄榄
        Color(0xFF5E8578), // 灰青
        Color(0xFF8A5F6D), // 绛红
        Color(0xFF667899), // 岩蓝
        Color(0xFF99704F), // 驼色
        Color(0xFF7A6B8A), // 藕紫
        Color(0xFF5E7D8A), // 钢青
    ),
)

private fun darkAccBookColors() = AccBookColors(
    income = Color(0xFF8FC79E),
    warning = Color(0xFFE0B35C),
    critical = Color(0xFFE9886F),
    gold = Color(0xFFD8B76A),
    goldDeep = Color(0xFFB6913F),
    hairline = Color(0xFF332E25),
    categoryPalette = listOf(
        Color(0xFFD8BC85),
        Color(0xFFB4C491),
        Color(0xFF93C6B8),
        Color(0xFFD0A0AF),
        Color(0xFFA9B6D4),
        Color(0xFFD4B08F),
        Color(0xFFC0B0D4),
        Color(0xFF9FC2D4),
    ),
)

val LocalAccBookColors = staticCompositionLocalOf { lightAccBookColors() }

val MaterialTheme.accBook: AccBookColors
    @Composable get() = LocalAccBookColors.current

/** 分类名 → 调色板稳定取色（同一分类全 App 同色，跨会话不变） */
fun categoryColor(palette: List<Color>, categoryName: String): Color {
    if (categoryName.isEmpty()) return palette.first()
    var h = 0
    for (ch in categoryName) h = (h * 31 + ch.code) and 0x7FFFFFFF
    return palette[(h % palette.size).coerceAtLeast(0)]
}

// ---------- 明暗两套 Material 配色（暖白 / 暖墨） ----------

private fun lightScheme() = lightColorScheme(
    background = Color(0xFFF7F4EE),
    onBackground = Color(0xFF201C15),
    surface = Color(0xFFFDFBF6),
    onSurface = Color(0xFF201C15),
    surfaceVariant = Color(0xFFEFE9DD),
    onSurfaceVariant = Color(0xFF6D6656),
    surfaceContainer = Color(0xFFF1ECE1),
    surfaceContainerLow = Color(0xFFF6F2E9),
    surfaceContainerHigh = Color(0xFFECE6DA),
    primary = Color(0xFF8C6A1D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF1E1BC),
    onPrimaryContainer = Color(0xFF3A2C05),
    secondary = Color(0xFF6B6455),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5DFD0),
    onSecondaryContainer = Color(0xFF241F13),
    tertiary = Color(0xFF4E6B57),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD2E8D8),
    onTertiaryContainer = Color(0xFF0D2117),
    error = Color(0xFFA5402C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF410001),
    outline = Color(0xFF98907E),
    outlineVariant = Color(0xFFE3DCCC),
    inverseSurface = Color(0xFF35302A),
    inverseOnSurface = Color(0xFFF6F1E7),
    inversePrimary = Color(0xFFD8B76A),
)

private fun darkScheme() = darkColorScheme(
    background = Color(0xFF1A1712),
    onBackground = Color(0xFFEBE5D8),
    surface = Color(0xFF211D16),
    onSurface = Color(0xFFEBE5D8),
    surfaceVariant = Color(0xFF2E2920),
    onSurfaceVariant = Color(0xFFB0A793),
    surfaceContainer = Color(0xFF25211A),
    surfaceContainerLow = Color(0xFF211D16),
    surfaceContainerHigh = Color(0xFF2F2A21),
    primary = Color(0xFFD8B76A),
    onPrimary = Color(0xFF241B04),
    primaryContainer = Color(0xFF5E4A12),
    onPrimaryContainer = Color(0xFFF5E1B0),
    secondary = Color(0xFFB5AC98),
    onSecondary = Color(0xFF211D14),
    secondaryContainer = Color(0xFF383225),
    onSecondaryContainer = Color(0xFFD2C9B5),
    tertiary = Color(0xFFAFCDB7),
    onTertiary = Color(0xFF1B3524),
    tertiaryContainer = Color(0xFF354D3D),
    onTertiaryContainer = Color(0xFFCCE9D3),
    error = Color(0xFFE9886F),
    onError = Color(0xFF4A1002),
    errorContainer = Color(0xFF6A2A18),
    onErrorContainer = Color(0xFFFFDAD2),
    outline = Color(0xFF8B8270),
    outlineVariant = Color(0xFF3A352B),
    inverseSurface = Color(0xFFEBE5D8),
    inverseOnSurface = Color(0xFF35302A),
    inversePrimary = Color(0xFF8C6A1D),
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
