package com.controleco.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.controleco.app.keepalive.Brand
import com.controleco.app.ui.theme.accBook

/**
 * 设置体系共用视觉件（个人页 / 设置页 / 各设置子页）。
 *
 * 设计：浅蓝页面底色上叠白色分组卡片（16dp 圆角 + 发丝描边），
 * 分组标题加粗小字；行内标题与右侧取值同一行，长说明折行在下方，保证窄屏不挤压。
 */

/** 分组标题：加粗小字，与卡片拉开间距 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** 白色分组卡片：圆角 + 发丝描边，行与行之间用 [RowDivider] 分隔 */
@Composable
fun PaneCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.accBook.hairline, RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
fun RowDivider(startPadding: Int = 16) {
    HorizontalDivider(
        color = MaterialTheme.accBook.hairline,
        modifier = Modifier.padding(start = startPadding.dp),
    )
}

/**
 * 通用设置行：标题行（标题 + 右侧取值 + 箭头）在上，说明文字整行折行在下。
 * onClick 为 null 时是不可点击的纯展示行（无箭头）；没有说明时行高自动收紧。
 * [trailingColor] 用于需要强调的取值（如待确认笔数的警示色），缺省用次要文字色。
 */
@Composable
fun InfoRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    trailingColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (subtitle.isNullOrBlank()) 16.dp else 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = trailingColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 开关行的统一外壳：标题 + 说明（可选）+ 右侧附加控件（按钮/取值）+ 开关 */
@Composable
fun SwitchRowShell(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    switch: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (subtitle.isNullOrBlank()) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing()
        switch?.invoke()
    }
}

/** 纯开关行 */
@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchRowShell(
        title = title,
        subtitle = subtitle,
        switch = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

/** 折叠式教程：默认收起，展开后按序号列步骤（权限引导页共用） */
@Composable
fun PermissionTutorial(title: String, steps: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起教程" else title, style = MaterialTheme.typography.labelLarge)
        }
        if (expanded) {
            steps.forEachIndexed { i, step ->
                Text(
                    "${i + 1}. $step",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 分品牌的系统无障碍设置路径 */
fun a11yPathFor(brand: Brand): String = when (brand) {
    Brand.XIAOMI -> "或：设置 → 更多设置 → 无障碍 → 已下载的应用 → 我ECO 支付页面采集"
    Brand.HUAWEI, Brand.HONOR -> "或：设置 → 辅助功能 → 无障碍 → 已下载的服务 → 我ECO 支付页面采集"
    Brand.OPPO, Brand.ONEPLUS, Brand.REALME ->
        "或：设置 → 其他设置 → 无障碍 → 已下载的应用（版本间路径有差异，可在设置中搜索「无障碍」）"
    else -> "或：系统设置中搜索「无障碍」→ 已下载的服务/应用 → 我ECO 支付页面采集"
}

/** 分品牌的悬浮窗权限路径（悬浮确认卡的前提） */
fun overlayPathFor(brand: Brand): String = when (brand) {
    Brand.XIAOMI -> "或：设置 → 应用设置 → 应用管理 → 我ECO → 权限管理 → 「显示悬浮窗」允许"
    Brand.HUAWEI, Brand.HONOR -> "或：设置 → 应用和服务 → 应用管理 → 我ECO → 权限 → 「悬浮窗」允许"
    Brand.OPPO, Brand.ONEPLUS, Brand.REALME -> "或：手机管家 → 权限与隐私 → 悬浮窗管理，允许 我ECO"
    else -> "或：系统设置 → 应用 → 我ECO → 权限 → 「显示在其他应用上层」允许"
}

/**
 * 设置子页骨架：返回箭头 + 标题 + 可滚动内容列（页面底色沿用全局浅蓝）。
 * 各设置页统一用它，保证页头、间距、底部留白完全一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            content = content,
        )
    }
}
