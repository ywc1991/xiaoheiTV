package com.xiaohei.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 港澳台分组的回归护栏。
 *
 * 这组频道是最脆的一块：源全在境外，单台单线路，一挂就整台消失。
 * 2026-11 就踩过一次——翡翠台、黃金翡翠台、亞洲劇台、美亞電影台
 * 四条线路全指向同一台中间服务器（103.172.187.30:12000），
 * 那台一关，四个频道同时变成「所有线路均无法播放」。
 *
 * 所以这里不追求「每台都有几条」，而是守住「不能把整台压在一台服务器上」。
 */
class HkmoChannelTest {

    private fun asset(): File = listOf(
        File("src/main/assets/channels_main.m3u"),
        File("app/src/main/assets/channels_main.m3u")
    ).first { it.exists() }

    private fun hkmo() = M3uParser.parse(asset().readText())
        .filter { it.group == ChannelGroups.HKMO_TW && !it.isAudio }

    /** 已经整体挂掉的中间服务器，不许再当作某个台的唯一线路 */
    private fun deadFarm(url: String) =
        url.contains("103.172.187.30:12000") || url.contains("aktv.top")

    @Test
    fun `港澳台频道数量与线路完整性`() {
        val list = hkmo()
        assertTrue("港澳台应有足够频道，实际 ${list.size}", list.size >= 18)
        val empty = list.filter { it.urls.isEmpty() }.map { it.name }
        assertTrue("这些台没有线路：$empty", empty.isEmpty())
        assertEquals("港澳台不应重名", list.size, list.map { it.name }.toSet().size)
        assertTrue(
            "线路必须是 http(s)",
            list.all { c -> c.urls.all { it.startsWith("http://") || it.startsWith("https://") } }
        )
        // 同一台内出现重复地址会让自动换线原地打转
        assertTrue("同台存在重复线路", list.all { c -> c.urls.size == c.urls.toSet().size })
    }

    @Test
    fun `没有任何台把全部线路压在同一台已失效的服务器上`() {
        val naked = hkmo().filter { c -> c.urls.all { deadFarm(it) } }.map { it.name }
        assertTrue("这些台仍然只依赖已失效的中间服务器：$naked", naked.isEmpty())
    }

    /**
     * 死线不能留：它排在某台的第二个，就只在首选线路出问题时才被用到——
     * 那时用户已经在等，再撞一次 502 / 404 等于多等一轮超时。
     * 2026-11-15 实测：103.172.187.30:12000 整站 502，
     * r.jdshipin.com 的短链（62WM7）4 小时内从 302 变 404。
     */
    @Test
    fun `已确认失效的服务器不得出现在任何一条线路上`() {
        val stale = listOf("103.172.187.30:12000", "r.jdshipin.com", "aktv.top", "epg.pw")
        val bad = hkmo().flatMap { c -> c.urls.filter { u -> stale.any { u.contains(it) } }.map { "${c.name} → $it" } }
        assertTrue("这些线路所在服务器已确认失效，应删除：$bad", bad.isEmpty())
    }

    @Test
    fun `含卫视字样的港澳台频道不会被分到卫视组`() {
        val all = M3uParser.parse(asset().readText())
        val lost = all.filter { it.group == ChannelGroups.SATELLITE }
            .map { it.name }
            .filter { n -> listOf("東森", "东森", "TVBS", "翡翠", "民视", "台视").any { n.contains(it) } }
        assertTrue("这些港澳台频道被误分到卫视：$lost", lost.isEmpty())
    }
}
