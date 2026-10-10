package com.controleco.app.keepalive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

/**
 * 保活相关系统页跳转：自启动页按品牌尝试多个已知组件，失败回退到本应用的应用信息页。
 * 部分品牌无公开检测接口（如 MIUI 自启动），只能引导 + 跳转。
 */
object KeepAliveIntents {

    private val FLAG = Intent.FLAG_ACTIVITY_NEW_TASK

    /** 各品牌自启动设置页候选（按常见度排列，逐个尝试） */
    private val autostartComponents: Map<Brand, List<Pair<String, String>>> = mapOf(
        Brand.XIAOMI to listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        Brand.HUAWEI to listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        ),
        Brand.HONOR to listOf(
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity",
        ),
        Brand.OPPO to listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        ),
        Brand.ONEPLUS to listOf(
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
        Brand.REALME to listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        ),
        Brand.VIVO to listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to "com.iqoo.secure.safeguard.PurviewTabActivity",
        ),
    )

    /** 尝试打开品牌自启动设置页；找不到已知页面返回 false（调用方回退应用信息页并提示） */
    fun launchAutostart(context: Context, brand: Brand): Boolean {
        for ((pkg, cls) in autostartComponents[brand].orEmpty()) {
            val ok = runCatching {
                context.startActivity(Intent().setClassName(pkg, cls).addFlags(FLAG))
            }.isSuccess
            if (ok) return true
        }
        return false
    }

    fun launchAppDetails(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(FLAG),
        )
    }.isSuccess

    /** 弹出系统"忽略电池优化"确认框（清单已声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS） */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(FLAG),
        )
    }.isSuccess

    fun launchBatteryOptimizationList(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(FLAG))
    }.isSuccess

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 统一入口：优先直达自启动页，失败回退应用信息并提示 */
    fun openAutostartWithFallback(context: Context, brand: Brand) {
        if (!launchAutostart(context, brand)) {
            launchAppDetails(context)
            Toast.makeText(context, "未找到自启动设置页，请在应用信息中查找「自启动」", Toast.LENGTH_LONG).show()
        }
    }
}
