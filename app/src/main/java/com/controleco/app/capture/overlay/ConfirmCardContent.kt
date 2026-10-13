package com.controleco.app.capture.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.controleco.app.capture.CaptureDefaults
import com.controleco.app.capture.MerchantRules
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.PendingTransactionEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.ui.common.DropdownSelector
import com.controleco.app.ui.common.formatTimestamp
import com.controleco.app.ui.theme.accBook
import com.controleco.app.util.Money

/**
 * 悬浮确认卡的编辑状态。
 *
 * 自动回填只在用户没有手改过该字段时生效（用户改过的值不被后续富化覆盖），
 * 未改过的字段随后续捕获（页面合并/跨通道归并）刷新。
 */
internal class ConfirmCardState {

    var pendingId by mutableStateOf(-1L)
    var type by mutableStateOf(TxType.EXPENSE)
    var isRefund by mutableStateOf(false)
    var sourceLabel by mutableStateOf("")
    var ruleName by mutableStateOf("")
    var timestamp by mutableStateOf(0L)
    var amountText by mutableStateOf("")
    var counterparty by mutableStateOf("")
    var note by mutableStateOf("")
    var categoryId by mutableStateOf<Long?>(null)
    var accountId by mutableStateOf<Long?>(null)
    var categories by mutableStateOf<List<CategoryEntity>>(emptyList())
    var accounts by mutableStateOf<List<AccountEntity>>(emptyList())
    var learnedRules by mutableStateOf<List<com.controleco.app.data.db.MerchantRuleEntity>>(emptyList())

    private var amountUserEdited = false
    private var counterpartyUserEdited = false

    val amountCents: Long? get() = Money.parseCents(amountText)?.takeIf { it > 0 }
    val canSubmit: Boolean get() = amountCents != null && categoryId != null && accountId != null

    /** 首次绑定（新的待确认条目） */
    fun bind(
        pending: PendingTransactionEntity,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity>,
        learnedRules: List<com.controleco.app.data.db.MerchantRuleEntity> = emptyList(),
    ) {
        amountUserEdited = false
        counterpartyUserEdited = false
        pendingId = pending.id
        type = pending.type
        isRefund = pending.refundMark == com.controleco.app.data.db.RefundMark.REFUND_INCOME
        sourceLabel = com.controleco.app.capture.NotificationRules.sourceLabelOrRule(pending.sourcePackage, pending.ruleName)
        ruleName = pending.ruleName
        timestamp = pending.timestamp
        amountText = if (pending.amountCents > 0) Money.formatCents(pending.amountCents) else ""
        counterparty = pending.counterparty
        note = pending.note
        this.categories = categories
        this.accounts = accounts
        this.learnedRules = learnedRules
        categoryId = CaptureDefaults.categoryId(
            categories,
            pending.type,
            pending.counterparty.ifBlank { pending.note },
            learnedRules = learnedRules,
            counterparty = pending.counterparty,
            note = pending.note,
        )
        accountId = CaptureDefaults.accountId(
            accounts,
            pending.sourcePackage,
            CaptureDefaults.bankNameOf(pending.sourcePackage, pending.ruleName),
            learnedAccountId = MerchantRules.accountIdFrom(
                learnedRules, accounts, pending.type, pending.counterparty, pending.note,
            ),
        )
    }

    /** 富化刷新（页面合并/跨通道归并后）：用户改过的字段不动 */
    fun mergeFrom(
        pending: PendingTransactionEntity,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity>,
        learnedRules: List<com.controleco.app.data.db.MerchantRuleEntity> = emptyList(),
    ) {
        if (pendingId != pending.id) return
        if (!amountUserEdited && pending.amountCents > 0) {
            amountText = Money.formatCents(pending.amountCents)
        }
        if (!counterpartyUserEdited && pending.counterparty.isNotBlank()) {
            counterparty = pending.counterparty
        }
        note = pending.note
        ruleName = pending.ruleName
        timestamp = pending.timestamp
        this.categories = categories
        this.accounts = accounts
        this.learnedRules = learnedRules
        if (categoryId == null) {
            categoryId = CaptureDefaults.categoryId(
                categories, pending.type, pending.counterparty.ifBlank { pending.note },
                learnedRules = learnedRules, counterparty = pending.counterparty, note = pending.note,
            )
        }
        if (accountId == null) {
            accountId = CaptureDefaults.accountId(
                accounts,
                pending.sourcePackage,
                CaptureDefaults.bankNameOf(pending.sourcePackage, pending.ruleName),
            )
        }
    }

