package com.accbook.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.MonthHeader
import com.accbook.app.ui.common.SummaryRow
import com.accbook.app.util.Money

@Composable
fun StatsScreen(vm: LedgerViewModel) {
    val month by vm.month.collectAsStateWithLifecycle()
    val totals by vm.monthTotals.collectAsStateWithLifecycle(initialValue = emptyList())
    val byCategory by vm.expenseByCategory.collectAsStateWithLifecycle(initialValue = emptyList())

    val expense = totals.firstOrNull { it.type == TxType.EXPENSE }?.totalCents ?: 0L
    val income = totals.firstOrNull { it.type == TxType.INCOME }?.totalCents ?: 0L

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        MonthHeader(month = month, onPrev = vm::prevMonth, onNext = vm::nextMonth)
        SummaryRow(incomeCents = income, expenseCents = expense)
        HorizontalDivider()

        Text(
            "支出分类占比",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(16.dp),
        )

        if (byCategory.isEmpty()) {
            Text(
                "本月暂无支出记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            byCategory.forEach { c ->
                val fraction = if (expense > 0) c.totalCents.toDouble() / expense else 0.0
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(c.categoryName, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Text(Money.formatYuan(c.totalCents), style = MaterialTheme.typography.bodyMedium)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
}
