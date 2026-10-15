package com.controleco.app.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 行内滚轮选择器（3D 透视 + 渐隐，Wheel Picker 观感）：
 * 平时以"三行微缩滚轮"的姿态待在行尾（当前值居中、上下项按透视旋转并渐隐）；
 * **点一下就地激活**（无弹窗、无跳转），上下拖动带吸附，点任意条目即选定并收回，
 * 滚动停下片刻自动提交并收回。
 *
 * 手势约定：**收起态是纯静态渲染**（三行文字，没有任何子控件的指针处理），
 * 整块点击由外壳承接——保证"点一下一定能展开"；展开态才挂 LazyColumn 与条目点击。
 *
 * 状态纯粹：值由调用方持有（[selectedIndex] + [onSelected]）。
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
    val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeIndex)
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    // 居中（吸附后）的条目：停下时 firstVisibleItemIndex 即选中项
    val centeredIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, items.lastIndex) }
    }

    // 激活期间：拖动 → 停下即提交；再静置片刻收回（"融入"行内）
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        var scrolled = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                scrolled = true
            } else if (scrolled) {
                onSelected(centeredIndex)
                delay(900)
                if (!listState.isScrollInProgress) expanded = false
            }
        }
    }

    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val bandAlpha by animateFloatAsState(if (expanded) 1f else 0f, tween(180), label = "wheelBand")

    // 互斥手势：收起时只有外壳可点（无任何子指针处理），展开时只有滚轮自己处理——
    // 两个方向都不留"禁用中的点击器"，避免禁用点击器吞掉事件（真机反馈：点击无反应）
    val tapToExpand = if (expanded) Modifier else Modifier.clickable { expanded = true }

    Box(
        modifier = modifier
            .height(itemHeight * 3)
            .width(96.dp)
            .then(tapToExpand),
        contentAlignment = Alignment.Center,
    ) {
        // 选中带：只有激活时出现，明示"中间这行就是当前值"
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .graphicsLayer { alpha = bandAlpha }
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(9.dp)),
        )
        if (expanded) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = itemHeight),
                flingBehavior = rememberSnapFlingBehavior(listState),
            ) {
                itemsIndexed(items) { index, label ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(itemHeight)
                            .graphicsLayer {
                                // 3D 透视：离中心越远，绕 X 轴转得越多、越淡
                                val center = listState.firstVisibleItemIndex +
                                    listState.firstVisibleItemScrollOffset / itemHeightPx
                                val offset = (index - center).coerceIn(-2.5f, 2.5f)
                                rotationX = offset * 18f
                                transformOrigin = TransformOrigin.Center
                                cameraDistance = 6f
                                alpha = (1f - 0.26f * abs(offset)).coerceIn(0.25f, 1f)
                            }
                            .clickable {
                                onSelected(index)
                                scope.launch { listState.scrollToItem(index) }
                                expanded = false
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        WheelText(label, index == centeredIndex)
                    }
                }
            }
        } else {
            // 收起态：三行静态微缩滚轮（前一/当前/后一），零子控件——点击一定落到外壳
            Column(Modifier.fillMaxSize()) {
                StaticWheelLine(items.getOrNull(safeIndex - 1), offset = -1f, itemHeight = itemHeight, selected = false)
                StaticWheelLine(items[safeIndex], offset = 0f, itemHeight = itemHeight, selected = true)
                StaticWheelLine(items.getOrNull(safeIndex + 1), offset = 1f, itemHeight = itemHeight, selected = false)
            }
        }
    }
}

/** 收起态的一行静态滚轮文字（同样的 3D 透视与渐隐，偏移量固定） */
@Composable
private fun StaticWheelLine(label: String?, offset: Float, itemHeight: Dp, selected: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(itemHeight)
            .graphicsLayer {
                rotationX = offset * 18f
                transformOrigin = TransformOrigin.Center
                cameraDistance = 6f
                alpha = (1f - 0.26f * abs(offset)).coerceIn(0.25f, 1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        label?.let { WheelText(it, selected) }
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
