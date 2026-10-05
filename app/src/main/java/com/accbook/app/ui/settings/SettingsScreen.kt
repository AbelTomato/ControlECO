package com.accbook.app.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel

@Composable
fun SettingsScreen(
    vm: LedgerViewModel,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
    onImport: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenCaptureLog: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
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
        SettingRow("防杀后台与自启动（保活设置）", onClick = onOpenKeepAlive)
        val pendingCount by vm.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
        SettingRow(
            if (pendingCount > 0) "待确认账单（$pendingCount）" else "待确认账单",
            onClick = onOpenPending,
        )
        SettingRow("通知捕获日志", onClick = onOpenCaptureLog)
        CaptureToggles(vm)
        Text(
            "无障碍抓取与银行短信识别将在后续版本提供。",
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
