package com.accbook.app.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.BuildConfig
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.ui.common.InfoRow
import com.accbook.app.ui.common.PaneCard
import com.accbook.app.ui.common.RowDivider
import com.accbook.app.ui.common.SectionTitle
import com.accbook.app.ui.common.SettingsPage
import com.accbook.app.util.AppLog

/**
 * 设置（一级页）：只做导航分发，五个子页各自干净——
 * 账户（账户/分类/预算）、账单导入、权限（教程与开关）、开发者功能（手动启动/日志与诊断）、关于。
 */
@Composable
fun SettingsScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()

    // 权限状态可被系统设置改变：回到本页时重新探测
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val grantedCount = remember(resumeTick) {
        listOf(vm.isListenerGranted(), vm.isA11yEnabled(), vm.isOverlayGranted()).count { it }
    }

    LaunchedEffect(Unit) { AppLog.i("settings", "hub opened, granted=$grantedCount/3") }

    SettingsPage(title = "设置", onBack = onBack) {
        SectionTitle("账本")
        PaneCard {
            InfoRow(
                title = "账户",
                subtitle = "账户管理 · 分类管理 · 每月预算",
                trailing = if (accounts.isEmpty()) "未添加" else "${accounts.size} 个账户",
                onClick = onOpenAccount,
            )
            RowDivider()
            InfoRow(
                title = "账单导入",
                subtitle = "微信 xlsx / 支付宝 CSV，含退款显示开关",
                trailing = "导入",
                onClick = onOpenImport,
            )
        }

        SectionTitle("自动记账")
        PaneCard {
            InfoRow(
                title = "权限与自动记账",
                subtitle = "通知监听 / 无障碍 / 悬浮窗：状态、开关与开启教程",
                trailing = "$grantedCount/3 已授权",
                onClick = onOpenPermissions,
            )
        }

        SectionTitle("其他")
        PaneCard {
            InfoRow(
                title = "开发者功能",
                subtitle = "手动启动捕获链路 · 日志与诊断",
                trailing = "调试",
                onClick = onOpenDeveloper,
            )
            RowDivider()
            InfoRow(
                title = "关于 AccBook",
                subtitle = "隐私与安全 · 版本信息",
                trailing = "v${BuildConfig.VERSION_NAME}",
                onClick = onOpenAbout,
            )
        }

        Text(
            "全部数据只保存在本机（隐私红线），设置项改动即时生效。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}
