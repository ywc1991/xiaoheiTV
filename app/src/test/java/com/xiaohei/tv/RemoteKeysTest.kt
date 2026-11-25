package com.xiaohei.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遥控器按键归一的 JVM 单测。
 *
 * 这些映射错了，在用户那台电视 / 机顶盒 / 投影仪上就是「某个键按了没反应」，
 * 且这类问题没法远程定位，只能用单测把每个品牌的键钉死。
 */
class RemoteKeysTest {

    @Test
    fun `OK键覆盖标准与蓝牙遥控各品牌`() {
        assertTrue(RemoteKeys.isOk(RemoteKeys.DPAD_CENTER))
        assertTrue(RemoteKeys.isOk(RemoteKeys.ENTER))         // 极米 / 坚果蓝牙遥控
        assertTrue(RemoteKeys.isOk(RemoteKeys.NUMPAD_ENTER))
        assertTrue(RemoteKeys.isOk(RemoteKeys.BUTTON_A))
        assertTrue(RemoteKeys.isOk(RemoteKeys.BUTTON_SELECT))
        assertFalse(RemoteKeys.isOk(RemoteKeys.BACK))
    }

    @Test
    fun `菜单键覆盖各品牌且不抢方向OK返回键`() {
        assertTrue(RemoteKeys.isSettings(RemoteKeys.MENU))
        assertTrue(RemoteKeys.isSettings(RemoteKeys.SETTINGS))
        assertTrue(RemoteKeys.isSettings(RemoteKeys.TV_CONTENTS_MENU))
        assertTrue(RemoteKeys.isSettings(RemoteKeys.INFO))
        assertTrue(RemoteKeys.isSettings(RemoteKeys.PROG_RED))
        assertTrue(RemoteKeys.isSettings(RemoteKeys.PROG_BLUE))
        // 绝不能把方向 / OK / 返回当成菜单键，否则列表里按 OK 会直接弹设置
        assertFalse(RemoteKeys.isSettings(RemoteKeys.ENTER))
        assertFalse(RemoteKeys.isSettings(RemoteKeys.BACK))
        assertFalse(RemoteKeys.isSettings(RemoteKeys.CHANNEL_UP))
    }

    @Test
    fun `物理换台键方向正确_CH加是下一个台`() {
        assertEquals(1, RemoteKeys.channelStep(RemoteKeys.CHANNEL_UP))
        assertEquals(-1, RemoteKeys.channelStep(RemoteKeys.CHANNEL_DOWN))
        assertEquals(0, RemoteKeys.channelStep(RemoteKeys.DPAD_CENTER))
    }

    @Test
    fun `播放暂停与上一键归位正确`() {
        assertTrue(RemoteKeys.isPlayPause(RemoteKeys.MEDIA_PLAY_PAUSE))
        assertTrue(RemoteKeys.isPlayPause(RemoteKeys.MEDIA_PLAY))
        assertTrue(RemoteKeys.isPlayPause(RemoteKeys.MEDIA_PAUSE))
        assertFalse(RemoteKeys.isPlayPause(RemoteKeys.MEDIA_NEXT))
        assertEquals(1, RemoteKeys.mediaSkip(RemoteKeys.MEDIA_NEXT))
        assertEquals(-1, RemoteKeys.mediaSkip(RemoteKeys.MEDIA_PREVIOUS))
        assertEquals(0, RemoteKeys.mediaSkip(RemoteKeys.CHANNEL_UP))
    }

    @Test
    fun `翻页与快进快退键在盒子投影仪上也能切台`() {
        // 下一台 / 下一集
        assertEquals(1, RemoteKeys.mediaSkip(RemoteKeys.PAGE_DOWN))
        assertEquals(1, RemoteKeys.mediaSkip(RemoteKeys.MEDIA_FAST_FORWARD))
        // 上一台 / 上一集
        assertEquals(-1, RemoteKeys.mediaSkip(RemoteKeys.PAGE_UP))
        assertEquals(-1, RemoteKeys.mediaSkip(RemoteKeys.MEDIA_REWIND))
        // 翻页键绝不能被当成菜单 / OK / 返回
        assertFalse(RemoteKeys.isSettings(RemoteKeys.PAGE_UP))
        assertFalse(RemoteKeys.isOk(RemoteKeys.PAGE_DOWN))
        assertFalse(RemoteKeys.isBack(RemoteKeys.MEDIA_REWIND))
    }

    @Test
    fun `返回键认标准BACK与部分投影的ESCAPE`() {
        assertTrue(RemoteKeys.isBack(RemoteKeys.BACK))
        assertTrue(RemoteKeys.isBack(RemoteKeys.ESCAPE))
        assertFalse(RemoteKeys.isBack(RemoteKeys.MENU))
    }

    @Test
    fun `数字键主键盘与小键盘都认_非数字返回负一`() {
        // 主键盘区 7..16
        assertEquals(0, RemoteKeys.digit(RemoteKeys.DIGIT_0))
        assertEquals(9, RemoteKeys.digit(RemoteKeys.DIGIT_9))
        assertEquals(5, RemoteKeys.digit(RemoteKeys.DIGIT_0 + 5))
        // 小键盘区 144..153（机顶盒常见）
        assertEquals(0, RemoteKeys.digit(RemoteKeys.NUMPAD_0))
        assertEquals(9, RemoteKeys.digit(RemoteKeys.NUMPAD_9))
        assertEquals(3, RemoteKeys.digit(RemoteKeys.NUMPAD_0 + 3))
        // 非数字
        assertEquals(-1, RemoteKeys.digit(RemoteKeys.ENTER))
        assertEquals(-1, RemoteKeys.digit(RemoteKeys.CHANNEL_UP))
    }
}
