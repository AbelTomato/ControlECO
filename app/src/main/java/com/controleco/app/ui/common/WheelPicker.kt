package com.controleco.app.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
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
 * 平时就以"三行微缩滚轮"的姿态待在行尾（当前值居中、上下项按透视旋转并渐隐）；
 * **点一下就地激活**（无弹窗、无跳转），上下拖动带吸附，点任意条目即选定并收回，
 * 滚动停下片刻自动提交并收回。收起态不吃手势，页面照常滚动。
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

    Box(
        modifier = modifier
            .height(itemHeight * 3)
            .width(96.dp)
            // 收起态整块可点 = 激活滚轮；激活后让位给条目与滚动手势
            .clickable(enabled = !expanded) { expanded = true },
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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = itemHeight),
            flingBehavior = rememberSnapFlingBehavior(listState),
            userScrollEnabled = expanded,
        ) {
            itemsIndexed(items) { index, label ->
                val selected = index == safeIndex || (expanded && index == centeredIndex)
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
                        .clickable(enabled = expanded) {
                            onSelected(index)
                            scope.launch { listState.animateScrollToItem(index) }
                            expanded = false
                        },
                    contentAlignment = Alignment.Center,
                ) {
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
            }
        }
    }
}
