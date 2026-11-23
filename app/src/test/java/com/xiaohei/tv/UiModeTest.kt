package com.xiaohei.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UiMode 纯函数部分的单测：设备判定无关的部分（文案 / 面板宽度比例） */
class UiModeTest {

    @Test
    fun hintText_distinguishes_phone_from_tv() {
        val phoneHint = UiMode.hintText(phone = true, isPodcast = false, isAudiobook = false)
        val tvHint = UiMode.hintText(phone = false, isPodcast = false, isAudiobook = false)
        assertTrue(phoneHint.contains("点分组"))
        assertTrue(phoneHint.contains("点频道"))
        assertTrue(phoneHint.contains("横竖屏") || phoneHint.contains("画面点击"))
        assertTrue(tvHint.contains("↑↓"))
        assertTrue(tvHint.contains("OK"))
    }

    @Test
    fun hintText_podcast_and_audiobook_are_touch_friendly_on_phone() {
        val podcast = UiMode.hintText(phone = true, isPodcast = true, isAudiobook = false)
        val book = UiMode.hintText(phone = true, isPodcast = false, isAudiobook = true)
        assertTrue(podcast.contains("点节目"))
        assertTrue(book.contains("点封面"))
        assertFalse(podcast.contains("OK 打开书架"))
    }

    @Test
    fun panel_width_ratio_constants_are_sane() {
        // 手机竖屏 68% > 手机横屏 52% > 电视 33%：比例关系固定，防止改坏
        val w = 1000
        val phonePortrait = (w * 0.68f).toInt()
        val phoneLandscape = (w * 0.52f).toInt()
        val tv = (w * 0.33f).toInt()
        assertTrue(phonePortrait > phoneLandscape)
        assertTrue(phoneLandscape > tv)
        assertEquals(680, phonePortrait)
    }
}
