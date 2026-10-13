package com.controleco.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.SettingsPage
import com.controleco.app.ui.common.formatTimestamp

/**
 * 设置 → 商户归类：学习到的「商户 → 分类/账户」规则（确认或编辑捕获交易时自动学习）。
 * 列表可逐条删除或清空——学了不想要的归类时，删掉即可（不会影响已入账的账单）。
 */
@Composable
fun SettingsMerchantRulesScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val rules by vm.merchantRules.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var clearAsk by remember { mutableStateOf(false) }

    val categoryNames = categories.associate { it.id to it.name }
    val accountNames = accounts.associate { it.id to it.name }

    SettingsPage(title = "商户归类", onBack = onBack) {
        Text(
            "确认或修改捕获账单时自动学习：同一商户下次捕获会预选学到的分类与账户。删掉某条即不再自动归类。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        TextButton(onClick = { clearAsk = true }, enabled = rules.isNotEmpty()) { Text("清空全部（${rules.size}）") }

        if (rules.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "还没有学到的归类\n确认几笔捕获账单后会出现",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rules, key = { it.id }) { rule ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                rule.matchKey,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                (if (rule.type == TxType.EXPENSE) "支出" else "收入") +
                                    " → " + (categoryNames[rule.categoryId] ?: "已删除分类") +
                                    (rule.accountId?.let { " · " + (accountNames[it] ?: "已删除账户") } ?: "") +
                                    " · 命中 ${rule.hits} 次",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatTimestamp(rule.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.deleteMerchantRule(rule.id) }) { Text("删除") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (clearAsk) {
        AlertDialog(
            onDismissRequest = { clearAsk = false },
            title = { Text("清空商户归类") },
            text = { Text("只清除学习到的规则，不影响已入账的账单。之后确认账单会重新开始学习。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearMerchantRules()
                    clearAsk = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { clearAsk = false }) { Text("取消") } },
        )
    }
}
