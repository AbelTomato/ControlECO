package com.controleco.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 启动时入账方式校正（纯函数）测试。
 *
 * 定稿默认取向：自动入账开、悬浮窗入账关。旧版本两个开关彼此独立，
 * 机内可能留下"两个都开"的历史状态（实测：升级后首次进入两个开关都亮，
 * 捕获走自动入账路径、悬浮卡永远不弹）——启动时收敛，保证只有一种"免确认"方式生效。
 */
class EntryModeReconcileTest {

    @Test
    fun `两个都开且自动入账是用户显式设置时自动入账优先`() {
        val fix = entryModeFix(autoSet = true, overlaySet = true, autoEntry = true, overlayConfirm = true)
        assertEquals(EntryModeFix.DISABLE_OVERLAY, fix)
    }

    @Test
    fun `两个都开但悬浮窗是显式选择自动入账只是新默认值时不夺走悬浮窗模式`() {
        val fix = entryModeFix(autoSet = false, overlaySet = true, autoEntry = true, overlayConfirm = true)
        assertEquals(EntryModeFix.DISABLE_AUTO, fix)
    }

    @Test
    fun `只有自动入账开着时不校正`() {
        assertEquals(
            EntryModeFix.NONE,
            entryModeFix(autoSet = true, overlaySet = false, autoEntry = true, overlayConfirm = false),
        )
    }

    @Test
    fun `只有悬浮窗开着时不校正`() {
        assertEquals(
            EntryModeFix.NONE,
            entryModeFix(autoSet = true, overlaySet = true, autoEntry = false, overlayConfirm = true),
        )
    }

    @Test
    fun `两个都关（仅提醒）时不校正`() {
        assertEquals(
            EntryModeFix.NONE,
            entryModeFix(autoSet = false, overlaySet = false, autoEntry = false, overlayConfirm = false),
        )
    }

    @Test
    fun `全新安装（默认值自动入账开）不校正`() {
        assertEquals(
            EntryModeFix.NONE,
            entryModeFix(autoSet = false, overlaySet = false, autoEntry = true, overlayConfirm = false),
        )
    }
}
