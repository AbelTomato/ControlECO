package com.controleco.app.capture.overlay

import com.controleco.app.capture.NotificationRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

/**
 * 关闭卡片的处置判定：关闭（✕/点卡片外/长时间无操作）= 视为入账，只有点「忽略」才不入账；
 * 金额缺失或分类/账户不全时不能入账，保留待确认。
 */
class OverlayDismissOutcomeTest {

    private fun snapshot(
        amountCents: Long = 2060L,
        categoryId: Long? = 3L,
        accountId: Long? = 1L,
        counterparty: String = "示例商户",
    ) = DismissSnapshot(amountCents, categoryId, accountId, counterparty)

    @Test
    fun `金额分类账户齐备时关闭即入账`() {
        assertSame(DismissOutcome.ENTER, dismissOutcome(snapshot()))
    }

    @Test
    fun `金额为 0 的待补录条目关闭不入账（保留待确认）`() {
        assertSame(DismissOutcome.KEEP_PENDING, dismissOutcome(snapshot(amountCents = 0L)))
    }

    @Test
    fun `缺分类或账户时保留待确认`() {
        assertSame(DismissOutcome.KEEP_PENDING, dismissOutcome(snapshot(categoryId = null)))
        assertSame(DismissOutcome.KEEP_PENDING, dismissOutcome(snapshot(accountId = null)))
    }

    @Test
    fun `金额非法（负数）不入账`() {
        assertSame(DismissOutcome.KEEP_PENDING, dismissOutcome(snapshot(amountCents = -1L)))
    }
}
