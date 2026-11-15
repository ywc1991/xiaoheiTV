package com.xiaohei.tv.data

import com.xiaohei.tv.model.PodcastShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastRssTest {

    private val show = PodcastShow("storyfm", "故事FM", "故事 FM", "https://feeds.storyfm.cn/storyfm.xml")

    private fun rss(vararg items: String) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>""")
        append("<title>故事FM</title>")
        items.forEach { append(it) }
        append("</channel></rss>")
    }

    private fun item(
        title: String,
        url: String? = "https://tk.wavpub.com/a.m4a",
        duration: String? = "42:10",
        pubDate: String? = "Sat, 12 Oct 2024 08:00:00 +0800"
    ) = buildString {
        append("<item>")
        append("<title><![CDATA[").append(title).append("]]></title>")
        if (url != null) append("""<enclosure url="$url" type="audio/mp4" length="123"/>""")
        if (duration != null) append("<itunes:duration>").append(duration).append("</itunes:duration>")
        if (pubDate != null) append("<pubDate>").append(pubDate).append("</pubDate>")
        append("</item>")
    }

    @Test
    fun `解析出标题音频地址与时长`() {
        val list = PodcastRss.parse(rss(item("一个母亲，不必一个人")), show)
        assertEquals(1, list.size)
        val e = list.single()
        assertEquals("一个母亲，不必一个人", e.title)
        assertEquals("https://tk.wavpub.com/a.m4a", e.url)
        // 42 分 10 秒
        assertEquals((42 * 60 + 10) * 1000L, e.durationMs)
        assertTrue("发布日期应解析出来", e.pubDateMs > 0)
        assertEquals("storyfm", e.showId)
    }

    @Test
    fun `时长支持小时制与纯秒数`() {
        assertEquals(3_661_000L, PodcastRss.durationMs("01:01:01"))
        assertEquals(90_000L, PodcastRss.durationMs("90"))
        assertEquals(0L, PodcastRss.durationMs(""))
        assertEquals(0L, PodcastRss.durationMs("--:--"))
    }

    @Test
    fun `没有音频地址的分集要跳过`() {
        val xml = rss(item("有音频"), item("只有文字", url = null))
        val list = PodcastRss.parse(xml, show)
        assertEquals(1, list.size)
        assertEquals("有音频", list.single().title)
    }

    @Test
    fun `标题里的 HTML 与转义字符被洗净`() {
        assertEquals("盲刺客 & 上", PodcastRss.clean("<![CDATA[<b>盲刺客</b> &amp; 上]]>"))
        assertEquals("A & B", PodcastRss.clean("A &amp; B"))
        assertEquals("换行 折叠", PodcastRss.clean("换行\n  折叠"))
    }

    @Test
    fun `最多只取 limit 集`() {
        val xml = rss(*Array(20) { item("第 ${it + 1} 集") })
        assertEquals(5, PodcastRss.parse(xml, show, limit = 5).size)
    }

    @Test
    fun `属性顺序颠倒的 enclosure 也能拿到地址`() {
        val xml = rss(
            """<item><title>T</title><enclosure length="9" type="audio/mpeg" url="https://x.example.com/b.mp3"/></item>"""
        )
        assertEquals("https://x.example.com/b.mp3", PodcastRss.parse(xml, show).single().url)
    }

    @Test
    fun `空文档与畸形文档不抛异常`() {
        assertTrue(PodcastRss.parse("", show).isEmpty())
        assertTrue(PodcastRss.parse("<rss><channel></channel></rss>", show).isEmpty())
        assertTrue(PodcastRss.parse("<item><title>没有闭合", show).isEmpty())
    }

    @Test
    fun `真实 RSS 切片能解析出三集且地址可用`() {
        val xml = javaClass.classLoader?.getResourceAsStream("feeds/storyfm_sample.xml")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        assertTrue("找不到测试样本", xml != null)
        val list = PodcastRss.parse(xml!!, show)
        assertEquals(3, list.size)
        assertTrue("音频地址应是 https 直链", list.all { it.url.startsWith("https://") })
        assertTrue("标题不能为空", list.all { it.title.isNotBlank() })
        // 故事FM 的 itunes:duration 写的是纯秒数（3108），这条覆盖「秒数」分支
        assertEquals(3_108_000L, list[0].durationMs)
        assertTrue("应解析出发布时间", list.all { it.pubDateMs > 0 })
    }

    @Test
    fun `内置播客目录的 id 名称与地址都不重复`() {
        val shows = PodcastCatalog.shows
        assertTrue("至少 8 档节目，实际 ${shows.size}", shows.size >= 8)
        assertEquals("id 不能重复", shows.size, shows.map { it.id }.toSet().size)
        assertEquals("节目名不能重复", shows.size, shows.map { it.name }.toSet().size)
        shows.forEach { s ->
            assertTrue("${s.name} 的 RSS 必须是 http(s)", s.feed.startsWith("https://") || s.feed.startsWith("http://"))
            assertTrue("${s.name} 缺作者", s.author.isNotBlank())
        }
    }

    @Test
    fun `播客节目行没有音频地址且标记正确`() {
        val ch = PodcastCatalog.asChannel(PodcastCatalog.shows.first())
        assertTrue(ch.isPodcast)
        assertTrue(ch.isAudio)
        assertTrue("节目行本身不该有线路", ch.urls.isEmpty())
        assertEquals(ChannelGroups.PODCAST, ch.group)
        assertFalse(ch.name.isBlank())
        assertEquals(PodcastCatalog.shows.first().author, ch.subtitle)
    }

    @Test
    fun `播客不会被当成可切台的视频频道`() {
        assertFalse(ChannelGroups.zapList(listOf(PodcastCatalog.asChannel(PodcastCatalog.shows.first())), false)
            .any { it.isPodcast })
    }
}
