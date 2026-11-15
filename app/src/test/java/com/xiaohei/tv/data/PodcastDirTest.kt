package com.xiaohei.tv.data

import com.xiaohei.tv.model.PodcastShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端目录是不可信输入：解析、校验、合并这三层但凡放松一点，
 * 一份改坏的 JSON 就能把电视上的播客分组清空，或者让 id 变成 `../` 写到别处去。
 */
class PodcastDirTest {

    private fun show(id: String, name: String = id) =
        PodcastShow(id = id, name = name, author = "", feed = "https://example.com/$id.xml")

    // ------------------------------------------------------------ 解析

    @Test
    fun `正常 JSON 按文件顺序解析出节目`() {
        val r = PodcastDir.parse(
            """
            {"version": 3, "shows": [
              {"id":"a1","name":"甲","author":"作者甲","feed":"https://x.com/a.xml","desc":"说明"},
              {"id":"b2","name":"乙","feed":"https://x.com/b.xml","enabled":true}
            ]}
            """.trimIndent()
        )
        assertNotNull(r)
        assertEquals(3, r!!.version)
        assertEquals(2, r.shows.size)
        assertEquals("a1", r.shows[0].id)
        assertEquals("甲", r.shows[0].name)
        assertEquals("作者甲", r.shows[0].author)
        assertEquals("说明", r.shows[0].desc)
        assertEquals("b2", r.shows[1].id)
    }

    @Test
    fun `enabled 为 false 的条目进 removed 而不是进列表`() {
        val r = PodcastDir.parse(
            """{"shows":[{"id":"storyfm","enabled":false},{"id":"ok","name":"好的","feed":"https://x.com/o.xml"}]}"""
        )!!
        assertEquals(1, r.shows.size)
        assertEquals("ok", r.shows[0].id)
        assertTrue("storyfm" in r.removed)
    }

    @Test
    fun `id 里带路径穿越的一律丢掉`() {
        val r = PodcastDir.parse(
            """
            {"shows":[
              {"id":"../../databases/x","name":"坏","feed":"https://x.com/1.xml"},
              {"id":"a/b","name":"坏2","feed":"https://x.com/2.xml"},
              {"id":"大写","name":"坏3","feed":"https://x.com/3.xml"},
              {"id":"good_one","name":"好","feed":"https://x.com/4.xml"}
            ]}
            """.trimIndent()
        )!!
        assertEquals(1, r.shows.size)
        assertEquals("good_one", r.shows[0].id)
    }

    @Test
    fun `非 http 的 feed 丢掉`() {
        val r = PodcastDir.parse(
            """
            {"shows":[
              {"id":"a","name":"甲","feed":"file:///etc/passwd"},
              {"id":"b","name":"乙","feed":"javascript:alert(1)"},
              {"id":"c","name":"丙","feed":"https://x.com/c.xml"}
            ]}
            """.trimIndent()
        )!!
        assertEquals(1, r.shows.size)
        assertEquals("c", r.shows[0].id)
    }

    @Test
    fun `重复 id 只留第一条`() {
        val r = PodcastDir.parse(
            """
            {"shows":[
              {"id":"same","name":"先来的","feed":"https://x.com/1.xml"},
              {"id":"same","name":"后来的","feed":"https://x.com/2.xml"}
            ]}
            """.trimIndent()
        )!!
        assertEquals(1, r.shows.size)
        assertEquals("先来的", r.shows[0].name)
    }

    @Test
    fun `超长字段被截断而不是整条丢掉`() {
        val name = "长".repeat(60)
        val r = PodcastDir.parse(
            """{"shows":[{"id":"a","name":"$name","feed":"https://x.com/a.xml"}]}"""
        )!!
        assertEquals(24, r.shows[0].name.length)
    }

    @Test
    fun `条数上限 40`() {
        val body = (1..80).joinToString(",") { """{"id":"s$it","name":"台$it","feed":"https://x.com/$it.xml"}""" }
        val r = PodcastDir.parse("""{"shows":[$body]}""")!!
        assertEquals(PodcastDir.MAX_SHOWS, r.shows.size)
    }

