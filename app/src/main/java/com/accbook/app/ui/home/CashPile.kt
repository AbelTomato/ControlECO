package com.accbook.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.accbook.app.data.budget.Denomination
import com.accbook.app.ui.theme.AccBookTheme
import com.accbook.app.ui.theme.NoteColor
import com.accbook.app.ui.theme.accBook
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/** 纸币堆尺寸常量（dp） */
object CashPileSpecs {
    /** 票面 2.08:1（贴近真币 155:77） */
    const val NOTE_W_DP = 96f
    const val NOTE_H_DP = 46f
    const val STACK_COUNT = 3
    const val STACK_MAX_NOTES = 8
    const val CAPACITY = STACK_COUNT * STACK_MAX_NOTES
    const val STACK_STEP_Y_DP = 6f
    const val GROUND_PAD_DP = 36f
}

/** 纸币槽位：cx 相对画布中心（dp），cy 相对基线（≤0 向上），rotation 姿态抖动（度） */
data class NoteSpec(val cx: Float, val cy: Float, val rotation: Float)

/**
 * 纸币堆布局：确定性（固定种子）预计算的 3 叠 × 8 张槽位。
 * 填充序号交错分配（i → 叠 i%3、层 i/3），因此数量增减表现为"整排一起长/拆"，
 * 被抽走的总是序号最高的一批 = 每叠的顶层纸币。剩余比例 → 张数，绝不与金额 1:1。
 */
class CashStackLayout private constructor(val stacks: List<List<NoteSpec>>, val spanDp: Float) {

    /** 填充序号 → 槽位（供结算动画定位被抽出/新增的纸币） */
    fun specAt(fillIndex: Int): NoteSpec {
        val s = Math.floorMod(fillIndex, CashPileSpecs.STACK_COUNT)
        val l = fillIndex / CashPileSpecs.STACK_COUNT
        val list = stacks[s]
        return list[l.coerceIn(0, list.lastIndex)]
    }

    companion object {
        fun build(canvasWidthDp: Float, seed: Long = 2026L): CashStackLayout {
            val rnd = Random(seed)
            val w = CashPileSpecs.NOTE_W_DP
            val gap = ((canvasWidthDp - w) / (CashPileSpecs.STACK_COUNT - 1 + 0.8f))
                .coerceIn(w * 0.72f, 124f)
            val stacks = List(CashPileSpecs.STACK_COUNT) { s ->
                val cx = (s - (CashPileSpecs.STACK_COUNT - 1) / 2f) * gap
                List(CashPileSpecs.STACK_MAX_NOTES) { l ->
                    NoteSpec(
                        cx = cx + (rnd.nextFloat() - 0.5f) * 4.5f,
                        cy = -l * CashPileSpecs.STACK_STEP_Y_DP + (rnd.nextFloat() - 0.5f) * 1.4f,
                        rotation = (rnd.nextFloat() - 0.5f) * 5f,
                    )
                }
            }
            val span = (stacks.maxOf { row -> row.maxOf { abs(it.cx) } } + w / 2f)
            return CashStackLayout(stacks, span)
        }
    }
}

/** 总张数（连续）→ 每叠张数（交错填充）；小数部分 = 对应叠顶层纸币的长出比例 */
fun stackCounts(totalNotes: Float, stacks: Int = CashPileSpecs.STACK_COUNT): FloatArray {
    val counts = FloatArray(stacks)
    val capacity = stacks * CashPileSpecs.STACK_MAX_NOTES
    val full = floor(totalNotes).toInt().coerceIn(0, capacity)
    val frac = (totalNotes - full).coerceIn(0f, 1f)
    for (i in 0 until full) counts[i % stacks] += 1f
    if (full < capacity && frac > 0.03f) counts[full % stacks] += frac
    return counts
}

/**
 * 单张纸币：投影 + 底色 + 细描边 + 内面版面 + 圆形印章 + 顶边高光 + 面额数字（右中）。
 * 纯几何、无厚重黑描边、无渐变；label 传 null 时不画数字（迷你图标/被遮的下层）。
 */
