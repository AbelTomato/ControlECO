package com.accbook.app.ui.accounts

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.accbook.app.data.CategoryIcon
import com.accbook.app.data.LedgerViewModel
import com.accbook.app.data.db.CategoryEntity
import com.accbook.app.data.db.TxType
import com.accbook.app.ui.common.CategoryLogo
import com.accbook.app.ui.common.Glyph
import com.accbook.app.ui.common.GlyphIcon
import com.accbook.app.ui.common.glyphByName
import com.accbook.app.ui.common.glyphForName
import com.accbook.app.ui.common.glyphTint
import com.accbook.app.ui.theme.accBook
import com.accbook.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val budgets by vm.categoryBudgets.collectAsStateWithLifecycle()
    val monthlyBudget by vm.monthlyBudgetCents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var draftIcon by remember { mutableStateOf("") }
    // 本次编辑里刚导入、还没保存的图片：取消时删掉，避免留孤儿文件
    var freshImport by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val fileName = withContext(Dispatchers.IO) { CategoryIcon.importImage(context, uri) }
                if (fileName != null) {
                    CategoryIcon.deleteImage(context, freshImport)
                    freshImport = fileName
                    draftIcon = CategoryIcon.image(fileName)
                } else {
                    Toast.makeText(context, "这张图片读取失败，换一张试试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openEditor(category: CategoryEntity?) {
        editing = category
        draftIcon = category?.icon ?: ""
        freshImport = null
        showEditor = true
    }

    fun discardFreshImport() {
        CategoryIcon.deleteImage(context, freshImport)
        freshImport = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { openEditor(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "新增分类")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            listOf(TxType.EXPENSE to "支出分类", TxType.INCOME to "收入分类").forEach { (type, label) ->
                val list = categories.filter { it.type == type }
                if (list.isNotEmpty()) {
                    item(key = "header-$type") {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(list, key = { it.id }) { c ->
                        val budget = budgets[c.id] ?: 0L
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { openEditor(c) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CategoryLogo(categoryName = c.name, icon = c.icon, size = 30.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (c.type == TxType.EXPENSE && budget > 0L) {
                                Text(
                                    Money.formatYuanGrouped(budget),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showEditor) {
        CategoryEditorDialog(
            initial = editing,
            initialBudgetCents = editing?.let { budgets[it.id] } ?: 0L,
            // 可分配余量 = 月总预算 − 其它分类预算之和（本分类不计入，等于本次最多能设多少）
            totalBudgetCents = monthlyBudget,
            otherAllocatedCents = budgets.entries
                .filter { it.key != editing?.id }
                .sumOf { it.value },
            icon = draftIcon,
            onIconChange = { draftIcon = it },
            onPickImage = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onClearFreshImport = { discardFreshImport() },
            onSave = { id, name, type, budgetCents, icon ->
                vm.saveCategory(id, name, type, icon) { savedId, err ->
                    if (err == null) {
                        vm.setCategoryBudget(savedId, if (type == TxType.EXPENSE) budgetCents else 0L)
                        freshImport = null // 已入库，文件归分类所有
                        showEditor = false
                    }
                }
            },
            onDelete = editing?.let { entity ->
                {
                    vm.deleteCategory(entity.id) { err ->
                        if (err == null) {
                            discardFreshImport()
                            showEditor = false
                        }
                    }
                }
            },
            onClose = {
                discardFreshImport()
                showEditor = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorDialog(
    initial: CategoryEntity?,
    initialBudgetCents: Long,
    totalBudgetCents: Long,
    otherAllocatedCents: Long,
    icon: String,
    onIconChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onClearFreshImport: () -> Unit,
    onSave: (Long, String, TxType, Long, String) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: TxType.EXPENSE) }
    var budgetText by remember {
        mutableStateOf(if (initialBudgetCents > 0L) Money.formatCents(initialBudgetCents) else "")
    }
    var emojiText by remember { mutableStateOf(CategoryIcon.emojiOf(icon) ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    val emojiInvalid = emojiText.isNotBlank() && !CategoryIcon.isEmojiInput(emojiText)
    val palette = MaterialTheme.accBook.categoryPalette

    // 还可分配多少：总预算 − 其它分类预算（本分类不计）。没设总预算时不限制
    val hasTotalBudget = totalBudgetCents > 0L
    val allocatableCents = if (hasTotalBudget) (totalBudgetCents - otherAllocatedCents).coerceAtLeast(0L) else 0L
    // 上限取"可分配"与"本分类现值"的较大者：历史数据已经超标时，至少能原样保存/往下调
    val maxAllowedCents = if (hasTotalBudget) maxOf(allocatableCents, initialBudgetCents) else Long.MAX_VALUE

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "新增分类" else "编辑分类") },
        text = {
            Column(
                Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名称") },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = type == TxType.EXPENSE,
                        onClick = { type = TxType.EXPENSE },
                        label = { Text("支出") },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = type == TxType.INCOME,
                        onClick = { type = TxType.INCOME },
                        label = { Text("收入") },
                    )
                }

                // ---- 预算（放在图标之上：先定"多少钱"，再定"长什么样"）----
                if (type == TxType.EXPENSE) {
                    OutlinedTextField(
                        value = budgetText,
                        onValueChange = { budgetText = it },
                        label = { Text("分类月预算") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    if (hasTotalBudget) {
                        // 一句话就够：剩下的额度就是本次能填的上限；填超了直接变红，不额外加文字
                        val typed = Money.parseCents(budgetText.trim())
                        val overAllocatable = typed != null && typed > allocatableCents
                        Text(
                            "还可分配 ${Money.formatYuanGrouped(allocatableCents)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (overAllocatable) {
                                MaterialTheme.accBook.critical
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                // ---- 图标：当前效果 + 相册图片 / 恢复默认 ----
                Text("图标", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryLogo(categoryName = name, icon = icon, size = 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            iconLabel(icon),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(onClick = onPickImage) { Text("相册图片") }
                            if (icon.isNotEmpty()) {
                                TextButton(onClick = {
                                    onClearFreshImport()
                                    emojiText = ""
                                    onIconChange("")
                                }) { Text("恢复默认") }
                            }
                        }
                    }
                }

                // ---- 图形颜色：**常驻**（emoji / 相册图片也能一键切到"自定颜色的图形"）----
                val glyphName = CategoryIcon.glyphOf(icon)
                val isGlyphBased = CategoryIcon.emojiOf(icon) == null && CategoryIcon.imageOf(icon) == null
                val currentColor = if (isGlyphBased) CategoryIcon.glyphColorOf(icon) else null
                // 当前（或将要用的）形状：选中色块上叠它，让"选色"和"选形状"看得见关联
                val previewGlyph = glyphName?.let { glyphByName(it) } ?: glyphForName(name.ifBlank { "默认" })
                Text("图形颜色", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 默认：按分类名自动取色（等于清除自定义颜色）
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable {
                                // 只改过颜色的回到"全自动"；已固定形状的保留形状
                                onIconChange(if (glyphName == null) "" else CategoryIcon.glyph(glyphName))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "默认",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (currentColor == null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    palette.forEach { c ->
                        val rgb = c.toArgb() and 0xFFFFFF
                        val selected = currentColor == rgb
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                                .clickable {
                                    // 固定过形状就保留形状；否则只存颜色（形状按分类名取）——
                                    // 当前是 emoji / 图片时也走这条路：等于"换成这个颜色的图形"
                                    emojiText = ""
                                    onIconChange(
                                        if (glyphName == null) {
                                            CategoryIcon.glyphColorOnly(rgb)
                                        } else {
                                            CategoryIcon.glyph(glyphName, rgb)
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(if (selected) 20.dp else 18.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(c),
                            )
                            if (selected) {
                                GlyphIcon(glyph = previewGlyph, tint = MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp)
                            }
                        }
                    }
                }

                // ---- emoji：只留一个输入框，列一堆预设反而干扰选择 ----
                OutlinedTextField(
                    value = emojiText,
                    onValueChange = { input ->
                        emojiText = input
                        if (CategoryIcon.isEmojiInput(input)) onIconChange(CategoryIcon.emoji(input))
                    },
                    label = { Text("在此输入emoji作为图标") },
                    placeholder = { Text("在此输入emoji作为图标") },
                    singleLine = true,
                    isError = emojiInvalid,
                    supportingText = if (emojiInvalid) {
                        { Text("请输入 emoji（不接受文字）") }
                    } else {
                        null
                    },
                )

                // ---- 内置图形 ----
                Text("内置图形", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val currentGlyph = CategoryIcon.glyphOf(icon)
                    // 自定义了颜色就整排按这个颜色预览：一眼看清"换成这个形状会是什么样"
                    val customColor = CategoryIcon.glyphColorOf(icon)
                    Glyph.entries.forEach { g ->
                        val selected = currentGlyph == g.name
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                )
                                .clickable {
                                    emojiText = ""
                                    // 换图形时保留已选的颜色（没选颜色就按分类名自动取）
                                    onIconChange(CategoryIcon.glyph(g.name, customColor))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            GlyphIcon(
                                glyph = g,
                                tint = customColor?.let { Color(it or 0xFF000000.toInt()) }
                                    ?: glyphTint(g, name.ifBlank { "默认" }),
                                size = 32.dp,
                            )
                        }
                    }
                }

                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除该分类", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) {
                    error = "请输入名称"
                    return@TextButton
                }
                val raw = budgetText.trim()
                val budgetCents = if (raw.isEmpty()) {
                    0L
                } else {
                    val cents = Money.parseCents(raw)
                    if (cents == null || cents < 0) {
                        error = "分类月预算请输入有效金额（或留空）"
                        return@TextButton
                    }
                    cents
                }
                if (type == TxType.EXPENSE && budgetCents > maxAllowedCents) {
                    error = "最多可设 ${Money.formatYuanGrouped(maxAllowedCents)}"
                    return@TextButton
                }
                onSave(initial?.id ?: 0L, trimmed, type, budgetCents, icon)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/** 图标来源的说明文案 */
private fun iconLabel(icon: String): String = when {
    CategoryIcon.emojiOf(icon) != null -> "emoji 图标"
    CategoryIcon.glyphOf(icon) != null -> "内置图形"
    CategoryIcon.glyphColorOf(icon) != null -> "内置图形（自定颜色）"
    CategoryIcon.imageOf(icon) != null -> "相册图片"
    else -> "默认：按分类名自动取形"
}
