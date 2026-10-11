package com.controleco.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.controleco.app.capture.CaptureCenter
import com.controleco.app.capture.CapturePermissions
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.AccBookNav
import com.controleco.app.ui.theme.AccBookTheme
import com.controleco.app.update.AppUpdate

class MainActivity : ComponentActivity() {

    /** 悬浮卡/通知点击跳转：route + 自增序号（同路由重复点击也能触发导航） */
    private var navEvent by mutableStateOf(0L to null as String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeRoute(intent)
        // 权限自检只在"冷启动"提醒一次（重建/旋转不重弹），且从捕获通知进入时不打扰
        val coldStart = savedInstanceState == null
        val fromCaptureRoute = intent?.getStringExtra(CaptureCenter.EXTRA_ROUTE) != null
        val promptPermissions = coldStart && CapturePermissions.claimLaunchPrompt()
        // 自动更新检查：同样只认冷启动、从捕获通知进入时不打扰（结果就绪后弹对话框）
        if (coldStart && !fromCaptureRoute) AppUpdate.maybeCheck(this)
        setContent {
            AccBookTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: LedgerViewModel = viewModel()
                    AccBookNav(vm, navEvent, promptPermissions)
                    UpdateAvailableDialog()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeRoute(intent)
    }

    override fun onResume() {
        super.onResume()
        CaptureCenter.setAppForeground(true)
    }

    override fun onPause() {
        super.onPause()
        CaptureCenter.setAppForeground(false)
    }

    private fun consumeRoute(intent: Intent?) {
        val route = intent?.getStringExtra(CaptureCenter.EXTRA_ROUTE) ?: return
        // 点击通知进 App 时关闭悬浮卡（冲突处理：两通道只留一个待处理入口）
        CaptureCenter.onRouteOpened(route)
        navEvent = (navEvent.first + 1) to route
    }
}

/**
 * 发现新版本的应用内对话框：下载交给系统浏览器（GitHub 直连 + 镜像两条路，用户自选），
 * 忽略/下载后收起（下次检查间隔 2 小时，不会反复打扰）。
 */
@Composable
private fun UpdateAvailableDialog() {
    val context = LocalContext.current
    val info = AppUpdate.updateAvailable.collectAsStateWithLifecycle().value ?: return

    val open: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    AlertDialog(
        onDismissRequest = AppUpdate::consumeUpdate,
        title = { Text("发现新版本 ${info.tagName}") },
        text = {
            Text(
                buildString {
                    append("当前 v${BuildConfig.VERSION_NAME}")
                    if (info.notes.isNotBlank()) {
                        append("\n\n")
                        append(info.notes.trimEnd())
                    }
                },
            )
        },
        confirmButton = {
            Row {
                if (info.apkUrlMirror.isNotBlank()) {
                    TextButton(onClick = { open(info.apkUrlMirror); AppUpdate.consumeUpdate() }) {
                        Text("镜像下载")
                    }
                }
                if (info.apkUrl.isNotBlank()) {
                    TextButton(onClick = { open(info.apkUrl); AppUpdate.consumeUpdate() }) {
                        Text("下载更新")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = AppUpdate::consumeUpdate) { Text("下次再说") }
        },
    )
}
