package com.accbook.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 分类图标：一个字符串描述图标来源，存在 `categories.icon` 列里。
 *
 * - `""`                        默认：按分类名取内置图形（关键词 → 几何/SVG 图形）
 * - `EMOJI:🍜`                  自定义 emoji
 * - `GLYPH:BURGER`              内置图形（`Glyph` 枚举名），颜色按分类名自动取
 * - `GLYPH:BURGER:DC2626`       内置图形 + 自定义颜色（RGB 十六进制）
 * - `IMAGE:cat_icon_123.jpg`    应用内部存储里的图片（相册选图后压缩存入）
 */
object CategoryIcon {

    private const val EMOJI_PREFIX = "EMOJI:"
    private const val GLYPH_PREFIX = "GLYPH:"
    private const val IMAGE_PREFIX = "IMAGE:"
    private const val IMAGE_FILE_PREFIX = "cat_icon_"

    /** emoji 允许的最大码点数（组合 emoji 如家庭/肤色序列会占几个码点） */
    const val MAX_EMOJI_CODE_POINTS = 8

    /** 图标图片解码目标（列表里最大 44dp，160px 足够且省内存） */
    private const val ICON_TARGET_PX = 160

    /** 相册原图压缩上限（超过就降采样，避免内部存储堆积大图） */
    private const val IMPORT_MAX_PX = 512

    fun emoji(value: String) = EMOJI_PREFIX + value.trim()

    /** 内置图形规格；[colorRgb] 为 null 时按分类名自动取色 */
    fun glyph(name: String, colorRgb: Int? = null): String {
        val base = GLYPH_PREFIX + name
        return if (colorRgb == null) base else base + ":" + "%06X".format(colorRgb and 0xFFFFFF)
    }

    fun image(fileName: String) = IMAGE_PREFIX + fileName

    fun emojiOf(icon: String): String? =
        icon.takeIf { it.startsWith(EMOJI_PREFIX) }?.removePrefix(EMOJI_PREFIX)?.ifBlank { null }

    /** 内置图形名（颜色部分不算名字） */
    fun glyphOf(icon: String): String? = glyphParts(icon)?.first?.ifBlank { null }

    /** 内置图形的自定义颜色（RGB 整数）；没写颜色返回 null（= 按分类名取色） */
    fun glyphColorOf(icon: String): Int? = glyphParts(icon)?.second

    private fun glyphParts(icon: String): Pair<String, Int?>? {
        if (!icon.startsWith(GLYPH_PREFIX)) return null
        val body = icon.removePrefix(GLYPH_PREFIX)
        val sep = body.indexOf(':')
        if (sep < 0) return body to null
        val name = body.substring(0, sep)
        // 用 Long 解析再取低 24 位：手写成 8 位（带 FF 前缀）也认，不因超出 Int 范围整段作废
        val rgb = body.substring(sep + 1).trim().toLongOrNull(16)?.let { (it and 0xFFFFFF).toInt() }
        return name to rgb
    }

    fun imageOf(icon: String): String? =
        icon.takeIf { it.startsWith(IMAGE_PREFIX) }?.removePrefix(IMAGE_PREFIX)?.ifBlank { null }

    /**
     * 输入像个 emoji 吗：非空、码点数受限、且不含字母/数字/汉字（避免把普通文字当图标）。
     */
    fun isEmojiInput(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        var count = 0
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            if (isPlainTextCodePoint(cp)) return false
            count++
            if (count > MAX_EMOJI_CODE_POINTS) return false
            i += Character.charCount(cp)
        }
        return true
    }

    private fun isPlainTextCodePoint(cp: Int): Boolean =
        (cp in 0x30..0x39) || // 0-9
            (cp in 0x41..0x5A) || // A-Z
            (cp in 0x61..0x7A) || // a-z
            (cp in 0x4E00..0x9FFF) // 汉字

    /**
     * 相册选图 → 压缩复制进内部存储（`filesDir/cat_icon_<时间戳>.jpg`）。
     * 不依赖 URI 授权、重启后仍在；文件名带时间戳，换图后缓存键自然变化。失败返回 null。
     */
    fun importImage(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, IMPORT_MAX_PX)
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null
        val file = File(context.filesDir, "$IMAGE_FILE_PREFIX${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
        bmp.recycle()
        file.name
    }.getOrNull()

    /** 换图标 / 删分类时清掉旧图，避免内部存储越攒越多 */
    fun deleteImage(context: Context, fileName: String?) {
        val name = fileName?.takeIf { it.startsWith(IMAGE_FILE_PREFIX) } ?: return
        runCatching { File(context.filesDir, name).delete() }
    }

    /** 内部存储里的图标图片（160px 解码 + 24 张 LRU 缓存，列表滚动不重复解） */
    fun loadImage(context: Context, fileName: String): Bitmap? {
        cache.get(fileName)?.let { return it }
        val file = File(context.filesDir, fileName)
        if (!file.exists()) return null
        val bmp = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, ICON_TARGET_PX)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull() ?: return null
        cache.put(fileName, bmp)
        return bmp
    }

    private val cache = object : LruCache<String, Bitmap>(24) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** 降采样倍数：解码结果的长边不超过 maxPx 的 2 倍（BitmapFactory 只支持 2 的幂） */
    private fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        while (max(width, height) / sample > maxPx * 2) sample *= 2
        return sample
    }
}
