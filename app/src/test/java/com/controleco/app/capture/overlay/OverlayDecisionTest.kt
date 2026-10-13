package com.controleco.app.capture.overlay

import com.controleco.app.capture.NotificationRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮确认卡的显示条件判定（纯函数）：任一条不满足都回落横幅通知（通知是兜底通道，永远保留）。
 */
class OverlayDecisionTest {

    private fun conditions(
        enabled: Boolean = true,
        canDrawOverlays: Boolean = true,
        appInForeground: Boolean = false,
        screenInteractive: Boolean = true,
        keyguardLocked: Boolean = false,
        foregroundPackage: String? = NotificationRules.WECHAT,
        sourcePackage: String = NotificationRules.WECHAT,
        foregroundIsPaymentContext: Boolean = false,
    ) = OverlayConditions(
        enabled = enabled,
        canDrawOverlays = canDrawOverlays,
        appInForeground = appInForeground,
        screenInteractive = screenInteractive,
        keyguardLocked = keyguardLocked,
        foregroundPackage = foregroundPackage,
        sourcePackage = sourcePackage,
        foregroundIsPaymentContext = foregroundIsPaymentContext,
    )

    @Test
    fun `支付应用前台且屏幕可用时弹卡`() {
        assertTrue(shouldShowOverlay(conditions()))
    }

    @Test
    fun `开关关闭不弹卡`() {
        assertFalse(shouldShowOverlay(conditions(enabled = false)))
    }

    @Test
    fun `没有悬浮窗权限不弹卡`() {
        assertFalse(shouldShowOverlay(conditions(canDrawOverlays = false)))
    }

    @Test
    fun `本应用在前台不弹卡（用应用内列表）`() {
        assertFalse(shouldShowOverlay(conditions(appInForeground = true)))
    }

    @Test
    fun `屏幕熄灭不弹卡`() {
        assertFalse(shouldShowOverlay(conditions(screenInteractive = false)))
    }

    @Test
    fun `锁屏不弹卡`() {
        assertFalse(shouldShowOverlay(conditions(keyguardLocked = true)))
    }

    @Test
    fun `判不出前台应用时放行`() {
        assertTrue(shouldShowOverlay(conditions(foregroundPackage = null)))
    }

    @Test
    fun `前台应用与来源不一致（已切到无关应用）不弹卡`() {
        assertFalse(
            shouldShowOverlay(
                conditions(foregroundPackage = NotificationRules.ALIPAY, sourcePackage = NotificationRules.WECHAT),
            ),
        )
    }

    @Test
    fun `前台是别的支付购物应用时仍弹卡（跳转支付）`() {
        // 京东下单 → 拉起微信支付：通知到达时前台是京东（监听目录内），仍在支付语境
        assertTrue(
            shouldShowOverlay(
                conditions(
                    foregroundPackage = "com.jingdong.app.mall",
                    sourcePackage = NotificationRules.WECHAT,
                    foregroundIsPaymentContext = true,
                ),
            ),
        )
    }

    @Test
    fun `银行短信到达时不做前台匹配（人在收银台或别处）`() {
        assertTrue(
            shouldShowOverlay(
                conditions(
                    foregroundPackage = NotificationRules.WECHAT,
                    sourcePackage = "SMS:工商银行",
                ),
            ),
        )
    }

    @Test
    fun `银行推送到达时不做前台匹配`() {
        assertTrue(
            shouldShowOverlay(
                conditions(
                    foregroundPackage = "com.tencent.mm",
                    sourcePackage = "com.icbc",
                ),
            ),
        )
    }
}
