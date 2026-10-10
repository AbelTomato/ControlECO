package com.accbook.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.ui.common.InfoRow
import com.accbook.app.ui.common.PaneCard
import com.accbook.app.ui.common.RowDivider
import com.accbook.app.ui.common.SectionTitle
import com.accbook.app.ui.common.SettingsPage
import com.accbook.app.ui.common.SwitchRow
import com.accbook.app.util.AppLog

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

    val autoEntry by vm.autoEntryEnabled.collectAsStateWithLifecycle()

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
            InfoRow(title = "开发者功能", onClick = onOpenDeveloper)
            RowDivider()
            InfoRow(title = "关于 AccBook", onClick = onOpenAbout)
        }
    }
}
