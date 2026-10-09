package com.accbook.app.ui.transactions

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.accbook.app.data.CardTransform
import com.accbook.app.ui.theme.accBook
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 卡面背景图调整（全屏，仿相册/裁剪的交互）：
 * - 整张图片铺满全屏预览，中间一个**与账单卡片同尺寸的参考框**，框内就是卡片最终看到的部分；
 * - 手指只做平移与缩放（单指拖动 / 双指捏合），旋转放在底部滑条上（避免手势误触旋转）；
 * - 框外压暗 + 三分构图线 + 四角标记；「复位」回到居中铺满。
 */
@Composable
fun CardImageEditorDialog(
    image: Bitmap?,
    initial: CardTransform,
    onApply: (CardTransform) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial.normalized()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val colors = MaterialTheme.accBook
    val overlayInk = colors.overlayInk
    val backdrop = MaterialTheme.colorScheme.scrim
    val bitmap = remember(image) { image?.asImageBitmap() }
    val density = LocalDensity.current
    val metrics = remember(density) {
        FrameMetrics(
            horizontalMarginPx = with(density) { 32.dp.toPx() },
            cardHeightPx = with(density) { 152.dp.toPx() },
            verticalPaddingPx = with(density) { 24.dp.toPx() },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false：对话框窗口按全屏铺开（默认会被状态栏整体顶下去，
        // 窗口又按整屏高度测量，结果底部的滑条/提示被挤出屏幕外——实测底部 152px 全被裁掉）；
        // 内边距改由内容自己处理：顶部栏吃状态栏、底部滑条区吃导航栏
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // 对话框窗口在部分 ROM（实测 MIUI）上会被状态栏整体顶下去，而窗口高度仍是整屏高度：
        // 内容底部这段就跑到屏幕外了（底栏的滑条/提示被裁掉）。这里量出窗口在屏幕上的实际偏移，
        // 等价地在底部让出这么多高度，内容底部就落回屏幕内；平台不偏移时该值为 0，不影响正常设备。
        var windowOffsetPx by remember { mutableIntStateOf(0) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val onScreen = it.positionOnScreen().y.roundToInt()
                    if (onScreen != windowOffsetPx) windowOffsetPx = onScreen
                }
                .padding(bottom = with(density) { windowOffsetPx.toDp() })
                .background(backdrop),
        ) {
            Column(Modifier.fillMaxSize()) {
                // ---- 顶栏：取消 / 标题 / 应用 ----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消", color = overlayInk) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "调整背景图",
                        style = MaterialTheme.typography.titleMedium,
                        color = overlayInk,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onApply(draft) }) { Text("应用", color = overlayInk) }
                }

                // ---- 画布：全屏图片 + 卡片参考框 ----
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectTransformGestures(panZoomLock = true) { _, pan, zoom, _ ->
                                val w = metrics.spec(canvasSize).widthPx.coerceAtLeast(1f)
                                val h = metrics.spec(canvasSize).heightPx.coerceAtLeast(1f)
                                draft = draft.copy(
                                    scale = draft.scale * zoom,
                                    offsetX = draft.offsetX + pan.x / w,
                                    offsetY = draft.offsetY + pan.y / h,
                                ).normalized()
                            }
                        },
                ) {
                    val spec = metrics.spec(canvasSize)
                    val frameLeft = spec.leftPx
                    val frameTop = spec.topPx
                    val frameW = spec.widthPx
                    val frameH = spec.heightPx
                    val frameCenter = Offset(frameLeft + frameW / 2f, frameTop + frameH / 2f)

                    val bmp = bitmap
                    if (bmp != null) {
                        // 与卡面完全相同的映射：居中铺满（cover）+ 位移/缩放/旋转
                        val t = draft.normalized()
                        val k = max(frameW / bmp.width, frameH / bmp.height) * t.scale
                        val w = bmp.width * k
                        val h = bmp.height * k
                        withTransform({
                            translate(t.offsetX * frameW, t.offsetY * frameH)
                            rotate(t.rotation, pivot = frameCenter)
                        }) {
                            drawImage(
                                image = bmp,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(bmp.width, bmp.height),
                                dstOffset = IntOffset(
                                    (frameCenter.x - w / 2f).toInt(),
                                    (frameCenter.y - h / 2f).toInt(),
                                ),
                                dstSize = IntSize(w.toInt(), h.toInt()),
                                filterQuality = FilterQuality.Medium,
                            )
                        }
                    }

                    // 框外压暗，视线聚焦到卡片范围
                    val dim = backdrop.copy(alpha = 0.62f)
                    drawRect(dim, topLeft = Offset(0f, 0f), size = Size(size.width, frameTop))
                    drawRect(
                        dim,
                        topLeft = Offset(0f, frameTop + frameH),
                        size = Size(size.width, size.height - frameTop - frameH),
                    )
                    drawRect(dim, topLeft = Offset(0f, frameTop), size = Size(frameLeft, frameH))
                    drawRect(
                        dim,
                        topLeft = Offset(frameLeft + frameW, frameTop),
                        size = Size(size.width - frameLeft - frameW, frameH),
                    )

                    // 三分构图线（辅助排版，不参与最终画面）
                    val grid = overlayInk.copy(alpha = 0.25f)
                    for (i in 1..2) {
                        val x = frameLeft + frameW * i / 3f
                        drawLine(grid, Offset(x, frameTop), Offset(x, frameTop + frameH), 1.dp.toPx())
                        val y = frameTop + frameH * i / 3f
                        drawLine(grid, Offset(frameLeft, y), Offset(frameLeft + frameW, y), 1.dp.toPx())
                    }

                    // 参考框 + 四角标记
                    drawRect(
                        color = overlayInk.copy(alpha = 0.85f),
                        topLeft = Offset(frameLeft, frameTop),
                        size = Size(frameW, frameH),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    val tick = 20.dp.toPx()
                    val tickW = 3.dp.toPx()
                    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                        drawLine(overlayInk, Offset(x, y), Offset(x + dx * tick, y), tickW)
                        drawLine(overlayInk, Offset(x, y), Offset(x, y + dy * tick), tickW)
                    }
                    corner(frameLeft, frameTop, 1f, 1f)
                    corner(frameLeft + frameW, frameTop, -1f, 1f)
                    corner(frameLeft, frameTop + frameH, 1f, -1f)
                    corner(frameLeft + frameW, frameTop + frameH, -1f, -1f)
                }

                // ---- 底部：旋转滑条 + 提示（与全屏预览同一套深色观感，不用白面板）----
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    color = backdrop.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp).navigationBarsPadding()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("旋转", style = MaterialTheme.typography.labelLarge, color = overlayInk)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${draft.rotation.roundToInt()}°",
                                style = MaterialTheme.typography.labelLarge,
                                color = overlayInk.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { draft = CardTransform.DEFAULT }) {
                                Text("复位", color = overlayInk)
                            }
                        }
                        Slider(
                            value = draft.rotation,
                            onValueChange = { draft = draft.copy(rotation = it).normalized() },
                            valueRange = 0f..360f,
                            colors = SliderDefaults.colors(
                                thumbColor = overlayInk,
                                activeTrackColor = overlayInk,
                                inactiveTrackColor = overlayInk.copy(alpha = 0.28f),
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent,
                            ),
                        )
                        Text(
                            "单指拖动移动　双指捏合缩放　下方滑条旋转",
                            style = MaterialTheme.typography.labelMedium,
                            color = overlayInk.copy(alpha = 0.62f),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

/** 参考框几何：与账单卡片同尺寸（宽 = 屏宽 − 32dp、高 = 152dp），在画布里居中 */
private data class FrameSpec(val leftPx: Float, val topPx: Float, val widthPx: Float, val heightPx: Float)

/**
 * 参考框尺寸计算（绘制与手势命中共用同一份）：
 * 横屏/小高度时整体等比缩小，保证参考框始终完整可见。
 */
private class FrameMetrics(
    private val horizontalMarginPx: Float,
    private val cardHeightPx: Float,
    private val verticalPaddingPx: Float,
) {
    fun spec(canvas: IntSize): FrameSpec {
        val maxW = (canvas.width - horizontalMarginPx).coerceAtLeast(1f)
        val maxH = (canvas.height - verticalPaddingPx).coerceAtLeast(1f)
        var w = maxW
        var h = cardHeightPx
        if (h > maxH) {
            val k = maxH / h
            h *= k
            w = (maxW * k).coerceAtLeast(1f)
        }
        return FrameSpec((canvas.width - w) / 2f, (canvas.height - h) / 2f, w, h)
    }
}
