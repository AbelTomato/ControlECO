package com.controleco.app.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.common.InfoRow
import com.controleco.app.ui.common.PaneCard
import com.controleco.app.ui.common.RowDivider
import com.controleco.app.ui.common.SectionTitle
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.SwitchRow
import com.controleco.app.util.AppLog

/**
 * 设置（一级页）：以导航分发为主，五个子页各自干净——
 * 账户（账户/分类）、账单导入、权限（教程与开关）、开发者功能（手动启动/日志与诊断）、关于。
 * 例外：「自动记账」段落顶部放一个总开关（自动入账），其余行仍只做导航、不放说明与取值。
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
    LaunchedEffect(Unit) { AppLog.i("settings", "hub opened") }

    val context = LocalContext.current
    val autoEntry by vm.autoEntryEnabled.collectAsStateWithLifecycle()
    val autoUpdate by vm.autoUpdateEnabled.collectAsStateWithLifecycle()

    SettingsPage(title = "设置", onBack = onBack) {
        SectionTitle("账本")
        PaneCard {
            InfoRow(title = "账户", onClick = onOpenAccount)
            RowDivider()
            InfoRow(title = "账单导入", onClick = onOpenImport)
        }

        SectionTitle("自动记账")
        PaneCard {
            SwitchRow(
                title = "自动入账",
                subtitle = "识别到付款直接记账，不再逐笔确认（分类/账户自动推测，可再改）",
                checked = autoEntry,
                onCheckedChange = { vm.setAutoEntryEnabled(it) },
            )
            RowDivider()
            InfoRow(title = "权限", onClick = onOpenPermissions)
        }

        SectionTitle("其他")
        PaneCard {
            SwitchRow(
                title = "自动更新",
                checked = autoUpdate,
                onCheckedChange = { vm.setAutoUpdateEnabled(it) },
            )
            RowDivider()
            InfoRow(
                title = "检查更新",
                onClick = {
                    Toast.makeText(context, "正在检查更新…", Toast.LENGTH_SHORT).show()
                    vm.checkUpdateNow { message ->
                        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    }
                },
            )
            RowDivider()
            InfoRow(title = "开发者功能", onClick = onOpenDeveloper)
            RowDivider()
            InfoRow(title = "关于 我ECO", onClick = onOpenAbout)
        }
    }
}
