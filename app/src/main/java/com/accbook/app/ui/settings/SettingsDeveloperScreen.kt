package com.accbook.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.BuildConfig
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.ui.common.InfoRow
import com.accbook.app.ui.common.PaneCard
import com.accbook.app.ui.common.RowDivider
import com.accbook.app.ui.common.SectionTitle
import com.accbook.app.ui.common.SettingsPage
import com.accbook.app.ui.common.SwitchRow
import com.accbook.app.ui.common.formatTimestamp

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
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
    val lastCaptureAt by vm.lastCaptureAt.collectAsStateWithLifecycle()
    val debugCaptureAll by vm.debugCaptureAll.collectAsStateWithLifecycle()

    fun toastFactory() = { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }

    SettingsPage(title = "开发者功能", onBack = onBack) {
        SectionTitle("手动启动")
        PaneCard {
            InfoRow(
                title = "悬浮确认窗（立即弹出）",
                subtitle = "预填一笔测试金额的悬浮卡片，用于确认悬浮窗权限与绘制是否正常",
                trailing = "启动",
                onClick = { vm.showFloatingTest(toastFactory()) },
            )
            RowDivider()
            InfoRow(
                title = "悬浮确认窗（延迟 20 秒）",
                subtitle = "20 秒后弹出，方便先切到微信/支付宝，模拟支付完成时的弹出时机",
                trailing = "启动",
                onClick = { vm.scheduleFloatingTest(toastFactory()) },
            )
            RowDivider()
            InfoRow(
                title = "动账通知（延迟 20 秒）",
                subtitle = "20 秒后发一条「自动记账提醒」横幅，用于验证通知渠道与点击跳转",
                trailing = "启动",
                onClick = { vm.scheduleNotifyTest(toastFactory()) },
            )
            Text(
                "测试只弹卡片/通知，不会写入账本；支付时若日志显示已弹出却看不到窗口，" +
                    "多半是支付保护中心拦截，见下方保活与自启动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        SectionTitle("日志与诊断")
        PaneCard {
            InfoRow(
                title = "待确认账单",
                subtitle = "捕获到但还没入账的记录",
                trailing = if (pendingCount > 0) "$pendingCount 条待处理" else "无",
                onClick = onOpenPending,
            )
            RowDivider()
            InfoRow(
                title = "捕获日志",
                subtitle = "通知 / 页面两条通道的原始快照，可复制原文",
                trailing = "查看",
                onClick = onOpenCaptureLog,
            )
            RowDivider()
            InfoRow(
                title = "保活与自启动",
                subtitle = "防杀后台、电池优化白名单与各品牌自启动路径",
                trailing = "去设置",
                onClick = onOpenKeepAlive,
            )
            RowDivider()
            InfoRow(
                title = "最近捕获",
                subtitle = "最后一次收到通知/页面事件的时间",
                trailing = if (lastCaptureAt == 0L) "尚未收到" else formatTimestamp(lastCaptureAt),
            )
            if (BuildConfig.DEBUG) {
                RowDivider()
                SwitchRow(
                    title = "记录所有应用的通知（调试）",
                    subtitle = "debug 排查用：所有应用的通知都进捕获日志（保留 7 天 / 最多 2000 条）",
                    checked = debugCaptureAll,
                    onCheckedChange = { vm.setDebugCaptureAll(it) },
                )
            }
        }
    }
}
