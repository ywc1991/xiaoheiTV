package com.xiaohei.tv.data

/**
 * WebVTT 字幕解析：把一条条「时间轴 + 文本」洗成可以直接按播放位置查的结构。
 *
 * 为什么不用 Media3 的字幕轨：播客是纯音频，字幕在 RSS 的 podcast:transcript 里，
 * 不在音频容器里；要走播放器字幕轨得自己塞一个 ExternalSubtitleSource，
 * 还要处理音轨禁用时字幕不渲染的问题。直接解析 + 跟播放器进度查表更简单可靠，
 * 一行代码就能在任何时候（快进、续播、后台恢复）读到当前句。
 *
 * 容错优先：VTT 里的 <v 说话人 1>、<c.color>、NOTE、cue id 都能吞，解析不抛异常。
 */
data class TranscriptCue(val startMs: Long, val endMs: Long, val text: String)

object PodcastTranscript {

    /** 00:00:03.390 或 00:03.390（后者是 WebVTT 允许的 MM:SS.mmm） */
    private val timeRegex = Regex(
        """(\d{1,3}):(\d{2})(?::(\d{2}))?[.,](\d{1,3})\s*-->\s*(\d{1,3}):(\d{2})(?::(\d{2}))?[.,](\d{1,3})"""
    )
    private val tagRegex = Regex("<[^>]+>")

    fun parse(vtt: String): List<TranscriptCue> {
        if (vtt.isEmpty()) return emptyList()
        val out = ArrayList<TranscriptCue>(256)
        val lines = vtt.lineSequence().iterator()
        var start = -1L
        var end = -1L
        val text = StringBuilder()

        fun flush() {
            if (start >= 0L && end > start) {
                val s = clean(text.toString())
                if (s.isNotEmpty()) out.add(TranscriptCue(start, end, s))
            }
            start = -1L
            end = -1L
            text.setLength(0)
        }

        while (lines.hasNext()) {
            val line = lines.next()
            val m = timeRegex.find(line)
            if (m != null) {
                flush()
                start = ms(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
                end = ms(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
                continue
            }
            if (start < 0L) continue
            if (line.isBlank()) {
                flush()
            } else {
                if (text.isNotEmpty()) text.append(' ')
                text.append(line.trim())
            }
        }
        flush()
        return out
    }

    /**
     * 取当前该显示的字幕。用二分而不是每帧遍历：一集 VTT 可以有几百条 cue，
     * 进度每秒刷一次，线性查找虽然也能跑，但没必要。
     * 相邻 cue 之间有空档（比如片头音乐）时返回 null，界面把字幕藏起来。
     */
    fun cueAt(cues: List<TranscriptCue>, posMs: Long): TranscriptCue? {
        if (cues.isEmpty() || posMs < 0L) return null
        var lo = 0
        var hi = cues.lastIndex
        var found: TranscriptCue? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            if (c.startMs <= posMs) {
                found = c
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found?.takeIf { posMs < it.endMs }
    }

    private fun ms(g1: String, g2: String, g3: String, frac: String): Long {
        val h = if (g3.isEmpty()) 0L else g1.toLongOrNull() ?: 0L
        val m = (if (g3.isEmpty()) g1 else g2).toLongOrNull() ?: 0L
        val s = (if (g3.isEmpty()) g2 else g3).toLongOrNull() ?: 0L
        // .1 表示 100ms、.12 表示 120ms、.123 表示 123ms；
        // 用字符串补齐而不是乘浮点数，避免 0.29 * 1000 被截成 289
        val millis = (frac.replace(',', '.').padEnd(3, '0').take(3).toIntOrNull() ?: 0)
            .toLong().coerceIn(0L, 999L)
        return ((h * 3600 + m * 60 + s) * 1000 + millis).coerceAtLeast(0L)
    }

    /** 去 <v ...> 说话人标记、去 HTML 标签与实体、收空白（中文之间不留多余空格） */
    private fun clean(raw: String): String {
        var s = tagRegex.replace(raw, "")
        s = s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
            .replace("&nbsp;", " ").replace("&amp;", "&")
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
