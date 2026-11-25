package com.xiaohei.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/** 字体缩放 / 密度钳制的纯逻辑单测：防止老人模式大字体再次撑破固定行高。 */
class DisplayCompatTest {

    @Test
    fun `正常缩放原样返回`() {
        assertEquals(1.0f, DisplayCompat.clampFontScale(1.0f))
        assertEquals(1.1f, DisplayCompat.clampFontScale(1.1f))
        assertEquals(1.25f, DisplayCompat.clampFontScale(1.25f))
    }

    @Test
    fun `超大字体被钳到上限`() {
        assertEquals(1.25f, DisplayCompat.clampFontScale(1.3f))   // 部分投影仪出厂值
        assertEquals(1.25f, DisplayCompat.clampFontScale(1.5f))   // 老人模式
        assertEquals(1.25f, DisplayCompat.clampFontScale(2.0f))
    }

    @Test
    fun `过小字体与非正数兜底`() {
        assertEquals(1.0f, DisplayCompat.clampFontScale(0.8f))
        assertEquals(1.0f, DisplayCompat.clampFontScale(0f))
        assertEquals(1.0f, DisplayCompat.clampFontScale(-1f))
        assertEquals(1.0f, DisplayCompat.clampFontScale(Float.NaN))
    }

    @Test
    fun `density 异常值被钳制`() {
        assertEquals(1.0f, DisplayCompat.clampDensity(1.0f))
        assertEquals(0.85f, DisplayCompat.clampDensity(0.6f))
        assertEquals(3.5f, DisplayCompat.clampDensity(5.0f))
        assertEquals(1.0f, DisplayCompat.clampDensity(Float.NaN))
    }
}
