package com.controleco.app.capture

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.controleco.app.data.db.AccountEntity
import com.controleco.app.data.db.CategoryEntity
import com.controleco.app.data.db.TxType
import com.controleco.app.util.AppLog
import com.controleco.app.util.Money

/**
 * 灵动岛提醒：捕获到动账后在屏幕顶部弹出胶囊，随即展开为确认卡（商户/分类/金额 + 确认/修改）。
 *
 * 全新实现——旧悬浮确认窗已整体弃用，UI 不做任何沿用。三态：胶囊 → 卡片 → 编辑
 * （修改后原地展开分类/账户选择）。分类/账户用横向 chips 点选：无焦点窗口里
 * Spinner 弹层和键盘都不可用，chips 是内联视图，点选与横滑只依赖普通触摸事件。
 *
 * 窗口参数：TYPE_APPLICATION_OVERLAY + NOT_FOCUSABLE（不抢当前应用焦点）+
 * NOT_TOUCH_MODAL（岛外触摸穿透）。窗口宽度 = 屏宽 - 32dp，胶囊态窗口随内容收缩居中。
 * 经典 View 而非 Compose：overlay 窗口没有生命周期宿主，ComposeView 需手工接
 * LifecycleOwner，不值得。
 *
 * 生命周期：show() 起胶囊 1.2s 后自动展开；30s 无操作自动消失并回调 onTimeout
 * （调用方补发通知兜底）。任何交互（展开/编辑/选 chip）都会重置超时。
 * 已知拦截：华为/荣耀"支付保护中心"、小米"支付保险箱"会在支付页上层拦截窗口，
 * addView 失败时返回 false 由调用方回落通知。
 */
object FloatingIslandWindow {

    private const val PILL_EXPAND_DELAY_MS = 1_200L
    private const val AUTO_DISMISS_MS = 30_000L

    // 灵动岛暗色系（capture 原生窗口为硬编码色既有例外，见开发日志验收约定）
    private const val BG = 0xF21C1C1E.toInt()
    private const val ACCENT = 0xFF0A84FF.toInt()
    private const val TEXT_SECONDARY = 0xE6FFFFFF.toInt()
    private const val TEXT_FAINT = 0x99FFFFFF.toInt()
    private const val CHIP_BG = 0x22FFFFFF
    private const val CHIP_TEXT = 0xCCFFFFFF.toInt()
    private const val GHOST_BG = 0x1FFFFFFF
    private const val DOT_EXPENSE = 0xFFFFA694.toInt()
    private const val DOT_INCOME = 0xFF8FC79E.toInt()

    private var current: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var expandTask: Runnable? = null
    private var autoDismiss: Runnable? = null

    fun isShowing(): Boolean = current != null

