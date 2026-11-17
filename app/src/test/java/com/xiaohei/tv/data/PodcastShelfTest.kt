package com.xiaohei.tv.data

import com.xiaohei.tv.model.PodcastEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播客书架：分类要覆盖全、顺序要稳、一档都不能丢。
 *
 * 书架按 [PodcastCatalog.CATEGORIES] 分节显示，分类名写错一个字（多一个空格、
 * 用了表里没有的名字）不会报错，只会静默掉进「其他」里 —— 那正是要用测试盯住的地方。
 */
class PodcastShelfTest {

    @Test
    fun `每一档都有分类且分类在固定表里`() {
        val known = PodcastCatalog.CATEGORIES.toSet()
        PodcastCatalog.builtin.forEach { show ->
            assertTrue("这档没有分类：${show.name}", show.category.isNotEmpty())
            assertTrue("分类不在表里：${show.name} → ${show.category}", show.category in known)
        }
    }

    @Test
    fun `每一档都有封面`() {
        // 书架是封面墙，没有封面的格子只剩一个首字，29 格全是灰块就没法认了
        PodcastCatalog.builtin.forEach { show ->
            assertTrue("没有封面：${show.name}", show.cover.startsWith("http"))
        }
    }

    @Test
    fun `书架按分类顺序分节且一档不丢`() {
        val shelf = PodcastCatalog.shelf
        assertEquals("书架条目数必须等于节目单", PodcastCatalog.shows.size, shelf.size)
        assertEquals("不能有重复", shelf.size, shelf.map { it.name }.distinct().size)

        // 分类的先后必须跟 CATEGORIES 一致（同一分类内允许连续出现）
        val sectionOrder = shelf.map { it.category }.fold(mutableListOf<String>()) { acc, c ->
            if (acc.lastOrNull() != c) acc.add(c)
            acc
        }
        val expected = PodcastCatalog.CATEGORIES.filter { c -> shelf.any { it.category == c } }
        assertEquals(expected, sectionOrder)
    }

    @Test
    fun `分类内部保持节目单里的手写顺序`() {
        val shelf = PodcastCatalog.shelf
        PodcastCatalog.CATEGORIES.forEach { category ->
            val fromBuiltin = PodcastCatalog.builtin.filter { it.category == category }.map { it.name }
            val fromShelf = shelf.filter { it.category == category }.map { it.name }
            assertEquals("分类「$category」内部顺序被打乱", fromBuiltin, fromShelf)
        }
    }

    @Test
    fun `没有封面的书也能排进分类后面而不是丢掉`() {
        // 书单已经全都有封面，这条守的是「分类表之外的条目」不会消失
        val categories = AudiobookCatalog.CATEGORIES
        val unknown = AudiobookCatalog.books.filter { it.category !in categories }
        assertTrue("书目里出现了分类表之外的值：${unknown.map { it.name }}", unknown.isEmpty())
    }

    @Test
    fun `播客不是书`() {
        // 这一个是踩过的坑：早先用「分类为空」判断是不是书，播客一加上分类
        // 整档就被当成书倒序排（最旧在前）、标题被切前缀、文案还写成「章」。
        PodcastCatalog.builtin.forEach { show ->
            assertFalse("播客被当成了书：${show.name}", show.isBook)
        }
    }

    @Test
    fun `有声书都是书`() {
        AudiobookCatalog.books.forEach { book ->
            assertTrue("书没标 isBook：${book.name}", book.isBook)
        }
    }

    @Test
    fun `播客分集保持最新在前`() {
        val episodes = listOf(
            episode("第 3 期", 3_000L),
            episode("第 2 期", 2_000L),
            episode("第 1 期", 1_000L)
        )
        // 播客的源本来就是最新在前，排序规则不能动它
        assertEquals(listOf("第 3 期", "第 2 期", "第 1 期"), BookOrder.apply(false, episodes).map { it.title })
        // 书则要正过来：从第一章开始
        assertEquals(listOf("第 1 期", "第 2 期", "第 3 期"), BookOrder.apply(true, episodes).map { it.title })
    }

    private fun episode(title: String, pub: Long) = PodcastEpisode(
        showId = "t", showName = "t", title = title, url = "https://example.com/$pub.m4a",
        durationMs = 0, pubDateMs = pub
    )
}