fun DrawScope.drawBanknote(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    rotation: Float,
    color: NoteColor,
    label: TextLayoutResult?,
    alpha: Float = 1f,
    scale: Float = 1f,
) {
    if (alpha <= 0.01f || w <= 0f) return
    val sw = w * scale
    val sh = h * scale
    rotate(rotation, pivot = Offset(cx, cy)) {
        val corner = CornerRadius(sh * 0.14f)
        drawRoundRect(
            Color.Black.copy(alpha = 0.07f),
            Offset(cx - sw / 2f + 2f.dp.toPx(), cy - sh / 2f + 3f.dp.toPx()),
            Size(sw, sh),
            corner,
            alpha = alpha,
        )
        drawRoundRect(color.base, Offset(cx - sw / 2f, cy - sh / 2f), Size(sw, sh), corner, alpha = alpha)
        drawRoundRect(
            color.deep,
            Offset(cx - sw / 2f, cy - sh / 2f),
            Size(sw, sh),
            corner,
            alpha = alpha * 0.55f,
            style = Stroke(sh * 0.045f),
        )
        val inset = sh * 0.16f
        drawRoundRect(
            color.face,
            Offset(cx - sw / 2f + inset, cy - sh / 2f + inset),
            Size(sw - inset * 2f, sh - inset * 2f),
            CornerRadius(sh * 0.09f),
            alpha = alpha * 0.9f,
        )
        drawCircle(color.deep, radius = sh * 0.16f, center = Offset(cx - sw * 0.30f, cy), alpha = alpha * 0.4f)
        drawLine(
            Color.White.copy(alpha = 0.26f),
            Offset(cx - sw * 0.36f, cy - sh / 2f + sh * 0.09f),
            Offset(cx + sw * 0.36f, cy - sh / 2f + sh * 0.09f),
            strokeWidth = sh * 0.03f,
            alpha = alpha,
        )
        label?.let {
            drawText(
                it,
                topLeft = Offset(cx + sw / 2f - sh * 0.13f - it.size.width, cy - it.size.height / 2f),
                alpha = alpha,
            )
        }
    }
}

/** 纸币堆：3 叠各自自底向上绘制（顶张最后画=覆盖下层），只有各叠顶张画面额数字 */
fun DrawScope.drawCashStack(
    layout: CashStackLayout,
    totalNotes: Float,
    noteColors: List<NoteColor>,
    labels: Map<Denomination, TextLayoutResult>,
    baselineY: Float,
    px: Float,
) {
    val centerX = size.width / 2f
    val counts = stackCounts(totalNotes)
    val hundred = noteColors[Denomination.HUNDRED.ordinal]
    for (s in layout.stacks.indices) {
        val c = counts[s]
        val full = floor(c).toInt()
        val hasFrac = c > full + 0.03f
        val list = layout.stacks[s]
        val visible = if (hasFrac) full + 1 else full
        for (l in 0 until visible.coerceAtMost(list.size)) {
            val spec = list[l]
            val isTop = if (hasFrac) l == full else l == full - 1
            val scale = if (hasFrac && l == full) 0.5f + 0.5f * (c - full) else 1f
            drawBanknote(
                cx = centerX + spec.cx * px,
                cy = baselineY + spec.cy * px,
                w = CashPileSpecs.NOTE_W_DP * px,
                h = CashPileSpecs.NOTE_H_DP * px,
                rotation = spec.rotation,
                color = hundred,
                label = if (isTop) labels[Denomination.HUNDRED] else null,
                alpha = 1f,
                scale = scale,
            )
        }
    }
}

private fun DrawScope.drawGround(layout: CashStackLayout, baselineY: Float, px: Float) {
    val half = (layout.spanDp + 10f) * px
    drawOval(
        Color.Black.copy(alpha = 0.06f),
        Offset(size.width / 2f - half, baselineY - 3.dp.toPx()),
        Size(half * 2f, 11.dp.toPx()),
    )
}

// ---------------- 粒子系统（弹出出现 / 抽出消散） ----------------

internal enum class NoteFxKind { POP_IN, PULL_OUT }

/**
 * 固定对象池粒子控制器：运动参数 spawn 时一次性写入，每帧仅读 Animatable 进度
 * 在 draw phase 解析求值（零临时对象、零布局改动、零重组）。
 * 纸币动画全部"原地发生"——没有从屏幕顶部掉落的过程。
 */
class CashFxController(private val poolSize: Int = 16) {

    /** 堆内纸币总数（连续值；小数部分 = 顶张长出比例） */
    val stackCount = Animatable(0f)
    var forming = false
        private set
    /** 堆整体的入场 layer 动画；纸币主体由实际弹出进入 */
    val layerAlpha = Animatable(0f)
    val layerScale = Animatable(0.94f)

    internal class Slot {
        var kind = NoteFxKind.POP_IN
        var cx = 0f; var cy = 0f; var rotation = 0f
        var denom = Denomination.HUNDRED
        var durationMs = 280
        var lift = 0f      // PULL_OUT 上移距离（dp）
        var drift = 0f     // PULL_OUT 水平漂移（dp）
        var tilt = 0f      // PULL_OUT 额外倾斜（度）
        val progress = Animatable(0f)
        val fade = Animatable(0f)
        var inUse = false
    }

    internal val slots = Array(poolSize) { Slot() }

    private fun acquire(): Slot? = slots.firstOrNull { !it.inUse }?.also { it.inUse = true }
    private fun release(s: Slot) { s.inUse = false }

