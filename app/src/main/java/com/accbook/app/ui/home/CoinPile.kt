package com.accbook.app.ui.home

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.accbook.app.ui.theme.AccBookTheme
import com.accbook.app.ui.theme.accBook
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.accbook.app.ui.theme.AccBookColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** 金币堆尺寸常量（dp） */
object CoinPileSpecs {
    const val COIN_RADIUS_DP = 15f
    const val GROUND_PAD_DP = 38f
    const val MAX_PILE_ROWS = 8
}

/**
 * 金币堆布局：确定性（固定种子）预计算的有限金币槽位。
 * 填充顺序 = 自底向上（底行 → 顶行），因此"金币数量"天然决定了堆的宽度与高度：
 * 少量金币 = 居中的小堆，满额 = 宽而高的金字塔堆。剩余比例 → 数量，绝不与金额 1:1。
 */
data class PileCoinSpec(
    val cx: Float,       // 相对画布水平中心的偏移（dp）
    val cy: Float,       // 相对地面基线的高度（dp，≤0 向上）
    val rx: Float,
    val ry: Float,
    val rotation: Float, // 姿态抖动（度）
    val alpha: Float,    // 远处略淡
)

object CoinPileLayout {
    fun build(canvasWidthDp: Float, coinRadiusDp: Float = CoinPileSpecs.COIN_RADIUS_DP, seed: Long = 2026L): List<PileCoinSpec> {
        val rnd = Random(seed)
        val maxRow = ((canvasWidthDp * 0.80f) / (coinRadiusDp * 1.42f)).toInt().coerceIn(10, 18)
        val specs = ArrayList<PileCoinSpec>(120)
        var row = 0
        while (row < CoinPileSpecs.MAX_PILE_ROWS) {
            val rowCoins = maxRow - row * 2
            if (rowCoins < 1) break
            val stepX = coinRadiusDp * (1.30f + row * 0.025f)
            val rowWidth = (rowCoins - 1) * stepX
            repeat(rowCoins) { c ->
                val jx = (rnd.nextFloat() - 0.5f) * coinRadiusDp * 0.56f
                val jy = (rnd.nextFloat() - 0.5f) * coinRadiusDp * 0.30f
                val depth = row.toFloat() / (CoinPileSpecs.MAX_PILE_ROWS - 1)
                val scale = (1.06f - depth * 0.20f) * (0.96f + rnd.nextFloat() * 0.08f)
                specs += PileCoinSpec(
                    cx = -rowWidth / 2f + c * stepX + jx,
                    cy = -row * coinRadiusDp * 1.02f + jy,
                    rx = coinRadiusDp * scale,
                    ry = coinRadiusDp * 0.74f * scale,
                    rotation = (rnd.nextFloat() - 0.5f) * 28f,
                    alpha = 0.78f + 0.22f * (1f - depth),
                )
            }
            row++
        }
        return specs
    }
}

data class CoinPalette(
    val base: Color,
    val rim: Color,
    val face: Color,
    val highlight: Color,
    val shadow: Color,
    val mark: Color,
    val markPaint: Paint,
)

fun coinPalette(ab: AccBookColors): CoinPalette = CoinPalette(
    base = ab.goldDeep,
    rim = lerp(ab.goldDeep, Color(0xFF6C4511), 0.30f),
    face = lerp(ab.gold, Color(0xFFFFE8A8), 0.42f),
    highlight = Color.White.copy(alpha = 0.62f),
    shadow = Color(0xFF4A3210).copy(alpha = 0.18f),
    mark = lerp(ab.goldDeep, Color(0xFF5B3B10), 0.42f),
    markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    },
)

/**
 * 独立金币几何：正圆币面 + 偏移币身 + 内圈 + 金属高光 + 中心人民币符号。
 * 所有首页金币（堆、入场、收入、支出碎裂、HUD）共用这一个绘制函数。
 */
