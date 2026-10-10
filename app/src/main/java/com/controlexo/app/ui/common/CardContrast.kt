package com.controlexo.app.ui.common

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 结余卡背景明暗判定与对比度保障（WCAG 2.x 口径）。
 *
 * 用法：先量背景亮度（图片取采样均值、默认卡面取渐变底色），据此选浅底/深底配色，
 * 再用 [scrimAlphaFor] 反算需要叠多厚的遮罩，保证正文对比度 ≥ [MIN_CONTRAST]。
 */
const val MIN_CONTRAST = 3f

/** sRGB 单通道线性化 */
private fun linearize(channel: Float): Float =
    if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

/** 相对亮度（0 黑 → 1 白），入参为 0..1 的 sRGB 通道 */
fun relativeLuminance(r: Float, g: Float, b: Float): Float =
    0.2126f * linearize(r) + 0.7152f * linearize(g) + 0.0722f * linearize(b)

fun relativeLuminance(color: Color): Float = relativeLuminance(color.red, color.green, color.blue)

/** 两亮度之间的对比度比值（1 ~ 21） */
fun contrastRatio(a: Float, b: Float): Float =
    (max(a, b) + 0.05f) / (min(a, b) + 0.05f)

/**
 * 让 [inkLuminance] 的文字与背景达到 [minRatio] 所需的遮罩不透明度。
 * 返回 0 表示已经达标；返回 0..0.9 表示还需叠 [scrim]（浅底用白、深底用黑）的透明度。
 */
fun scrimAlphaFor(bgLuminance: Float, inkLuminance: Float, minRatio: Float = MIN_CONTRAST): Float {
    val bg = bgLuminance.coerceIn(0.0001f, 0.9999f)
    if (contrastRatio(bg, inkLuminance) >= minRatio) return 0f
    return if (inkLuminance > bg) {
        // 白字：把背景压暗 —— L' = bg × (1 - a)
        val target = ((inkLuminance + 0.05f) / minRatio - 0.05f).coerceAtLeast(0f)
        (1f - target / bg).coerceIn(0f, 0.9f)
    } else {
        // 墨字：把背景提亮 —— L' = bg + a × (1 - bg)
        val target = (minRatio * (inkLuminance + 0.05f) - 0.05f).coerceAtMost(1f)
        ((target - bg) / (1f - bg)).coerceIn(0f, 0.9f)
    }
}
