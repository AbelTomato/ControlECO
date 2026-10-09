package com.accbook.app.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.keepalive.Brand
import com.accbook.app.ui.common.PaneCard
import com.accbook.app.ui.common.PermissionTutorial
import com.accbook.app.ui.common.RowDivider
import com.accbook.app.ui.common.SectionTitle
import com.accbook.app.ui.common.SettingsPage
import com.accbook.app.ui.common.SwitchRow
import com.accbook.app.ui.common.SwitchRowShell
import com.accbook.app.ui.common.a11yPathFor
import com.accbook.app.ui.common.overlayPathFor

/**
 * 设置 → 权限：三块权限（通知监听 / 无障碍 / 悬浮窗）的状态、开关与开启教程都在这一页。
 * 手动触发（测试）按钮不在这里，见「开发者功能 → 手动启动」。
 */
@Composable
fun SettingsPermissionScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
) {
    // 从系统设置页返回时重新探测授权状态
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPage(title = "权限与自动记账", onBack = onBack) {
        NotificationSection(vm, resumeTick, onOpenNotificationAccess)
        A11ySection(vm, resumeTick, onOpenAccessibilitySettings)
        OverlaySection(vm, resumeTick, onOpenOverlayPermission)

        Text(
            "银行短信识别将在后续版本提供。开关都正常仍收不到通知时，" +
                "见「开发者功能 → 日志与诊断」中的保活与自启动；" +
                "测试悬浮窗/通知链路可用「开发者功能 → 手动启动」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun NotificationSection(vm: LedgerViewModel, resumeTick: Int, onOpenNotificationAccess: () -> Unit) {
    val connected by vm.listenerConnected.collectAsStateWithLifecycle()
    val granted = remember(resumeTick) { vm.isListenerGranted() }
    val captureEnabled by vm.captureEnabled.collectAsStateWithLifecycle()
    val refundLinkEnabled by vm.refundLinkEnabled.collectAsStateWithLifecycle()

    SectionTitle("通知监听")
    PaneCard {
        SwitchRowShell(
            title = "通知监听",
            subtitle = when {
                granted && connected -> "已连接，收款/动账通知将自动进入待确认队列"
                granted -> "已授权（连接状态未知，若长时间无记录请重启手机）"
                else -> "未开启，需在系统设置中授予「通知使用权」"
            },
            trailing = {
                if (!granted) TextButton(onClick = onOpenNotificationAccess) { Text("去开启") }
            },
        )
        RowDivider()
        SwitchRow(
            title = "自动记录收款/动账通知",
            subtitle = "捕获微信/支付宝/云闪付的收款与动账通知，解析后进入待确认队列",
            checked = captureEnabled,
            onCheckedChange = { vm.setCaptureEnabled(it) },
        )
        RowDivider()
        CaptureNotifySetting(vm, resumeTick)
        RowDivider()
        SwitchRow(
            title = "退款到账关联提醒",
            subtitle = "微信「退款到账通知」不带金额无法自动入账：弹出提醒卡/通知，" +
                "点击进入 App 选择被退款的账单（生成退款收入并标记原支出已退款）",
            checked = refundLinkEnabled,
            onCheckedChange = { vm.setRefundLinkEnabled(it) },
        )
        RowDivider()
        PermissionTutorial(
            "通知监听教程",
            listOf(
                "安卓 13+ 侧载应用需先解锁：应用信息 → 右上角「⋯」→ 允许受限制的设置" +
                    "（否则通知使用权列表里找不到或开不了 AccBook）。",
                "系统设置中搜索「通知使用权 / 通知访问」→ 找到 AccBook → 打开开关。",
                "回到本页状态应显示「已连接」；付款后可在捕获日志看到通知快照。",
                "若长时间无记录：重启手机 → 打开应用再看状态（部分机型需重新开关一次）。",
            ),
        )
    }
    // 通知权限（POST_NOTIFICATIONS）在下面的提醒行内按需申请
    if (Build.VERSION.SDK_INT >= 33 && !vm.canPostNotifications()) {
        Text(
            "提示：动账通知提醒需要系统通知权限，点该行的「申请权限」即可。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CaptureNotifySetting(vm: LedgerViewModel, resumeTick: Int) {
    val enabled by vm.captureNotifyEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifGranted = remember(resumeTick) { vm.canPostNotifications() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) "通知权限已授予" else "未授予通知权限，将收不到入账提醒",
            Toast.LENGTH_LONG,
        ).show()
    }

    SwitchRowShell(
        title = "动账通知提醒",
        subtitle = "捕获到动账/退款时屏幕上方横幅提醒（支付宝支付页会拦截悬浮窗，通知是可靠入口），" +
            "点击进入待确认队列；与悬浮卡同笔二选一操作即可，操作后另一方自动消失",
        trailing = {
            if (!notifGranted) {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text("申请权限") }
            } else {
                TextButton(onClick = { openCaptureChannelSettings(context) }) { Text("横幅设置") }
            }
        },
        switch = {
            Switch(checked = enabled, onCheckedChange = { vm.setCaptureNotifyEnabled(it) })
        },
    )
}

@Composable
private fun A11ySection(vm: LedgerViewModel, resumeTick: Int, onOpenAccessibilitySettings: () -> Unit) {
    val connected by vm.a11yConnected.collectAsStateWithLifecycle()
    val enabled = remember(resumeTick) { vm.isA11yEnabled() }
    val captureOn by vm.a11yCaptureEnabled.collectAsStateWithLifecycle()

    SectionTitle("无障碍（页面采集）")
    PaneCard {
        SwitchRowShell(
            title = "无障碍页面采集",
            subtitle = when {
                enabled && connected -> "已连接，支付成功页/账单详情页自动补全收款方与支付方式"
                enabled -> "已开启（连接未知，若长时间无记录请重启手机）"
                else -> "未开启，需在系统无障碍设置中启用「AccBook 支付页面采集」"
            },
            trailing = {
                if (!enabled) TextButton(onClick = onOpenAccessibilitySettings) { Text("去开启") }
            },
        )
        RowDivider()
        SwitchRow(
            title = "自动记录支付页面",
            subtitle = "无障碍开启时，从支付页/账单详情提取金额进入待确认队列；与通知捕获同笔自动合并，只入账一次",
            checked = captureOn,
            onCheckedChange = { vm.setA11yCaptureEnabled(it) },
        )
        RowDivider()
        PermissionTutorial(
            "无障碍开启教程",
            listOf(
                "安卓 13+ 侧载应用需先解锁：应用信息 → 右上角「⋯」→ 允许受限制的设置" +
                    "（否则无障碍列表里找不到或开不了 AccBook）。",
                a11yPathFor(Brand.current()),
                "打开「AccBook 支付页面采集」开关；系统弹 10 秒倒计时确认属正常（小米十秒盾）。",
                "回到本页状态应显示「已连接」；付款后在捕获日志的「页面」通道能看到页面快照。",
            ),
        )
    }
}

@Composable
private fun OverlaySection(vm: LedgerViewModel, resumeTick: Int, onOpenOverlayPermission: () -> Unit) {
    val granted = remember(resumeTick) { vm.isOverlayGranted() }
    val floatingOn by vm.floatingConfirmEnabled.collectAsStateWithLifecycle()

    SectionTitle("悬浮窗")
    PaneCard {
        SwitchRowShell(
            title = "悬浮确认窗",
            subtitle = if (granted) {
                "已授权，支付完成后悬浮预填卡片，点一下即入账（不进待确认队列）"
            } else {
                "需要「显示在其他应用上层」权限；未授权时降级为进入待确认队列"
            },
            trailing = {
                if (!granted) TextButton(onClick = onOpenOverlayPermission) { Text("去授权") }
            },
        )
        RowDivider()
        SwitchRow(
            title = "支付完成弹出悬浮窗",
            subtitle = "需同时开启无障碍页面采集；华为/荣耀支付保护中心、小米支付保险箱可能拦截，见保活设置",
            checked = floatingOn,
            onCheckedChange = { vm.setFloatingConfirmEnabled(it) },
        )
        RowDivider()
        PermissionTutorial(
            "悬浮窗授权教程",
            listOf(
                "推荐：点上方「去授权」，直达「显示在其他应用上层 / 悬浮窗」开关，打开即可。",
                overlayPathFor(Brand.current()),
                "若开关为灰色不可开：安卓 13+ 需先做「允许受限制的设置」（见通知监听教程第 1 步）。",
                "授权后用「开发者功能 → 手动启动」里的测试弹窗验证；" +
                    "支付时若 Logcat 有 floating confirm shown 但看不到窗，" +
                    "是支付保护中心/支付保险箱拦截，见保活设置。",
            ),
        )
    }
}

/** 跳转"自动记账提醒"渠道的通知设置（横幅/悬浮通知、重要性都在这里开） */
private fun openCaptureChannelSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, com.accbook.app.capture.CaptureCenter.CHANNEL_CAPTURE),
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }.onFailure { Toast.makeText(context, "未找到通知设置页", Toast.LENGTH_SHORT).show() }
    }
}
