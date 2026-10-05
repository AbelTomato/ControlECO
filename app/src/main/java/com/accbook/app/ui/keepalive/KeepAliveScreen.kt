package com.accbook.app.ui.keepalive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.keepalive.Brand
import com.accbook.app.keepalive.DeviceBrands
import com.accbook.app.keepalive.KeepAliveAction
import com.accbook.app.keepalive.KeepAliveIntents
import com.accbook.app.ui.common.formatTimestamp
import android.os.Build

/**
 * 防杀后台与自启动引导：服务状态 + 电池优化白名单 + 分品牌设置步骤。
 * 自启动/省电策略大多无法程序检测，因此以"状态可查的尽量显示 + 步骤引导 + 直达跳转"的方式呈现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepAliveScreen(vm: LedgerViewModel, onBack: () -> Unit, onOpenNotificationAccess: () -> Unit) {
    val brand = remember { Brand.current() }
    val context = LocalContext.current
    val connected by vm.listenerConnected.collectAsStateWithLifecycle()
    val lastCaptureAt by vm.lastCaptureAt.collectAsStateWithLifecycle()

    // 从系统设置页返回时刷新可检测状态
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(resumeTick) { vm.isListenerGranted() }
    val batteryIgnoring = remember(resumeTick) { KeepAliveIntents.isIgnoringBatteryOptimizations(context) }
    val model = remember(resumeTick) { Build.MODEL ?: "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("防杀后台与自启动") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "服务状态（${brand.displayName} · $model）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            StatusRow(
                label = "通知监听",
                value = when {
                    granted && connected -> "已连接"
                    granted -> "已授权（连接未知）"
                    else -> "未授权"
                },
                action = if (!granted) "去开启" to onOpenNotificationAccess else null,
            )
            StatusRow(
                label = "最近捕获通知",
                value = if (lastCaptureAt == 0L) "尚未收到" else formatTimestamp(lastCaptureAt),
                action = null,
            )
            HorizontalDivider()

            Text(
                "电池优化白名单",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (batteryIgnoring) "已忽略电池优化" else "未忽略电池优化",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "申请系统忽略电池优化（Doze 白名单）；品牌的省电策略仍需按下方步骤手动设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (!batteryIgnoring) {
                    TextButton(onClick = { KeepAliveIntents.requestIgnoreBatteryOptimizations(context) }) {
                        Text("申请忽略")
                    }
                }
            }
            HorizontalDivider()

            Text(
                "${brand.displayName} 设置步骤",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DeviceBrands.stepsFor(brand).forEachIndexed { index, step ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("${index + 1}. ${step.title}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (step.actions.isNotEmpty()) {
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (KeepAliveAction.AUTOSTART in step.actions) {
                                TextButton(onClick = { KeepAliveIntents.openAutostartWithFallback(context, brand) }) {
                                    Text("打开自启动设置")
                                }
                            }
                            if (KeepAliveAction.APP_INFO in step.actions) {
                                TextButton(onClick = { KeepAliveIntents.launchAppDetails(context) }) {
                                    Text("应用信息")
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            Text(
                "说明：自启动、省电策略等开关大多无法被程序读取，请按步骤手动确认。" +
                    "若开关都正常仍长时间收不到通知：重启手机 → 打开应用 → 到「通知捕获日志」查看是否有事件；" +
                    "仍无事件则把通知使用权重新开关一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, action: Pair<String, () -> Unit>?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        if (action != null) {
            TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}
