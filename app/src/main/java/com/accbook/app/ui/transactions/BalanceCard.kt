package com.accbook.app.ui.transactions

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ImageBitmap as ComposeImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.accbook.app.data.PeriodMode
import com.accbook.app.ui.common.AutoScaledText
import com.accbook.app.ui.common.PeriodSwitch
import com.accbook.app.ui.common.periodBalanceLabel
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 账单页顶部悬浮结余卡（取代原来的页眉 + 汇总行）：
 *   - 大字 = 当前周期结余；右侧两行 = 收入（+，绿）/ 支出（−，红），不写文字标签；
 *   - 左上 = 月/年 周期切换，右上 = 背景设置；
 *   - 背景默认为自绘几何图案（浅蓝渐变 + 同心圆装饰），可换成相册图片（自动压暗保证文字可读）。
 */
@Composable
fun BalanceCard(
    mode: PeriodMode,
    balanceCents: Long,
    incomeCents: Long,
    expenseCents: Long,
    imageUri: String?,
    onModeChange: (PeriodMode) -> Unit,
    onOpenImageSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ab = MaterialTheme.accBook
    val hasImage = !imageUri.isNullOrBlank()
    val bitmap by rememberCardBitmap(imageUri)

    // 有图 = 深底 + 白字；默认 = 浅蓝底 + 深墨字（都用主题 token，不散落颜色字面量）
    val ink = if (hasImage) ab.onCardImage else MaterialTheme.colorScheme.onPrimaryContainer
    val sub = ink.copy(alpha = 0.72f)
    val incomeColor = if (hasImage) ab.incomeOnCard else ab.income
    val expenseColor = if (hasImage) ab.expenseOnCard else ab.critical

    val base = MaterialTheme.colorScheme.primaryContainer
    val deep = lerp(base, ab.primaryStrong, 0.45f)
    val deco = MaterialTheme.colorScheme.onPrimaryContainer

    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(152.dp)
            .shadow(elevation = 10.dp, shape = shape)
            .clip(shape),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val bmp = bitmap
            if (bmp != null) {
                // 居中裁剪铺满 + 压暗遮罩（保证卡上文字可读）
                val scale = max(size.width / bmp.width, size.height / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                drawImage(
                    image = bmp,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bmp.width, bmp.height),
                    dstOffset = IntOffset(((size.width - w) / 2f).toInt(), ((size.height - h) / 2f).toInt()),
                    dstSize = IntSize(w.toInt(), h.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
                drawRect(ab.cardScrim)
            } else {
                // 默认几何背景：浅蓝竖向渐变 + 右上同心圆 + 左下圆环（都被卡片圆角裁掉）
                drawRect(Brush.verticalGradient(listOf(base, deep)))
                val ring = deco.copy(alpha = 0.10f)
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
                // 左上一条细高光，让卡片有"被点亮"的层次
                drawLine(
                    color = deco.copy(alpha = 0.06f),
                    start = Offset(0f, size.height * 0.30f),
                    end = Offset(size.width, size.height * 0.06f),
                    strokeWidth = size.height * 0.10f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PeriodSwitch(mode = mode, onChange = onModeChange)
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
                        tint = sub,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(periodBalanceLabel(mode), style = MaterialTheme.typography.labelMedium, color = sub)
                    Spacer(Modifier.height(2.dp))
                    AutoScaledText(
                        text = Money.formatYuanGroupedFixed(balanceCents),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = ink,
                        maxWidth = 190.dp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Money.formatSignedPlain(incomeCents),
                        style = MaterialTheme.typography.titleSmall,
                        color = incomeColor,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        Money.formatSignedPlain(-expenseCents),
                        style = MaterialTheme.typography.titleSmall,
                        color = expenseColor,
                    )
                }
            }
        }
    }
}

/**
 * 卡片背景图：按 URI 解码并降采样（长边约 1600px 以内），
 * 失败（文件被删/无权限）时回落默认几何背景。
 */
@Composable
private fun rememberCardBitmap(uri: String?): androidx.compose.runtime.State<ComposeImageBitmap?> {
    val context = LocalContext.current
    return produceState<ComposeImageBitmap?>(initialValue = null, key1 = uri) {
        value = if (uri.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { loadScaledBitmap(context, Uri.parse(uri)) }?.asImageBitmap()
        }
    }
}

private fun loadScaledBitmap(context: Context, uri: Uri, maxSize: Int = 1600): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > maxSize * 2 || bounds.outHeight / sample > maxSize * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}.getOrNull()

/** 备用：如果后续需要直接放 ImageBitmap（当前用 Canvas 绘制，保留扩展位） */
@Suppress("unused")
private fun ImageBitmap.isUsable(): Boolean = width > 0 && height > 0
