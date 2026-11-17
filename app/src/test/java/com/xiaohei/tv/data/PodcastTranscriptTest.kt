package com.xiaohei.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastTranscriptTest {

    @Test
    fun `真实 VTT 片段解析出时间轴并去掉说话人标记`() {
        val vtt = """
            WEBVTT

            00:00:00.390 --> 00:00:03.295
            <v 说话人 1>本节目由津津乐道制作播出。

            00:00:24.550 --> 00:00:27.020
            <v 说话人 2>这是我们的标志性声音对对哎，

            01:06:50.910 --> 01:06:54.830
            <v 说话人 3>对，想能挣到钱的还好，没挣到钱的那就是光剩败了对对。
        """.trimIndent()
        val cues = PodcastTranscript.parse(vtt)
        assertEquals(3, cues.size)
        assertEquals(390L, cues[0].startMs)
        assertEquals(3295L, cues[0].endMs)
        assertEquals("本节目由津津乐道制作播出。", cues[0].text)
        assertEquals("这是我们的标志性声音对对哎，", cues[1].text)
        assertEquals(4_010_910L, cues[2].startMs)
    }

    @Test
    fun `按播放位置查当前句 空档返回空`() {
        val cues = listOf(
            TranscriptCue(1_000L, 3_000L, "第一句"),
            TranscriptCue(5_000L, 8_000L, "第二句")
        )
        assertNull(PodcastTranscript.cueAt(cues, 500L))
        assertEquals("第一句", PodcastTranscript.cueAt(cues, 2_999L)?.text)
        assertNull(PodcastTranscript.cueAt(cues, 4_000L))
        assertEquals("第二句", PodcastTranscript.cueAt(cues, 7_999L)?.text)
        assertNull(PodcastTranscript.cueAt(cues, 9_000L))
    }

    @Test
    fun `毫秒 1 位 2 位 3 位都不丢精度`() {
        val vtt = """
            WEBVTT

            00:00:01.1 --> 00:00:02.12
            甲

            00:00:03.123 --> 00:00:04.999
            乙
        """.trimIndent()
        val cues = PodcastTranscript.parse(vtt)
        assertEquals(1_100L, cues[0].startMs)
        assertEquals(2_120L, cues[0].endMs)
        assertEquals(3_123L, cues[1].startMs)
    }

    @Test
    fun `NOTE 块 cue 编号与多行文本都能吞`() {
        val vtt = """
            WEBVTT

            NOTE 这是注释，不是字幕

            cue-1
            00:00:01.000 --> 00:00:02.000
            <c.yellow>黄色</c>
            第二行

            00:00:03.000 --> 00:00:04.000
            <v 说话人 1>A &amp; B</v>
        """.trimIndent()
        val cues = PodcastTranscript.parse(vtt)
        assertEquals(2, cues.size)
        assertEquals("黄色 第二行", cues[0].text)
        assertEquals("A & B", cues[1].text)
    }

    @Test
    fun `空文档与畸形时间轴不抛异常`() {
        assertTrue(PodcastTranscript.parse("").isEmpty())
        assertTrue(PodcastTranscript.parse("WEBVTT\n\n不是时间轴\n随便一行").isEmpty())
    }
}
