package com.controleco.app.ui.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controleco.app.data.LedgerViewModel
import com.controleco.app.ui.theme.accBook
import com.controleco.app.data.db.RefundMark
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.DropdownSelector
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.util.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val bill by vm.parsedBill.collectAsStateWithLifecycle()
    val error by vm.importError.collectAsStateWithLifecycle()
    val done by vm.importDone.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val showRefunds by vm.showRefunds.collectAsStateWithLifecycle()

    var accountId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(accounts) {
        if (accountId == null && accounts.isNotEmpty()) accountId = accounts.first().id
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.parseBillFile(uri.toString())
    }

    DisposableEffect(Unit) {
        onDispose { vm.clearImportState() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入账单") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        val b = bill
        val d = done
        when {
            d != null -> {
                Column(
                    Modifier.padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("导入完成", style = MaterialTheme.typography.titleMedium)
                    Text("成功导入 ${d.inserted} 条，跳过重复 ${d.duplicated} 条。", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { vm.clearImportState() }, modifier = Modifier.fillMaxWidth()) {
                        Text("继续导入")
                    }
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
                }
            }

            b != null -> {
                Column(Modifier.padding(padding).fillMaxSize()) {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("识别为：${b.source.displayName} 账单", style = MaterialTheme.typography.titleMedium)
                        val incomeCount = b.rows.count { it.type == TxType.INCOME }
                        val expenseCount = b.rows.count { it.type == TxType.EXPENSE }
                        Text(
                            "共 ${b.rows.size} 条（支出 $expenseCount，收入 $incomeCount）；" +
                                "已跳过不计收支 ${b.skippedOtherType} 条、未完成 ${b.skippedStatus} 条。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (b.refundCount > 0) {
                            Text(
                                "其中退款相关 ${b.refundCount} 条（已入库；当前${if (showRefunds) "已开启显示" else "未开启显示，不计入流水与汇总"}，可在设置中切换）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        DropdownSelector(
                            label = "记入账户",
                            options = accounts.map { it.id to it.name },
                            selectedId = accountId,
                            onSelect = { accountId = it },
                        )
                        Button(
                            onClick = { vm.commitImport(b, accountId ?: return@Button) },
                            enabled = accountId != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("确认导入 ${b.rows.size} 条") }
                        HorizontalDivider()
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(b.rows) { _, r ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${formatTimestamp(r.timestamp)}  ${r.counterparty.ifBlank { "(无对方)" }}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${r.note.ifBlank { r.goods }.ifBlank { "-" }} → ${r.guessedCategoryName}" +
                                            if (r.refundMark != RefundMark.NONE) {
                                                " ｜" + if (r.refundMark == RefundMark.REFUND_INCOME) "退款收入" else "已退款"
                                            } else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    (if (r.type == TxType.EXPENSE) "-" else "+") + Money.formatYuan(r.amountCents),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (r.type == TxType.INCOME) MaterialTheme.accBook.income else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                Column(
                    Modifier.padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("选择从微信/支付宝导出的账单文件（微信 xlsx / 支付宝 CSV），导入前会先预览。", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text("选择账单文件")
                    }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    HorizontalDivider()
                    Text(
                        "导出方法：\n" +
                            "· 微信：我 → 服务 → 钱包 → 账单 → 右上角常见问题 → 下载账单 → 用于个人对账（xlsx 发送到邮箱）\n" +
                            "· 支付宝：我的 → 账单 → 右上角“…” → 开具交易流水证明 → 用于个人对账（CSV 发送到邮箱）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
