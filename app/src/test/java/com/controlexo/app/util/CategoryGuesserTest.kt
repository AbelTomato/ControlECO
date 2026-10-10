package com.controlexo.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryGuesserTest {

    @Test
    fun `expense guesses`() {
        assertEquals("餐饮", CategoryGuesser.guess("美团外卖-黄焖鸡"))
        assertEquals("交通", CategoryGuesser.guess("滴滴出行"))
        assertEquals("购物", CategoryGuesser.guess("京东商城-京东超市"))
        assertEquals("日用", CategoryGuesser.guess("某某超市"))
        assertEquals("居住", CategoryGuesser.guess("电费缴费"))
        assertEquals("娱乐", CategoryGuesser.guess("腾讯视频会员"))
        assertEquals("医疗", CategoryGuesser.guess("康泰药房"))
    }

    @Test
    fun `income guesses`() {
        assertEquals("工资", CategoryGuesser.guess("工资代发"))
        assertEquals("理财", CategoryGuesser.guess("余额宝-收益发放"))
        assertEquals("红包", CategoryGuesser.guess("微信红包"))
    }

    @Test
    fun `fallback to other`() {
        assertEquals("其他", CategoryGuesser.guess("某个完全不认识的商户"))
        assertEquals("其他", CategoryGuesser.guess(""))
    }
}
