package com.accbook.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 打开 App 时的权限提醒：只列"功能开着但没授权"的项。 */
class CapturePermissionsTest {

    @Test
    fun `权限齐全时不提醒`() {
        val ready = CapturePermission.entries.associateWith { true }
        assertTrue(missingPermissions(ready).isEmpty())
    }

    @Test
    fun `按固定顺序列出缺失项`() {
        val ready = mapOf(
            CapturePermission.LISTENER to true,
            CapturePermission.A11Y to false,
            CapturePermission.OVERLAY to true,
            CapturePermission.NOTIFY to false,
        )
        assertEquals(
            listOf(CapturePermission.A11Y, CapturePermission.NOTIFY),
            missingPermissions(ready),
        )
    }

    @Test
    fun `功能未启用（视为就绪）不提醒`() {
        // 用户主动关掉「自动记录支付页面」= 无障碍未授权也不算问题
        val ready = mapOf(
            CapturePermission.LISTENER to false,
            CapturePermission.A11Y to true,
            CapturePermission.OVERLAY to true,
            CapturePermission.NOTIFY to true,
        )
        assertEquals(listOf(CapturePermission.LISTENER), missingPermissions(ready))
    }

    @Test
    fun `缺省未提供的项按缺失处理`() {
        assertEquals(CapturePermission.entries.toList(), missingPermissions(emptyMap()))
    }

    @Test
    fun `权限项短标签`() {
        assertEquals("通知监听", CapturePermission.LISTENER.label)
        assertEquals("无障碍", CapturePermission.A11Y.label)
        assertEquals("悬浮窗", CapturePermission.OVERLAY.label)
        assertEquals("通知权限", CapturePermission.NOTIFY.label)
    }
}
