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
        // 收藏 / 央视 / 卫视 / 体育 / 港澳台 / 海外 / 广播 / 有声书 / 播客
        assertEquals(9, ChannelGroups.ORDER.size)
        assertTrue(ChannelGroups.ORDER.contains(ChannelGroups.RADIO))
        // 有声书紧挨在播客前面：都是点播音频，放一起才好找
        assertEquals(
            ChannelGroups.AUDIOBOOK,
            ChannelGroups.ORDER[ChannelGroups.ORDER.size - 2]
        )
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
        assertTrue("至少应有 8 个电台，实际 ${radio.size}", radio.size >= 8)
        assertTrue("视频频道数量不应减少，实际 ${video.size}", video.size >= 60)
        assertTrue(radio.all { it.group == ChannelGroups.RADIO })
        assertTrue(radio.all { it.urls.isNotEmpty() })
        assertTrue(radio.all { c -> c.urls.all { it.startsWith("http://") || it.startsWith("https://") } })
        assertTrue(video.none { it.group == ChannelGroups.RADIO })
        // 「其他」分组已移除，内置源只落在五个视频分组里（体育、海外分组上线后加入）
        assertTrue(video.none { it.group == "其他" })
        assertEquals(
            setOf(
                ChannelGroups.CCTV,
                ChannelGroups.SATELLITE,
                ChannelGroups.SPORTS,
                ChannelGroups.HKMO_TW,
                ChannelGroups.OVERSEAS
            ),
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
        // 用户后来要求摘掉北京、上海以及各省市地方台，music 里只剩国家级 + 网络台。
        val music = listOf(
            "音乐之声", "AsiaFM亚洲音乐台", "891音乐台"
        )
        val names = radio.map { it.name }.toSet()
        val missing = music.filter { it !in names }
        assertTrue("缺少音乐频道：$missing", missing.isEmpty())
        music.forEach { n ->
            val c = radio.first { it.name == n }
            assertTrue("$n 应至少有一条线路", c.urls.isNotEmpty())
            assertTrue("$n 的线路必须都是 http(s)", c.urls.all { it.startsWith("http") })
        }
        assertTrue("电台总数应不少于 8，实际 ${radio.size}", radio.size >= 8)
        assertEquals("电台不应重名", radio.size, names.size)
    }

    @Test
    fun `v2_9_0 加的那批音乐 FM 已按要求摘除`() {
        val f = listOf(
            File("src/main/assets/channels_main.m3u"),
            File("app/src/main/assets/channels_main.m3u")
        ).firstOrNull { it.exists() }
        assertTrue("找不到内置频道清单", f != null)
        val names = M3uParser.parse(f!!.readText()).filter { it.isAudio }.map { it.name }.toSet()
        // 用户要求「去掉最近一次新增的广播频道，保留第一次的」，
        // 这条用例防止以后从旧分支合并时把这 8 个台又带回来。
        val removed = listOf(
            "北京音乐广播", "浙江音乐调频", "河南音乐广播", "上海经典金曲",
            "四川音乐广播", "湖南音乐之声", "重庆音乐广播", "陕西音乐广播"
        )
        val back = removed.filter { it in names }
        assertTrue("这些台应已摘除：$back", back.isEmpty())
        assertEquals("广播分组应正好 8 个台，实际 ${names.size}", 8, names.size)
    }

    @Test
    fun `地方台已按要求整批摘除，只留国家级与网络台`() {
        val f = listOf(
            File("src/main/assets/channels_main.m3u"),
            File("app/src/main/assets/channels_main.m3u")
        ).firstOrNull { it.exists() }
        assertTrue("找不到内置频道清单", f != null)
        val names = M3uParser.parse(f!!.readText()).filter { it.isAudio }.map { it.name }.toSet()
        // 用户要求「去掉北京的广播，上海的广播」「去掉地方的广播电台」，
        // 这些省市台不能回流。
        val removed = listOf(
            "北京新闻广播", "北京交通广播",
            "上海新闻广播", "上海动感101", "第一财经广播", "上海经典音乐广播",
            "广东音乐之声", "广东珠江经济台", "广东交通之声", "深圳飞扬971",
            "江苏经典流行音乐广播", "四川综合广播", "陕西新闻广播",
            "天津新闻广播", "广西音乐广播", "云南音乐广播"
        )
        val back = removed.filter { it in names }
        assertTrue("北京/上海及地方电台应已摘除：$back", back.isEmpty())
        // 央广/国际台必须还在，不能把国家级一起删掉。
        val keep = listOf(
            "中国之声", "经济之声", "音乐之声",
            "环球资讯广播", "华语环球广播", "南海之声"
        )
        val gone = keep.filter { it !in names }
        assertTrue("国家级电台不应被删除：$gone", gone.isEmpty())
    }

    @Test
    fun `电台不得再使用蜻蜓分片清单`() {
        val f = listOf(
            File("src/main/assets/channels_main.m3u"),
            File("app/src/main/assets/channels_main.m3u")
        ).firstOrNull { it.exists() }
        assertTrue("找不到内置频道清单", f != null)
        val radio = M3uParser.parse(f!!.readText()).filter { it.isAudio }

        // 蜻蜓（qingting）的 /64k.m3u8 分支是 MPEG-2 AAC 24kHz 分片，
        // 实测电视端 C2SoftAacDec 逐帧报错并「substituting silence」，
        // 表现就是有画面没声音。同一路节目必须走它的 /64k.mp3 直连。
        val hls = radio.flatMap { c -> c.urls.map { c.name to it } }
            .filter { (_, u) -> u.contains("qingting.fm") || u.contains("qtfm.cn") }
            .filter { (_, u) -> u.contains(".m3u8") }
        assertTrue("蜻蜓电台不能再用分片清单：$hls", hls.isEmpty())
    }

    @Test
    fun `每个电台都必须有一条能解码的线路`() {
        val f = listOf(
            File("src/main/assets/channels_main.m3u"),
            File("app/src/main/assets/channels_main.m3u")
        ).firstOrNull { it.exists() }
        assertTrue("找不到内置频道清单", f != null)
        val radio = M3uParser.parse(f!!.readText()).filter { it.isAudio }
        // 只允许两种：央广/国际台的 MPEG-4 AAC 分片，以及 MP3 直连。
        // 出现别的形态说明有人又塞了没验证过的源进来。
        val odd = radio.flatMap { c -> c.urls.map { c.name to it } }
            .filterNot { (_, u) ->
                u.endsWith(".mp3") || u.endsWith(".aac") || u.endsWith(".m3u8")
            }
        assertTrue("存在无法判定类型的电台线路：$odd", odd.isEmpty())
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
