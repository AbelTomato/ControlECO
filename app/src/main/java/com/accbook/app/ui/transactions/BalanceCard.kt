package com.accbook.app.ui.transactions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.accbook.app.data.PeriodMode
import com.accbook.app.ui.common.AutoScaledText
import com.accbook.app.ui.theme.CardTones
import com.accbook.app.ui.common.PeriodSwitch
import com.accbook.app.ui.common.periodBalanceLabel
import com.accbook.app.ui.common.relativeLuminance
import com.accbook.app.ui.common.scrimAlphaFor
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 账单页顶部悬浮结余卡（取代原来的页眉 + 汇总行）：
 *   - 大字 = 当前周期结余；右侧两行 = 收入（+，绿）/ 支出（−，红），不写文字标签；
 *   - 左上 = 月/年 周期切换，右上 = 背景设置；
 *   - 背景默认为自绘几何图案（浅蓝渐变 + 同心圆装饰），也可换成相册图片；
 *   - **自动明暗判定**：量出背景亮度 → 选浅底/深底配色，对比度不足时按 WCAG ≥ 3 叠遮罩
 *     （浅底叠白、深底叠黑），保证卡上每处文字都清晰。
 */
@Composable
fun BalanceCard(
    mode: PeriodMode,
    balanceCents: Long,
    incomeCents: Long,
    expenseCents: Long,
    imagePath: String?,
    onModeChange: (PeriodMode) -> Unit,
    onOpenImageSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ab = MaterialTheme.accBook
    val loaded by rememberCardImage(imagePath)

    // 默认几何卡面：浅蓝渐变到主色，取渐变深色端作为"背景亮度"（文字落在下半部分）
    val base = MaterialTheme.colorScheme.primaryContainer
    val deep = lerp(base, ab.primaryStrong, 0.45f)

    // 自动明暗判定只针对**自定义背景图**：默认卡面是自绘渐变，直接用主题配色更稳
    // （自绘卡面的深浅由明暗主题决定，不该再叠遮罩压暗）。
    val hasImage = loaded?.bitmap != null
    val imageLuminance = loaded?.luminance ?: 0f
    val tones: CardTones = if (!hasImage) {
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
    } else {
        // 深于 0.30 亮度用深底白字，否则浅底墨字；再按 WCAG ≥ 3 反算遮罩厚度
        if (imageLuminance < 0.30f) ab.cardOnDark else ab.cardOnLight
    }
    val scrimAlpha = if (!hasImage) {
        0f
    } else {
        maxOf(
            scrimAlphaFor(imageLuminance, relativeLuminance(tones.ink)),
            scrimAlphaFor(imageLuminance, relativeLuminance(tones.income)),
            scrimAlphaFor(imageLuminance, relativeLuminance(tones.expense)),
        )
    }

    val deco = if (imageLuminance < 0.30f) Color.White else Color.Black
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(152.dp)
            .shadow(elevation = 10.dp, shape = shape)
            .clip(shape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val bmp = loaded?.bitmap
            if (bmp != null) {
                // 居中裁剪铺满
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
            } else {
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
            if (scrimAlpha > 0.01f) drawRect(tones.scrim.copy(alpha = scrimAlpha))
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

/** 卡面图片 + 其整体亮度（0 黑 → 1 白） */
private data class CardImage(val bitmap: ImageBitmap, val luminance: Float)

@Composable
private fun rememberCardImage(path: String?): State<CardImage?> {
    val context = LocalContext.current
    return produceState<CardImage?>(initialValue = null, key1 = path) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { loadCardImage(context, path) }
        }
    }
}

/** 读取卡面图片（内部文件或历史 content://），并采样估算整体亮度 */
private fun loadCardImage(context: Context, path: String): CardImage? = runCatching {
    val bmp = if (path.startsWith("content://")) {
        val uri = Uri.parse(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } else {
        if (!File(path).exists()) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        BitmapFactory.decodeFile(path, opts)
    } ?: return@runCatching null
    CardImage(bmp.asImageBitmap(), meanLuminance(bmp))
}.getOrNull()

private fun sampleSize(width: Int, height: Int, maxSize: Int = 1600): Int {
    var sample = 1
    while (width / sample > maxSize * 2 || height / sample > maxSize * 2) sample *= 2
    return sample
}

/** 隔点采样求平均相对亮度（≤48×48 个采样点，够稳且快） */
private fun meanLuminance(bmp: Bitmap): Float {
    val stepX = max(1, bmp.width / 48)
    val stepY = max(1, bmp.height / 48)
    var sum = 0.0
    var count = 0
    var y = 0
    while (y < bmp.height) {
        var x = 0
        while (x < bmp.width) {
            val c = bmp.getPixel(x, y)
            sum += relativeLuminance(
                android.graphics.Color.red(c) / 255f,
                android.graphics.Color.green(c) / 255f,
                android.graphics.Color.blue(c) / 255f,
            )
            count++
            x += stepX
        }
        y += stepY
    }
    return if (count == 0) 1f else (sum / count).toFloat()
}

/**
 * 相册选图 → **复制到应用内部存储**（`filesDir/card_bg.jpg`，长边压到 1600px 以内）：
 * 不依赖 URI 授权、重启后仍在，原图被删也不影响卡片。返回内部文件路径，失败返回 null。
 */
fun importCardImage(context: Context, uri: Uri): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return@runCatching null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return@runCatching null
    val file = File(context.filesDir, "card_bg.jpg")
    FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
    bmp.recycle()
    file.absolutePath
}.getOrNull()
