package com.accbook.app.ui.settings

import androidx.compose.runtime.Composable
import com.accbook.app.ui.common.InfoRow
import com.accbook.app.ui.common.PaneCard
import com.accbook.app.ui.common.RowDivider
import com.accbook.app.ui.common.SectionTitle
import com.accbook.app.ui.common.SettingsPage

/**
 * 设置 → 账户：只放账户与分类两个入口（预算相关内容在个人页）。
 */
@Composable
fun SettingsAccountScreen(
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    SettingsPage(title = "账户", onBack = onBack) {
        SectionTitle("账户与分类")
        PaneCard {
            InfoRow(title = "账户管理", onClick = onOpenAccounts)
            RowDivider()
            InfoRow(title = "分类管理", onClick = onOpenCategories)
        }
    }
}