fun DrawScope.drawCoin(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    rotation: Float,
    p: CoinPalette,
    alpha: Float = 1f,
    squash: Float = 0f,
) {
    if (alpha <= 0.01f || rx <= 0f) return
    val radius = rx * (1f + squash * 0.10f)
    val sideDepth = radius * 0.18f
    val faceY = cy - sideDepth * 0.28f
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    rotate(rotation, pivot = Offset(cx, cy)) {
        drawCircle(p.shadow, radius * 1.04f, Offset(cx, cy + radius * 0.95f), alpha = alpha * 0.25f)
        drawCircle(p.rim, radius, Offset(cx, cy + sideDepth), alpha = alpha)
        drawCircle(lerp(p.base, Color.Black, 0.10f), radius * 0.92f, Offset(cx, cy + sideDepth * 0.72f), alpha = alpha)
        drawCircle(p.base, radius, Offset(cx, faceY), alpha = alpha)
        drawCircle(p.face, radius * 0.86f, Offset(cx, faceY), alpha = alpha)
        drawCircle(
            p.rim,
            radius * 0.72f,
            Offset(cx, faceY),
            alpha = alpha * 0.54f,
            style = Stroke(radius * 0.055f),
        )
        drawArc(
            color = p.highlight,
            startAngle = 205f,
            sweepAngle = 112f,
            useCenter = false,
            topLeft = Offset(cx - radius * 0.78f, faceY - radius * 0.78f),
            size = Size(radius * 1.56f, radius * 1.56f),
            style = Stroke(radius * 0.085f),
            alpha = alpha * 0.82f,
        )
        drawArc(
            color = Color.White.copy(alpha = 0.34f),
            startAngle = 225f,
            sweepAngle = 62f,
            useCenter = false,
            topLeft = Offset(cx - radius * 0.63f, faceY - radius * 0.63f),
            size = Size(radius * 1.26f, radius * 1.26f),
            style = Stroke(radius * 0.055f),
            alpha = alpha,
        )
        p.markPaint.color = p.mark.toArgb()
        p.markPaint.alpha = a
        p.markPaint.textSize = radius * 0.92f
        drawContext.canvas.nativeCanvas.drawText("¥", cx, faceY + radius * 0.33f, p.markPaint)
    }
}

/** 金币堆：按确定性布局绘制前 floor(count) 枚（自顶向下画，底部行最后画=近处覆盖远处），小数部分让顶币平滑长出 */
fun DrawScope.drawCoinPile(
    layout: List<PileCoinSpec>,
    countFraction: Float,
    palette: CoinPalette,
    baselineY: Float,
    px: Float,
) {
    val centerX = size.width / 2f
    val total = floor(countFraction).toInt().coerceIn(0, layout.size)
    for (i in total - 1 downTo 0) {
        val s = layout[i]
        drawCoin(centerX + s.cx * px, baselineY + s.cy * px, s.rx * px, s.ry * px, s.rotation, palette, alpha = s.alpha)
    }
    val frac = countFraction - total
    if (frac > 0.03f && total < layout.size) {
        val s = layout[total]
        val k = 0.5f + 0.5f * frac
        drawCoin(centerX + s.cx * px, baselineY + s.cy * px, s.rx * px * k, s.ry * px * k, s.rotation, palette, alpha = s.alpha)
    }
}

private fun DrawScope.drawGround(layout: List<PileCoinSpec>, palette: CoinPalette, baselineY: Float, px: Float) {
    val halfRow = ((layout.maxOfOrNull { abs(it.cx) } ?: 40f) + 14f) * px
    drawOval(palette.shadow, Offset(size.width / 2f - halfRow, baselineY - 3.dp.toPx()), Size(halfRow * 2f, 10.dp.toPx()))
}

// ---------------- 粒子系统（掉落 / 弹出碎裂） ----------------

internal enum class ParticleKind { FALL, BURST }

/**
 * 固定对象池粒子控制器：所有运动参数在 spawn 时一次性写入（缓存），
 * 每帧仅读 Animatable 进度在 draw phase 解析求值，零临时对象、零布局改动。
 * 同时存在的动画金币 ≤ poolSize，碎片由 BURST 粒子的第二进度解析绘制，不单独占池。
 */
class CoinFxController(private val poolSize: Int = 24) {

