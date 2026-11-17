package com.xiaohei.tv.data

import com.xiaohei.tv.model.PodcastEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookCatalogTest {

    private val idRegex = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

    @Test
    fun `书目本身是干净的：id 唯一且能当文件名`() {
        val ids = AudiobookCatalog.books.map { it.id }
        assertEquals("id 不能重复", ids.size, ids.distinct().size)
        ids.forEach { id ->
            assertTrue("id 必须是安全的文件名：$id", idRegex.matches(id))
        }
    }

    @Test
    fun `书名不与播客重名`() {
        // byName 会先查播客再查书，重名会让「哪一档被打开」变得不可预期
        val podcastNames = PodcastCatalog.builtin.map { it.name }.toSet()
        AudiobookCatalog.books.forEach { book ->
            assertTrue("书名与播客重名：${book.name}", book.name !in podcastNames)
        }
        val names = AudiobookCatalog.books.map { it.name }
        assertEquals("书名不能重复", names.size, names.distinct().size)
    }

    @Test
    fun `每本书都有 feed 与封面`() {
        AudiobookCatalog.books.forEach { book ->
            assertTrue("feed 必须是 https：${book.name}", book.feed.startsWith("https://"))
            // 少数封面只在 http 上能取到（qingting 的图床），应用已允许明文流量
            assertTrue("封面必须是 http(s)：${book.name}", book.cover.startsWith("http"))
        }
    }

    @Test
    fun `分类都在固定清单里，书架不丢书`() {
        AudiobookCatalog.books.forEach { book ->
            assertTrue("未登记的分类：${book.name} / ${book.category}",
                book.category in AudiobookCatalog.CATEGORIES)
        }
        // 书架是展示用的全量视图：一本书都不能漏，也不能重复
        assertEquals(AudiobookCatalog.books.size, AudiobookCatalog.shelf.size)
        assertEquals(AudiobookCatalog.books.toSet(), AudiobookCatalog.shelf.toSet())
    }

    @Test
    fun `章节上限按整本算，不是播客的 40 集`() {
        AudiobookCatalog.books.forEach { book ->
            // 书是要听完的：任何一本被截到 40 集以内都属于功能残缺
            assertTrue("章节上限太小：${book.name}", book.maxEpisodes >= 500)
        }
    }

    @Test
    fun `书名可以反查回书`() {
        AudiobookCatalog.books.forEach { book ->
            assertEquals(book, AudiobookCatalog.byName(book.name))
            assertEquals(book, PodcastCatalog.byName(book.name))
        }
    }

    @Test
    fun `书按分类顺序排列`() {
        val order = AudiobookCatalog.shelf.map { AudiobookCatalog.CATEGORIES.indexOf(it.category) }
        assertEquals("书架里的分类必须按固定顺序连续排列", order, order.sorted())
    }

    private fun ep(title: String, dateMs: Long) = PodcastEpisode(
        showId = "bk", showName = "书", title = title, url = "https://x/$title.mp3",
        durationMs = 0L, pubDateMs = dateMs
    )

    @Test
    fun `书按发布时间正序：最新上传的排到最后`() {
        // RSS 是「最新在前」，照搬会把整本书倒着播
        val newestFirst = listOf(ep("第三回", 3000), ep("第二回", 2000), ep("第一回", 1000))
        val ordered = BookOrder.apply(true, newestFirst)
        assertEquals(listOf("第一回", "第二回", "第三回"), ordered.map { it.title })
    }

    @Test
    fun `没有日期的书整体翻转：第一集回到最前`() {
        val noDate = listOf(ep("第三回", 0), ep("第二回", 0), ep("第一回", 0))
        val ordered = BookOrder.apply(true, noDate)
        assertEquals(listOf("第一回", "第二回", "第三回"), ordered.map { it.title })
    }

    @Test
    fun `播客保持原顺序：最新一集仍在最前`() {
        val newestFirst = listOf(ep("最新一期", 3000), ep("上一期", 2000))
        val ordered = BookOrder.apply(false, newestFirst)
        assertEquals(listOf("最新一期", "上一期"), ordered.map { it.title })
    }

    @Test
    fun `章节名前的集数编号被去掉`() {
        // 真实的三种写法
        assertEquals("第一回王教头私走延安府", BookOrder.chapterTitle("001_第一回王教头私走延安府"))
        assertEquals("《安徒生童话》埃米尔的最后一天", BookOrder.chapterTitle("108《安徒生童话》埃米尔的最后一天"))
        assertEquals(
            "三国演义第一回-宴桃园豪杰三结义",
            BookOrder.chapterTitle("1三国演义第一回-宴桃园豪杰三结义", "三国演义")
        )
        // 本来就以章节标记开头的，原样保留
        assertEquals("第一回说楔子敷陈大义", BookOrder.chapterTitle("第一回说楔子敷陈大义"))
    }

    @Test
    fun `书名里的数字不会被当成集数切掉`() {
        assertEquals("1984", BookOrder.chapterTitle("1984"))
        assertEquals("3体", BookOrder.chapterTitle("3体"))
        assertEquals("24个比利", BookOrder.chapterTitle("24个比利", "三国演义"))
    }
}
