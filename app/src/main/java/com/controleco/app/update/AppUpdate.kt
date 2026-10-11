package com.controleco.app.update

import android.content.Context
import com.controleco.app.BuildConfig
import com.controleco.app.util.AppLog
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 应用内自动更新（beta 分发走 GitHub Release）：
 * 启动时后台查一次最新 release——先直连 GitHub API，超时/失败改走镜像（gh-proxy.com 代理 api.github.com
 * 与 release 下载，实测可用；ghfast.top 不支持 API 前缀）。
 * 发现新版本只弹一次应用内对话框（不打断启动，点下载交给系统浏览器），查不到/无更新全程静默。
 *
 * 网络是本应用唯一的联网用途：只读 GitHub 的 releases 接口，不上传任何数据。
 */
object AppUpdate {

    private const val REPO = "ArrogHie/ControlECO"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    /** 镜像：前缀拼接代理（API 查询与 APK 下载都支持） */
    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    private const val PREFS_NAME = "settings"
    private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
    private const val KEY_LAST_CHECK_AT = "update_last_check_at"

    /** 两次检查的最小间隔：进程常驻（通知监听）时避免每次打开都打 API */
    private const val MIN_CHECK_INTERVAL_MS = 2 * 3_600_000L
    /** 直连超时给短一些，失败尽快切镜像；镜像同值 */
    private const val TIMEOUT_MS = 6_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _updateAvailable = MutableStateFlow<ReleaseInfo?>(null)

    /** 非空 = 检测到新版本（MainActivity 收集后弹对话框，处理完置回 null） */
    val updateAvailable: StateFlow<ReleaseInfo?> = _updateAvailable

    data class ReleaseInfo(
        /** tag 名（如 v0.4.6-beta），直接用于展示 */
        val tagName: String,
        /** GitHub 直连 APK 下载地址 */
        val apkUrl: String,
        /** 镜像 APK 下载地址（直连慢时备用） */
        val apkUrlMirror: String,
        /** release 说明（原文截断展示） */
        val notes: String,
    )

