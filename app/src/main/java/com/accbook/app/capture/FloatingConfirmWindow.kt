package com.accbook.app.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.accbook.app.data.db.AccountEntity
import com.accbook.app.data.db.CategoryEntity
import com.accbook.app.util.AppLog
import com.accbook.app.util.Money

/**
 * 悬浮确认窗：支付完成瞬间悬浮在支付页上方，预填金额/收款方/分类/账户，点一下入账。
 *
 * 实现取舍：用经典 View 而非 Compose——TYPE_APPLICATION_OVERLAY 窗口没有 Activity 生命周期，
 * ComposeView 需要手工接 LifecycleOwner，得不偿失；悬浮窗 UI 保持极简。
 *
 * 窗口参数：NOT_FOCUSABLE（不抢支付页焦点，无障碍 rootInActiveWindow 仍指向支付页）+
 * NOT_TOUCH_MODAL（卡片外触摸穿透到支付页）。分类/账户用 ‹ › 循环选择而非 Spinner——
 * Spinner 弹出层在无焦点窗口里不可用。
 *
 * 已知拦截：华为/荣耀"支付保护中心"、小米"支付保险箱"会盖住支付页并拦截悬浮窗，
 * 对策文案在保活设置页（KeepAliveScreen / DeviceBrands）。
 */
object FloatingConfirmWindow {

    private const val AUTO_DISMISS_MS = 90_000L

    private var current: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoDismiss: Runnable? = null

    fun isShowing(): Boolean = current != null

    fun show(
        context: Context,
        parsed: PageRules.ParsedPage,
        categories: List<CategoryEntity>,
        accounts: List<AccountEntity>,
        onConfirm: (categoryId: Long, accountId: Long) -> Unit,
        onIgnore: () -> Unit,
    ) {
        dismiss()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = context.resources.displayMetrics.density
        val dp: (Int) -> Int = { v -> (v * density + 0.5f).toInt() }

        var catIndex = 0
        var accIndex = 0

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(0xF21C1C1E.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }

        fun line(text: String, size: Float, color: Int, bold: Boolean = false, topPad: Int = 0): TextView =
            TextView(context).apply {
                this.text = text
                this.textSize = size
                setTextColor(color)
                setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setPadding(0, dp(topPad), 0, 0)
            }

        root.addView(line("${parsed.sourceLabel}支付 · 请确认入账", 12f, 0x99FFFFFF.toInt()))
        root.addView(
            line(
                (if (parsed.type == com.accbook.app.data.db.TxType.EXPENSE) "支出 " else "收入 ") +
                    Money.formatYuan(parsed.amountCents),
                22f, Color.WHITE, bold = true, topPad = 6,
            ),
        )
        if (parsed.counterparty.isNotBlank()) {
            root.addView(line("收款方 ${parsed.counterparty}", 14f, 0xE6FFFFFF.toInt(), topPad = 6))
        }
        parsed.paymentMethod?.let {
            root.addView(line("支付方式 $it", 12f, 0x99FFFFFF.toInt(), topPad = 2))
        }

        // 分类 ‹ › 循环选择
        val catLabel = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        fun refreshCat() { catLabel.text = categories[catIndex].name }
        root.addView(cyclerRow(context, "分类", catLabel, dp,
            onPrev = { catIndex = (catIndex + categories.size - 1) % categories.size; refreshCat() },
            onNext = { catIndex = (catIndex + 1) % categories.size; refreshCat() },
        ))
        refreshCat()

        // 账户 ‹ › 循环选择
        val accLabel = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        fun refreshAcc() { accLabel.text = accounts[accIndex].name }
        root.addView(cyclerRow(context, "账户", accLabel, dp,
            onPrev = { accIndex = (accIndex + accounts.size - 1) % accounts.size; refreshAcc() },
            onNext = { accIndex = (accIndex + 1) % accounts.size; refreshAcc() },
        ))
        refreshAcc()

        // 确认 / 忽略
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val ignore = TextView(context).apply {
            text = "忽略"
            textSize = 14f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener {
                dismiss()
                onIgnore()
            }
        }
        val confirm = Button(context).apply {
            text = "确认入账"
            textSize = 14f
            isAllCaps = false
            setOnClickListener {
                dismiss()
                onConfirm(categories[catIndex].id, accounts[accIndex].id)
            }
        }
        buttons.addView(ignore)
        buttons.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        buttons.addView(confirm)
        root.addView(buttons)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(64)
            horizontalMargin = 0.03f
        }

