package com.accbook.app.ui.transactions

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.accbook.app.data.CardTransform

/**
 * 背景图调整对话框：选完图片后立刻进入，直接在看得到的卡面上调整。
 * 单指拖动 = 位移，双指捏合 = 缩放，双指旋转 = 旋转；「复位」回到居中铺满。
 * 预览用的就是真实卡面（同样的尺寸与配色），所见即所得。
 */
@Composable
fun CardImageEditorDialog(
    initial: CardTransform,
    onApply: (CardTransform) -> Unit,
    onDismiss: () -> Unit,
    preview: @Composable (CardTransform) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("调整背景图", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "单指拖动移动 · 双指捏合缩放 · 双指旋转",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(152.dp)
                        .onSizeChanged { boxSize = it }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                val w = boxSize.width.coerceAtLeast(1).toFloat()
                                val h = boxSize.height.coerceAtLeast(1).toFloat()
                                draft = draft.copy(
                                    scale = draft.scale * zoom,
                                    offsetX = draft.offsetX + pan.x / w,
                                    offsetY = draft.offsetY + pan.y / h,
                                    rotation = draft.rotation + rotation,
                                ).normalized()
                            }
                        },
                ) {
                    preview(draft)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { draft = CardTransform.DEFAULT }) { Text("复位") }
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { onApply(draft) }) { Text("应用") }
                }
            }
        }
    }
}
