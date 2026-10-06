package com.accbook.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.accbook.app.ui.theme.AccBookColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** 金币堆尺寸常量（dp） */
object CoinPileSpecs {
    const val COIN_RADIUS_DP = 11f
    const val GROUND_PAD_DP = 30f
    /** FALL 粒子从画布顶部出现时相对基线的高度上限保护值 */
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
        val stepX = coinRadiusDp * 1.5f
        val rowH = coinRadiusDp * 1.1f
        val maxRow = ((canvasWidthDp * 0.62f) / stepX).toInt().coerceIn(7, 12)
        val specs = ArrayList<PileCoinSpec>(64)
        var row = 0
        while (row < CoinPileSpecs.MAX_PILE_ROWS) {
            val rowCoins = maxRow - row * 2
            if (rowCoins < 1) break
            val rowWidth = (rowCoins - 1) * stepX
            repeat(rowCoins) { c ->
                val jx = (rnd.nextFloat() - 0.5f) * coinRadiusDp * 0.5f
                val jy = (rnd.nextFloat() - 0.5f) * coinRadiusDp * 0.22f
                val shrink = (1f - row * 0.016f).coerceIn(0.82f, 1f)
                specs += PileCoinSpec(
                    cx = -rowWidth / 2f + c * stepX + jx,
                    cy = -row * rowH + jy,
                    rx = coinRadiusDp * shrink,
                    ry = coinRadiusDp * 0.78f * shrink,
                    rotation = (rnd.nextFloat() - 0.5f) * 22f,
                    alpha = 0.85f + 0.15f * shrink,
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
)

fun coinPalette(ab: AccBookColors): CoinPalette = CoinPalette(
    base = ab.gold,
    rim = ab.goldDeep,
    face = lerp(ab.gold, Color.White, 0.24f),
    highlight = Color.White.copy(alpha = 0.32f),
    shadow = Color.Black.copy(alpha = 0.07f),
)

/**
 * 单枚金币：底形 + 边缘环 + 内面 + 小高光 + 轻影；扁椭圆呈现轻微俯视透视。
 * 无厚重描边、无渐变；squash 用于落币时的轻微压扁。
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
    val sy = ry * (1f - squash)
    val sx = rx * (1f + squash * 0.6f)
    rotate(rotation, pivot = Offset(cx, cy)) {
        drawOval(p.shadow, Offset(cx - sx * 0.92f, cy + sy * 0.62f), Size(sx * 1.84f, sy * 0.9f), alpha = alpha * 0.8f)
        drawOval(p.base, Offset(cx - sx, cy - sy), Size(sx * 2f, sy * 2f), alpha = alpha)
        drawOval(p.rim, Offset(cx - sx * 0.88f, cy - sy * 0.88f), Size(sx * 1.76f, sy * 1.76f), alpha = alpha * 0.5f, style = Stroke(sx * 0.09f))
        drawOval(p.face, Offset(cx - sx * 0.6f, cy - sy * 0.6f), Size(sx * 1.2f, sy * 1.2f), alpha = alpha)
        drawOval(p.highlight, Offset(cx - sx * 0.5f, cy - sy * 0.64f), Size(sx * 0.52f, sy * 0.42f), alpha = alpha)
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
class CoinFxController(private val poolSize: Int = 18) {

    /** 金币堆视觉数量（连续值；小数部分 = 顶币长出比例） */
    val pileCount = Animatable(0f)
    /** 堆整体的入场 layer 动画（scale/alpha，不触发重绘内容） */
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

    /** 入场 layer 动画：轻微 scale/alpha 出现（不逐币构建） */
    suspend fun reveal(durationMs: Int = 340) {
        layerScale.snapTo(0.94f)
        coroutineScope {
            launch { layerAlpha.animateTo(1f, tween(durationMs)) }
            launch { layerScale.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) }
        }
    }

    suspend fun snapVisible() {
        layerAlpha.snapTo(1f)
        layerScale.snapTo(1f)
    }

    /** 掉落金币落到指定槽位（入场装饰 / 收入入池），带重力加速、落定 squash、随后融入淡出 */
    suspend fun fallOntoSlots(
        layout: List<PileCoinSpec>,
        slotIndices: List<Int>,
        spawnYDp: Float,
        staggerMs: Long = 55,
    ) = coroutineScope {
        if (layout.isEmpty() || slotIndices.isEmpty()) return@coroutineScope
        val rnd = Random(77)
        val jobs = ArrayList<Job>(slotIndices.size)
        slotIndices.forEachIndexed { k, idx ->
            val spec = layout[idx % layout.size]
            jobs += launch {
                delay(k * staggerMs + rnd.nextLong(50))
                val slot = acquire() ?: return@launch
                slot.kind = ParticleKind.FALL
                slot.targetX = spec.cx; slot.targetY = spec.cy
                slot.startX = spec.cx + (rnd.nextFloat() - 0.5f) * 56f
                slot.startY = spawnYDp
                slot.durationMs = 470 + rnd.nextInt(190)
                slot.rotation0 = (rnd.nextFloat() - 0.5f) * 30f
                slot.rotationVel = 0f
                slot.scale = 1f
                slot.progress.snapTo(0f)
                slot.second.snapTo(0f)
                slot.progress.animateTo(1f, tween(slot.durationMs, easing = LinearEasing))
                slot.second.animateTo(1f, tween(170, easing = LinearEasing))
                release(slot)
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
                slot.kind = ParticleKind.BURST
                slot.startX = spec.cx
                slot.startY = spec.cy
                slot.apex = 58f + 62f * intensity + rnd.nextFloat() * 26f
                slot.burstAt = 0.5f + rnd.nextFloat() * 0.08f
                slot.vx = (rnd.nextFloat() - 0.5f) * 110f
                slot.durationMs = 520 + rnd.nextInt(170)
                slot.rotation0 = (rnd.nextFloat() - 0.5f) * 40f
                slot.rotationVel = (rnd.nextFloat() - 0.5f) * 1.6f
                slot.scale = 1f
                val fragCount = if (intensity > 0.55f) 4 else 3
                slot.fragmentAngle = FloatArray(fragCount) { (rnd.nextFloat() - 0.5f) * 1.9f }
                slot.fragmentDist = FloatArray(fragCount) { 24f + rnd.nextFloat() * 24f }
                slot.fragmentSize = FloatArray(fragCount) { 2.6f + rnd.nextFloat() * 1.9f }
                slot.progress.snapTo(0f)
                slot.second.snapTo(0f)
                val total = slot.durationMs
                slot.progress.animateTo(slot.burstAt, tween((total * slot.burstAt).toInt(), easing = LinearEasing))
                launch { slot.second.animateTo(1f, tween(300 + rnd.nextInt(90), easing = LinearEasing)) }
                slot.progress.animateTo(1f, tween((total * (1f - slot.burstAt)).toInt(), easing = LinearEasing))
                release(slot)
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
                val squash = if (t > 0.86f) 0.24f * sin((t - 0.86f) / 0.14f * PI).toFloat() else 0f
                val alpha = (t / 0.1f).coerceAtMost(1f) * (1f - slot.second.value)
                val r = coinRadiusDp * px * slot.scale
                drawCoin(x, y, r, r * 0.86f, slot.rotation0 * (1f - t), palette, alpha = alpha, squash = squash)
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