    fun updateAmount(text: String) {
        amountText = text
        amountUserEdited = true
    }

    fun updateCounterparty(text: String) {
        counterparty = text
        counterpartyUserEdited = true
    }
}

/**
 * 底部确认卡内容（全部用应用自己的设计语言：白卡 + hairline 边框 + 大圆角，
 * 分类/账户均为下拉选择（并排一行），金额用 tabular 数字排版；整体字号紧凑，避免遮挡支付页）。
 *
 * 交互：点 ✕ 或卡片上方空白 = 关闭（关闭视为入账，由调用方按卡内当前值落账）；
 * 「忽略」= 不入账（丢弃该笔）；「记一笔」= 以当前编辑值入账。
 */
@Composable
internal fun ConfirmCardContent(
    state: ConfirmCardState,
    onConfirm: (amountCents: Long, categoryId: Long, accountId: Long, counterparty: String) -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.accBook
    var showTimeoutHint by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            // 上面全屏透明区：点按 = 关闭卡片（视为入账；不入账请点卡内「忽略」）。indication=null 避免整屏水波纹
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairline),
                shadowElevation = 10.dp,
                tonalElevation = 0.dp,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    // 标题行：方向 + 来源 + 关闭（关闭视为入账）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val title = when {
                            state.isRefund -> "捕获到一笔退款"
                            state.type == TxType.EXPENSE -> "捕获到一笔支出"
                            else -> "捕获到一笔收入"
                        }
                        val titleColor = when {
                            state.isRefund -> colors.warning
                            state.type == TxType.INCOME -> colors.income
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = titleColor,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            state.sourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "关闭并入账",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // 金额（可编辑；金额为 0 的条目显示"金额待补"提示）
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (state.type == TxType.EXPENSE) "-" else "+",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = state.amountText,
                            onValueChange = { state.updateAmount(it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(if (state.amountCents == null) "金额（待补）" else "金额", style = MaterialTheme.typography.labelSmall) },
                            prefix = { Text(Money.symbol) },
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = state.amountCents == null && state.amountText.isNotBlank(),
                        )
                    }
                    if (state.amountCents == null && state.amountText.isBlank()) {
                        Text(
                            "金额缺失：直接在金额栏补录，或在待确认列表里补",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.warning,
                        )
                    }

                    // 商户/备注
                    OutlinedTextField(
                        value = state.counterparty,
                        onValueChange = { state.updateCounterparty(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("商户/备注", style = MaterialTheme.typography.labelSmall) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )

                    // 分类 + 账户：下拉并排（此前分类是横向滚动 chips，左右滑动体感差、占高度）
                    val catsOfType = state.categories.filter { it.type == state.type }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DropdownSelector(
                            label = "分类",
                            options = catsOfType.map { it.id to it.name },
                            selectedId = state.categoryId,
                            onSelect = { state.categoryId = it },
                            modifier = Modifier.weight(1f),
                        )
                        DropdownSelector(
                            label = "账户",
                            options = state.accounts.map { it.id to it.name },
                            selectedId = state.accountId,
                            onSelect = { state.accountId = it },
                            modifier = Modifier.weight(1.25f),
                        )
                    }

                    // 底部动作
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onIgnore) { Text("忽略", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatTimestamp(state.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = {
                                if (state.canSubmit) {
                                    onConfirm(
                                        state.amountCents!!,
                                        state.categoryId!!,
                                        state.accountId!!,
                                        state.counterparty,
                                    )
                                } else {
                                    showTimeoutHint = true
                                }
                            },
                        ) {
                            Text(if (showTimeoutHint && !state.canSubmit) "先填金额/分类/账户" else "记一笔")
                        }
                    }
                }
            }
        }
    }
}

/** 「已记入 支出 ¥20.06 · 撤销」细条（非阻塞，底部悬浮，几秒后自动消失） */
@Composable
internal fun SavedStripContent(text: String, onUndo: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.accBook.hairline),
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier
                .clickable(enabled = false) {}
                .background(Color.Transparent)
                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onUndo) { Text("撤销") }
        }
    }
}
