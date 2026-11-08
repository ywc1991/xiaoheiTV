package com.xiaohei.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdFilterTest {

    private val base = "http://cdn.example.com/live/chan1/index.m3u8"

    private fun playlist(block: StringBuilder.() -> Unit): String =
        StringBuilder().apply {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:6")
            appendLine("#EXT-X-MEDIA-SEQUENCE:100")
            block()
        }.toString()

    private fun seq(n: Int) = "seg$n.ts"

    private fun content(n: Int, start: Int = 0): String = buildString {
        for (i in start until start + n) {
            appendLine("#EXTINF:5.000,")
            appendLine(seq(i))
        }
    }

    @Test
    fun `其他域名插入的广告分片会被剔除`() {
        val text = playlist {
            append(content(8))
            appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXTINF:15.000,")
            appendLine("http://adserver.other.com/ad1.ts")
            appendLine("#EXTINF:15.000,")
            appendLine("http://adserver.other.com/ad2.ts")
            appendLine("#EXT-X-DISCONTINUITY")
            append(content(8, 8))
        }
        val out = AdFilter.filter(text, base)
        assertFalse("广告域名应被剔除", out.contains("adserver.other.com"))
        assertTrue("列表头必须保留", out.startsWith("#EXTM3U"))
        assertEquals("内容分片必须全部保留", 16, out.lines().count { it.endsWith(".ts") })
    }

    @Test
    fun `CUE-OUT 区间内的广告分片会被剔除_即使同域名`() {
        val text = playlist {
            append(content(8))
            appendLine("#EXT-X-CUE-OUT:30.000")
            appendLine("#EXTINF:10.000,")
            appendLine("ad1.ts")
            appendLine("#EXTINF:10.000,")
            appendLine("ad2.ts")
            appendLine("#EXT-X-CUE-IN")
            append(content(8, 8))
        }
        val out = AdFilter.filter(text, base)
        assertFalse(out.contains("ad1.ts"))
        assertFalse(out.contains("ad2.ts"))
        assertFalse("CUE 标记已无意义，应一并清理", out.contains("#EXT-X-CUE"))
        assertEquals(16, out.lines().count { it.endsWith(".ts") })
    }

    @Test
    fun `路径命中广告关键词的分片会被剔除`() {
        val text = playlist {
            append(content(10))
            appendLine("#EXTINF:12.000,")
            appendLine("http://cdn.example.com/ads/ad_roll_01.ts")
            append(content(6, 10))
        }
        val out = AdFilter.filter(text, base)
        assertFalse(out.contains("ad_roll_01.ts"))
        assertEquals(16, out.lines().count { it.endsWith(".ts") })
    }

    @Test
    fun `正常列表原样返回`() {
        val text = playlist { append(content(12)) }
        assertEquals(text, AdFilter.filter(text, base))
    }

    @Test
    fun `广告占比过高时宁可不过滤`() {
        val text = playlist {
            append(content(4))
            for (i in 0 until 8) {
                appendLine("#EXTINF:15.000,")
                appendLine("http://adserver.other.com/a$i.ts")
            }
        }
        assertEquals("剔除比例过高应放弃过滤", text, AdFilter.filter(text, base))
    }

    @Test
    fun `master 列表不做处理`() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n720p/index.m3u8\n"
        assertEquals(text, AdFilter.filter(text, base))
    }

    @Test
    fun `非 m3u8 内容原样返回`() {
        assertEquals("hello", AdFilter.filter("hello", base))
        assertEquals("", AdFilter.filter("", base))
    }
}