    suspend fun clearParticles() {
        slots.forEach { slot ->
            slot.inUse = false
            slot.progress.stop()
            slot.fade.stop()
            slot.progress.snapTo(0f)
            slot.fade.snapTo(0f)
        }
    }

    /**
     * 首次进入/后台恢复：当前资源堆以"纸币逐张原地弹出"的方式形成
     * （批次交错、每张 pop 完成即把堆数量 +1，无缝交给堆本体绘制）。
     */
    suspend fun formPile(layout: CashStackLayout, targetCount: Int, denom: Denomination = Denomination.HUNDRED) {
        val target = targetCount.coerceIn(0, CashPileSpecs.CAPACITY)
        forming = true
        try {
            layerAlpha.snapTo(1f)
            layerScale.snapTo(1f)
            clearParticles()
            stackCount.snapTo(0f)
            if (target == 0) return
            val rnd = Random(2027)
            var settled = 0
            for (i in 0 until target) {
                val spec = layout.specAt(i)
                delay(i * 42L + rnd.nextLong(36))
                val slot = acquire() ?: continue
                slot.kind = NoteFxKind.POP_IN
                slot.cx = spec.cx; slot.cy = spec.cy; slot.rotation = spec.rotation
                slot.denom = denom
                slot.durationMs = 240 + rnd.nextInt(70)
                slot.progress.snapTo(0f)
                slot.fade.snapTo(0f)
                slot.progress.animateTo(1f, tween(slot.durationMs, easing = LinearEasing))
                settled++
                stackCount.snapTo(settled.toFloat())
                release(slot)
            }
            stackCount.snapTo(target.toFloat())
        } finally {
            forming = false
        }
    }

    /**
     * 收入结算：纸币在堆的新槽位直接出现（scale+alpha+轻微沉降）。
     * holdMs = 堆数量动画追到该槽位所需的时间——粒子保持到堆本体接手后再淡出，避免闪断。
     */
    suspend fun popInNotes(
        layout: CashStackLayout,
        fillIndices: List<Int>,
        denom: Denomination,
        holdMs: Long = 0L,
        staggerMs: Long = 55,
    ) = coroutineScope {
        if (fillIndices.isEmpty()) return@coroutineScope
        val rnd = Random(77)
        val jobs = ArrayList<Job>(fillIndices.size)
        fillIndices.forEachIndexed { k, idx ->
            val spec = layout.specAt(idx)
            jobs += launch {
                delay(k * staggerMs + rnd.nextLong(40))
                val slot = acquire() ?: return@launch
                try {
                    slot.kind = NoteFxKind.POP_IN
                    slot.cx = spec.cx; slot.cy = spec.cy; slot.rotation = spec.rotation
                    slot.denom = denom
                    slot.durationMs = 260 + rnd.nextInt(80)
                    slot.progress.snapTo(0f)
                    slot.fade.snapTo(0f)
                    slot.progress.animateTo(1f, tween(slot.durationMs, easing = LinearEasing))
                    delay(holdMs.coerceAtLeast(0L))
                    slot.fade.animateTo(1f, tween(170, easing = LinearEasing))
                } finally {
                    release(slot)
                }
            }
        }
        jobs.joinAll()
    }

    /**
     * 支出结算：从堆顶抽出纸币——上移 + 微倾斜 + 轻微放大，后 45% 淡出消散。
     * 力度（上移距离/倾斜/节奏）随金额占预算比例增强。
     */
    suspend fun pullOutNotes(
        layout: CashStackLayout,
        fillIndices: List<Int>,
        denom: Denomination,
        intensity: Float,
    ) = coroutineScope {
        if (fillIndices.isEmpty()) return@coroutineScope
        val rnd = Random(31337)
        val jobs = ArrayList<Job>(fillIndices.size)
        fillIndices.forEachIndexed { k, idx ->
            val spec = layout.specAt(idx)
            jobs += launch {
                delay(k * (44L + (20L * intensity).toLong()) + rnd.nextLong(40))
                val slot = acquire() ?: return@launch
                try {
                    slot.kind = NoteFxKind.PULL_OUT
                    slot.cx = spec.cx; slot.cy = spec.cy; slot.rotation = spec.rotation
                    slot.denom = denom
                    slot.durationMs = 420 + rnd.nextInt(130) + (60 * intensity).toInt()
                    slot.lift = 36f + 46f * intensity + rnd.nextFloat() * 14f
                    slot.drift = (rnd.nextFloat() - 0.5f) * 30f
                    slot.tilt = (rnd.nextFloat() - 0.5f) * 16f
                    slot.progress.snapTo(0f)
                    slot.fade.snapTo(0f)
                    slot.progress.animateTo(1f, tween(slot.durationMs, easing = LinearEasing))
                } finally {
                    release(slot)
                }
            }
        }
        jobs.joinAll()
    }
}