        runCatching {
            wm.addView(root, params)
            current = root
            AppLog.i("Page", "floating confirm shown ${Money.formatYuan(parsed.amountCents)} 收款方=${parsed.counterparty}")
            autoDismiss = Runnable { dismiss() }.also { mainHandler.postDelayed(it, AUTO_DISMISS_MS) }
        }.onFailure {
            AppLog.e("Page", "floating confirm addView failed", it)
        }
    }

    private fun cyclerRow(
        context: Context,
        label: String,
        value: TextView,
        dp: (Int) -> Int,
        onPrev: () -> Unit,
        onNext: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        addView(TextView(context).apply {
            text = "$label "
            textSize = 12f
            setTextColor(0x99FFFFFF.toInt())
        })
        addView(cycleButton(context, "‹", dp, onPrev))
        value.setPadding(dp(12), 0, dp(12), 0)
        addView(value)
        addView(cycleButton(context, "›", dp, onNext))
    }

    private fun cycleButton(context: Context, text: String, dp: (Int) -> Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(2), dp(12), dp(2))
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onClick() }
        }

    /**
     * 退款关联提醒卡：无金额的退款到账通知触发，点击跳进 App 选择被退款的账单。
     * 独立于入账确认卡（没有金额可预填，只有跳转动作）。
     */
    fun showRefundLink(
        context: Context,
        sourceLabel: String,
        onOpen: () -> Unit,
        onIgnore: () -> Unit,
    ) {
        dismiss()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = context.resources.displayMetrics.density
        val dp: (Int) -> Int = { v -> (v * density + 0.5f).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(0xF21C1C1E.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }
        fun line(text: String, size: Float, color: Int, bold: Boolean = false, topPad: Int = 0): TextView =
            TextView(context).apply {
                this.text = text
                this.textSize = size
                setTextColor(color)
                setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setPadding(0, dp(topPad), 0, 0)
            }

        root.addView(line("AccBook · 退款提醒", 12f, 0x99FFFFFF.toInt()))
        root.addView(line("${sourceLabel}退款到账", 18f, Color.WHITE, bold = true, topPad = 4))
        root.addView(line("退款通知未带金额，请选择被退款的账单以记录退款收入", 13f, 0xB3FFFFFF.toInt(), topPad = 4))

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val ignore = TextView(context).apply {
            text = "忽略"
            textSize = 14f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener {
                dismiss()
                onIgnore()
            }
        }
        val open = Button(context).apply {
            text = "去关联账单"
            textSize = 14f
            isAllCaps = false
            setOnClickListener {
                dismiss()
                onOpen()
            }
        }
        buttons.addView(ignore)
        buttons.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        buttons.addView(open)
        root.addView(buttons)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(64)
            horizontalMargin = 0.03f
        }
        runCatching {
            wm.addView(root, params)
            current = root
            AppLog.i("Page", "refund link floating shown")
            autoDismiss = Runnable { dismiss() }.also { mainHandler.postDelayed(it, AUTO_DISMISS_MS) }
        }.onFailure {
            AppLog.e("Page", "refund floating addView failed", it)
        }
    }

    fun dismiss() {
        autoDismiss?.let { mainHandler.removeCallbacks(it) }
        autoDismiss = null
        val view = current ?: return
        current = null
        runCatching {
            (view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        }.onFailure { AppLog.w("Page", "floating dismiss failed", it) }
    }
}
