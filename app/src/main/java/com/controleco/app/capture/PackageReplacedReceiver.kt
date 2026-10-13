package com.controleco.app.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.controleco.app.BuildConfig
import com.controleco.app.util.AppLog

/**
 * 应用被覆盖安装后（ACTION_MY_PACKAGE_REPLACED）：检测 versionCode 变化并打上
 * "更新后需重新授权"提示——系统不保证更新后通知监听仍被绑定（小米/澎湃尤为常见），
 * 提示显示在权限页，监听一旦重连成功即自动清除。
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppLog.i("Ntf", "package replaced -> checkAppUpdated(${BuildConfig.VERSION_CODE})")
        runCatching { CaptureCenter.checkAppUpdated(BuildConfig.VERSION_CODE) }
            .onFailure { AppLog.w("Ntf", "checkAppUpdated failed", it) }
    }
}
