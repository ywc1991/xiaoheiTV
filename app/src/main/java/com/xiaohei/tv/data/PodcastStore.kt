package com.xiaohei.tv.data

import android.content.Context
import com.xiaohei.tv.model.PodcastEpisode
import com.xiaohei.tv.model.PodcastShow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 播客分集仓库：RSS 拉取 + 本地缓存。
 *
 * 三层缓存，依次命中：
 *  1. 内存（本次运行打开过的档，切换回来是 0 延迟）
 *  2. 磁盘 TSV（12 小时内直接用，不必再拉几 MB 的 XML）
 *  3. 网络（都过期或没有缓存时才拉）
 *
 * 网络失败时**退回旧缓存**：播客断网时不该变成一片空白，上次能听的列表照样能听。
 * TSV 而不是 JSON/CBOR：分集就是几列文本，自己写自己读，不必为了缓存引一个序列化库。
 * 缓存首行带格式版本（#feed\tv2\t）：加字幕列以后旧缓存必须重拉一次才有字幕地址。
 */
class PodcastStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "podfeed")
    private val memory = HashMap<String, List<PodcastEpisode>>()

    /** 只读内存缓存，用来判断「能不能不转圈直接渲染」 */
    fun memory(id: String): List<PodcastEpisode>? = memory[id]

    fun episodes(show: PodcastShow, force: Boolean = false): List<PodcastEpisode> {
        if (!force) memory[show.id]?.let { return it }

        val cached = readCache(show)
        if (!force && cached != null && fresh(show.id)) {
            memory[show.id] = cached
            return cached
        }

        // 远端节目单可能还写着已失效的 xyzfm 地址；依次试候选源，
        // 第一档拿到分集就用它，并把实际成功的 feed 记进缓存，避免下次再踩空。
        var usedFeed = show.feed
        var remote: List<PodcastEpisode>? = null
        for (feed in PodcastCatalog.feedCandidates(show)) {
            val got = runCatching { fetch(show, feed) }.getOrNull()
            if (!got.isNullOrEmpty()) {
                remote = got
                usedFeed = feed
                break
            }
        }
        if (!remote.isNullOrEmpty()) {
            memory[show.id] = remote
            runCatching { writeCache(show.id, remote, usedFeed) }
            return remote
        }

        // 拉不到就用旧的（哪怕过期），总比空列表强
        val fallback = cached ?: emptyList()
        if (fallback.isNotEmpty()) memory[show.id] = fallback
        return fallback
    }

    private fun cacheFile(id: String) = File(dir, "$id.tsv")

    private fun fresh(id: String): Boolean {
        val f = cacheFile(id)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS
    }

    private fun readCache(show: PodcastShow): List<PodcastEpisode>? {
        val id = show.id
        val f = cacheFile(id)
        if (!f.exists()) return null
        return runCatching {
            val lines = f.readLines()
            // 首行记着这份缓存属于哪个 feed：远端目录改了地址以后，
            // 旧缓存里的分集已经不成立了，不能接着当「这一档的最新 40 集」用
            val head = lines.firstOrNull().orEmpty()
            val cachedFeed = head.removePrefix(FEED_TAG).trim()
            if (!head.startsWith(FEED_TAG) ||
                cachedFeed !in PodcastCatalog.feedCandidates(show)
            ) {
                return@runCatching null
            }
            lines.drop(1).mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 4) return@mapNotNull null
                val title = unescape(p[0])
                val url = p[1]
                if (title.isEmpty() || !url.startsWith("http")) return@mapNotNull null
                PodcastEpisode(
                    showId = id,
                    showName = show.name,
                    title = title,
                    url = url,
                    durationMs = p[2].toLongOrNull() ?: 0L,
                    pubDateMs = p[3].toLongOrNull() ?: 0L,
                    transcriptUrl = p.getOrNull(4).orEmpty()
                )
            }
        }.getOrNull()
    }

    private fun writeCache(id: String, list: List<PodcastEpisode>, feed: String) {
        if (list.isEmpty()) return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val sb = StringBuilder(list.size * 96)
            sb.append(FEED_TAG).append(feed.replace('\t', ' ').replace('\n', ' ')).append('\n')
            for (e in list) {
                sb.append(escape(e.title)).append('\t')
                    .append(e.url).append('\t')
                    .append(e.durationMs).append('\t')
                    .append(e.pubDateMs).append('\t')
                    .append(e.transcriptUrl).append('\n')
            }
            cacheFile(id).writeText(sb.toString())
        }
    }

    private fun escape(s: String): String =
        s.replace("\t", " ").replace("\n", " ").replace("\r", " ")

    private fun unescape(s: String): String = s.trim()

    private fun fetch(show: PodcastShow, feed: String): List<PodcastEpisode> {
        val conn = (URL(feed).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/rss+xml, application/xml;q=0.9, */*;q=0.8")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            if (conn.responseCode !in 200..299) return emptyList()
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                val sb = StringBuilder(1 shl 19)
                val buf = CharArray(8192)
                while (sb.length < PodcastRss.MAX_CHARS) {
                    val n = r.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            return BookOrder.apply(
                isBook = show.isBook,
                list = PodcastRss.parse(text, show, show.maxEpisodes)
            ).map { it.copy(showName = show.name) }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private companion object {
        const val TTL_MS = 12 * 60 * 60 * 1000L
        /** 缓存首行的来源标记，用来挡住「换了 feed 还在用旧分集」 */
        const val FEED_TAG = "#feed\tv2\t"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000
        const val UA = "XiaoHeiTV/2.9 (Android TV)"
    }
}
