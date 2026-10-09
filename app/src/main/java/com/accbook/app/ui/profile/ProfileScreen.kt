package com.accbook.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.BuildConfig
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.budget.Budget
import com.accbook.app.ui.common.BudgetEditDialog
import com.accbook.app.ui.common.InfoRow
import com.accbook.app.ui.common.PaneCard
import com.accbook.app.ui.common.RowDivider
import com.accbook.app.ui.common.SectionTitle
import com.accbook.app.ui.theme.accBook
import com.accbook.app.ui.theme.toneColor
import com.accbook.app.util.Money
import java.time.LocalDate

/**
 * 个人页：身份卡 + 本月预算（预算总结 + 两个入口）+ 设置入口。
 * 除设置跳转外只保留预算这一件事，其余全部收进设置体系。
 */
@Composable
fun ProfileScreen(
    vm: LedgerViewModel,
    onOpenSettings: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    val budget by vm.monthlyBudgetCents.collectAsStateWithLifecycle()
    val spent by vm.homeNetSpent.collectAsStateWithLifecycle()

    // 与首页同口径：本月净支出未到位前整页不画（避免预算卡先显示 0 再跳数）
    val spentReady = spent ?: return
    val today = LocalDate.now()
    val snapshot = remember(budget, spentReady, today) { Budget.compute(budget, spentReady, today) }
    var showBudgetDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        // ---- 身份卡 ----
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "账",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("我的账本", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- 本月预算 ----
        SectionTitle("本月预算")
        PaneCard {
            BudgetSummary(
                budgetCents = budget,
                spentCents = spentReady,
                snapshot = snapshot,
                monthValue = today.monthValue,
                onSetBudget = { showBudgetDialog = true },
            )
            RowDivider()
            InfoRow(title = "每月总预算", onClick = { showBudgetDialog = true })
            RowDivider()
            InfoRow(title = "分类预算", onClick = onOpenCategories)
        }

        Spacer(Modifier.height(18.dp))

        // ---- 设置入口 ----
        PaneCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.accBook.primaryStrong.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.accBook.primaryStrong,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text("设置", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
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

/** 预算总结卡：左侧本月预算大字，右侧档位 + 已花 + 剩余 */
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
                TextButton(onClick = onSetBudget, contentPadding = PaddingValues(0.dp)) {
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
                    color = if (snapshot.tone == Budget.Tone.LOW) {
                        toneColor(snapshot.tone, ab)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
