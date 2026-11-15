package com.xiaohei.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 动态壁纸数学层的纯 JVM 单测：不依赖 Android，电视上跑不起来也能先验证行为。 */
class RadioBackdropMathTest {

    @Test
    fun `老电视档位确实降到一半开销`() {
        assertEquals(33L, RadioBackdropMath.frameMs(false))
        assertEquals(66L, RadioBackdropMath.frameMs(true))
        assertEquals(3, RadioBackdropMath.glowCount(false))
        assertEquals(2, RadioBackdropMath.glowCount(true))
        assertEquals(64, RadioBackdropMath.waveSamples(false))
        assertEquals(32, RadioBackdropMath.waveSamples(true))
        assertEquals(18, RadioBackdropMath.particleCount(false))
        assertEquals(0, RadioBackdropMath.particleCount(true))
    }

    @Test
    fun `四套配色结构一致且颜色合法`() {
        assertEquals(4, RadioBackdropMath.PALETTES.size)
        RadioBackdropMath.PALETTES.forEach { row ->
            assertEquals("每套应为 底色+3 光晕+波形", 5, row.size)
            row.forEach { rgb ->
                assertTrue("颜色应落在 0x000000..0xFFFFFF", rgb in 0..0xFFFFFF)
            }
        }
    }

    @Test
    fun `同一个台永远同一套配色且索引不越界`() {
        val names = listOf("中国之声", "经济之声", "音乐之声", "环球资讯", "上海动感101", "", "合肥新闻综合")
        names.forEach { n ->
            val p = RadioBackdropMath.paletteFor(n)
            assertTrue(p >= 0 && p < RadioBackdropMath.PALETTES.size)
            assertEquals(p, RadioBackdropMath.paletteFor(n))
        }
        // 至少能覆盖到多套配色，切台才有视觉变化
        assertTrue(names.map { RadioBackdropMath.paletteFor(it) }.toSet().size >= 3)
        // 空台名、极端 hash 也不能算出负数索引
        assertTrue(RadioBackdropMath.paletteFor(Int.MIN_VALUE.toString()) >= 0)
    }

    @Test
    fun `相位收敛且异常输入不会污染画面`() {
        assertEquals(0f, RadioBackdropMath.phase(-10f), 0f)
        assertEquals(0f, RadioBackdropMath.phase(Float.NaN), 0f)
        assertTrue(RadioBackdropMath.phase(1_000_000f) < 3600f)
        assertEquals(12.5f, RadioBackdropMath.phase(12.5f), 0.001f)
    }

    @Test
    fun `波形确定性有界且两端收拢`() {
        for (t in floatArrayOf(0f, 0.5f, 3.7f, 120f, 3599f)) {
            for (i in 0..64) {
                val x = i / 64f
                val y = RadioBackdropMath.wave(t, x)
                assertFalse("不应出现 NaN", y.isNaN())
                assertTrue("波形应落在 [-1,1]，实际 $y", abs(y) <= 1.0001f)
                assertEquals("同一时刻同一位置必须稳定", y, RadioBackdropMath.wave(t, x), 0f)
            }
        }
        assertEquals(0f, RadioBackdropMath.envelope(0f), 0.001f)
        assertEquals(0f, RadioBackdropMath.envelope(1f), 0.001f)
        assertTrue(RadioBackdropMath.envelope(0.5f) > 0.99f)
        // 时间推进后画面确实在变化
        assertTrue(abs(RadioBackdropMath.wave(0f, 0.3f) - RadioBackdropMath.wave(2f, 0.3f)) > 0.01f)
    }

    @Test
    fun `光晕始终留在屏幕内且不同光晕不同步`() {
        val w = 1920f
        val h = 1080f
        for (t in floatArrayOf(0f, 1f, 37f, 600f, 3600f)) {
            for (i in 0 until RadioBackdropMath.MAX_GLOWS) {
                val x = RadioBackdropMath.glowX(t, i, w)
                val y = RadioBackdropMath.glowY(t, i, h)
                assertFalse(x.isNaN()); assertFalse(y.isNaN())
                assertTrue("x 越界: $x", x in 0f..w)
                assertTrue("y 越界: $y", y in 0f..h)
            }
        }
        assertTrue(
            abs(RadioBackdropMath.glowX(10f, 0, w) - RadioBackdropMath.glowX(10f, 1, w)) > 1f
        )
    }

    @Test
    fun `光环呼吸幅度克制`() {
        for (t in floatArrayOf(0f, 1f, 50f, 3500f)) {
            val s = RadioBackdropMath.haloScale(t)
            assertTrue("呼吸应在 ±3.5% 内，实际 $s", s in 0.964f..1.036f)
        }
    }

    @Test
    fun `微粒循环上浮且不越界`() {
        val h = 1080f
        for (t in floatArrayOf(0f, 1f, 200f, 3599f)) {
            for (i in 0 until 18) {
                val y = RadioBackdropMath.particleY(t, i, h)
                assertFalse(y.isNaN())
                assertTrue("y 越界: $y", y in 0f..h)
            }
        }
        // 上浮一秒后位置确实变了
        assertTrue(
            abs(RadioBackdropMath.particleY(0f, 3, h) - RadioBackdropMath.particleY(1f, 3, h)) > 1f
        )
    }
}