    fun isAutoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_UPDATE_ENABLED, true)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply()
        AppLog.i("Update", "autoUpdateEnabled=$enabled")
    }

    /**
     * 启动时检查（MainActivity 冷启动调用；从捕获通知进入 App 时不调，不打扰确认动线）。
     * 开关关闭 / 距上次检查不足间隔 / 正在查 → 跳过；结果进 [updateAvailable]。
     */
    fun maybeCheck(context: Context) {
        val app = context.applicationContext
        if (!isAutoUpdateEnabled(app)) return
        val now = System.currentTimeMillis()
        val prefs = prefs(app)
        if (now - prefs.getLong(KEY_LAST_CHECK_AT, 0L) < MIN_CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK_AT, now).apply()
        scope.launch {
            val release = runCatching { fetchLatestRelease() }
                .onFailure { AppLog.w("Update", "check failed", it) }
                .getOrNull()
            if (release == null) {
                AppLog.i("Update", "no release info（仓库尚无 release 或直连与镜像都失败）")
                return@launch
            }
            if (!isNewerVersion(release.tagName, BuildConfig.VERSION_NAME)) {
                AppLog.i("Update", "up to date: ${release.tagName} vs ${BuildConfig.VERSION_NAME}")
                return@launch
            }
            AppLog.i("Update", "new version: ${release.tagName} (current ${BuildConfig.VERSION_NAME})")
            _updateAvailable.value = release
        }
    }

    /** 对话框处理完（下载/忽略）后清掉，避免旋转屏幕重弹 */
    fun consumeUpdate() {
        _updateAvailable.value = null
    }

    /** 直连 → 失败（除"仓库还没有 release"外）→ 镜像 */
    internal fun fetchLatestRelease(): ReleaseInfo? {
        val direct = fetch(API_URL)
        if (direct?.first == 404) return null // 仓库还没有任何 release，镜像也必然 404
        val json = direct?.takeIf { it.first in 200..299 }?.second
            ?: fetch(MIRROR_PREFIX + API_URL)?.takeIf { it.first in 200..299 }?.second
        if (json == null) {
            AppLog.i("Update", "fetch failed: direct=${direct?.first ?: "error"}")
            return null
        }
        return parseReleaseJson(json)
    }

    /** 返回 (HTTP 状态码, 响应体)；网络异常返回 null */
    private fun fetch(url: String): Pair<Int, String>? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ControlECO-App")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        code to body
    } catch (e: Exception) {
        AppLog.w("Update", "fetch error url=$url: ${e.message}")
        null
    }

    /**
     * 从 releases/latest 的 JSON 里取 tag、首个 .apk 资产与说明。
     * 用正则而非 org.json：字段位置固定、结构简单，且纯函数可直接 JVM 单测。
     */
    internal fun parseReleaseJson(json: String): ReleaseInfo? {
        val tag = Regex("""\"tag_name\"\s*:\s*\"([^\"]+)\"""").find(json)?.groupValues?.get(1) ?: return null
        val apkUrl = Regex("""\"browser_download_url\"\s*:\s*\"(https:[^\"]+\.apk)\"""").find(json)?.groupValues?.get(1)
        val rawBody = Regex("""\"body\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""").find(json)?.groupValues?.get(1)
        return ReleaseInfo(
            tagName = tag,
            apkUrl = apkUrl.orEmpty(),
            apkUrlMirror = apkUrl?.let { MIRROR_PREFIX + it }.orEmpty(),
            notes = rawBody?.let { unescapeJson(it) }?.take(300).orEmpty(),
        )
    }

    /** JSON 字符串转义还原（\n \r \" \\ \/ 与 \uXXXX），够展示 release 说明即可 */
    private fun unescapeJson(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\' || i + 1 >= text.length) {
                sb.append(c); i++; continue
            }
            val next = text[i + 1]
            when (next) {
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\n'); i += 2 } // \r\n 展示用统一成 \n
                't' -> { sb.append('\t'); i += 2 }
                'u' -> {
                    val hex = if (i + 5 < text.length) text.substring(i + 2, i + 6) else null
                    val code = hex?.toIntOrNull(16)
                    if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(next); i += 2 }
                }
                else -> { sb.append(next); i += 2 } // \" \\ \/ 等
            }
        }
        return sb.toString()
    }

    /**
     * 语义化版本比较（0.4.6-beta vs 0.4.5-beta）：
     * 数字段逐位比；相等时稳定版 > 预发布（0.4.5 比 0.4.5-beta 新，用于去 beta 尾巴的收尾发布）。
     * 解析不了的一律视为"不比当前新"（宁可不提醒）。
     */
    internal fun isNewerVersion(latest: String, current: String): Boolean {
        val l = parseVersion(latest) ?: return false
        val c = parseVersion(current) ?: return false
        for (i in 0 until maxOf(l.nums.size, c.nums.size)) {
            val lv = l.nums.getOrElse(i) { 0 }
            val cv = c.nums.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return c.pre != null && l.pre == null
    }

    private data class Version(val nums: List<Int>, val pre: String?)

    /** "v0.4.6-beta" → ([0,4,6], "beta")；数字段缺失按 0 补 */
    private fun parseVersion(text: String): Version? {
        val t = text.trim().removePrefix("v").removePrefix("V")
        if (t.isEmpty()) return null
        val base = t.substringBefore('-')
        val pre = t.substringAfter('-', "").ifEmpty { null }
        val nums = base.split('.').map { seg ->
            seg.filter { it.isDigit() }.ifEmpty { "0" }.toInt()
        }
        if (nums.none { it > 0 } && nums.all { it == 0 }) return null // 全 0/非数字，不是版本号
        return Version(nums, pre)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