    /** 金币堆视觉数量（连续值；小数部分 = 顶币长出比例） */
    val pileCount = Animatable(0f)
    var forming = false
        private set
    /** 堆整体的入场 layer 动画；金币主体只由实际落币进入 */
    val layerAlpha = Animatable(0f)
    val layerScale = Animatable(0.94f)

    internal class Slot {
        var kind = ParticleKind.FALL
        // dp 坐标（x 相对画布中心，y 相对基线，≤0 向上）
        var startX = 0f; var startY = 0f; var targetX = 0f; var targetY = 0f
        var durationMs = 500
        var rotation0 = 0f; var rotationVel = 0f
        var scale = 1f
        var burstAt = 1f
        var apex = 0f
        var vx = 0f
        var fragmentAngle = floatArrayOf()
        var fragmentDist = floatArrayOf()
        var fragmentSize = floatArrayOf()
        val progress = Animatable(0f)
        // FALL：落定后融入淡出；BURST：碎裂碎片进度
        val second = Animatable(0f)
        var inUse = false
    }

    internal val slots = Array(poolSize) { Slot() }

    private fun acquire(): Slot? = slots.firstOrNull { !it.inUse }?.also { it.inUse = true }
    private fun release(s: Slot) { s.inUse = false }

    suspend fun clearParticles() {
        slots.forEach { slot ->
            slot.inUse = false
            slot.progress.stop()
            slot.second.stop()
            slot.progress.snapTo(0f)
            slot.second.snapTo(0f)
        }
    }

    suspend fun formPile(
        layout: List<PileCoinSpec>,
        targetCount: Int,
        spawnYDp: Float,
    ) {
        val target = targetCount.coerceIn(0, layout.size)
        forming = true
        try {
            layerAlpha.snapTo(1f)
            layerScale.snapTo(1f)
            clearParticles()
            pileCount.snapTo(0f)
            if (target == 0) return
            var next = 0
            val settled = AtomicInteger(0)
            while (next < target) {
                val batchEnd = minOf(target, next + 10)
                val indices = (next until batchEnd).toList()
                fallOntoSlots(
                    layout = layout,
                    slotIndices = indices,
                    spawnYDp = spawnYDp,
                    staggerMs = 52,
                    onSettled = {
                        val count = settled.incrementAndGet()
                        pileCount.snapTo(count.toFloat().coerceAtMost(target.toFloat()))
                    },
                )
                next = batchEnd
            }
            pileCount.snapTo(target.toFloat())
        } finally {
            forming = false
        }
    }

    /** 掉落金币落到指定槽位（入场形成 / 收入入池），带重力加速、落定 squash、随后融入堆体 */
    suspend fun fallOntoSlots(
        layout: List<PileCoinSpec>,
        slotIndices: List<Int>,
        spawnYDp: Float,
        staggerMs: Long = 55,
        onSettled: (suspend () -> Unit)? = null,
    ) = coroutineScope {
        if (layout.isEmpty() || slotIndices.isEmpty()) return@coroutineScope
        val rnd = Random(77)
        val jobs = ArrayList<Job>(slotIndices.size)
        slotIndices.forEachIndexed { k, idx ->
            val spec = layout[idx % layout.size]
            jobs += launch {
                delay(k * staggerMs + rnd.nextLong(50))
                val slot = acquire() ?: return@launch
                try {
                    slot.kind = ParticleKind.FALL
                    slot.targetX = spec.cx; slot.targetY = spec.cy
                    slot.startX = spec.cx + (rnd.nextFloat() - 0.5f) * 56f
                    slot.startY = spawnYDp
                    slot.durationMs = 420 + rnd.nextInt(150)
                    slot.rotation0 = (rnd.nextFloat() - 0.5f) * 34f
                    slot.rotationVel = (rnd.nextFloat() - 0.5f) * 0.9f
                    slot.scale = 0.88f + rnd.nextFloat() * 0.24f
                    slot.progress.snapTo(0f)
                    slot.second.snapTo(0f)
                    slot.progress.animateTo(1f, tween(slot.durationMs, easing = LinearEasing))
                    slot.second.animateTo(1f, tween(150, easing = LinearEasing))
                    if (onSettled != null) onSettled()
                } finally {
                    release(slot)
                }
            }
        }
        jobs.joinAll()
    }

