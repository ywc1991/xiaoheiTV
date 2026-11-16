package com.xiaohei.tv.data

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * ICY 内联元数据（Shoutcast / Icecast 那一套）。
 *
 * 老式电台流（mp3 / aacp 直连，不是 HLS 切片）会把文字**插在音频字节中间**：
 * 每 `icy-metaint` 个音频字节后面跟 1 个长度字节（单位 16 字节），再跟那么多元数据，
 * 内容形如 `StreamTitle='歌手 - 歌名';`，末尾用零字节补齐。
 *
 * 所以这里有两件事要做，缺一不可：
 * 1. 把这段文字从音频里**剔除**，否则解码器会把文字当音频喂进去，直接爆音或断流；
 * 2. 顺便把它解析出来，就是「正在播放」。
 *
 * 实测（2026-11-15，23 个台逐个拆流）：只有 891 音乐台带这个元数据，
 * 其余是 HLS 切片流，分片里除了 Apple 时间戳什么都没有。
 * 所以这里的定位是「有就读出来」，读不到由界面显示「直播中」——不编造内容。
 */
object IcyMeta {

    /** 元数据块最大长度：长度字节是 0..255 个 16 字节块 */
    const val MAX_BLOCK = 255 * 16

    private const val KEY = "StreamTitle='"
    private const val MAX_TITLE = 80

    /**
     * 从一段元数据块里取出曲目。取不到返回 null（空块、只有 StreamUrl、内容是 "(null)" 等）。
     */
    fun parseTitle(block: ByteArray, length: Int): String? {
        if (length <= 0) return null
        val text = decode(block, 0, length)
        val at = text.indexOf(KEY)
        if (at < 0) return null
        val from = at + KEY.length
        val to = text.indexOf("';", from)
        // 蜻蜓那一批台发的是 StreamTitle=''; —— 引号中间什么都没有。
        // 早先这里会取到收尾的 "'" 和 ";"，界面上就显示成「曲目: ';」。
        if (to == from) return null
        val raw = if (to > from) text.substring(from, to) else text.substring(from)
        return clean(raw)
    }

    /**
     * ICY 没有规定编码：国内台多数是 UTF-8，少数老设备还在用 GBK。
     * 先严格按 UTF-8 解（解不动会抛异常，不会悄悄变成乱码），失败再退 GBK。
     */
    fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        strict(bytes, offset, length, Charsets.UTF_8)?.let { return it }
        strict(bytes, offset, length, Charset.forName("GBK"))?.let { return it }
        return String(bytes, offset, length, Charsets.ISO_8859_1)
    }

    private fun strict(bytes: ByteArray, offset: Int, length: Int, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes, offset, length))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** 去掉补齐用的零字节与控制字符，并把「等于没写」的标题当成没有 */
    private fun clean(raw: String): String? {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            if (c == '\u0000') break
            if (c.code < 0x20) continue
            sb.append(c)
        }
        val out = sb.toString().trim()
        if (out.length < 2) return null
        val low = out.lowercase()
        if (low == "(null)" || low == "null" || low == "unknown" || low == "n/a") return null
        // 只剩标点（"';"、"- - -"、"...") 等于没写，别把它当成节目名显示出来
        if (out.none { it.isLetterOrDigit() }) return null
        return if (out.length > MAX_TITLE) out.substring(0, MAX_TITLE) else out
    }
}

/**
 * 把 ICY 内联元数据从音频流里剔掉的流式小状态机。
 *
 * 纯 Kotlin、不碰 Android 与网络，单测里可以拿合成流逐字节验证。
 * 调用方每次把上游读到的一块字节交给 [push]，拿回「真正的音频」写进播放器。
 */
class IcyStripper(private val metaint: Int) {

    init {
        require(metaint > 0) { "metaint 必须为正数" }
    }

    /** 当前这块音频已经走了多少字节，走满 metaint 就该切元数据了 */
    private var posInBlock = 0

    /** 元数据块还剩多少字节要吞 */
    private var metaLeft = 0
    private var metaLen = 0
    private val metaBuf = ByteArray(IcyMeta.MAX_BLOCK)
    private var title: String? = null

    /**
     * 过一遍 [length] 个上游字节：音频写进 [dst]，元数据就地解析。
     * 返回写进 [dst] 的音频字节数。要求 `dst.size >= length`，否则多出来的字节会被丢掉。
     */
    fun push(src: ByteArray, length: Int, dst: ByteArray): Int {
        var si = 0
        var di = 0
        while (si < length) {
            if (metaLeft > 0) {
                // 正在吞元数据：整块攒齐再解析，避免标题被切在两块之间
                val take = minOf(metaLeft, length - si)
                System.arraycopy(src, si, metaBuf, metaLen, take)
                metaLen += take
                si += take
                metaLeft -= take
                if (metaLeft == 0) {
                    IcyMeta.parseTitle(metaBuf, metaLen)?.let { title = it }
                    metaLen = 0
                }
                continue
            }
            if (posInBlock == metaint) {
                // 音频走满一轮，这里是长度字节；0 表示这一段没有元数据
                val blocks = src[si].toInt() and 0xFF
                si++
                posInBlock = 0
                if (blocks == 0) continue
                metaLeft = blocks * 16
                metaLen = 0
                continue
            }
            val take = minOf(metaint - posInBlock, length - si)
            System.arraycopy(src, si, dst, di, take)
            si += take
            di += take
            posInBlock += take
        }
        return di
    }

    /** 取走一次解析出的标题（取过就没了） */
    fun takeTitle(): String? = title.also { title = null }
}
