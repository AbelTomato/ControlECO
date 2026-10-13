package com.controleco.app.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.keepalive.Brand
import com.controleco.app.ui.common.PaneCard
import com.controleco.app.ui.common.PermissionTutorial
import com.controleco.app.ui.common.RowDivider
import com.controleco.app.ui.common.SectionTitle
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SwitchRow
import com.controleco.app.ui.common.a11yPathFor
import com.controleco.app.ui.theme.accBook

/**
 * 设置 → 权限：两块权限（通知监听 / 无障碍）的状态、开关与开启教程都在这一页。
 * 行上只留标题与开关；授权状态用右侧短标签表达（未授权时附「去开启」）。
 */
@Composable
fun SettingsPermissionScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
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

    SettingsPage(title = "权限", onBack = onBack) {
        NotificationSection(vm, resumeTick, onOpenNotificationAccess)
        A11ySection(vm, resumeTick, onOpenAccessibilitySettings)
        BankSection(vm, resumeTick)
    }
}

/** 权限状态行：标题 + 状态短标签（已授权=绿，未开启=灰）+ 未授权时的直达按钮 */
@Composable
private fun PermissionStatusRow(
    title: String,
    granted: Boolean,
    grantedText: String,
    ungrantedText: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (granted) grantedText else ungrantedText,
            style = MaterialTheme.typography.labelMedium,
            color = if (granted) MaterialTheme.accBook.income else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!granted) {
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
private fun NotificationSection(vm: LedgerViewModel, resumeTick: Int, onOpenNotificationAccess: () -> Unit) {
    val granted = remember(resumeTick) { vm.isListenerGranted() }
    val captureEnabled by vm.captureEnabled.collectAsStateWithLifecycle()
    val refundLinkEnabled by vm.refundLinkEnabled.collectAsStateWithLifecycle()

    SectionTitle("通知监听")
    PaneCard {
        PermissionStatusRow(
            title = "通知监听",
            granted = granted,
            grantedText = "已授权",
            ungrantedText = "未开启",
            actionText = "去开启",
            onAction = onOpenNotificationAccess,
        )
        RowDivider()
        SwitchRow(
            title = "自动记录收款/动账通知",
            checked = captureEnabled,
            onCheckedChange = { vm.setCaptureEnabled(it) },
        )
        RowDivider()
        BankPushSetting(vm)
        RowDivider()
        CaptureNotifySetting(vm, resumeTick)
        RowDivider()
        SwitchRow(
            title = "退款到账关联提醒",
            checked = refundLinkEnabled,
            onCheckedChange = { vm.setRefundLinkEnabled(it) },
        )
        RowDivider()
        PermissionTutorial(
            "通知监听教程",
            listOf(
                "安卓 13+ 侧载应用需先解锁：应用信息 → 右上角「⋯」→ 允许受限制的设置" +
                    "（否则通知使用权列表里找不到或开不了 我ECO）。",
                "系统设置中搜索「通知使用权 / 通知访问」→ 找到 我ECO → 打开开关。",
                "回到本页状态应显示「已连接」；付款后可在捕获日志看到通知快照。",
                "若长时间无记录：重启手机 → 打开应用再看状态（部分机型需重新开关一次）。",
            ),
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

    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "动账通知提醒",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (!notifGranted) {
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) {
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }) { Text("权限") }
        } else {
            TextButton(onClick = { openCaptureChannelSettings(context) }) { Text("横幅") }
        }
        Switch(checked = enabled, onCheckedChange = { vm.setCaptureNotifyEnabled(it) })
    }
}

@Composable
private fun A11ySection(vm: LedgerViewModel, resumeTick: Int, onOpenAccessibilitySettings: () -> Unit) {
    val enabled = remember(resumeTick) { vm.isA11yEnabled() }
    val captureOn by vm.a11yCaptureEnabled.collectAsStateWithLifecycle()

    SectionTitle("无障碍（页面采集）")
    PaneCard {
        PermissionStatusRow(
            title = "无障碍页面采集",
            granted = enabled,
            grantedText = "已授权",
            ungrantedText = "未开启",
            actionText = "去开启",
            onAction = onOpenAccessibilitySettings,
        )
        RowDivider()
        SwitchRow(
            title = "自动记录支付页面",
            checked = captureOn,
            onCheckedChange = { vm.setA11yCaptureEnabled(it) },
        )
        RowDivider()
        PermissionTutorial(
            "无障碍开启教程",
            listOf(
                "安卓 13+ 侧载应用需先解锁：应用信息 → 右上角「⋯」→ 允许受限制的设置" +
                    "（否则无障碍列表里找不到或开不了 我ECO）。",
                a11yPathFor(Brand.current()),
                "打开「我ECO 支付页面采集」开关；系统弹 10 秒倒计时确认属正常（小米十秒盾）。",
                "回到本页状态应显示「已连接」；付款后在捕获日志的「页面」通道能看到页面快照。",
            ),
        )
    }
}

@Composable
private fun BankPushSetting(vm: LedgerViewModel) {
    val enabled by vm.bankPushEnabled.collectAsStateWithLifecycle()
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("银行 App 动账推送", style = MaterialTheme.typography.bodyLarge)
            Text(
                "银行 App 的动账通知（与动账短信同源，靠【银行名】签名识别）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = { vm.setBankPushEnabled(it) })
    }
}

/**
 * 银行动账短信：只收新到短信（RECEIVE_SMS），不读历史、非默认短信应用；
 * 非银行号码与无银行签名的短信在采集层丢弃（内容不入库）。开关默认关，开启时申请权限。
 */
@Composable
private fun BankSection(vm: LedgerViewModel, resumeTick: Int) {
    val context = LocalContext.current
    val enabled by vm.smsCaptureEnabled.collectAsStateWithLifecycle()
    val granted = remember(resumeTick) { vm.isSmsPermissionGranted() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        Toast.makeText(
            context,
            if (ok) "短信权限已授予，银行动账短信将进入待确认" else "未授予短信权限，银行动账短信无法记录",
            Toast.LENGTH_LONG,
        ).show()
    }

    SectionTitle("银行动账短信")
    PaneCard {
        PermissionStatusRow(
            title = "收短信权限",
            granted = granted,
            grantedText = "已授权",
            ungrantedText = "未授权",
            actionText = "去授权",
            onAction = { permissionLauncher.launch(android.Manifest.permission.RECEIVE_SMS) },
        )
        RowDivider()
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "记录银行动账短信",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    vm.setSmsCaptureEnabled(on)
                    if (on && !granted) permissionLauncher.launch(android.Manifest.permission.RECEIVE_SMS)
                },
            )
        }
        RowDivider()
        PermissionTutorial(
            "为什么需要短信权限",
            listOf(
                "银行卡直接刷卡/被扣款时可能没有任何 App 通知，动账短信是唯一的线索。",
                "只接收新到的短信，不读取历史短信、不需要成为默认短信应用。",
                "只有银行号码（95588 等）或带【银行名】签名的短信会被解析；" +
                    "其他短信在采集层直接丢弃，内容不会入库。",
                "关闭本开关或撤销权限后，短信通道立即失效。",
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
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, com.controleco.app.capture.CaptureCenter.CHANNEL_CAPTURE),
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
