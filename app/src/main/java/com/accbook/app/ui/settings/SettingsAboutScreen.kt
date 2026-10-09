package com.accbook.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.accbook.app.BuildConfig
import com.accbook.app.ui.common.PaneCard
import com.accbook.app.ui.common.SectionTitle
import com.accbook.app.ui.common.SettingsPage

/**
 * 设置 → 关于 AccBook：版本、隐私承诺与数据存储说明（隐私红线在界面上可见）。
 */
@Composable
fun SettingsAboutScreen(onBack: () -> Unit) {
    SettingsPage(title = "关于 AccBook", onBack = onBack) {
        PaneCard(modifier = Modifier.padding(top = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "账",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("AccBook 账本", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "本地记账 · 账单导入 · 自动捕获",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionTitle("隐私与安全")
        PaneCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Bullet("只写本机数据库，不联网、不上传，无云端账号与第三方统计。")
                Spacer(Modifier.height(8.dp))
                Bullet("数据存于应用私有目录（Room / SQLite），卸载即清除，可被系统备份规则排除。")
                Spacer(Modifier.height(8.dp))
                Bullet("通知监听 / 无障碍 / 悬浮窗权限只用于识别支付信息，捕获内容仅在本机解析与留存。")
                Spacer(Modifier.height(8.dp))
                Bullet("账单导入文件只在本机解析，识别与去重后写入本地账本。")
            }
        }

        SectionTitle("版本")
        PaneCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    "版本号 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "构建类型：${if (BuildConfig.DEBUG) "debug（含调试开关）" else "release"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("· ", style = MaterialTheme.typography.bodyMedium)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
