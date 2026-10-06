package com.accbook.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.accbook.app.ui.theme.accBook
import com.accbook.app.ui.theme.categoryColor
import com.accbook.app.util.Money
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 顶部月份切换条（流水/统计共用，切换同一状态）；trailing 为右上角附加操作入口 */
@Composable
fun MonthHeader(
    month: java.time.YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一月") }
        Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onNext) { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下一月") }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 收入/支出/结余汇总行；cancelledCents 非空时额外显示"已取消收支"列（退款到账合计） */
@Composable
fun SummaryRow(incomeCents: Long, expenseCents: Long, cancelledCents: Long? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SummaryColumn("支出", Money.formatYuan(expenseCents), Modifier.weight(1f))
        SummaryColumn("收入", Money.formatYuan(incomeCents), Modifier.weight(1f))
        SummaryColumn("结余", Money.formatYuan(incomeCents - expenseCents), Modifier.weight(1f))
        if (cancelledCents != null) {
            SummaryColumn("已取消", Money.formatYuan(cancelledCents), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * 分类徽标：分类名首字符 + 稳定取色的淡色圆底（替代 emoji/图标，全 App 同分类同色）。
 */
@Composable
fun CategoryBadge(categoryName: String, size: Dp = 36.dp) {
    val color = categoryColor(MaterialTheme.accBook.categoryPalette, categoryName)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            categoryName.firstOrNull()?.toString() ?: "·",
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

/** 通用下拉选择（非实验性 API 实现） */
@Composable
fun DropdownSelector(label: String, options: List<Pair<Long, String>>, selectedId: Long?, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText = options.firstOrNull { it.first == selectedId }?.second ?: "请选择"
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label：$selectedText")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}

/** 日期选择按钮（系统日期选择器，毫秒值为 UTC 零点） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerButton(date: LocalDate, onPick: (LocalDate) -> Unit) {
    var show by remember { mutableStateOf(false) }
    if (show) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    show = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("取消") } },
        ) {
            DatePicker(state = state)
        }
    }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Text("日期：${date.year}年${date.monthValue}月${date.dayOfMonth}日")
    }
}

fun dayOfWeekZh(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

private val tsFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

fun formatTimestamp(millis: Long): String = tsFormatter.format(Instant.ofEpochMilli(millis))

/** 每月预算设置对话框（首页/设置共用）；保存 0 = 清除预算 */
@Composable
fun BudgetEditDialog(currentCents: Long, onSave: (Long) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(if (currentCents > 0) Money.formatCents(currentCents) else "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("每月预算") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "设置后首页显示剩余可用与今天建议花销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("预算金额（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (currentCents > 0) {
                    TextButton(onClick = { onSave(0) }) { Text("清除预算") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = Money.parseCents(text)
                if (cents == null || cents <= 0) {
                    error = "请输入有效金额"
                    return@TextButton
                }
                onSave(cents)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
