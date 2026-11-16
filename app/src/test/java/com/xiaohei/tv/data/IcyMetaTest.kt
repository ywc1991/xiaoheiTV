package com.xiaohei.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ICY 元数据解析与剔除。
 *
 * 这块最容易出的不是「显示错」，而是**剔除时算错位**：一旦少剔或多剔一个字节，
 * 后面整条音频流的帧边界就全错，表现是爆音、卡顿或者直接断流。
 * 所以下面既验证能取出标题，也逐字节验证「音频一个不多一个不少」。
 */
class IcyMetaTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    /** 按 ICY 的格式打包一段元数据：长度字节 + N 个 16 字节块（零补齐） */
    private fun metaBlock(title: String?): ByteArray =
        bytes(if (title == null) "" else "StreamTitle='$title';").let { raw ->
            val blocks = (raw.size + 15) / 16
            val out = ByteArray(1 + blocks * 16)
            out[0] = blocks.toByte()
            System.arraycopy(raw, 0, out, 1, raw.size)
            out
        }

    @Test
    fun `能取出歌手与歌名`() {
        val block = metaBlock("许嵩 - 多余的解释")
        assertEquals("许嵩 - 多余的解释", IcyMeta.parseTitle(block, block.size))
    }

    @Test
    fun `末尾补零不影响结果`() {
        val raw = bytes("StreamTitle='周杰伦 - 晴天';")
        val padded = ByteArray(raw.size + 20)
        System.arraycopy(raw, 0, padded, 0, raw.size)
        assertEquals("周杰伦 - 晴天", IcyMeta.parseTitle(padded, padded.size))
    }

    @Test
    fun `没有 StreamTitle 时返回 null`() {
        assertNull(IcyMeta.parseTitle(bytes("StreamUrl='http://x';"), 20))
        assertNull(IcyMeta.parseTitle(ByteArray(0), 0))
    }

    @Test
    fun `占位标题当成没有`() {
        assertNull(IcyMeta.parseTitle(bytes("StreamTitle='(null)';"), 21))
        assertNull(IcyMeta.parseTitle(bytes("StreamTitle='  ';"), 15))
    }

    @Test
    fun `GBK 编码的标题也能解出来`() {
        val raw = "StreamTitle='刘若英 - 后来';".toByteArray(charset("GBK"))
        assertEquals("刘若英 - 后来", IcyMeta.parseTitle(raw, raw.size))
    }

    @Test
    fun `剔除后音频字节一个不多一个不少`() {
        val metaint = 8
        val audio = ByteArray(metaint * 3) { it.toByte() }
        // 音频 8 字节 → 元数据 → 音频 8 字节 → 空元数据块（长度字节为 0）→ 音频 8 字节
        val stream = audio.copyOfRange(0, 8) + metaBlock("A - B") +
            audio.copyOfRange(8, 16) + byteArrayOf(0) +
            audio.copyOfRange(16, 24)

        val stripper = IcyStripper(metaint)
        val out = ByteArray(stream.size)
        val n = stripper.push(stream, stream.size, out)

        assertEquals("音频长度不对", audio.size, n)
        assertTrue("音频内容被改动", out.copyOf(n).contentEquals(audio))
        assertEquals("A - B", stripper.takeTitle())
        assertNull("不应有第二个标题", stripper.takeTitle())
    }

    @Test
    fun `元数据被切在两块缓冲区里也能拼回来`() {
        val metaint = 4
        val block = metaBlock("李健 - 贝加尔湖畔")
        val stream = byteArrayOf(1, 2, 3, 4) + block
        // 故意切在长度字节之后、元数据中间：上游一次只给几十字节时就是这样
        val cut = 5 + block.size / 2
        val stripper = IcyStripper(metaint)

        val first = ByteArray(cut)
        val n1 = stripper.push(stream, cut, first)

        val tail = stream.copyOfRange(cut, stream.size)
        val second = ByteArray(tail.size)
        val n2 = stripper.push(tail, tail.size, second)

        assertEquals("音频应当正好 4 字节", 4, n1 + n2)
        assertTrue(
            "音频内容被改动",
            (first.copyOf(n1) + second.copyOf(n2)).contentEquals(byteArrayOf(1, 2, 3, 4))
        )
        assertEquals("李健 - 贝加尔湖畔", stripper.takeTitle())
    }

    @Test
    fun `中文台常见的零长度元数据块不会吞掉音频`() {
        val metaint = 16
        val audio = ByteArray(64) { (it + 1).toByte() }
        // 每 16 字节音频后面都跟一个「0 长度」标记，这是最常见的形态
        val stream = ByteArray(80)
        System.arraycopy(audio, 0, stream, 0, 16)
        stream[16] = 0
        System.arraycopy(audio, 16, stream, 17, 16)
        stream[33] = 0
        System.arraycopy(audio, 32, stream, 34, 16)
        stream[50] = 0
        System.arraycopy(audio, 48, stream, 51, 16)
        stream[67] = 0
        System.arraycopy(audio, 64, stream, 68, 0)

        val stripper = IcyStripper(metaint)
        val out = ByteArray(stream.size)
        val n = stripper.push(stream, 68, out)
        assertEquals(64, n)
        assertTrue(out.copyOf(n).contentEquals(audio))
        assertNull(stripper.takeTitle())
    }

    @Test
    fun `空标题不会被当成曲目显示成引号分号`() {
        // 蜻蜓那批台真实返回的就是 StreamTitle='';（引号中间什么都没有）。
        // 早先这里会解析出 "';\u0000"，界面上显示「曲目: ';」。
        val block = "StreamTitle='';\u0000\u0000".toByteArray(Charsets.UTF_8)
        assertNull(IcyMeta.parseTitle(block, block.size))
    }

    @Test
    fun `只有标点的标题一律视为没有`() {
        listOf("StreamTitle='- - -';", "StreamTitle='...';", "StreamTitle=\"';\";").forEach { s ->
            val b = (s + "\u0000").toByteArray(Charsets.UTF_8)
            assertNull("$s 不应被当成曲目", IcyMeta.parseTitle(b, b.size))
        }
    }

    @Test
    fun `正常标题仍然照常解析`() {
        val b = "StreamTitle='王泓昊 - 不想说清楚';\u0000".toByteArray(Charsets.UTF_8)
        assertEquals("王泓昊 - 不想说清楚", IcyMeta.parseTitle(b, b.size))
    }
}
