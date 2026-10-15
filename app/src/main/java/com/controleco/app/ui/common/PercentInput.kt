package com.controleco.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.controleco.app.data.budget.CategoryBudgetMath

/**
 * 比例输入："00.00 %" 四个数字格（十位 / 个位 / 十分位 / 百分位）。
 *
 * 交互（自测定稿）：
 * - 打开时**下划线定位在最左端**第一格；点哪一格光标落到哪一格（该格下划线加粗换主色）；
 * - 输入是**覆盖**：在光标格填入数字、光标自然后移（例：75.00 光标在第 0 格输入 4 → 45.00，光标到第 1 格）；
 * - **退格**：删光标左侧的数字并后退；光标已在最左端时删最右侧的数字
 *   （打开弹窗就是全格值时，第一次退格立刻见效）；
 * - 空格衬半透明灰 "0" 占位，输入数字后占位消失。
 *
 * 键盘接入：1dp 透明 [BasicTextField]，其文本固定为一个**非数字掩码**（`____`）、选中整格——
 * 用户输入的字符必然"替换掩码的一位"，由此稳定推断目标格（见 [CategoryBudgetMath.typeDigit]）；
 * 掩码不含数字，保证输入任何数字都会产生文本变化，不会漏事件；退格则表现为文本变短。
 */
@Composable
fun PercentInput(
    cells: List<Char?>,
    onCellsChange: (List<Char?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 光标（0..3，永远落在某一格；打开时在最左端）
    var caret by remember { mutableIntStateOf(0) }
    // 隐藏输入域的掩码文本
    val mask = remember { "_".repeat(4) }
    var field by remember { mutableStateOf(TextFieldValue(mask, TextRange(0, 1))) }

    // 文本永远回到掩码、选中光标所在整格 → 下一次输入天然是"覆盖"
    fun syncField() {
        field = TextFieldValue(mask, TextRange(caret, caret + 1))
    }

    // 点某一格：光标落到该格并弹出键盘
    fun tapCell(index: Int) {
        caret = index
        syncField()
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Box(modifier) {
        BasicTextField(
            value = field,
            onValueChange = { nv ->
                val text = nv.text
                if (text.length == mask.length) {
                    // 覆盖输入：唯一与掩码不同的那一位就是目标格
                    val idx = mask.indices.firstOrNull { text[it] != mask[it] }
                    val digit = idx?.let { text[it] }
                    if (idx != null && digit != null && digit.isDigit()) {
                        val (next, nextCaret) = CategoryBudgetMath.typeDigit(cells, idx, digit)
                        onCellsChange(next)
                        caret = nextCaret
                    }
                } else if (text.length < mask.length) {
                    // 退格（含"删除选中格"）：语义由我们决定，不看 IME 具体删了哪一位
                    val (next, nextCaret) = CategoryBudgetMath.backspace(cells, caret)
                    onCellsChange(next)
                    caret = nextCaret
                }
                syncField()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            interactionSource = interaction,
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            DigitCell(char = cells.getOrNull(0), active = caret == 0) { tapCell(0) }
            DigitCell(char = cells.getOrNull(1), active = caret == 1) { tapCell(1) }
            Text(
                ".",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            DigitCell(char = cells.getOrNull(2), active = caret == 2) { tapCell(2) }
            DigitCell(char = cells.getOrNull(3), active = caret == 3) { tapCell(3) }
            Text(
                "%",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 单个数字格：空格衬半透明灰 "0"（输入后消失）；下划线指示光标位置（当前格加粗换主色）。
 */
@Composable
private fun DigitCell(char: Char?, active: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(24.dp).clickable(onClick = onClick),
    ) {
        Box(Modifier.height(34.dp).width(24.dp), contentAlignment = Alignment.Center) {
            if (char != null) {
                Text(
                    char.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    "0",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                )
            }
        }
        Box(
            Modifier
                .width(24.dp)
                .height(if (active) 2.dp else 1.dp)
                .background(
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
        )
    }
}