    /**
     * 弹出灵动岛。返回 false = 窗口没加上（无悬浮窗权限/被支付保护拦截），调用方应回落通知。
     */
    fun show(
        context: Context,
        parsed: PageRules.ParsedPage,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity>,
        suggestedCategoryId: Long? = null,
        onConfirm: (categoryId: Long, accountId: Long) -> Unit,
        onIgnore: () -> Unit,
        onTimeout: () -> Unit,
    ): Boolean {
        dismiss()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val density = context.resources.displayMetrics.density
        val dp: (Int) -> Int = { v -> (v * density + 0.5f).toInt() }
        // 系统"减少动画"开启时所有动效直切（与 App 内 rememberReducedMotion 同口径）
        val reducedMotion = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f

        var catIndex = suggestedCategoryId
            ?.let { id -> categories.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 } ?: 0
        var accIndex = 0

        val isExpense = parsed.type == TxType.EXPENSE
        val kind = if (isExpense) "支出" else "收入"
        val dotColor = if (isExpense) DOT_EXPENSE else DOT_INCOME

        fun scheduleAutoDismiss() {
            autoDismiss?.let { mainHandler.removeCallbacks(it) }
            autoDismiss = Runnable { dismiss(); onTimeout() }.also {
                mainHandler.postDelayed(it, AUTO_DISMISS_MS)
            }
        }

        val root = FrameLayout(context)

        // —— 胶囊态：方向色点 + 方向 金额 ——
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(BG); cornerRadius = dp(22).toFloat() }
            setPadding(dp(16), dp(11), dp(16), dp(11))
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(dotColor)
                }
            })
            addView(TextView(context).apply {
                text = "$kind ${Money.formatYuan(parsed.amountCents)}"
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        root.addView(pill, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // —— 卡片态 ——
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(BG); cornerRadius = dp(24).toFloat() }
            setPadding(dp(18), dp(14), dp(18), dp(16))
            visibility = View.GONE
            if (!reducedMotion) layoutTransition = LayoutTransition()
        }
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        fun line(text: String, size: Float, color: Int, bold: Boolean = false, topPad: Int = 0): TextView =
            TextView(context).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setPadding(0, dp(topPad), 0, 0)
            }

        // 标题行 + ✕（忽略；✕ 在标题右侧不占主按钮位，保持「确认/修改」两个主按钮）
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "${parsed.sourceLabel} · 请确认入账"
                textSize = 12f
                setTextColor(TEXT_FAINT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "✕"
                textSize = 14f
                setTextColor(0x66FFFFFF.toInt())
                setPadding(dp(10), dp(4), dp(2), dp(4))
                setOnClickListener { dismiss(); onIgnore() }
            })
        })
        card.addView(line("$kind ${Money.formatYuan(parsed.amountCents)}", 26f, Color.WHITE, bold = true, topPad = 6))
        if (parsed.counterparty.isNotBlank()) {
            card.addView(line("商户 ${parsed.counterparty}", 14f, TEXT_SECONDARY, topPad = 8))
        }
        parsed.paymentMethod?.let {
            card.addView(line("支付方式 $it", 12f, TEXT_FAINT, topPad = 2))
        }

        // 折叠态的分类预览：确认就按这个分类入账（建议分类已按商户猜好）
        val catPreview = line("分类 ${categories[catIndex].name}", 14f, TEXT_SECONDARY, topPad = 8)
        card.addView(catPreview)

        // —— 编辑态：分类/账户横向 chips ——
        val editSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        fun chipStrip(label: String, names: List<String>, initial: Int, onSelect: (Int) -> Unit): LinearLayout {
            val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val chips = names.map { name ->
                TextView(context).apply {
                    text = name
                    textSize = 13f
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(8) }
                    background = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
                }
            }
            fun refresh(selected: Int) {
                chips.forEachIndexed { i, c ->
                    c.setTextColor(if (i == selected) Color.WHITE else CHIP_TEXT)
                    (c.background as GradientDrawable).setColor(if (i == selected) ACCENT else CHIP_BG)
                }
            }
            chips.forEachIndexed { i, c ->
                c.setOnClickListener {
                    onSelect(i)
                    refresh(i)
                    scheduleAutoDismiss()
                }
                strip.addView(c)
            }
            refresh(initial)
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = label
                    textSize = 12f
                    setTextColor(TEXT_FAINT)
                    setPadding(0, dp(12), 0, 0)
                })
                addView(HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(strip)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) })
            }
        }

        editSection.addView(chipStrip("分类", categories.map { it.name }, catIndex) { catIndex = it })
        editSection.addView(chipStrip("账户", accounts.map { it.name }, accIndex) { accIndex = it })
        card.addView(editSection)

        // —— 按钮行：修改（幽灵）/ 确认（实心） ——
        val modify = TextView(context).apply {
            text = "修改"
            textSize = 14f
            setTextColor(CHIP_TEXT)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(GHOST_BG) }
            setPadding(dp(20), dp(9), dp(20), dp(9))
        }
        val confirm = TextView(context).apply {
            text = "确认"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(ACCENT) }
            setPadding(dp(24), dp(9), dp(24), dp(9))
            setOnClickListener {
                dismiss()
                onConfirm(categories[catIndex].id, accounts[accIndex].id)
            }
        }
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
            addView(modify)
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(confirm)
        })

        var editing = false
        fun setEditing(on: Boolean) {
            editing = on
            catPreview.visibility = if (on) View.GONE else View.VISIBLE
            editSection.visibility = if (on) View.VISIBLE else View.GONE
            modify.text = if (on) "收起" else "修改"
            scheduleAutoDismiss()
        }
        modify.setOnClickListener {
            setEditing(!editing)
            if (!reducedMotion) {
                card.alpha = 0.7f
                card.animate().alpha(1f).setDuration(160L).start()
            }
        }

        fun expand() {
            expandTask?.let { mainHandler.removeCallbacks(it) }
            expandTask = null
            pill.visibility = View.GONE
            card.visibility = View.VISIBLE
            scheduleAutoDismiss()
            if (!reducedMotion) {
                card.pivotY = 0f
                card.scaleX = 0.88f
                card.scaleY = 0.88f
                card.alpha = 0f
                card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240L).start()
            }
        }
        pill.setOnClickListener { expand() }

        val params = WindowManager.LayoutParams(
            context.resources.displayMetrics.widthPixels - dp(32),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44)
        }

        val added = runCatching {
            wm.addView(root, params)
            current = root
            true
        }.onFailure { AppLog.e("Page", "island addView failed", it) }.getOrDefault(false)
        if (!added) return false

        AppLog.i("Page", "island shown ${Money.formatYuan(parsed.amountCents)} 商户=${parsed.counterparty}")
        scheduleAutoDismiss()
        expandTask = Runnable { expand() }.also { mainHandler.postDelayed(it, PILL_EXPAND_DELAY_MS) }
        if (!reducedMotion) {
            pill.scaleX = 0.6f
            pill.scaleY = 0.6f
            pill.alpha = 0f
            pill.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220L).start()
        }
        return true
    }

    fun dismiss() {
        expandTask?.let { mainHandler.removeCallbacks(it) }
        expandTask = null
        autoDismiss?.let { mainHandler.removeCallbacks(it) }
        autoDismiss = null
        val view = current ?: return
        current = null
        runCatching {
            (view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        }.onFailure { AppLog.w("Page", "island dismiss failed", it) }
    }
}
