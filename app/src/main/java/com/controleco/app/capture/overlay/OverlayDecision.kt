package com.controleco.app.capture.overlay

import com.controleco.app.capture.CaptureDefaults

/**
 * 悬浮确认卡的显示决策（纯函数，便于单测）与时间常量。
 *
 * 卡片是"刚付完款、人还在支付页"场景的快速确认入口；不满足条件时**一律回落横幅通知**
 * （通知是兜底通道，永远保留）。参考自研方案的方案取舍见
 * docs/自动记账捕获机制.md 第二节。
 */

/** 捕获到弹出卡片的延迟：支付保护层在支付页停留期会拦截悬浮窗，稍等更稳（失败仍回落通知） */
internal const val OVERLAY_DELAY_MS = 3_000L

/** 卡片无操作自动收起（收起 = 保留待确认 + 补发通知，不丢单） */
internal const val OVERLAY_AUTO_DISMISS_MS = 60_000L

/** 确认后的「已记入 · 撤销」细条停留时长 */
internal const val SAVED_STRIP_DISMISS_MS = 6_000L

/** 卡片显示条件（四个入参都可能变，弹出前需再判一次） */
internal data class OverlayConditions(
    /** 设置里「悬浮确认卡」开关 */
    val enabled: Boolean,
    /** Settings.canDrawOverlays（悬浮窗权限） */
    val canDrawOverlays: Boolean,
    /** 本应用是否在前台（在前台时应使用应用内的待确认列表，不弹卡） */
    val appInForeground: Boolean,
    /** 屏幕是否点亮 */
    val screenInteractive: Boolean,
    /** 是否已锁屏 */
    val keyguardLocked: Boolean,
    /** 当前前台应用包名（由无障碍服务跟踪；判不出来为 null） */
    val foregroundPackage: String?,
    /** 本次捕获的来源键（包名 / SMS:银行 / 银行推送包名） */
    val sourcePackage: String,
)

internal fun shouldShowOverlay(c: OverlayConditions): Boolean = when {
    !c.enabled || !c.canDrawOverlays -> false
    c.appInForeground -> false
    !c.screenInteractive || c.keyguardLocked -> false
    // 判不出前台应用（无障碍未开）→ 放行；判得出则要求一致（人还在支付页才弹卡）
    c.foregroundPackage == null || c.foregroundPackage == c.sourcePackage -> true
    // 银行侧（短信/银行推送/云闪付）到达时用户常不在来源应用里（在收银台/别处），不做前台匹配
    CaptureDefaults.isBankSource(c.sourcePackage) -> true
    else -> false
}
