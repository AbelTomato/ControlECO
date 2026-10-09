package com.accbook.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.budget.Budget
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.BudgetEditDialog
import com.accbook.app.ui.common.InfoRow
import com.accbook.app.ui.common.PaneCard
import com.accbook.app.ui.common.RowDivider
import com.accbook.app.ui.common.SectionTitle
import com.accbook.app.ui.common.SettingsPage
import com.accbook.app.ui.theme.accBook
import com.accbook.app.ui.theme.toneColor
import com.accbook.app.util.Money
import java.time.LocalDate

/**
 * 设置 → 账户：账户管理、分类管理、每月预算与分类预算。
 * 顶部的预算总结卡与首页同口径（剩余/档位/建议日花），点卡片即可改预算。
 */
@Composable
fun SettingsAccountScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    val budget by vm.monthlyBudgetCents.collectAsStateWithLifecycle()
    val spent by vm.homeNetSpent.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val categoryBudgets by vm.categoryBudgets.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()

    // 与首页同口径：本月净支出未到位前整页不画（避免预算卡先显示 0 再跳数）
    val spentReady = spent ?: return
    val today = LocalDate.now()
    val snapshot = remember(budget, spentReady, today) { Budget.compute(budget, spentReady, today) }
    var showBudgetDialog by remember { mutableStateOf(false) }

    val expenseCategories = categories.count { it.type == TxType.EXPENSE }
    val budgetedCategories = categoryBudgets.count { it.value > 0L }

    SettingsPage(title = "账户", onBack = onBack) {
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

        SectionTitle("账户与分类")
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
                subtitle = "支出与收入分类，点分类可改月预算",
                trailing = "$expenseCategories 个支出分类",
                onClick = onOpenCategories,
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
