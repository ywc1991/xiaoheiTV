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

    // ---------------------------------------------------------- v2.5.0 新增：节目中间插播

    private fun abs(n: Int, start: Int, host: String, dur: String = "5.000"): String = buildString {
        for (i in start until start + n) {
            appendLine("#EXTINF:$dur,")
            appendLine("$host/seg$i.ts")
        }
    }

    @Test
    fun `插播广告占窗口四成也能整块剔除_旧版少数派阈值会漏`() {
        val text = playlist {
            append(abs(10, 0, "http://cdn.example.com/live"))
            appendLine("#EXT-X-DISCONTINUITY")
            append(abs(4, 100, "http://ad.example.net/spot"))
            appendLine("#EXT-X-DISCONTINUITY")
            append(abs(10, 10, "http://cdn.example.com/live"))
        }
        val out = AdFilter.filter(text, base)
        assertFalse("占 4/24 的异域名广告块应被剔除", out.contains("ad.example.net"))
        assertEquals(20, out.lines().count { it.endsWith(".ts") })
    }

    @Test
    fun `DATERANGE 与 CUE-OUT-CONT 标记的插播广告会被剔除`() {
        val text = playlist {
            append(content(8))
            appendLine("#EXT-X-DATERANGE:ID=\"ad1\",SCTE35-OUT=0xFC30")
            appendLine("#EXT-X-CUE-OUT-CONT:10/10")
            appendLine("#EXTINF:10.000,")
            appendLine("mid1.ts")
            appendLine("#EXTINF:10.000,")
            appendLine("mid2.ts")
            appendLine("#EXT-X-CUE-IN")
            append(content(8, 8))
        }
        val out = AdFilter.filter(text, base)
        assertFalse(out.contains("mid1.ts"))
        assertFalse(out.contains("mid2.ts"))
        assertFalse("SCTE35 / CUE 标记应一并清理", out.contains("SCTE35") || out.contains("CUE"))
        assertEquals(16, out.lines().count { it.endsWith(".ts") })
    }

    @Test
    fun `同域名但断点加时长突变的连续广告块会被剔除`() {
        val text = playlist {
            append(content(8))
            appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXTINF:20.000,")
            appendLine("insert1.ts")
            appendLine("#EXTINF:20.000,")
            appendLine("insert2.ts")
            appendLine("#EXT-X-DISCONTINUITY")
            append(content(8, 8))
        }
        val out = AdFilter.filter(text, base)
        assertFalse(out.contains("insert1.ts"))
        assertFalse(out.contains("insert2.ts"))
        assertEquals(16, out.lines().count { it.endsWith(".ts") })
    }

    @Test
    fun `单片时长突变不误删_正片跳变也可能出现`() {
        val text = playlist {
            append(content(8))
            appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXTINF:20.000,")
            appendLine("jump.ts")
            appendLine("#EXT-X-DISCONTINUITY")
            append(content(8, 8))
        }
        assertEquals("弱信号必须成块才动手", text, AdFilter.filter(text, base))
    }

    @Test
    fun `双 CDN 交替负载均衡不误删`() {
        val text = playlist {
            for (i in 0 until 10) {
                val host = if (i % 2 == 0) "http://cdn.example.com/live" else "http://mirror.example.com/live"
                appendLine("#EXTINF:5.000,")
                appendLine("$host/seg$i.ts")
            }
        }
        assertEquals(text, AdFilter.filter(text, base))
    }

    @Test
    fun `保留正片总时长不足时放弃过滤_保护起播缓冲`() {
        val text = playlist {
            append(abs(4, 0, "http://cdn.example.com/live", "0.200"))
            appendLine("#EXTINF:5.000,")
            appendLine("http://cdn.example.com/ads/a1.ts")
            appendLine("#EXTINF:5.000,")
            appendLine("http://cdn.example.com/ads/a2.ts")
        }
        assertEquals("剩下 0.8 秒正片不够起播，宁可看广告", text, AdFilter.filter(text, base))
    }

    @Test
    fun `后台下发的规则立即生效`() {
        try {
            assertEquals(2, AdRules.update("host:myad.example;token:/spot/"))
            val text = playlist {
                append(content(8))
                appendLine("#EXTINF:5.000,")
                appendLine("http://cdn.example.com/spot/s1.ts")
                appendLine("#EXTINF:5.000,")
                appendLine("http://cdn.example.com/spot/s2.ts")
                append(content(6, 8))
            }
            val out = AdFilter.filter(text, base)
            assertFalse(out.contains("/spot/"))
            assertEquals(14, out.lines().count { it.endsWith(".ts") })
        } finally {
            AdRules.update("clear")
        }
    }

    @Test
    fun `解析耗时与命中数会进统计`() {
        AdRules.resetStats()
        AdFilter.filter(playlist { append(content(12)) }, base)
        assertTrue(AdRules.playlists.get() >= 1L)
        assertTrue(AdRules.snapshot().contains("playlists="))
        assertTrue(AdRules.snapshot().contains("costUs"))
    }

    @Test
    fun `单次清单解析耗时在微秒级_不拖慢切台`() {
        val text = playlist { append(content(20)) }
        repeat(30) { AdFilter.filter(text, base) }   // 预热
        AdRules.resetStats()
        repeat(300) { AdFilter.filter(text, base) }
        val avg = AdRules.totalCostUs.get() / 300L
        // JVM 上有 GC / JIT 抖动，1ms 的门槛仍比一帧 16ms 小一个数量级
        assertTrue("平均 ${avg}us，应远小于一帧 16ms", avg < 1_000L)
    }

    @Test
    fun `畸形清单不抛异常_原样放行`() {
        // 免费源的清单什么格式都有：非法时长、坏掉的 URI、异常 base、超长文本。
        // 这些跑在播放器加载线程，抛出去就是整机闪退，必须一律原样放行。
        val weird = listOf(
            "#EXTM3U\n#EXTINF:not-a-number,\nhttp://[bad/a.ts\nhttp://[bad/b.ts\nhttp://[bad/c.ts\n",
            "#EXTM3U\n#EXTINF:1e309,\nhttp://a.example/b.ts\n#EXTINF:-5,\n" +
                "http://a.example/c.ts\n#EXTINF:5,\nhttp://a.example/d.ts\n",
            "#EXTM3U\n" + "#EXT-X-CUE-OUT:30\n".repeat(200),
            "#EXTM3U\n#EXTINF:5,\n\u0000\u0000\u0000\n#EXTINF:5,\nb.ts\n#EXTINF:5,\nc.ts\n"
        )
        for (text in weird) {
            val out = AdFilter.filter(text, "http://[invalid-base")
            assertTrue("异常输入不该被吞成空内容", out.isNotEmpty())
        }
        // 超过 256KB 的异常清单直接放行，不解析、不占内存
        val huge = "#EXTM3U\n" + "X".repeat(300_000)
        assertEquals(huge, AdFilter.filter(huge, base))
    }
}
