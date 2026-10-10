package com.controleco.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 卡面图片的加载与统计（放数据层：ViewModel 在进程启动时就开始解码，
 * 不再等账单页第一次组合——那时候已经晚了，会先闪一帧默认背景）。
 */
data class CardImage(
    /** 正常尺寸位图（解码完成前为 null） */
    val full: Bitmap?,
    /** 超小缩略图：几毫秒就能拿到，先顶着显示（真实的模糊图，不是纯色块） */
    val preview: Bitmap?,
    val colorArgb: Int,
    val luminance: Float,
) {
    /** 可以直接画的东西：全尺寸优先，其次缩略图 */
    val drawable: Bitmap? get() = full ?: preview

    fun asImage(): ImageBitmap? = drawable?.asImageBitmap()
}

/** 导入结果：内部文件路径 + 平均色（ARGB）+ 整体亮度 */
data class ImportedCardImage(val path: String, val colorArgb: Int, val luminance: Float)

object CardImageLoader {

    /** 常规显示用尺寸上限（长边） */
    private const val FULL_SIZE = 1600

    /** 占位用缩略图尺寸上限：JPEG 支持 DCT 缩放，这一步通常只要几毫秒 */
    const val PREVIEW_SIZE = 80

    /** 按目标尺寸解码（内部文件或 content:// 均可）；失败返回 null */
    fun decode(context: Context, path: String, targetSize: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, path)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetSize) }
        openStream(context, path)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    private fun openStream(context: Context, path: String) =
        if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
        } else {
            if (!File(path).exists()) null else File(path).inputStream()
        }

    private fun sampleSize(width: Int, height: Int, targetSize: Int): Int {
        var sample = 1
        while (width / sample > targetSize * 2 || height / sample > targetSize * 2) sample *= 2
        return sample
    }

    /** 隔点采样的平均色（ARGB） */
    fun averageColor(bmp: Bitmap): Int {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var count = 0
        forEachSample(bmp) { c ->
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++
        }
        if (count == 0) return Color.GRAY
        return Color.rgb(
            (r / count).toInt().coerceIn(0, 255),
            (g / count).toInt().coerceIn(0, 255),
            (b / count).toInt().coerceIn(0, 255),
        )
    }

    /** 隔点采样的平均相对亮度（0 黑 → 1 白） */
    fun meanLuminance(bmp: Bitmap): Float {
        var sum = 0.0
        var count = 0
        forEachSample(bmp) { c ->
            sum += com.controleco.app.ui.common.relativeLuminance(
                Color.red(c) / 255f,
                Color.green(c) / 255f,
                Color.blue(c) / 255f,
            )
            count++
        }
        return if (count == 0) 1f else (sum / count).toFloat()
    }

    private inline fun forEachSample(bmp: Bitmap, action: (Int) -> Unit) {
        val stepX = max(1, bmp.width / 48)
        val stepY = max(1, bmp.height / 48)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                action(bmp.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
    }
}

/**
 * 相册选图 → **复制到应用内部存储**（`filesDir/card_bg_<时间戳>.jpg`，长边压到 1600px 以内）：
 * 不依赖 URI 授权、重启后仍在，原图被删也不影响；文件名带时间戳使"换图"能被 UI 感知。
 * 同时返回平均色与亮度（用于解码完成前打底）。失败返回 null。
 */
fun importCardImage(context: Context, uri: Uri): ImportedCardImage? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return@runCatching null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
    }
    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return@runCatching null

    val color = CardImageLoader.averageColor(bmp)
    val luminance = CardImageLoader.meanLuminance(bmp)

    val file = File(context.filesDir, "card_bg_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
    bmp.recycle()
    // 旧图**先不删**：编辑器里点「取消」要把上一张原样恢复，只有真正「应用」时才清旧
    // （清理时机见 LedgerViewModel.loadCardImage / deleteOtherCardImages）
    ImportedCardImage(file.absolutePath, color, luminance)
}.getOrNull()

/** 清理旧卡面图：只保留 [keepPath]（"应用"/"取消"收尾时调用；传 null = 全删） */
fun deleteOtherCardImages(context: Context, keepPath: String?) {
    val keep = keepPath?.let { File(it).name }
    context.filesDir.listFiles { f -> f.name.startsWith("card_bg") && f.name != keep }
        ?.forEach { it.delete() }
}

private fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > 3200 || height / sample > 3200) sample *= 2
    return sample
}