    /**
     * 支出结算：从堆顶被移除的槽位弹出金币，抛物线上升，在最高点附近碎裂成少量金色碎片
     * （碎片继承水平方向、轻微外扩、快速淡出）。不做爆炸，力度随金额比例增强。
     */
    suspend fun burstFrom(origins: List<PileCoinSpec>, intensity: Float) = coroutineScope {
        if (origins.isEmpty()) return@coroutineScope
        val rnd = Random(31337)
        val jobs = ArrayList<Job>(origins.size)
        origins.forEachIndexed { k, spec ->
            jobs += launch {
                delay(k * 46L + rnd.nextLong(40))
                val slot = acquire() ?: return@launch
                try {
                    slot.kind = ParticleKind.BURST
                    slot.startX = spec.cx
                    slot.startY = spec.cy
                    slot.apex = 52f + 72f * intensity + rnd.nextFloat() * 22f
                    slot.burstAt = 0.46f + rnd.nextFloat() * 0.08f
                    slot.vx = (rnd.nextFloat() - 0.5f) * (96f + 48f * intensity)
                    slot.durationMs = 470 + rnd.nextInt(150)
                    slot.rotation0 = (rnd.nextFloat() - 0.5f) * 46f
                    slot.rotationVel = (rnd.nextFloat() - 0.5f) * 1.8f
                    slot.scale = 0.90f + rnd.nextFloat() * 0.20f
                    val fragCount = if (intensity > 0.55f) 4 else 3
                    slot.fragmentAngle = FloatArray(fragCount) { (rnd.nextFloat() - 0.5f) * 1.9f }
                    slot.fragmentDist = FloatArray(fragCount) { 18f + rnd.nextFloat() * 22f }
                    slot.fragmentSize = FloatArray(fragCount) { 2.3f + rnd.nextFloat() * 1.6f }
                    slot.progress.snapTo(0f)
                    slot.second.snapTo(0f)
                    val total = slot.durationMs
                    slot.progress.animateTo(slot.burstAt, tween((total * slot.burstAt).toInt(), easing = LinearEasing))
                    coroutineScope {
                        launch { slot.second.animateTo(1f, tween(270 + rnd.nextInt(80), easing = LinearEasing)) }
                        slot.progress.animateTo(1f, tween((total * (1f - slot.burstAt)).toInt(), easing = LinearEasing))
                    }
                } finally {
                    release(slot)
                }
            }
        }
        jobs.joinAll()
    }

    companion object {
        /** BURST 抛物线重力（每进度平方的 dp 数），由 apex 与 burstAt 反解，保证最高点恰在碎裂点 */
        fun gravityOf(apex: Float, burstAt: Float): Float = 2f * apex / (burstAt * burstAt)
    }
}

