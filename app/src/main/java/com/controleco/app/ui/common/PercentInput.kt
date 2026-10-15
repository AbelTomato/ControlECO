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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 比例输入："00.00 %" 四个数字格（十位 / 个位 / 十分位 / 百分位）。
 *
 * 样式按自测约定：**无边框、只有下划线**；空格子衬半透明灰 "0" 提示可以输入；
 * 小数点固定在第二与第三格之间——输入只打数字，不用打小数点（也就打不出非法值）。
 * 点任意格子聚焦，数字追加、退格删除；当前待输入格的下划线加粗高亮。
 *
 * 纯状态组件：数字串（0~4 位）由调用方持有，编解码见
 * [com.controleco.app.data.budget.CategoryBudgetMath.bpToDigits]/`digitsToBp`。
 */
@Composable
fun PercentInput(
    digits: String,
    onDigitsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    // 待输入格：已有数字的下一格；四格填满时停在最后一格
    val caretIndex = digits.length.coerceAtMost(3)

    Box(modifier) {
        // 隐藏数字输入域：只接数字、上限四位（配合固定小数点 = 天然限定 00.00 格式）
        BasicTextField(
            value = digits,
            onValueChange = { raw -> onDigitsChange(raw.filter { it.isDigit() }.take(4)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            interactionSource = interaction,
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                focusRequester.requestFocus()
                keyboard?.show()
            },
        ) {
            DigitCell(char = digits.getOrNull(0), active = focused && caretIndex == 0)
            DigitCell(char = digits.getOrNull(1), active = focused && caretIndex == 1)
            Text(
                ".",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            DigitCell(char = digits.getOrNull(2), active = focused && caretIndex == 2)
            DigitCell(char = digits.getOrNull(3), active = focused && caretIndex == 3)
            Text(
                "%",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** 单个数字格：底衬半透明灰 "0"，已输入时显示数字；下方一条下划线（激活时加粗换主色） */
@Composable
private fun DigitCell(char: Char?, active: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(24.dp),
    ) {
        Box(Modifier.height(34.dp).width(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "0",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
            )
            char?.let {
                Text(
                    it.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
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
