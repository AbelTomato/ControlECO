package com.controlexo.app.data

/**
 * 结余卡背景图的位移/缩放/旋转（用户手动调整）。
 *
 * 位移用**卡片宽高的比例**表示（与分辨率无关）：offsetX = 0.1 表示图片右移卡片宽度的 10%。
 * 默认值 = 居中铺满（scale 1、无位移、不旋转）。
 */
data class CardTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** 旋转角度（度） */
    val rotation: Float = 0f,
) {
    /** 夹到合理区间，避免误操作把图甩飞 */
    fun normalized(): CardTransform = copy(
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
        offsetX = offsetX.coerceIn(-1.5f, 1.5f),
        offsetY = offsetY.coerceIn(-1.5f, 1.5f),
        rotation = ((rotation % 360f) + 360f) % 360f,
    )

    fun serialize(): String = "$scale|$offsetX|$offsetY|$rotation"

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 4f

        val DEFAULT = CardTransform()

        fun parse(raw: String?): CardTransform {
            if (raw.isNullOrBlank()) return DEFAULT
            val parts = raw.split('|')
            if (parts.size != 4) return DEFAULT
            val scale = parts[0].toFloatOrNull() ?: return DEFAULT
            val dx = parts[1].toFloatOrNull() ?: return DEFAULT
            val dy = parts[2].toFloatOrNull() ?: return DEFAULT
            val rot = parts[3].toFloatOrNull() ?: return DEFAULT
            return CardTransform(scale, dx, dy, rot).normalized()
        }
    }
}
