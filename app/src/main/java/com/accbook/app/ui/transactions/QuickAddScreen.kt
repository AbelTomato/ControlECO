package com.accbook.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.DatePickerButton
import com.accbook.app.ui.common.DropdownSelector
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import java.time.LocalDate
import java.time.ZoneId

/**
 * 快速记账：金额优先——"¥ 0" + 自绘数字键盘 + 常用分类，
 * 目标动线：输入金额 → 选分类 → 记一笔；商户/账户/备注/日期收进"更多细节"。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    var amountText by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TxType.EXPENSE) }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var note by remember { mutableStateOf("") }
    var showMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 默认选中：第一个账户 + 当前类型的第一个分类
    LaunchedEffect(accounts, categories, type) {
        if (accountId == null) accountId = accounts.firstOrNull()?.id
        if (categories.firstOrNull { it.id == categoryId }?.type != type) {
            categoryId = categories.firstOrNull { it.type == type }?.id
        }
    }

    fun appendKey(key: String) {
        error = null
        if (key == ".") {
            if (amountText.isEmpty()) amountText = "0."
            else if (!amountText.contains('.')) amountText += "."
        } else {
            val next = if (amountText == "0") key else amountText + key
            val dot = next.indexOf('.')
            if (dot >= 0 && next.length - dot - 1 > 2) return
            if (next.replace(".", "").length > 9) return
            amountText = next
        }
    }

    fun backspace() {
        error = null
        if (amountText.isNotEmpty()) amountText = amountText.dropLast(1)
    }

    val cents = Money.parseCents(amountText)
    val valid = cents != null && cents > 0 && categoryId != null && accountId != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记一笔") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // 金额主体
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("¥", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.accBook.goldDeep)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(
                        amountText.ifEmpty { "0" },
                        style = MaterialTheme.typography.displayMedium,
                        color = if (amountText.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                    )
                }
            }
            Text(
                error ?: "输入金额 → 选分类 → 记一笔",
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("分类", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = type == TxType.EXPENSE,
                    onClick = { type = TxType.EXPENSE },
                    label = { Text("支出") },
                )
                Spacer(Modifier.padding(start = 8.dp))
                FilterChip(
                    selected = type == TxType.INCOME,
                    onClick = { type = TxType.INCOME },
                    label = { Text("收入") },
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.filter { it.type == type }.forEach { c ->
                    FilterChip(
                        selected = categoryId == c.id,
                        onClick = { categoryId = c.id },
                        label = { Text(c.name) },
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (showMore) {
                DropdownSelector(
                    label = "账户",
                    options = accounts.map { it.id to it.name },
                    selectedId = accountId,
                    onSelect = { accountId = it },
                )
                DatePickerButton(date = date, onPick = { date = it })
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(
                    onClick = { showMore = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("更多细节：账户 · 日期 · 备注") }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.accBook.hairline)
            NumberKeypad(
                onDigit = ::appendKey,
                onDot = { appendKey(".") },
                onBackspace = ::backspace,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val amount = Money.parseCents(amountText)
                    when {
                        amount == null || amount <= 0L -> error = "请输入有效金额"
                        categoryId == null -> error = "请选择分类"
                        accountId == null -> error = "请选择账户"
                        else -> {
                            val ts = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            vm.saveTransaction(-1L, type, amount, categoryId!!, accountId!!, ts, note.trim())
                            onBack()
                        }
                    }
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text("记一笔", style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 4×3 数字键盘：1-9 / 小数点 / 0 / 退格，大触控目标 */
@Composable
private fun NumberKeypad(
    onDigit: (String) -> Unit,
    onDot: () -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "⌫"),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = {
                                when (key) {
                                    "." -> onDot()
                                    "⌫" -> onBackspace()
                                    else -> onDigit(key)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text(
                                key,
                                fontSize = 24.sp,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }
        }
    }
}