/** 粒子绘制：全部解析求值，无分配 */
fun DrawScope.drawCoinParticles(
    fx: CoinFxController,
    palette: CoinPalette,
    baselineY: Float,
    px: Float,
    coinRadiusDp: Float,
) {
    val centerX = size.width / 2f
    for (slot in fx.slots) {
        if (!slot.inUse) continue
        val t = slot.progress.value
        if (t <= 0f) continue
        when (slot.kind) {
            ParticleKind.FALL -> {
                val e = t * t // 重力加速
                val x = centerX + (slot.startX + (slot.targetX - slot.startX) * t) * px
                val y = baselineY + (slot.startY + (slot.targetY - slot.startY) * e) * px
                val squash = if (t > 0.88f) 0.16f * sin((t - 0.88f) / 0.12f * PI).toFloat() else 0f
                val alpha = (t / 0.08f).coerceAtMost(1f) * (1f - slot.second.value)
                val r = coinRadiusDp * px * slot.scale
                val rotation = slot.rotation0 + slot.rotationVel * t * 90f
                drawCoin(x, y, r, r * 0.86f, rotation, palette, alpha = alpha, squash = squash)
            }
            ParticleKind.BURST -> {
                val g = CoinFxController.gravityOf(slot.apex, slot.burstAt)
                val x = centerX + (slot.startX + slot.vx * t) * px
                val y = baselineY + (slot.startY - (g * slot.burstAt * t - 0.5f * g * t * t)) * px
                val coinAlpha = if (t < slot.burstAt) 1f else (1f - (t - slot.burstAt) / 0.12f).coerceIn(0f, 1f)
                val rotation = slot.rotation0 + slot.rotationVel * t * 90f
                if (coinAlpha > 0f) {
                    val r = coinRadiusDp * px * slot.scale
                    drawCoin(x, y, r, r * 0.86f, rotation, palette, alpha = coinAlpha)
                }
                // 碎片：继承金币在碎裂点的运动方向（最高点处≈水平），轻微外扩 + 下坠 + 快速淡出
                val s = slot.second.value
                if (s > 0f && s < 1f) {
                    val burstX = centerX + (slot.startX + slot.vx * slot.burstAt) * px
                    val burstY = baselineY + (slot.startY - slot.apex) * px
                    val ease = 1f - (1f - s) * (1f - s)
                    val droop = 34f * s * s * px
                    for (k in slot.fragmentAngle.indices) {
                        val ang = slot.fragmentAngle[k]
                        val dist = slot.fragmentDist[k] * px * ease
                        val fx2 = burstX + cos(ang) * dist
                        val fy = burstY - sin(ang) * dist * 0.8f + droop
                        val size = slot.fragmentSize[k] * px * (1f - 0.3f * s)
                        drawCoin(fx2, fy, size, size * 0.8f, ang * 57f, palette, alpha = 1f - s)
                    }
                }
            }
        }
    }
}

/** 金币堆画布：layer 入场动画 + 地面 + 堆 + 粒子，全部在 draw phase */
@Composable
fun CoinPileView(
    fx: CoinFxController,
    layout: List<PileCoinSpec>,
    palette: CoinPalette,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    Canvas(
        modifier.graphicsLayer {
            alpha = fx.layerAlpha.value
            val s = fx.layerScale.value
            scaleX = s
            scaleY = s
            transformOrigin = TransformOrigin(0.5f, 0.85f)
        },
    ) {
        if (!visible || layout.isEmpty()) return@Canvas
        val px = density
        val baselineY = size.height - CoinPileSpecs.GROUND_PAD_DP.dp.toPx()
        drawGround(layout, palette, baselineY, px)
        drawCoinPile(layout, fx.pileCount.value, palette, baselineY, px)
        drawCoinParticles(fx, palette, baselineY, px, CoinPileSpecs.COIN_RADIUS_DP)
    }
}

/** 供会话编排计算槽位区间 */
fun pileSlotRange(fromFraction: Float, toFraction: Float): Pair<Int, Int> {
    val oldCount = floor(fromFraction).toInt()
    val newCount = ceil(toFraction).toInt()
    return oldCount to newCount
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F4EE, widthDp = 360, heightDp = 420)
@Composable
private fun CoinVisualPreview() {
    AccBookTheme {
        val palette = coinPalette(MaterialTheme.accBook)
        val layout = CoinPileLayout.build(320f)
        Canvas(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
        ) {
            val px = density
            val centerX = size.width * 0.5f
            drawCoin(centerX, 72.dp.toPx(), 52.dp.toPx(), 52.dp.toPx(), 0f, palette)
            drawCoin(centerX - 82.dp.toPx(), 180.dp.toPx(), 28.dp.toPx(), 28.dp.toPx(), -7f, palette)
            drawCoin(centerX, 180.dp.toPx(), 31.dp.toPx(), 31.dp.toPx(), 4f, palette)
            drawCoin(centerX + 82.dp.toPx(), 180.dp.toPx(), 25.dp.toPx(), 25.dp.toPx(), 9f, palette)
            drawCoinPile(
                layout = layout,
                countFraction = minOf(26f, layout.size.toFloat()),
                palette = palette,
                baselineY = size.height - 34.dp.toPx(),
                px = px,
            )
        }
    }
}