/** 粒子绘制：全部解析求值，无分配 */
fun DrawScope.drawCashParticles(
    fx: CashFxController,
    noteColors: List<NoteColor>,
    labels: Map<Denomination, TextLayoutResult>,
    baselineY: Float,
    px: Float,
) {
    val centerX = size.width / 2f
    for (slot in fx.slots) {
        if (!slot.inUse) continue
        val t = slot.progress.value
        if (t <= 0f) continue
        val color = noteColors[slot.denom.ordinal]
        val label = labels[slot.denom]
        when (slot.kind) {
            NoteFxKind.POP_IN -> {
                val e = 1f - (1f - t) * (1f - t) // easeOutQuad
                val alpha = (t / 0.3f).coerceAtMost(1f) * (1f - slot.fade.value)
                val scale = 0.62f + 0.38f * e
                val y = slot.cy - (1f - e) * 6f // 从上方 6dp 轻微沉降落位
                drawBanknote(
                    centerX + slot.cx * px, baselineY + y * px,
                    CashPileSpecs.NOTE_W_DP * px, CashPileSpecs.NOTE_H_DP * px,
                    slot.rotation, color, label, alpha = alpha, scale = scale,
                )
            }
            NoteFxKind.PULL_OUT -> {
                val e = 1f - (1f - t) * (1f - t)
                val alpha = if (t < 0.55f) 1f else (1f - (t - 0.55f) / 0.45f).coerceIn(0f, 1f)
                val y = slot.cy - slot.lift * e
                val x = slot.cx + slot.drift * t
                val rotation = slot.rotation + slot.tilt * t
                drawBanknote(
                    centerX + x * px, baselineY + y * px,
                    CashPileSpecs.NOTE_W_DP * px, CashPileSpecs.NOTE_H_DP * px,
                    rotation, color, label, alpha = alpha, scale = 1f + 0.06f * t,
                )
            }
        }
    }
}

/** 纸币堆画布：layer 入场 + 地面 + 堆 + 粒子，全部在 draw phase */
@Composable
fun CashPileView(
    fx: CashFxController,
    layout: CashStackLayout,
    noteColors: List<NoteColor>,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val labels = remember(measurer, noteColors) {
        Denomination.entries.associateWith { d ->
            measurer.measure(
                d.label,
                TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = noteColors[d.ordinal].deep),
            )
        }
    }
    Canvas(
        modifier.graphicsLayer {
            alpha = fx.layerAlpha.value
            val s = fx.layerScale.value
            scaleX = s
            scaleY = s
            transformOrigin = TransformOrigin(0.5f, 0.85f)
        },
    ) {
        if (!visible || layout.stacks.isEmpty()) return@Canvas
        val px = density
        val baselineY = size.height - CashPileSpecs.GROUND_PAD_DP.dp.toPx()
        drawGround(layout, baselineY, px)
        drawCashStack(layout, fx.stackCount.value, noteColors, labels, baselineY, px)
        drawCashParticles(fx, noteColors, labels, baselineY, px)
    }
}

/** 单张纸币图标（HUD 资源条 / 未设预算引导用）；宽度决定是否画面额数字 */
@Composable
fun BanknoteMark(noteColors: List<NoteColor>, width: Dp, modifier: Modifier = Modifier, withLabel: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val label = if (withLabel) {
        remember(measurer, noteColors) {
            measurer.measure(
                Denomination.HUNDRED.label,
                TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = noteColors[Denomination.HUNDRED.ordinal].deep),
            )
        }
    } else {
        null
    }
    Canvas(modifier.size(width, width * 0.48f)) {
        drawBanknote(
            cx = size.width / 2f,
            cy = size.height / 2f,
            w = size.width * 0.94f,
            h = size.height * 0.94f,
            rotation = -3f,
            color = noteColors[Denomination.HUNDRED.ordinal],
            label = label,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F4EE, widthDp = 360, heightDp = 420)
@Composable
private fun BanknoteVisualPreview() {
    AccBookTheme {
        val noteColors = MaterialTheme.accBook.noteColors
        val layout = CashStackLayout.build(320f)
        val measurer = rememberTextMeasurer()
        val labels = remember(measurer, noteColors) {
            Denomination.entries.associateWith { d ->
                measurer.measure(d.label, TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = noteColors[d.ordinal].deep))
            }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
        ) {
            // 单张各面额
            Denomination.entries.forEachIndexed { i, d ->
                drawBanknote(
                    40.dp.toPx() + i * 56.dp.toPx(), 56.dp.toPx(),
                    48.dp.toPx(), 23.dp.toPx(), 0f, noteColors[d.ordinal], labels[d],
                )
            }
            drawCashStack(
                layout = layout,
                totalNotes = 20f,
                noteColors = noteColors,
                labels = labels,
                baselineY = size.height - 36.dp.toPx(),
                px = density,
            )
        }
    }
}
