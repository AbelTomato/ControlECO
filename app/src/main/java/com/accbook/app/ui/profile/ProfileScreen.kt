package com.accbook.app.ui.profile

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.BuildConfig
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.budget.Budget
import com.accbook.app.data.db.TxType
import com.accbook.app.keepalive.Brand
import com.accbook.app.ui.common.BudgetEditDialog
import com.accbook.app.ui.theme.accBook
import com.accbook.app.ui.theme.toneColor
import com.accbook.app.util.Money
import java.time.LocalDate

/**
 * 个人页：身份卡 + 预算总结 + 全部设置（原「设置」标签页并入本页）。
 * 视觉：浅蓝页面底色上叠白色分组卡片，分组标题加粗小字（参考图样式）。
 */
@Composable
fun ProfileScreen(
    vm: LedgerViewModel,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
    onImport: () -> Unit,
    onOpenPending: () -> Unit,
    onOpenCaptureLog: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onOpenKeepAlive: () -> Unit,
) {
    val budget by vm.monthlyBudgetCents.collectAsStateWithLifecycle()
    val spent by vm.homeNetSpent.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val categoryBudgets by vm.categoryBudgets.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
    val today = LocalDate.now()
    val snapshot = remember(budget, spent, today) { Budget.compute(budget, spent, today) }
    var showBudgetDialog by remember { mutableStateOf(false) }

    val expenseCategories = categories.count { it.type == TxType.EXPENSE }
    val budgetedCategories = categoryBudgets.count { it.value > 0L }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        // ---- 身份卡 ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
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
                Text("我的账本", style = MaterialTheme.typography.titleMedium)
                Text(
                    "本地账本 · 数据不出设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 预算与提醒 ----
        SectionTitle("预算与提醒")
        PaneCard {
            BudgetSummary(
                budgetCents = budget,
                spentCents = spent,
                snapshot = snapshot,
                monthValue = today.monthValue,
                onSetBudget = { showBudgetDialog = true },
            )
            RowDivider()
            InfoRow(
                title = "每月总预算",
                subtitle = if (budget > 0) "首页剩余可用与今天建议花销的依据" else "未设置，首页模式①②会提示设置",
                trailing = if (budget > 0) Money.formatYuanGrouped(budget) else "未设置",
                onClick = { showBudgetDialog = true },
            )
            RowDivider()
            InfoRow(
                title = "分类预算",
                subtitle = "按分类设上限，首页模式②的圆环来自这里",
                trailing = if (budgetedCategories > 0) "$budgetedCategories 个分类" else "未设置",
                onClick = onOpenCategories,
            )
            RowDivider()
            InfoRow(
                title = "每日预算",
                subtitle = "剩余 ÷ 本月剩余天数（含今天）",
                trailing = snapshot?.let { "${Money.formatYuanGrouped(it.suggestedDailyCents)} / 天" } ?: "—",
            )
            LegendBox()
        }

        // ---- 记账与捕获 ----
        SectionTitle("自动记账")
        PaneCard {
            NotificationAccessSetting(vm, onOpenNotificationAccess)
            RowDivider()
            AccessibilityCaptureSetting(vm, onOpenAccessibilitySettings)
            RowDivider()
            FloatingConfirmSetting(vm, onOpenOverlayPermission)
            RowDivider()
            RefundLinkSetting(vm)
            RowDivider()
            CaptureNotifySetting(vm)
            RowDivider()
            CaptureToggles(vm)
            RowDivider()
            Text(
                "银行短信识别将在后续版本提供。无障碍/悬浮窗被拦截时见「运行与诊断」中的保活设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        // ---- 分类与数据 ----
        SectionTitle("分类与数据")
        PaneCard {
            InfoRow(
                title = "账户管理",
                subtitle = "银行卡 / 钱包 / 投资账户",
                trailing = if (accounts.isEmpty()) "未添加" else "${accounts.size} 个账户",
                onClick = onOpenAccounts,
            )
            RowDivider()
            InfoRow(
                title = "分类管理",
                subtitle = "支出与收入分类",
                trailing = "$expenseCategories 个支出分类",
                onClick = onOpenCategories,
            )
            RowDivider()
            InfoRow(
                title = "账单导入",
                subtitle = "微信 xlsx / 支付宝 CSV",
                trailing = "CSV / Excel",
                onClick = onImport,
            )
            Text(
                "导出路径：微信 我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单（用于个人对账，xlsx 发到邮箱）；" +
                    "支付宝 我的 → 账单 → 右上角“…” → 开具交易流水证明（用于个人对账，CSV 发到邮箱）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            RowDivider()
            RefundDisplaySetting(vm)
        }

        // ---- 运行与诊断 ----
        SectionTitle("运行与诊断")
        PaneCard {
            InfoRow(
                title = "保活与自启动",
                subtitle = "防杀后台与各品牌自启动路径",
                trailing = "去设置",
                onClick = onOpenKeepAlive,
            )
            RowDivider()
            InfoRow(
                title = "待确认账单",
                subtitle = "捕获到但还没入账的记录",
                trailing = if (pendingCount > 0) "$pendingCount 条待处理" else "无",
                onClick = onOpenPending,
            )
            RowDivider()
            InfoRow(
                title = "捕获日志",
                subtitle = "通知 / 页面两条通道的原始快照",
                trailing = "查看",
                onClick = onOpenCaptureLog,
            )
        }

        // ---- 通用 ----
        SectionTitle("通用")
        PaneCard {
            InfoRow(
                title = "隐私与安全",
                subtitle = "只写本机数据库，不联网、不上传，无云端账号",
                trailing = "本地存储",
            )
            RowDivider()
            InfoRow(
                title = "关于 AccBook",
                subtitle = "本地记账 · 账单导入 · 自动捕获",
                trailing = "v${BuildConfig.VERSION_NAME}",
            )
        }
    }

    if (showBudgetDialog) {
        BudgetEditDialog(
            currentCents = budget,
            onSave = { vm.setMonthlyBudget(it); showBudgetDialog = false },
            onDismiss = { showBudgetDialog = false },
        )
    }
}

// ---------------- 个人页视觉骨架 ----------------

/** 分组标题：加粗小字，与卡片拉开间距 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** 白色分组卡片：圆角 + 发丝描边，行与行之间用 [RowDivider] 分隔 */
@Composable
private fun PaneCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.accBook.hairline, RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.accBook.hairline,
        modifier = Modifier.padding(start = 16.dp),
    )
}

/**
 * 通用设置行：标题行（标题 + 右侧取值 + 箭头）在上，说明文字整行折行在下。
 * 说明偏长时也不会挤到右侧取值旁边，观感与参考图一致。
 */
@Composable
private fun InfoRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 预算总结卡：左侧总预算大字，右侧档位 + 已花 + 剩余 */
@Composable
private fun BudgetSummary(
    budgetCents: Long,
    spentCents: Long,
    snapshot: Budget.Snapshot?,
    monthValue: Int,
    onSetBudget: () -> Unit,
) {
    val ab = MaterialTheme.accBook
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${monthValue}月总预算",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (budgetCents > 0) Money.formatYuanGrouped(budgetCents) else "未设置",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (budgetCents <= 0L) {
                TextButton(onClick = onSetBudget, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("去设置本月预算")
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            val tone = snapshot?.tone
            Text(
                if (snapshot != null) "· ${Budget.toneLabel(snapshot.remainingRatio)}" else "· 未设预算",
                style = MaterialTheme.typography.labelLarge,
                color = if (tone != null) toneColor(tone, ab) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "已花 ${Money.formatYuanGrouped(snapshot?.spentCents ?: spentCents)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot != null) {
                Text(
                    "剩余 ${Money.formatYuanGrouped(snapshot.remainingCents)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (snapshot.tone == Budget.Tone.LOW) toneColor(snapshot.tone, ab) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 状态口径说明卡：与首页档位用色/阈值同源（Budget.toneLabel / remainingTone） */
@Composable
private fun LegendBox() {
    val ab = MaterialTheme.accBook
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "状态按剩余额度 / 预算计算，月度与分类一致",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            LegendDot(ab.critical, "偏紧 ≤20%")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(ab.income, "充裕 ≥50%")
            LegendDot(ab.warning, "留意 20%–50%")
        }
        Text(
            "剩余小于 0 时显示「已超支」，同属偏紧状态",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------- 各设置项 ----------------

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

@Composable
private fun NotificationAccessSetting(vm: LedgerViewModel, onOpenNotificationAccess: () -> Unit) {
    val connected by vm.listenerConnected.collectAsStateWithLifecycle()
    val granted = vm.isListenerGranted()
    SwitchRowShell(
        title = "通知监听",
        subtitle = when {
            granted && connected -> "已连接，收款/动账通知将自动进入待确认队列"
            granted -> "已授权（连接状态未知，若长时间无记录请重启手机）"
            else -> "未开启，需在系统设置中授予“通知使用权”"
        },
        trailing = {
            if (!granted) TextButton(onClick = onOpenNotificationAccess) { Text("去开启") }
        },
    )
}

@Composable
private fun AccessibilityCaptureSetting(vm: LedgerViewModel, onOpenAccessibilitySettings: () -> Unit) {
    val connected by vm.a11yConnected.collectAsStateWithLifecycle()
    // resumeTick 联动：从系统设置页返回时重新检测；状态流变化时（resumeTick 是 key）也会刷新
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val enabled = remember(resumeTick) { vm.isA11yEnabled() }
    val captureOn by vm.a11yCaptureEnabled.collectAsStateWithLifecycle()

    SwitchRowShell(
        title = "无障碍页面采集",
        subtitle = when {
            enabled && connected -> "已连接，支付成功页/账单详情页自动补全收款方与支付方式"
            enabled -> "已开启（连接未知，若长时间无记录请重启手机）"
            else -> "未开启，需在系统无障碍设置中启用「AccBook 支付页面采集」"
        },
        trailing = {
            if (!enabled) TextButton(onClick = onOpenAccessibilitySettings) { Text("去开启") }
        },
    )
    SwitchRow(
        title = "自动记录支付页面",
        subtitle = "无障碍开启时，从支付页/账单详情提取金额进入待确认队列；与通知捕获同笔自动合并，只入账一次",
        checked = captureOn,
        onCheckedChange = { vm.setA11yCaptureEnabled(it) },
    )
    PermissionTutorial(
        "无障碍开启教程",
        listOf(
            "安卓 13+ 侧载应用需先解锁：应用信息 → 右上角「⋯」→ 允许受限制的设置" +
                "（否则无障碍列表里找不到或开不了 AccBook）。",
            a11yPathFor(Brand.current()),
            "打开「AccBook 支付页面采集」开关；系统弹 10 秒倒计时确认属正常（小米十秒盾）。",
            "回到本页状态应显示「已连接」；付款后在捕获日志的「页面」通道能看到页面快照。",
        ),
    )
}

@Composable
private fun FloatingConfirmSetting(vm: LedgerViewModel, onOpenOverlayPermission: () -> Unit) {
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(resumeTick) { vm.isOverlayGranted() }
    val floatingOn by vm.floatingConfirmEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SwitchRowShell(
        title = "悬浮确认窗",
        subtitle = if (granted) "已授权，支付完成后悬浮预填卡片，点一下即入账（不进待确认队列）"
        else "需要「显示在其他应用上层」权限；未授权时降级为进入待确认队列",
        trailing = {
            if (BuildConfig.DEBUG) {
                TextButton(onClick = {
                    vm.showFloatingTest { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text("测试") }
                TextButton(onClick = {
                    vm.scheduleFloatingTest { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text("20秒") }
            }
            if (!granted) {
                TextButton(onClick = onOpenOverlayPermission) { Text("去授权") }
            }
        },
    )
    SwitchRow(
        title = "支付完成弹出悬浮窗",
        subtitle = "需同时开启无障碍页面采集；华为/荣耀支付保护中心、小米支付保险箱可能拦截，见保活设置",
        checked = floatingOn,
        onCheckedChange = { vm.setFloatingConfirmEnabled(it) },
    )
    PermissionTutorial(
        "悬浮窗授权教程",
        listOf(
            "推荐：点上方「去授权」，直达「显示在其他应用上层 / 悬浮窗」开关，打开即可。",
            overlayPathFor(Brand.current()),
            "若开关为灰色不可开：安卓 13+ 需先做「允许受限制的设置」（见无障碍教程第 1 步）。",
            "授权后用「测试弹窗」验证；支付时若 Logcat 有 floating confirm shown 但看不到窗，" +
                "是支付保护中心/支付保险箱拦截，见保活设置。",
        ),
    )
}

/** 分品牌的系统无障碍设置路径 */
private fun a11yPathFor(brand: Brand): String = when (brand) {
    Brand.XIAOMI -> "或：设置 → 更多设置 → 无障碍 → 已下载的应用 → AccBook 支付页面采集"
    Brand.HUAWEI, Brand.HONOR -> "或：设置 → 辅助功能 → 无障碍 → 已下载的服务 → AccBook 支付页面采集"
    Brand.OPPO, Brand.ONEPLUS, Brand.REALME ->
        "或：设置 → 其他设置 → 无障碍 → 已下载的应用（版本间路径有差异，可在设置中搜索「无障碍」）"
    else -> "或：系统设置中搜索「无障碍」→ 已下载的服务/应用 → AccBook 支付页面采集"
}

/** 分品牌的悬浮窗权限路径 */
private fun overlayPathFor(brand: Brand): String = when (brand) {
    Brand.XIAOMI -> "或：设置 → 应用设置 → 应用管理 → AccBook → 权限管理 → 「显示悬浮窗」允许"
    Brand.HUAWEI, Brand.HONOR -> "或：设置 → 应用和服务 → 应用管理 → AccBook → 权限 → 「悬浮窗」允许"
    Brand.OPPO, Brand.ONEPLUS, Brand.REALME -> "或：手机管家 → 权限与隐私 → 悬浮窗管理，允许 AccBook"
    else -> "或：系统设置 → 应用 → AccBook → 权限 → 「显示在其他应用上层」允许"
}

@Composable
private fun PermissionTutorial(title: String, steps: List<String>) {
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

@Composable
private fun RefundLinkSetting(vm: LedgerViewModel) {
    val enabled by vm.refundLinkEnabled.collectAsStateWithLifecycle()
    SwitchRow(
        title = "退款到账关联提醒",
        subtitle = "微信「退款到账通知」不带金额无法自动入账：弹出提醒卡/通知，" +
            "点击进入 App 选择被退款的账单（生成退款收入并标记原支出已退款）",
        checked = enabled,
        onCheckedChange = { vm.setRefundLinkEnabled(it) },
    )
}

@Composable
private fun CaptureNotifySetting(vm: LedgerViewModel) {
    val enabled by vm.captureNotifyEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notifGranted = remember(resumeTick) { vm.canPostNotifications() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Toast.makeText(context, if (granted) "通知权限已授予" else "未授予通知权限，将收不到入账提醒", Toast.LENGTH_LONG).show()
    }

    SwitchRowShell(
        title = "动账通知提醒",
        subtitle = "捕获到动账/退款时屏幕上方横幅提醒（支付宝支付页会拦截悬浮窗，通知是可靠入口），" +
            "点击进入待确认队列；与悬浮卡同笔二选一操作即可，操作后另一方自动消失",
        trailing = {
            if (BuildConfig.DEBUG) {
                TextButton(onClick = {
                    vm.scheduleNotifyTest { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text("20秒") }
            }
            if (!notifGranted) {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text("申请权限") }
            } else {
                TextButton(onClick = { openCaptureChannelSettings(context) }) { Text("横幅设置") }
            }
        },
        switch = {
            Switch(checked = enabled, onCheckedChange = { vm.setCaptureNotifyEnabled(it) })
        },
    )
}

/** 跳转"自动记账提醒"渠道的通知设置（横幅/悬浮通知、重要性都在这里开） */
private fun openCaptureChannelSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, com.accbook.app.capture.CaptureCenter.CHANNEL_CAPTURE),
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }.onFailure { Toast.makeText(context, "未找到通知设置页", Toast.LENGTH_SHORT).show() }
    }
}

@Composable
private fun CaptureToggles(vm: LedgerViewModel) {
    val captureEnabled by vm.captureEnabled.collectAsStateWithLifecycle()
    val debugCaptureAll by vm.debugCaptureAll.collectAsStateWithLifecycle()

    SwitchRow(
        title = "自动记录收款/动账通知",
        subtitle = "捕获微信/支付宝/云闪付的收款与动账通知，解析后进入待确认队列",
        checked = captureEnabled,
        onCheckedChange = { vm.setCaptureEnabled(it) },
    )
    SwitchRow(
        title = "记录所有应用的通知（调试）",
        subtitle = "debug 排查用：所有应用的通知都进捕获日志（保留 7 天 / 最多 2000 条）",
        checked = debugCaptureAll,
        onCheckedChange = { vm.setDebugCaptureAll(it) },
    )
}

/** 开关行的统一外壳：标题 + 说明 + 右侧附加控件（按钮/取值） */
@Composable
private fun SwitchRowShell(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit = {},
    switch: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        trailing()
        switch?.invoke()
    }
}

/** 纯开关行 */
@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchRowShell(
        title = title,
        subtitle = subtitle,
        switch = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}
