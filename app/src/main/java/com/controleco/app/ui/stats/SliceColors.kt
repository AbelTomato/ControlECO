package com.controleco.app.ui.stats

import androidx.compose.ui.graphics.Color
import com.controleco.app.data.CategoryIcon
import com.controleco.app.ui.theme.categoryColor
import com.controleco.app.ui.theme.ringColors

/**
 * 圆环/图例的配色来源（用户要求"统计页颜色和分类图标颜色同步"）：
 *
 * - 分类图标**自定义过颜色**（`GLYPH:名字:RRGGBB` 或"只改颜色"的 `GLYPH::RRGGBB`）→ 直接用那个颜色；
 * - 分类用的是**内置图形**（默认按名字取形，或选了某个图形但没改色）→ 用按分类名的自动取色，
 *   也就是账单列表里那个图标的颜色；
 * - 图标是 **emoji / 相册图片**（本身没有"颜色"可言）→ 交给 [ringColors] 从调色板里
 *   挑"离所有已用颜色最远"的一个。
 *
 * 结果只取决于切片名字集合与各分类的图标规格，换月换年不跳色。
 */
internal fun sliceColorsOf(
    palette: List<Color>,
    slices: List<CategorySlice>,
    iconByName: Map<String, String>,
): Map<String, Color> {
    val fixed = LinkedHashMap<String, Color>()
    val colorless = ArrayList<String>()
    slices.map { it.name }.filter { it.isNotEmpty() }.distinct().sorted().forEach { name ->
        val spec = iconByName[name].orEmpty()
        val explicit = CategoryIcon.glyphColorOf(spec)
        val hasNoColor = CategoryIcon.emojiOf(spec) != null || CategoryIcon.imageOf(spec) != null
        when {
            explicit != null -> fixed[name] = Color(explicit or 0xFF000000.toInt())
            hasNoColor -> colorless += name
            else -> fixed[name] = categoryColor(palette, name)
        }
    }
    return ringColors(palette, fixed, colorless)
}
