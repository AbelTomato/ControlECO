package com.controleco.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.controleco.app.ui.theme.accBook
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 行内滚轮选择器（3D 透视 + 渐隐，Wheel Picker 观感）。
 *
 * 交互（自测定稿）：
 * - 默认收起：三行微缩滚轮，**不可滑动**（在它上面拖动 = 正常滚动页面）；
 * - **点击**：悬浮突出——在数值处浮出一块更大的滚轮卡（无背景遮罩、非对话框），可上下拖动选择；
 *   **点其他区域**提交当前值并收起；
 * - **长按**：不松手直接上下滑动设定，**手指离开屏幕**即提交并收起。
 *
 * 长按路径刻意用"行内悬浮"而不是新开窗口：手势进行中弹出新窗口会被系统按防遮挡规则
 * 打断（ACTION_CANCEL），所以长按拖动时把悬浮卡画在页面里（外层行留了 zIndex 抬升，
 * 见 ProfileScreen 的接入点），抬手即收起；点击路径没有进行中的手势，才用真正的浮层
 * （自带"点其他区域收起"）。
 *
 * 连续位置 `pos`（单位 = 项，可含小数）是唯一事实源：三条路径与收起态都从它渲染，
 * 因此拖动时上下的视觉是连续的。
 */
@Composable
fun InlineWheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 28.dp,
) {
    if (items.isEmpty()) return
    val lastIndex = items.lastIndex
    val safeIndex = selectedIndex.coerceIn(0, lastIndex)
    var popupOpen by remember { mutableStateOf(false) }
    var inlineDrag by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(safeIndex.toFloat()) }

    // 两个悬浮都结束（收起）时，把位置对齐到外部值
    LaunchedEffect(safeIndex, popupOpen, inlineDrag) {
        if (!popupOpen && !inlineDrag) pos = safeIndex.toFloat()
    }

    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    // 落在正中的项（拖动中实时变化）：高亮与提交都以它为准
    val highlightIndex by remember {
        derivedStateOf { pos.roundToInt().coerceIn(0, lastIndex) }
    }

    fun commitAndClose() {
        val snapped = highlightIndex
        pos = snapped.toFloat()
        if (snapped != safeIndex) onSelected(snapped)
        popupOpen = false
        inlineDrag = false
    }

    fun dragBy(deltaY: Float) {
        pos = (pos - deltaY / itemHeightPx).coerceIn(0f, lastIndex.toFloat())
    }

    Box(
        modifier = modifier
            .height(itemHeight * 3)
            .width(96.dp)
            // 点击：悬浮突出；点其他区域收起由 Popup 的 onDismissRequest 负责
            .clickable(enabled = !inlineDrag) { popupOpen = true }
            // 长按：直接滑动设定；抬手（或取消）提交并收起
            .pointerInput(lastIndex) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { inlineDrag = true },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragBy(dragAmount.y)
                    },
                    onDragEnd = { commitAndClose() },
                    onDragCancel = { commitAndClose() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        WheelRows(
            items = items,
            pos = pos,
            highlight = highlightIndex,
            itemHeight = itemHeight,
            visibleRows = 3,
            modifier = Modifier.fillMaxSize(),
        )
        // 长按拖动中：行内悬浮卡（溢出占位区但不新开窗口，避免手势被打断）
        if (inlineDrag) {
            FloatingWheelCard(
                items = items,
                pos = pos,
                highlight = highlightIndex,
                itemHeight = itemHeight,
                modifier = Modifier.wrapContentSize(Alignment.Center, unbounded = true),
            )
        }
    }

    if (popupOpen) {
        Popup(
            popupPositionProvider = WheelPopupPosition,
            onDismissRequest = { commitAndClose() }, // 点其他区域：设定并退出悬浮
            properties = PopupProperties(focusable = true),
        ) {
            FloatingWheelCard(
                items = items,
                pos = pos,
                highlight = highlightIndex,
                itemHeight = itemHeight,
                onDrag = { dragBy(it) },
                onTapItem = { index ->
                    pos = index.toFloat()
                    commitAndClose()
                },
            )
        }
    }
}

/**
 * 悬浮滚轮卡：5 行滚动窗口 + 选中带。
 * [onDrag]/[onTapItem] 只有浮层版本需要——行内版本由外层长按手势驱动。
 */
@Composable
private fun FloatingWheelCard(
    items: List<String>,
    pos: Float,
    highlight: Int,
    itemHeight: Dp,
    modifier: Modifier = Modifier,
    onDrag: ((Float) -> Unit)? = null,
    onTapItem: ((Int) -> Unit)? = null,
) {
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val shape = RoundedCornerShape(16.dp)
    var card = modifier
        .width(120.dp)
        .height(itemHeight * 5)
        .shadow(12.dp, shape)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.accBook.hairline, shape)
    if (onDrag != null) {
        card = card.pointerInput(Unit) {
            detectVerticalDragGestures { change, dragAmount ->
                change.consume()
                onDrag(dragAmount)
            }
        }
    }
    if (onTapItem != null) {
        card = card.pointerInput(Unit, itemHeightPx) {
            detectTapGestures { offset: Offset ->
                val idx = (pos + (offset.y - size.height / 2f) / itemHeightPx)
                    .roundToInt().coerceIn(0, items.lastIndex)
                onTapItem(idx)
            }
        }
    }
    Box(card, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(9.dp)),
        )
        WheelRows(
            items = items,
            pos = pos,
            highlight = highlight,
            itemHeight = itemHeight,
            visibleRows = 5,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 滚轮画法：整列按 `pos` 平移，逐项 3D 透视 + 渐隐；高亮项主色加粗 */
@Composable
private fun WheelRows(
    items: List<String>,
    pos: Float,
    highlight: Int,
    itemHeight: Dp,
    visibleRows: Int,
    modifier: Modifier = Modifier,
) {
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.graphicsLayer {
                // 让 pos 那一项落在可视区正中
                translationY = ((visibleRows - 1f) / 2f - pos) * itemHeightPx
            },
        ) {
            items.forEachIndexed { index, label ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .graphicsLayer {
                            val d = index - pos
                            rotationX = d.coerceIn(-3f, 3f) * 16f
                            transformOrigin = TransformOrigin.Center
                            cameraDistance = 6f
                            alpha = (1f - 0.28f * abs(d)).coerceIn(0f, 1f)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    WheelText(label, selected = index == highlight)
                }
            }
        }
    }
}

/** 悬浮滚轮的位置：贴着数值所在的这一行浮出（右对齐；垂直居中于当前值），并夹在窗口内 */
private object WheelPopupPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = 8
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val y = (anchorBounds.center.y - popupContentSize.height / 2)
            .coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        return IntOffset(x, y)
    }
}

@Composable
private fun WheelText(label: String, selected: Boolean) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
