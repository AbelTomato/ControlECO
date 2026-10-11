package com.controleco.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.common.InfoRow
import com.controleco.app.ui.common.PaneCard
import com.controleco.app.ui.common.RowDivider
import com.controleco.app.ui.common.SectionTitle
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SwitchRow
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.ui.theme.accBook

/**
 * 设置 → 开发者功能：手动启动（链路测试）+ 日志与诊断（待确认、捕获日志、保活、调试开关）。
 * 所有"测试/排查"性质的东西集中在这一页，权限页与个人页保持干净。
 */
@Composable
fun SettingsDeveloperScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenCaptureLog: () -> Unit,
    onOpenKeepAlive: () -> Unit,
) {
    val context = LocalContext.current
    val lastCaptureAt by vm.lastCaptureAt.collectAsStateWithLifecycle()
    val debugCaptureAll by vm.debugCaptureAll.collectAsStateWithLifecycle()
    val debugCaptureAllPages by vm.debugCaptureAllPages.collectAsStateWithLifecycle()

    fun toast() = { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }

    SettingsPage(title = "开发者功能", onBack = onBack) {
        SectionTitle("手动启动")
        PaneCard {
            ActionRow("悬浮确认窗（立即）") { vm.showFloatingTest(toast()) }
            RowDivider()
            ActionRow("悬浮确认窗（延迟 20 秒）") { vm.scheduleFloatingTest(toast()) }
            RowDivider()
            ActionRow("动账通知（延迟 20 秒）") { vm.scheduleNotifyTest(toast()) }
        }

        SectionTitle("日志与诊断")
        PaneCard {
            InfoRow(title = "待确认账单", onClick = onOpenPending)
            RowDivider()
            InfoRow(title = "捕获日志", onClick = onOpenCaptureLog)
            RowDivider()
            InfoRow(title = "保活与自启动", onClick = onOpenKeepAlive)
            RowDivider()
            InfoRow(
                title = "最近捕获",
                trailing = if (lastCaptureAt == 0L) "尚未收到" else formatTimestamp(lastCaptureAt),
            )
            RowDivider()
            // release 也保留（开发者功能整体保留）：开关与捕获路径都不依赖 BuildConfig.DEBUG
            SwitchRow(
                title = "记录所有应用的通知（调试）",
                checked = debugCaptureAll,
                onCheckedChange = { vm.setDebugCaptureAll(it) },
            )
            RowDivider()
            SwitchRow(
                title = "捕获所有页面（调试）",
                checked = debugCaptureAllPages,
                onCheckedChange = { vm.setDebugCaptureAllPages(it) },
            )
        }
    }
}

/** 带右侧动作词的行：点整行即触发（动作词用主色，表明不是导航） */
@Composable
private fun ActionRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            "启动",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.accBook.primaryStrong,
        )
    }
}
