package com.controleco.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import com.controleco.app.BuildConfig
import com.controleco.app.util.AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 应用内更新检查（beta 分发走 GitHub Release，release 以正式发布形式上传、仅版本号带 -beta）：
 * 自动更新开着时启动查一次——先直连 GitHub API，超时/失败改走镜像（gh-proxy.com 代理 api.github.com
 * 与 release 下载，实测可用；ghfast.top 不支持 API 前缀）。
 * 发现新版本只做**弹窗提醒**；用户点「立即更新」确认后，应用内下载 APK（直连失败自动改走镜像）
 * 并拉起系统安装器完成安装，全程不需要用户手动下载。自动更新开关只控制"要不要自动检查"。
 *
 * 网络是本应用唯一的联网用途：只访问 GitHub 的 releases 接口与 release APK 下载，不上传任何数据。
 */
object AppUpdate {

    private const val REPO = "AbelTomato/ControlECO"

    /**
     * 用 releases 列表接口而不是 releases/latest：latest 不返回预发布（prerelease），
     * 历史上 beta 曾以 prerelease 发布导致它 404；列表接口（未认证）对正式版与预发布都返回
     * 全部已发布 release（新→旧），草稿未认证不可见、不会误取。
     */
    private const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=5"
    /** 镜像：前缀拼接代理（API 查询与 APK 下载都支持） */
    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    private const val PREFS_NAME = "settings"
    private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
    private const val KEY_LAST_CHECK_AT = "update_last_check_at"

    /** 两次检查的最小间隔：进程常驻（通知监听）时避免每次打开都打 API */
    private const val MIN_CHECK_INTERVAL_MS = 2 * 3_600_000L
    /** 直连超时给短一些，失败尽快切镜像；镜像同值 */
    private const val TIMEOUT_MS = 6_000

    /** APK 合理性下限：小到离谱的"APK"一定是错误页/残片 */
    private const val MIN_APK_BYTES = 1_000_000L

    /** 路径测速：单次超时与取样字节数（读满 64KB = 建连 + 首段吞吐的粗测） */
    private const val PROBE_TIMEOUT_MS = 5_000
    private const val PROBE_BYTES = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /**
     * 手动检查（设置页「检查更新」）：无视开关与最小间隔，查一次并刷新节流时间戳。
     * 发现新版本 → 进 [updateAvailable] 弹窗（onResult 收到 null，页面不用再提示）；
     * 其余结果经 onResult 在主线程回调（无更新/失败，供页面 Toast 提示）。
     */
    fun checkNow(context: Context, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        prefs(app).edit().putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply()
        scope.launch {
            val release = runCatching { fetchLatestRelease() }
                .onFailure { AppLog.w("Update", "manual check failed", it) }
                .getOrNull()
            val message = when {
                release == null -> "检查失败：直连与镜像都未成功，请稍后再试"
                !isNewerVersion(release.tagName, BuildConfig.VERSION_NAME) ->
                    "已是最新版本（${BuildConfig.VERSION_NAME}）"
                else -> null
            }
            if (message == null && release != null) {
                AppLog.i("Update", "manual check: new version ${release.tagName}")
                _updateAvailable.value = release
            }
            mainHandler.post { onResult(message) }
        }
    }

    // ---------- 确认更新：应用内下载 + 拉起系统安装 ----------

    /**
     * 安装前置检查：未授权「安装未知应用」时跳转授权页并返回引导文案（用户授权后回来再点一次即可）；
     * 已授权返回 null，可以直接下载。
     */
    fun installGate(context: Context): String? {
        if (context.packageManager.canRequestPackageInstalls()) return null
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { AppLog.w("Update", "open install-permission settings failed", it) }
        return "请在接下来的页面允许「安装未知应用」，回来后再点「立即更新」"
    }

