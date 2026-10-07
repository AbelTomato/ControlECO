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
    /** 发丝分隔线 */
    val hairline: Color,
    /** 分类徽标调色板：按分类名稳定取色，避免 emoji/图标依赖 */
    val categoryPalette: List<Color>,
)

private fun lightAccBookColors() = AccBookColors(
    income = Color(0xFF3D7A50),
    warning = Color(0xFFB47B10),
    critical = Color(0xFFC1352B),
    criticalAlert = Color(0xFFE01F1F),
    hairline = Color(0xFFE3EAF1),
    categoryPalette = listOf(
        Color(0xFF5E7C9E), // 蓝
        Color(0xFF6E8B84), // 青绿灰
        Color(0xFF7B7BA6), // 蓝紫
        Color(0xFF9C7B8B), // 灰玫
        Color(0xFF5F8CA6), // 钢青
        Color(0xFF78909C), // 石板灰
        Color(0xFF8A7FA8), // 藕紫
        Color(0xFF6F9A96), // 灰青
    ),
)

private fun darkAccBookColors() = AccBookColors(
    income = Color(0xFF8FC79E),
    warning = Color(0xFFE0B35C),
    critical = Color(0xFFF07C64),
    criticalAlert = Color(0xFFFF5844),
    hairline = Color(0xFF2A323B),
    categoryPalette = listOf(
        Color(0xFF9FBAD8),
        Color(0xFF9CC0B6),
        Color(0xFFB0AEDC),
        Color(0xFFD0A8B8),
        Color(0xFF9CC2D8),
        Color(0xFFB0C0CC),
        Color(0xFFC2B4DC),
        Color(0xFFA8CFC9),
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

// ---------- 明暗两套 Material 配色（白 + 浅蓝 / 冷墨蓝） ----------

private fun lightScheme() = lightColorScheme(
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1B2430),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2430),
    surfaceVariant = Color(0xFFEDF2F8),
    onSurfaceVariant = Color(0xFF5B6B7C),
    surfaceContainer = Color(0xFFF3F7FB),
    surfaceContainerLow = Color(0xFFF8FAFD),
    surfaceContainerHigh = Color(0xFFE9F0F7),
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
    background = Color(0xFF12161C),
    onBackground = Color(0xFFE4EAF1),
    surface = Color(0xFF171C23),
    onSurface = Color(0xFFE4EAF1),
    surfaceVariant = Color(0xFF232A33),
    onSurfaceVariant = Color(0xFFA3AFBC),
    surfaceContainer = Color(0xFF1C222A),
    surfaceContainerLow = Color(0xFF171C23),
    surfaceContainerHigh = Color(0xFF252C36),
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
