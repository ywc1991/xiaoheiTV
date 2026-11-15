package com.xiaohei.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RadioChannelTest {

    @Test
    fun `标了广播分组的条目被识别成纯音频电台`() {
        val list = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="广播",中国之声
            https://ngcdn001.cnr.cn/live/zgzs/index.m3u8
            https://lhttp.qtfm.cn/live/15318317/64k.mp3
            """.trimIndent()
        )
        assertEquals(1, list.size)
        val c = list.first()
        assertTrue(c.isAudio)
        assertEquals(ChannelGroups.RADIO, c.group)
        assertEquals(2, c.urls.size)
    }

    @Test
    fun `普通视频频道的分组规则完全不变`() {
        val list = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://x/CCTV1.png" group-title="央视",CCTV-1
            http://1.2.3.4/live/cctv1.m3u8
            #EXTINF:-1 group-title="央视",湖南卫视
            http://1.2.3.4/live/hunan.m3u8
            """.trimIndent()
        )
        assertEquals(2, list.size)
        assertTrue(list.none { it.isAudio })
        assertEquals(ChannelGroups.CCTV, list[0].group)
        assertEquals(ChannelGroups.SATELLITE, list[1].group)
        assertEquals("https://x/CCTV1.png", list[0].logo)
    }

    @Test
    fun `同名视频台与电台不会合并成一条`() {
        val list = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="央视",测试台
            http://a.example.com/video.m3u8
            #EXTINF:-1 group-title="广播",测试台
            https://ls.qingting.fm/live/339/64k.m3u8
            """.trimIndent()
        )
        assertEquals(2, list.size)
        assertEquals(1, list.count { it.isAudio })
        assertEquals(1, list.count { !it.isAudio })
    }

    @Test
    fun `同一电台分段出现时线路合并且保留音频标记`() {
        val list = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="广播",中国之声
            https://ngcdn001.cnr.cn/live/zgzs/index.m3u8
            #EXTINF:-1 group-title="广播",中国之声
            http://ngcdn002.cnr.cn/live/zgzs/index.m3u8
            """.trimIndent()
        )
        assertEquals(1, list.size)
        assertTrue(list[0].isAudio)
        assertEquals(2, list[0].urls.size)
    }

    @Test
    fun `常见广播分组别名都能识别`() {
        val titles = listOf("广播", "网络电台", "电台", "广播电台", "Radio", "FM", " radio ")
        titles.forEach { t ->
            val list = M3uParser.parse(
                """
                #EXTM3U
                #EXTINF:-1 group-title="$t",某电台
                https://ls.qingting.fm/live/339/64k.m3u8
                """.trimIndent()
            )
            assertTrue("分组名 $t 应识别为电台", list.single().isAudio)
        }
        // 地方电视台常见分组不能误判
        val tv = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="安徽频道",合肥新闻综合
            http://a.example.com/live.m3u8
            """.trimIndent()
        )
        assertFalse(tv.single().isAudio)
    }

    @Test
    fun `播客排在固定分组顺序的最后且不再有其他分组`() {
        assertEquals(ChannelGroups.PODCAST, ChannelGroups.ORDER.last())
        assertEquals(6, ChannelGroups.ORDER.size)
        assertTrue(ChannelGroups.ORDER.contains(ChannelGroups.RADIO))
        assertFalse("固定顺序里不应再有「其他」", ChannelGroups.ORDER.contains("其他"))
    }

    @Test
    fun `切台序列里永远不会出现播客节目行`() {
        val all = listOf(
            ch("CCTV-1", false),
            ch("中国之声", true),
            ch("故事FM", true).copy(isPodcast = true, urls = emptyList()),
            ch("湖南卫视", false)
        )
        assertTrue(ChannelGroups.zapList(all, inRadio = false).none { it.isPodcast })
        assertTrue(ChannelGroups.zapList(all, inRadio = true).none { it.isPodcast })
    }

    @Test
    fun `groupOf 不再返回其他`() {
        assertEquals(ChannelGroups.CCTV, ChannelGroups.groupOf("CCTV-1 综合"))
        assertEquals(ChannelGroups.SATELLITE, ChannelGroups.groupOf("湖南卫视"))
        assertEquals(ChannelGroups.HKMO_TW, ChannelGroups.groupOf("美亞電影台"))
        assertEquals("", ChannelGroups.groupOf("合肥新闻综合"))
    }

    @Test
    fun `台名不认识的频道沿用清单分组名且不再产生其他`() {
        val list = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="地方台",合肥新闻综合
            http://a.example.com/1.m3u8
            #EXTINF:-1 group-title="其他",某某频道
            http://a.example.com/2.m3u8
            #EXTINF:-1,无分组频道
            http://a.example.com/3.m3u8
            """.trimIndent()
        )
        assertEquals(3, list.size)
        assertEquals("地方台", list[0].group)
        assertEquals(ChannelGroups.SATELLITE, list[1].group)
        assertEquals(ChannelGroups.SATELLITE, list[2].group)
        assertTrue(list.none { it.group == "其他" })
        assertTrue(list.none { it.isAudio })
    }

    @Test
    fun `内置源包含一组线路完整的电台且不影响视频频道`() {
        val f = listOf(
            File("src/main/assets/channels_main.m3u"),
            File("app/src/main/assets/channels_main.m3u")
        ).firstOrNull { it.exists() }
        assertTrue("找不到内置频道清单", f != null)
        val list = M3uParser.parse(f!!.readText())
        val radio = list.filter { it.isAudio }
        val video = list.filter { !it.isAudio }
        assertTrue("至少应有 10 个电台，实际 ${radio.size}", radio.size >= 10)
        assertTrue("视频频道数量不应减少，实际 ${video.size}", video.size >= 60)
        assertTrue(radio.all { it.group == ChannelGroups.RADIO })
        assertTrue(radio.all { it.urls.isNotEmpty() })
        assertTrue(radio.all { c -> c.urls.all { it.startsWith("http://") || it.startsWith("https://") } })
        assertTrue(video.none { it.group == ChannelGroups.RADIO })
        // 「其他」分组已移除，内置源仍只落在三个视频分组里
        assertTrue(video.none { it.group == "其他" })
        assertEquals(
            setOf(ChannelGroups.CCTV, ChannelGroups.SATELLITE, ChannelGroups.HKMO_TW),
            video.map { it.group }.toSet()
        )
        // 电台名不能和视频台撞名，否则切台序列去重会漏台
        val clash = radio.map { it.name }.intersect(video.map { it.name }.toSet())
        assertTrue("电台与视频台重名：$clash", clash.isEmpty())
    }

    @Test
    fun `内置源里的音乐类电台齐全且线路可用`() {
        val f = listOf(
            File("src/main/assets/channels_main.m3u"),
            File("app/src/main/assets/channels_main.m3u")
        ).firstOrNull { it.exists() }
        assertTrue("找不到内置频道清单", f != null)
        val radio = M3uParser.parse(f!!.readText()).filter { it.isAudio }
        val music = listOf(
            "音乐之声", "上海动感101", "上海经典音乐广播", "广东音乐之声", "江苏经典流行音乐广播",
            "AsiaFM亚洲音乐台", "北京音乐广播", "浙江音乐调频", "河南音乐广播", "上海经典金曲",
            "四川音乐广播", "湖南音乐之声", "重庆音乐广播", "陕西音乐广播", "891音乐台"
        )
        val names = radio.map { it.name }.toSet()
        val missing = music.filter { it !in names }
        assertTrue("缺少音乐频道：$missing", missing.isEmpty())
        music.forEach { n ->
            val c = radio.first { it.name == n }
            assertTrue("$n 应至少有一条线路", c.urls.isNotEmpty())
            assertTrue("$n 的线路必须都是 http(s)", c.urls.all { it.startsWith("http") })
        }
        assertTrue("电台总数应不少于 23，实际 ${radio.size}", radio.size >= 23)
        assertEquals("电台不应重名", radio.size, names.size)
    }

    private fun ch(name: String, audio: Boolean) =
        com.xiaohei.tv.model.Channel(name, if (audio) ChannelGroups.RADIO else ChannelGroups.CCTV, "", listOf("https://x/$name.m3u8"), audio)

    @Test
    fun `看电视时上下键序列里不会有电台`() {
        val all = listOf(ch("CCTV-1", false), ch("中国之声", true), ch("湖南卫视", false))
        val seq = ChannelGroups.zapList(all, inRadio = false)
        assertEquals(2, seq.size)
        assertTrue(seq.none { it.isAudio })
    }

    @Test
    fun `听广播时上下键只在电台之间循环`() {
        val all = listOf(ch("CCTV-1", false), ch("中国之声", true), ch("经济之声", true))
        val seq = ChannelGroups.zapList(all, inRadio = true)
        assertEquals(2, seq.size)
        assertTrue(seq.all { it.isAudio })
    }

    @Test
    fun `清单里只有一种类型时仍可正常切台`() {
        val all = listOf(ch("CCTV-1", false), ch("CCTV-2", false))
        assertEquals(2, ChannelGroups.zapList(all, inRadio = true).size)
    }
}