    /**
     * 应用内下载 APK：直连 → 失败自动改走镜像（与检查同一套路）。
     * 进度百分比经 onProgress 回调（主线程；-1 = 总大小未知，进度条转圈）。
     * 完成经 onDone 回调（主线程）：success(File) = APK 已就绪，failure = 下载失败（两条路都没成）。
     * 已有同版本完整 APK（上次下过但安装被打断）时直接复用，不重复下载。
     */
    fun downloadApk(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Int) -> Unit,
        onDone: (Result<File>) -> Unit,
    ) {
        val progressMain = { pct: Int ->
            mainHandler.post { onProgress(pct) }
            Unit
        }
        scope.launch {
            val result = runCatching { downloadApkBlocking(context, release, progressMain) }
            mainHandler.post { onDone(result) }
        }
    }

    /** 下载完成（或复用旧文件）后拉起系统安装器；异常情况返回提示文案，正常返回 null */
    fun startInstaller(context: Context, apkFile: File): String? {
        if (!context.packageManager.canRequestPackageInstalls()) return installGate(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching {
            context.startActivity(intent)
            AppLog.i("Update", "installer launched: ${apkFile.name}")
            null
        }.getOrElse {
            AppLog.w("Update", "launch installer failed", it)
            "无法启动安装，请稍后再试"
        }
    }

    private fun downloadApkBlocking(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Int) -> Unit,
    ): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "update").apply { mkdirs() }
        // 只保留当前这次要装的包：清掉旧版本与残片
        dir.listFiles()?.forEach { if (it.name != "ControlECO-${release.tagName}.apk") it.delete() }
        val target = File(dir, "ControlECO-${release.tagName}.apk")
        if (target.exists() && target.length() > MIN_APK_BYTES) {
            AppLog.i("Update", "reuse downloaded ${target.name}")
            return target
        }
        val urls = buildList {
            if (release.apkUrl.isNotBlank()) add(release.apkUrl)
            if (release.apkUrlMirror.isNotBlank()) add(release.apkUrlMirror)
        }
        if (urls.isEmpty()) error("该 release 没有可下载的 APK 资产")
        // 下载前并行测速直连与镜像，网络环境更好的路径先试；中途失败仍按序回退另一条
        val ordered =
            if (urls.size < 2) urls
            else orderedByProbe(urls, probeUrls(urls)).also {
                AppLog.i("Update", "download path order: ${it.map { u -> if (u == urls[0]) "direct" else "mirror" }}")
            }
        val tmp = File(dir, target.name + ".part")
        var lastError: Exception? = null
        for (url in ordered) {
            try {
                tmp.delete()
                downloadToFile(url, tmp, onProgress)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                AppLog.i("Update", "apk downloaded from ${if (url == urls[0]) "direct" else "mirror"}: ${target.length()} bytes")
                return target
            } catch (e: Exception) {
                AppLog.w("Update", "download failed url=$url: ${e.message}")
                lastError = e
                tmp.delete()
            }
        }
        throw IllegalStateException("下载失败：直连与镜像都未成功", lastError)
    }

    /**
     * 并行测速各候选路径：范围 GET 实际读前 64KB（与真实下载同路径、含重定向），
     * 耗时 = 建连 + 首段吞吐，能区分"能连但很慢"的路径；失败记 null。
     */
    private fun probeUrls(urls: List<String>): List<Long?> {
        val rtts = arrayOfNulls<Long>(urls.size)
        val threads = urls.mapIndexed { i, url ->
            Thread({ rtts[i] = runCatching { probeUrl(url) }.getOrNull() }, "update-probe-$i").apply { start() }
        }
        threads.forEach { it.join() } // 单线程内已有 socket 超时兜底，join 不会无限等
        return rtts.toList()
    }

    /** 纯函数：按测速结果排序（失败排最后；平局/都失败保持原顺序，直连优先） */
    internal fun orderedByProbe(urls: List<String>, rtts: List<Long?>): List<String> =
        urls.indices
            .sortedWith(compareBy({ rtts.getOrNull(it) == null }, { rtts.getOrNull(it) ?: Long.MAX_VALUE }))
            .map { urls[it] }

    private fun probeUrl(url: String): Long {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            setRequestProperty("Range", "bytes=0-${PROBE_BYTES - 1}")
            setRequestProperty("User-Agent", "ControlECO-App")
        }
        try {
            val start = System.nanoTime()
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                var read = 0
                while (read < PROBE_BYTES) {
                    val n = input.read(buf, 0, minOf(buf.size, PROBE_BYTES - read))
                    if (n < 0) break
                    read += n
                }
            }
            return (System.nanoTime() - start) / 1_000_000
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadToFile(url: String, target: File, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = 30_000
            setRequestProperty("User-Agent", "ControlECO-App")
        }
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val pct = (read * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        if (total > 0 && target.length() != total) throw IllegalStateException("下载不完整")
        if (target.length() < MIN_APK_BYTES) throw IllegalStateException("文件过小，疑似非 APK")
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
     * 从 releases 列表的 JSON 里取最新 release 的 tag、首个 .apk 资产与说明。
     * 用正则而非 org.json：字段位置固定、结构简单，且纯函数可直接 JVM 单测。
     * 列表是新→旧排序：tag/资产/说明只取**第一个** release 对象（截到第二个 tag_name 为止），
     * 防止最新 release 没挂 APK 时错配到旧版本的资产。
     */
    internal fun parseReleaseJson(json: String): ReleaseInfo? {
        val tagMatch = Regex("""\"tag_name\"\s*:\s*\"([^\"]+)\"""").find(json) ?: return null
        val nextTag = Regex("""\"tag_name\"""").find(json, tagMatch.range.last + 1)
        val scope = if (nextTag != null) json.substring(0, nextTag.range.first) else json
        val apkUrl = Regex("""\"browser_download_url\"\s*:\s*\"(https:[^\"]+\.apk)\"""").find(scope)?.groupValues?.get(1)
        val rawBody = Regex("""\"body\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""").find(scope)?.groupValues?.get(1)
        return ReleaseInfo(
            tagName = tagMatch.groupValues[1],
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
