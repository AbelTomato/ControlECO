package com.controlexo.app.ui.transactions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.controlexo.app.data.CardImage
import com.controlexo.app.data.CardTransform
import com.controlexo.app.data.PeriodMode
import com.controlexo.app.ui.common.AutoScaledText
import com.controlexo.app.ui.common.PeriodSwitch
import com.controlexo.app.ui.common.periodBalanceLabel
import com.controlexo.app.ui.common.relativeLuminance
import com.controlexo.app.ui.common.scrimAlphaFor
import com.controlexo.app.ui.theme.CardTones
import com.controlexo.app.ui.theme.accBook
import com.controlexo.app.util.Money
import kotlin.math.max

/**
 * 账单页顶部悬浮结余卡（取代页眉 + 汇总行）：
 *   - 大字 = 当前周期结余；右侧两行 = 收入（+，绿）/ 支出（−，红），不写文字标签；
 *   - 左上 = 月/年 周期切换，右上 = 背景设置；
 *   - 背景默认为自绘几何图案，也可换成相册图片；图片在 ViewModel 里**启动即解码**
 *     （先缩略图后全尺寸），所以正常情况第一帧就是图片，不会先闪一帧默认背景；
 *   - 自动明暗判定：按背景亮度选浅底/深底配色，对比度不足按 WCAG ≥ 3 叠遮罩。
 */
@Composable
fun BalanceCard(
    mode: PeriodMode,
    balanceCents: Long,
    incomeCents: Long,
    expenseCents: Long,
    imagePath: String?,
    image: CardImage?,
    transform: CardTransform = CardTransform.DEFAULT,
    /** 背景图平均色（持久化）：连缩略图都还没出来时用它打底 */
    placeholderColor: Int? = null,
    placeholderLuminance: Float? = null,
    onModeChange: (PeriodMode) -> Unit,
    onOpenImageSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ab = MaterialTheme.accBook
    val drawable = remember(image?.full, image?.preview) { image?.asImage() }
    val customImage = image != null || !imagePath.isNullOrBlank()
    val placeholder = if (drawable == null && customImage) placeholderColor?.let { Color(it) } else null
    val effectiveLuminance = image?.luminance ?: placeholderLuminance ?: 0.9f

    val tones: CardTones = when {
        !customImage -> {
            // 默认（自绘）卡面：直接用主题配色，深浅由明暗主题决定，不叠遮罩
            val ink = MaterialTheme.colorScheme.onPrimaryContainer
            CardTones(
                ink = ink,
                sub = ink.copy(alpha = 0.72f),
                income = ab.income,
                expense = ab.critical,
                scrim = Color.Transparent,
                pillContainer = ab.primaryStrong.copy(alpha = 0.16f),
                pillSelected = ab.primaryStrong,
                pillSelectedContent = MaterialTheme.colorScheme.onPrimary,
            )
        }
        effectiveLuminance < 0.30f -> ab.cardOnDark
        else -> ab.cardOnLight
    }
    val scrimAlpha = if (!customImage || (drawable == null && placeholder == null)) {
        0f
    } else {
        maxOf(
            scrimAlphaFor(effectiveLuminance, relativeLuminance(tones.ink)),
            scrimAlphaFor(effectiveLuminance, relativeLuminance(tones.income)),
            scrimAlphaFor(effectiveLuminance, relativeLuminance(tones.expense)),
        )
    }

    val deco = if (effectiveLuminance < 0.30f) Color.White else Color.Black
    val base = MaterialTheme.colorScheme.primaryContainer
    val deep = lerp(base, ab.primaryStrong, 0.45f)
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(152.dp)
            .shadow(elevation = 10.dp, shape = shape)
            .clip(shape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val bmp = drawable
            when {
                bmp != null -> {
                    // 真实图片（全尺寸或缩略图）+ 用户调整的位移/缩放/旋转
                    val t = transform.normalized()
                    val scale = max(size.width / bmp.width, size.height / bmp.height) * t.scale
                    val w = bmp.width * scale
                    val h = bmp.height * scale
                    withTransform({
                        translate(t.offsetX * size.width, t.offsetY * size.height)
                        rotate(t.rotation, pivot = center)
                    }) {
                        drawImage(
                            image = bmp,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bmp.width, bmp.height),
                            dstOffset = IntOffset(((size.width - w) / 2f).toInt(), ((size.height - h) / 2f).toInt()),
                            dstSize = IntSize(w.toInt(), h.toInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                    if (scrimAlpha > 0.01f) drawRect(tones.scrim.copy(alpha = scrimAlpha))
                }
                placeholder != null -> {
                    // 缩略图还没好：先用持久化的平均色铺满（不是"默认卡面"，观感是同色 → 图片）
                    drawRect(placeholder)
                    if (scrimAlpha > 0.01f) drawRect(tones.scrim.copy(alpha = scrimAlpha))
                }
                customImage -> Unit // 设了图但还没解码完：留白，不画默认背景（避免闪一下）
                else -> {
                    // 默认几何背景：浅蓝竖向渐变 + 右上同心圆 + 左下圆环 + 一道斜高光
                    drawRect(Brush.verticalGradient(listOf(base, deep)))
                    val ring = deco.copy(alpha = 0.06f)
                    val cx = size.width * 0.94f
                    val cy = size.height * 0.02f
                    listOf(0.34f, 0.52f, 0.70f).forEach { f ->
                        drawCircle(
                            color = ring,
                            radius = size.height * f,
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                    drawCircle(
                        color = ring,
                        radius = size.height * 0.30f,
                        center = Offset(size.width * 0.04f, size.height * 1.04f),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    drawLine(
                        color = deco.copy(alpha = 0.04f),
                        start = Offset(0f, size.height * 0.30f),
                        end = Offset(size.width, size.height * 0.06f),
                        strokeWidth = size.height * 0.10f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PeriodSwitch(
                    mode = mode,
                    onChange = onModeChange,
                    containerColor = tones.pillContainer,
                    selectedColor = tones.pillSelected,
                    selectedContentColor = tones.pillSelectedContent,
                    contentColor = tones.sub,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onOpenImageSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "设置卡片背景",
                        tint = tones.sub,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(periodBalanceLabel(mode), style = MaterialTheme.typography.labelMedium, color = tones.sub)
                    Spacer(Modifier.height(2.dp))
                    AutoScaledText(
                        text = Money.formatYuanGroupedFixed(balanceCents),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = tones.ink,
                        maxWidth = 190.dp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Money.formatSignedPlain(incomeCents),
                        style = MaterialTheme.typography.titleSmall,
                        color = tones.income,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        Money.formatSignedPlain(-expenseCents),
                        style = MaterialTheme.typography.titleSmall,
                        color = tones.expense,
                    )
                }
            }
        }
    }
}
