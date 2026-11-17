package com.xiaohei.tv.data

import com.xiaohei.tv.model.PodcastEpisode
import com.xiaohei.tv.model.PodcastShow
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 播客 RSS 解析（RSS 2.0 + iTunes 扩展）。
 *
 * 刻意不用 XmlPullParser：那是 Android 运行时才有的类，在 JVM 单测里拿不到，
 * 一旦解析写错只能上真机才发现。这里用「按 <item> 切块 + 标签正则」的容错写法，
 * 纯 Kotlin、可单测，而且对国内播客源常见的脏数据（CDATA 混排、HTML 转义、
 * 属性顺序不固定、时长写成 mm:ss 或纯秒数）都能吞下去。
 *
 * 解析失败永远不抛异常：返回空列表，调用方按「这一档暂时取不到」处理。
 */
object PodcastRss {

    /** 单档最多取多少集：够翻很久了，也更省老电视的内存 */
    const val DEFAULT_LIMIT = 40

    /** 断网/超时的时候最多读这么多字符就停：超长 feed 只要最新的那些集 */
    const val MAX_CHARS = 6_000_000

    private val itemRegex = Regex("<item(?:\\s[^>]*)?>(.*?)</item>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val enclosureRegex = Regex("<enclosure\\b[^>]*\\burl\\s*=\\s*\"([^\"]+)\"",
        RegexOption.IGNORE_CASE)
    private val enclosureRegexSingle = Regex("<enclosure\\b[^>]*\\burl\\s*=\\s*'([^']+)'",
        RegexOption.IGNORE_CASE)
    private val tagRegexCache = HashMap<String, Regex>()

    fun parse(xml: String, show: PodcastShow, limit: Int = DEFAULT_LIMIT): List<PodcastEpisode> {
        if (xml.isEmpty() || limit <= 0) return emptyList()
        val out = ArrayList<PodcastEpisode>(minOf(limit, 40))
        for (m in itemRegex.findAll(xml)) {
            if (out.size >= limit) break
            val block = m.groupValues[1]
            val url = audioUrl(block) ?: continue
            val title = clean(text(block, "title")).ifEmpty { "未命名分集" }
            out.add(
                PodcastEpisode(
                    showId = show.id,
                    showName = show.name,
                    title = title,
                    url = url,
                    durationMs = durationMs(clean(text(block, "itunes:duration"))),
                    pubDateMs = pubDateMs(clean(text(block, "pubDate"))),
                    transcriptUrl = transcriptUrl(block)
                )
            )
        }
        return out
    }

    /**
     * WebVTT 字幕地址：<podcast:transcript url="..." type="text/vtt" rel="captions"/>。
     * 有的源同一集给好几条（转写稿 / 多语言），优先 captions + vtt，没有就取第一条 http 的。
     */
    private fun transcriptUrl(block: String): String {
        val tags = Regex("<podcast:transcript\\b[^>]*?/?>", RegexOption.IGNORE_CASE)
            .findAll(block).map { it.value }.toList()
        if (tags.isEmpty()) return ""
        fun attrOf(tag: String, name: String): String {
            Regex("\\b" + Regex.escape(name) + "\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                .find(tag)?.let { return it.groupValues[1] }
            Regex("\\b" + Regex.escape(name) + "\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE)
                .find(tag)?.let { return it.groupValues[1] }
            return ""
        }
        val vtt = tags.filter { attrOf(it, "url").startsWith("http") }
        val pick = vtt.firstOrNull {
            it.contains("captions", ignoreCase = true) && it.contains("vtt", ignoreCase = true)
        } ?: vtt.firstOrNull { it.contains("vtt", ignoreCase = true) }
        ?: vtt.firstOrNull() ?: return ""
        return attrOf(pick, "url")
    }

    /** 音频地址：标准是 <enclosure url="...">，少数源只写了 media:content */
    private fun audioUrl(block: String): String? {
        val enc = enclosureRegex.find(block)?.groupValues?.get(1)
            ?: enclosureRegexSingle.find(block)?.groupValues?.get(1)
            ?: text(block, "media:content")?.let { raw ->
                Regex("url\\s*=\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
            }
        val u = enc?.trim().orEmpty()
        return if (u.startsWith("http://") || u.startsWith("https://")) u else null
    }

    private fun text(block: String, tag: String): String? {
        val re = tagRegexCache.getOrPut(tag) {
            Regex("<" + Regex.escape(tag) + "(?:\\s[^>]*)?>(.*?)</" + Regex.escape(tag) + ">",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        }
        return re.find(block)?.groupValues?.get(1)
    }

    /**
     * 洗成能直接显示的一行文字：去 CDATA 包裹 → 反转义 → 去 HTML 标签 → 收空白。
     * 顺序很重要：先反转义才能把 `&lt;p&gt;` 这种「转义过的标签」也去掉。
     */
    fun clean(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var s = raw.trim()
        if (s.startsWith("<![CDATA[")) s = s.removePrefix("<![CDATA[")
        if (s.endsWith("]]>")) s = s.removeSuffix("]]>")
        s = s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
            .replace("&nbsp;", " ").replace("&amp;", "&")
        s = s.replace(Regex("<[^>]*>"), " ")
        s = s.replace(Regex("\\s+"), " ")
        return s.trim()
    }

    /** iTunes 时长：可能是 "01:02:03"、"42:10"，也可能直接是秒数 */
    fun durationMs(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        if (s.all { it.isDigit() }) return (s.toLongOrNull() ?: 0L) * 1000L
        val parts = s.split(":").map { it.trim().substringBefore('.').toLongOrNull() ?: return 0L }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            else -> 0L
        }
    }

    private val datePatterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss"
    )

    /** 发布时间：格式太杂，逐个试；全都失败就返回 0（列表里不显示日期那一栏） */
    fun pubDateMs(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        for (p in datePatterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                f.isLenient = true
                val d = f.parse(s) ?: continue
                return d.time
            } catch (_: Exception) {
                // 下一个格式
            }
        }
        return 0L
    }
}
