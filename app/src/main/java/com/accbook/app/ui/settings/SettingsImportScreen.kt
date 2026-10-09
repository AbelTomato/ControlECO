package com.accbook.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.ui.common.InfoRow
import com.accbook.app.ui.common.PaneCard
import com.accbook.app.ui.common.SectionTitle
import com.accbook.app.ui.common.SettingsPage
import com.accbook.app.ui.common.SwitchRow

/**
 * 设置 → 账单导入：导入入口 + 微信/支付宝导出账单的路径教程 + 退款记录显示开关。
 */
@Composable
fun SettingsImportScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onImport: () -> Unit,
) {
    SettingsPage(title = "账单导入", onBack = onBack) {
        SectionTitle("导入")
        PaneCard {
            InfoRow(
                title = "选择账单文件",
                subtitle = "解析后进入预览页，确认账户与分类再批量入账",
                trailing = "去导入",
                onClick = onImport,
            )
        }

        SectionTitle("导出账单（微信 / 支付宝）")
        PaneCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                TutorialStep(
                    "1. 微信",
                    "我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单，用途选「用于个人对账」，xlsx 会发送到邮箱。",
                )
                Spacer(Modifier.height(10.dp))
                TutorialStep(
                    "2. 支付宝",
                    "我的 → 账单 → 右上角「…」 → 开具交易流水证明，用途选「用于个人对账」，CSV 会发送到邮箱。",
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "把收到的文件传到手机，回到上面的「选择账单文件」导入即可；重复导入的账单会被自动去重。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionTitle("显示")
        PaneCard {
            RefundDisplaySetting(vm)
        }
    }
}

@Composable
private fun TutorialStep(title: String, detail: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun RefundDisplaySetting(vm: LedgerViewModel) {
    val showRefunds by vm.showRefunds.collectAsStateWithLifecycle()
    SwitchRow(
        title = "显示退款记录",
        subtitle = "开启后账单标记「退款收入 / 已退款」，并额外显示退款合计；关闭则不显示、不计入",
        checked = showRefunds,
        onCheckedChange = { vm.setShowRefunds(it) },
    )
}
