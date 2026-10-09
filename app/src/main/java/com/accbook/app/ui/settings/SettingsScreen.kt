package com.accbook.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.accbook.app.ui.common.InfoRow
import com.accbook.app.ui.common.PaneCard
import com.accbook.app.ui.common.RowDivider
import com.accbook.app.ui.common.SectionTitle
import com.accbook.app.ui.common.SettingsPage
import com.accbook.app.util.AppLog

/**
 * 设置（一级页）：只做导航分发，五个子页各自干净——
 * 账户（账户/分类）、账单导入、权限（教程与开关）、开发者功能（手动启动/日志与诊断）、关于。
 * 行上不放说明与取值，保持页面简单。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    LaunchedEffect(Unit) { AppLog.i("settings", "hub opened") }

    SettingsPage(title = "设置", onBack = onBack) {
        SectionTitle("账本")
        PaneCard {
            InfoRow(title = "账户", onClick = onOpenAccount)
            RowDivider()
            InfoRow(title = "账单导入", onClick = onOpenImport)
        }

        SectionTitle("自动记账")
        PaneCard {
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
