package com.accbook.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.BuildConfig
import com.accbook.app.capture.CaptureCenter
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.keepalive.Brand

@Composable
fun SettingsScreen(
    vm: LedgerViewModel,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
    onImport: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenCaptureLog: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onOpenKeepAlive: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )

        SettingRow("账户管理", onClick = onOpenAccounts)
        SettingRow("分类管理", onClick = onOpenCategories)
        HorizontalDivider()

        RefundDisplaySetting(vm)
        HorizontalDivider()

        Text(
            "数据导入",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SettingRow("导入微信/支付宝账单", onClick = onImport)
        Text(
            "导出路径：微信 我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单（用于个人对账，xlsx 发到邮箱）；" +
                "支付宝 我的 → 账单 → 右上角“…” → 开具交易流水证明（用于个人对账，CSV 发到邮箱）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider()

        Text(
            "自动记账",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        NotificationAccessSetting(vm, onOpenNotificationAccess)
        AccessibilityCaptureSetting(vm, onOpenAccessibilitySettings)
        FloatingConfirmSetting(vm, onOpenOverlayPermission)
        RefundLinkSetting(vm)
        CaptureNotifySetting(vm)
        SettingRow("防杀后台与自启动（保活设置）", onClick = onOpenKeepAlive)
        val pendingCount by vm.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
        SettingRow(
            if (pendingCount > 0) "待确认账单（$pendingCount）" else "待确认账单",
            onClick = onOpenPending,
        )
        SettingRow("捕获日志（通知/页面）", onClick = onOpenCaptureLog)
        CaptureToggles(vm)
        Text(
            "银行短信识别将在后续版本提供。无障碍/悬浮窗被拦截时见保活设置中的支付保护说明。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider()

        Text(
            "AccBook v0.2.1 · 数据仅保存在本机",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun RefundDisplaySetting(vm: LedgerViewModel) {
    val showRefunds by vm.showRefunds.collectAsStateWithLifecycle()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("显示退款记录", style = MaterialTheme.typography.bodyLarge)
            Text(
                "开启后，账单中退回的金额标记「退款收入」、被全额退款的支出标记「已退款」，" +
                    "计入总收支并额外显示「已取消收支」合计；关闭则不显示、不计入。切换立即生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = showRefunds, onCheckedChange = { vm.setShowRefunds(it) })
    }
}

@Composable
private fun NotificationAccessSetting(vm: LedgerViewModel, onOpenNotificationAccess: () -> Unit) {
    val connected by vm.listenerConnected.collectAsStateWithLifecycle()
    val granted = vm.isListenerGranted()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("通知监听", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    granted && connected -> "已连接，收款/动账通知将自动进入待确认队列"
                    granted -> "已授权（连接状态未知，若长时间无记录请重启手机）"
                    else -> "未开启，需在系统设置中授予“通知使用权”"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (!granted) {
            TextButton(onClick = onOpenNotificationAccess) { Text("去开启") }
        }
    }
}

@Composable
private fun AccessibilityCaptureSetting(vm: LedgerViewModel, onOpenAccessibilitySettings: () -> Unit) {
    val connected by vm.a11yConnected.collectAsStateWithLifecycle()
    // resumeTick 联动：从系统设置页返回时重新检测；状态流变化时（resumeTick 是 key）也会刷新
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val enabled = remember(resumeTick) { vm.isA11yEnabled() }
    val captureOn by vm.a11yCaptureEnabled.collectAsStateWithLifecycle()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("无障碍页面采集", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    enabled && connected -> "已连接，支付成功页/账单详情页自动补全收款方与支付方式"
                    enabled -> "已开启（连接未知，若长时间无记录请重启手机）"
                    else -> "未开启，需在系统无障碍设置中启用「AccBook 支付页面采集」"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (!enabled) {
            TextButton(onClick = onOpenAccessibilitySettings) { Text("去开启") }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("自动记录支付页面", style = MaterialTheme.typography.bodyLarge)
            Text(
                "无障碍开启时，从支付页/账单详情提取金额进入待确认队列；" +
                    "页面信息与通知捕获同笔自动合并，只入账一次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = captureOn, onCheckedChange = { vm.setA11yCaptureEnabled(it) })
    }
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

@Composable
private fun FloatingConfirmSetting(vm: LedgerViewModel, onOpenOverlayPermission: () -> Unit) {
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(resumeTick) { vm.isOverlayGranted() }
    val floatingOn by vm.floatingConfirmEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("悬浮确认窗", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (granted) "已授权，支付完成后悬浮预填卡片，点一下即入账（不进待确认队列）"
                else "需要「显示在其他应用上层」权限；未授权时降级为进入待确认队列",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (BuildConfig.DEBUG) {
            TextButton(onClick = {
                vm.showFloatingTest { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }) { Text("测试弹窗") }
            TextButton(onClick = {
                vm.scheduleFloatingTest { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }) { Text("20秒后弹窗") }
        }
        if (!granted) {
            TextButton(onClick = onOpenOverlayPermission) { Text("去授权") }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("支付完成弹出悬浮窗", style = MaterialTheme.typography.bodyLarge)
            Text(
                "需同时开启无障碍页面采集；华为/荣耀支付保护中心、小米支付保险箱可能拦截，见保活设置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = floatingOn, onCheckedChange = { vm.setFloatingConfirmEnabled(it) })
    }
    PermissionTutorial(
        "悬浮窗授权教程",
        listOf(
            "推荐：点上方「去授权」，直达「显示在其他应用上层 / 悬浮窗」开关，打开即可。",
            overlayPathFor(Brand.current()),
            "若开关为灰色不可开：安卓 13+ 需先做「允许受限制的设置」（见无障碍教程第 1 步）。",
            "授权后用「测试弹窗」验证；支付时若 Logcat 有 floating confirm shown 但看不到窗，" +
                "是支付保护中心/支付保险箱拦截，见保活设置。",
        ),
    )
}

/** 分品牌的系统无障碍设置路径 */
private fun a11yPathFor(brand: Brand): String = when (brand) {
    Brand.XIAOMI -> "或：设置 → 更多设置 → 无障碍 → 已下载的应用 → AccBook 支付页面采集"
    Brand.HUAWEI, Brand.HONOR -> "或：设置 → 辅助功能 → 无障碍 → 已下载的服务 → AccBook 支付页面采集"
    Brand.OPPO, Brand.ONEPLUS, Brand.REALME ->
        "或：设置 → 其他设置 → 无障碍 → 已下载的应用（版本间路径有差异，可在设置中搜索「无障碍」）"
    else -> "或：系统设置中搜索「无障碍」→ 已下载的服务/应用 → AccBook 支付页面采集"
}

/** 分品牌的悬浮窗权限路径 */
private fun overlayPathFor(brand: Brand): String = when (brand) {
    Brand.XIAOMI -> "或：设置 → 应用设置 → 应用管理 → AccBook → 权限管理 → 「显示悬浮窗」允许"
    Brand.HUAWEI, Brand.HONOR -> "或：设置 → 应用和服务 → 应用管理 → AccBook → 权限 → 「悬浮窗」允许"
    Brand.OPPO, Brand.ONEPLUS, Brand.REALME -> "或：手机管家 → 权限与隐私 → 悬浮窗管理，允许 AccBook"
    else -> "或：系统设置 → 应用 → AccBook → 权限 → 「显示在其他应用上层」允许"
}

@Composable
private fun PermissionTutorial(title: String, steps: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起教程" else title)
        }
        if (expanded) {
            steps.forEachIndexed { i, step ->
                Text(
                    "${i + 1}. $step",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun RefundLinkSetting(vm: LedgerViewModel) {
    val enabled by vm.refundLinkEnabled.collectAsStateWithLifecycle()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("退款到账关联提醒", style = MaterialTheme.typography.bodyLarge)
            Text(
                "微信「退款到账通知」不带金额无法自动入账：弹出提醒卡/通知，" +
                    "点击进入 App 选择被退款的账单（生成退款收入并标记原支出已退款）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = enabled, onCheckedChange = { vm.setRefundLinkEnabled(it) })
    }
}

@Composable
private fun CaptureNotifySetting(vm: LedgerViewModel) {
    val enabled by vm.captureNotifyEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notifGranted = remember(resumeTick) { vm.canPostNotifications() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Toast.makeText(context, if (granted) "通知权限已授予" else "未授予通知权限，将收不到入账提醒", Toast.LENGTH_LONG).show()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("动账通知提醒", style = MaterialTheme.typography.bodyLarge)
            Text(
                "捕获到动账/退款时屏幕上方横幅提醒（支付宝支付页会拦截悬浮窗，通知是可靠入口），" +
                    "点击进入待确认队列；与悬浮卡同笔二选一操作即可，操作后另一方自动消失",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (BuildConfig.DEBUG) {
            TextButton(onClick = {
                vm.scheduleNotifyTest { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }) { Text("20秒后测通知") }
        }
        if (!notifGranted) {
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) {
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }) { Text("申请权限") }
        } else {
            TextButton(onClick = { openCaptureChannelSettings(context) }) { Text("横幅设置") }
        }
        Switch(checked = enabled, onCheckedChange = { vm.setCaptureNotifyEnabled(it) })
    }
}

/** 跳转"自动记账提醒"渠道的通知设置（横幅/悬浮通知、重要性都在这里开） */
private fun openCaptureChannelSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CaptureCenter.CHANNEL_CAPTURE),
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

@Composable
private fun CaptureToggles(vm: LedgerViewModel) {
    val captureEnabled by vm.captureEnabled.collectAsStateWithLifecycle()
    val debugCaptureAll by vm.debugCaptureAll.collectAsStateWithLifecycle()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("自动记录收款/动账通知", style = MaterialTheme.typography.bodyLarge)
            Text(
                "捕获微信/支付宝/云闪付的收款与动账通知，解析后进入待确认队列",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = captureEnabled, onCheckedChange = { vm.setCaptureEnabled(it) })
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("记录所有应用的通知（调试）", style = MaterialTheme.typography.bodyLarge)
            Text(
                "debug 排查用：所有应用的通知都进捕获日志（保留 7 天 / 最多 2000 条）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = debugCaptureAll, onCheckedChange = { vm.setDebugCaptureAll(it) })
    }
}

@Composable
private fun SettingRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}
