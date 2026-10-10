package com.accbook.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.accbook.app.capture.CaptureCenter
import com.accbook.app.capture.CapturePermissions
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.ui.AccBookNav
import com.accbook.app.ui.theme.AccBookTheme

class MainActivity : ComponentActivity() {

    /** 悬浮卡/通知点击跳转：route + 自增序号（同路由重复点击也能触发导航） */
    private var navEvent by mutableStateOf(0L to null as String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeRoute(intent)
        // 权限自检只在"冷启动"提醒一次（重建/旋转不重弹），且从捕获通知进入时不打扰
        val promptPermissions = savedInstanceState == null && CapturePermissions.claimLaunchPrompt()
        setContent {
            AccBookTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: LedgerViewModel = viewModel()
                    AccBookNav(vm, navEvent, promptPermissions)
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
