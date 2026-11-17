package com.xiaohei.tv.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 单集 VTT 字幕仓库：内存 → 磁盘 → 网络。
 *
 * 字幕文件按 URL 存本地，一集一般几十 KB，听过的集下次直接命中磁盘；
 * 请求失败就退回旧文件，字幕只是锦上添花，绝不能因为下载失败影响音频播放。
 * 解析和下载都发生在这里，调用方只需在后台线程拿结果、回主线程刷界面。
 */
class TranscriptStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "podvtt")
    private val memory = HashMap<String, List<TranscriptCue>>()

    /** 返回空列表 = 这一集没有可用字幕；调用方据此隐藏字幕视图 */
    fun cues(url: String): List<TranscriptCue> {
        if (url.isEmpty()) return emptyList()
        memory[url]?.let { return it }
        val file = cacheFile(url)
        val cached = if (file.exists() && fresh(file)) read(file) else null
        if (cached != null) {
            memory[url] = cached
            return cached
        }
        val downloaded = download(url)
        if (downloaded != null) {
            val parsed = PodcastTranscript.parse(downloaded)
            if (parsed.isNotEmpty()) {
                memory[url] = parsed
                runCatching {
                    if (!dir.exists()) dir.mkdirs()
                    file.writeText(downloaded)
                }
                return parsed
            }
        }
        // 网络挂了但本地有过期字幕，照样能显示
        val stale = if (file.exists()) read(file) else null
        if (stale != null) memory[url] = stale
        return stale ?: emptyList()
    }

    private fun cacheFile(url: String) = File(dir, Integer.toHexString(url.hashCode()) + ".vtt")

    private fun fresh(file: File) =
        System.currentTimeMillis() - file.lastModified() < TTL_MS

    private fun read(file: File): List<TranscriptCue>? = runCatching {
        PodcastTranscript.parse(file.readText(Charsets.UTF_8)).takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun download(url: String): String? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/vtt,text/plain,*/*;q=0.8")
            setRequestProperty("Accept-Encoding", "identity")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                val sb = StringBuilder(64 * 1024)
                val buf = CharArray(8192)
                while (sb.length < MAX_CHARS) {
                    val n = r.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }.takeIf { it.contains("-->") }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private companion object {
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_CHARS = 2_000_000
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000
        const val UA = "XiaoHeiTV/2.15 (Android TV)"
    }
}
