package com.accbook.app.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBrandsTest {

    @Test
    fun `brand mapping from manufacturer string`() {
        assertEquals(Brand.XIAOMI, Brand.from("Xiaomi"))
        assertEquals(Brand.XIAOMI, Brand.from("Redmi"))
        assertEquals(Brand.XIAOMI, Brand.from("POCO"))
        assertEquals(Brand.HONOR, Brand.from("HONOR"))
        assertEquals(Brand.HUAWEI, Brand.from("HUAWEI"))
        assertEquals(Brand.OPPO, Brand.from("OPPO"))
        assertEquals(Brand.ONEPLUS, Brand.from("OnePlus"))
        assertEquals(Brand.REALME, Brand.from("realme"))
        assertEquals(Brand.VIVO, Brand.from("vivo"))
        assertEquals(Brand.VIVO, Brand.from("iQOO"))
        assertEquals(Brand.SAMSUNG, Brand.from("samsung"))
        assertEquals(Brand.OTHER, Brand.from("Google"))
        assertEquals(Brand.OTHER, Brand.from(""))
    }

    @Test
    fun `every brand has guidance steps`() {
        Brand.values().forEach { brand ->
            assertTrue("品牌 ${brand.name} 缺少保活步骤", DeviceBrands.stepsFor(brand).isNotEmpty())
        }
    }

    @Test
    fun `major brands include autostart or app-info jump`() {
        listOf(Brand.XIAOMI, Brand.HUAWEI, Brand.HONOR, Brand.OPPO, Brand.VIVO).forEach { brand ->
            val hasJump = DeviceBrands.stepsFor(brand).any { it.actions.isNotEmpty() }
            assertTrue("品牌 ${brand.name} 缺少可跳转步骤", hasJump)
        }
    }
}