    @Test
    fun `坏 JSON 与空目录都返回 null`() {
        assertNull(PodcastDir.parse(""))
        assertNull(PodcastDir.parse("not json"))
        assertNull(PodcastDir.parse("""{"shows": [ }"""))
        assertNull(PodcastDir.parse("""{"other": 1}"""))
        // 全是废条目 = 这份文件没用，不能拿它把列表清空
        assertNull(PodcastDir.parse("""{"shows":[{"id":"..","feed":"x"}]}"""))
    }

    // ------------------------------------------------------------ 合并

    @Test
    fun `远端在前，内置里没被提到的补在后面`() {
        val merged = PodcastDir.merge(listOf(show("a"), show("b")), listOf(show("c"), show("b")), emptySet())
        assertEquals(listOf("c", "b", "a"), merged.map { it.id })
    }

    @Test
    fun `removed 里的 id 不管远端内置都不出现`() {
        val merged = PodcastDir.merge(listOf(show("a"), show("b")), listOf(show("b")), setOf("b"))
        assertEquals(listOf("a"), merged.map { it.id })
    }

    @Test
    fun `远端改名生效`() {
        val remote = PodcastShow("a", "新名字", "新作者", "https://x.com/new.xml")
        val merged = PodcastDir.merge(listOf(show("a", "旧名字")), listOf(remote), emptySet())
        assertEquals(1, merged.size)
        assertEquals("新名字", merged[0].name)
        assertEquals("https://x.com/new.xml", merged[0].feed)
    }

    @Test
    fun `合并后不超过上限`() {
        val builtin = (1..40).map { show("b$it") }
        val remote = (1..10).map { show("r$it") }
        assertEquals(PodcastDir.MAX_SHOWS, PodcastDir.merge(builtin, remote, emptySet()).size)
    }

    // ------------------------------------------------------------ 生效目录

    @Test
    fun `apply 只在节目单真的变了时返回 true`() {
        PodcastCatalog.reset()
        try {
            val dir = PodcastDir.parse("""{"shows":[{"id":"newone","name":"新台","feed":"https://x.com/n.xml"}]}""")!!
            assertTrue(PodcastCatalog.apply(dir))
            assertTrue(PodcastCatalog.shows.any { it.id == "newone" })
            // 同样的目录再来一次：没变化
            assertEquals(false, PodcastCatalog.apply(dir))
        } finally {
            PodcastCatalog.reset()
        }
        assertEquals(PodcastCatalog.builtin, PodcastCatalog.shows)
    }

    @Test
    fun `apply(null) 退回内置清单`() {
        PodcastCatalog.reset()
        PodcastCatalog.apply(PodcastDir.parse("""{"shows":[{"id":"x1","name":"临时","feed":"https://x.com/x.xml"}]}"""))
        assertTrue(PodcastCatalog.shows.any { it.id == "x1" })
        assertTrue(PodcastCatalog.apply(null))
        assertEquals(PodcastCatalog.builtin, PodcastCatalog.shows)
    }

    /**
     * 仓库根目录那份真文件也要过一遍：它是要推到 GitHub 上给电视直接吃的，
     * 这里写错一个引号，线上就是「所有电视退回内置 11 档」。
     */
    @Test
    fun `仓库里的 podcasts json 能被解析`() {
        val f = java.io.File("../podcasts.json")
        if (!f.exists()) return // 换台机器/CI 上可能只取了 app 模块
        val r = PodcastDir.parse(f.readText())
        assertNotNull("podcasts.json 解析失败", r)
        assertTrue("至少要有内置那些档", r!!.shows.size >= PodcastCatalog.builtin.size)
        val ids = r.shows.map { it.id }
        assertEquals("id 不能重复", ids.size, ids.toSet().size)
        assertTrue("feed 必须是 http(s)", r.shows.all { it.feed.startsWith("https://") })
    }
}
