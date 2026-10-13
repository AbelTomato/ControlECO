package com.controleco.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.controleco.app.capture.AppGroup
import com.controleco.app.capture.CaptureAppCatalog
import com.controleco.app.ui.common.PaneCard
import com.controleco.app.ui.common.RowDivider
import com.controleco.app.ui.common.SectionTitle
import com.controleco.app.ui.common.SettingsPage

/**
 * 设置 → 监听的 App：按应用开关采集范围（支付 / 银行 / 电商外卖三组）。
 *
 * 语义（与 CaptureAppCatalog 一致）：本页只覆盖"包名可枚举"的通知/页面通道；
 * 目录外的来源（银行短信、未知银行 App 的动账推送）由各自通道的签名闸门决定，不受本页影响。
 */
@Composable
fun SettingsWatchedAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 目录变更直接写 SharedPreferences（无需 ViewModel 状态）；resumeTick 式的本地刷新够用
    var tick by remember { mutableIntStateOf(0) }
    val watched = remember(tick) { CaptureAppCatalog.watched(context) }

    SettingsPage(title = "监听的 App", onBack = onBack) {
        Text(
            "只记录勾选应用的通知/页面。银行短信与目录外银行 App 的动账推送由签名识别决定，不受本页影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        AppGroup.entries.forEach { group ->
            val groupApps = CaptureAppCatalog.apps.filter { it.group == group }
            if (groupApps.isEmpty()) return@forEach
            val allOn = groupApps.all { it.packageName in watched }
            SectionTitle(group.title)
            PaneCard {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("整组", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        CaptureAppCatalog.setGroupWatched(context, group, !allOn)
                        tick++
                    }) { Text(if (allOn) "全部关闭" else "全部开启") }
                }
                groupApps.forEach { app ->
                    RowDivider()
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(
                            checked = app.packageName in watched,
                            onCheckedChange = { on ->
                                CaptureAppCatalog.setWatched(context, app.packageName, on)
                                tick++
                            },
                        )
                    }
                }
            }
        }
    }
}
