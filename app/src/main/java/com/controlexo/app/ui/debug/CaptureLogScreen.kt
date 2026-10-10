package com.controlexo.app.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.controlexo.app.data.LedgerViewModel
import com.controlexo.app.data.db.CaptureChannel
import com.controlexo.app.ui.common.formatTimestamp

/**
 * 捕获日志（调试）：通知/页面两个通道的原始快照，可按通道筛选，点击复制全部内容便于提炼规则。
 * debug 构建可在 Logcat 用 tag=ControlECO 过滤全量捕获明细（[Ntf]=通知、[Page]=页面）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureLogScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val rows by vm.captureRows.collectAsStateWithLifecycle(initialValue = emptyList())
    val connected by vm.listenerConnected.collectAsStateWithLifecycle()
    val filter by vm.captureFilter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var clearAsk by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("捕获日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (rows.isNotEmpty()) {
                        TextButton(onClick = { clearAsk = true }) { Text("清空") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val granted = vm.isListenerGranted()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        granted && connected -> "通知监听：已连接"
                        granted -> "通知监听：已授权（连接状态未知，若长时间无记录请重启手机）"
                        else -> "通知监听：未授权"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!granted) {
                    TextButton(onClick = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("去开启") }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { vm.setCaptureFilter(null) },
                    label = { Text("全部") },
                )
                FilterChip(
                    selected = filter == CaptureChannel.NOTIFICATION,
                    onClick = { vm.setCaptureFilter(CaptureChannel.NOTIFICATION) },
                    label = { Text("通知") },
                )
                FilterChip(
                    selected = filter == CaptureChannel.A11Y,
                    onClick = { vm.setCaptureFilter(CaptureChannel.A11Y) },
                    label = { Text("页面") },
                )
            }
            Text(
                "共 ${rows.size} 条（仅保留最近记录）；点条目可复制原文；" +
                    "debug 构建下 Logcat 过滤 ControlECO 可看全部明细（[Ntf] 通知 / [Page] 页面）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider()

            if (rows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无捕获记录\n收到通知或打开支付页面后会出现",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(rows, key = { it.id }) { n ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    copyToClipboard(
                                        context,
                                        "channel=${if (n.source == CaptureChannel.A11Y) "页面" else "通知"}\n" +
                                            "pkg=${n.packageName}\ntitle=${n.title}\ntext=${n.text}\nbigText=${n.bigText}",
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${if (n.source == CaptureChannel.A11Y) "页面" else "通知"} · ${n.packageName} · " +
                                        n.matchedRule.ifBlank { "未匹配" } + if (n.queued) " · 已入待确认" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (n.title.isNotBlank()) {
                                    Text(
                                        n.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                val body = n.text.ifBlank { n.bigText }
                                if (body.isNotBlank()) {
                                    Text(
                                        body,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(
                                formatTimestamp(n.receivedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (clearAsk) {
        AlertDialog(
            onDismissRequest = { clearAsk = false },
            title = { Text("清空捕获日志") },
            text = { Text("仅清除调试日志，不影响已确认的交易与待确认队列。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearCaptureLog()
                    clearAsk = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { clearAsk = false }) { Text("取消") } },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("我ECO 捕获通知", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}
