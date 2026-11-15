package com.xiaohei.tv.data

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远端播客目录的取用：磁盘缓存 + 多源拉取。
 *
 * 为什么用 GitHub 放这份文件：App 里已经有一条走通的 GitHub 链路（检查更新），
 * 而国内直连 `raw.githubusercontent.com` / jsDelivr 基本都是握手超时——
 * 真正稳的是 `api.github.com`（Contents 接口带 `Accept: ...raw` 直接吐文件内容）。
 * 所以主源走接口，另外挂两个镜像兜底，全都不通用旧缓存。
 *
 * 刷新策略：每次启动都拉一次，但 30 分钟内不重复拉（GitHub 匿名接口是 60 次/小时，
 * 电视反复开关机很容易撞上限）；只有在拿到**能解析**的内容时才覆盖缓存，
 * 半截文件、错误页、被劫持的 HTML 都进不了缓存。
 */
class PodcastDirStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, CACHE_NAME)

    /** 读本地缓存并应用。同步执行，几 KB 的文本，够快，可以放在建列表之前 */
    fun applyCache(): Boolean {
        val text = runCatching { if (file.exists()) file.readText(Charsets.UTF_8) else null }
            .getOrNull() ?: return false
        val dir = PodcastDir.parse(text) ?: return false
        return PodcastCatalog.apply(dir)
    }

    /**
     * 拉远端目录并应用。返回 true 表示节目单变了。
     * 网络失败、内容坏了、30 分钟内刚拉过，都返回 false（用当前生效的清单继续）。
     */
    fun refresh(force: Boolean = false): Boolean {
        if (!force && file.exists() && System.currentTimeMillis() - file.lastModified() < TTL_MS) {
            return false
        }
        val text = fetch() ?: return false
        val dir = PodcastDir.parse(text) ?: run {
            Log.w(TAG, "远端目录解析失败，保持现有清单")
            return false
        }
        runCatching { file.writeText(text, Charsets.UTF_8) }
        val changed = PodcastCatalog.apply(dir)
        Log.i(TAG, "远端目录 v${dir.version} ${dir.shows.size} 档 · 变化=$changed")
        return changed
    }

    private data class Source(val url: String, val accept: String)

    private fun sources(): List<Source> {
        val rawPath = "https://raw.githubusercontent.com/$OWNER_REPO/main/$CACHE_NAME"
        return listOf(
            // 主源：Contents 接口返回原始文件内容。国内实测最稳的一条
            Source("https://api.github.com/repos/$OWNER_REPO/contents/$CACHE_NAME", "application/vnd.github.raw"),
            Source("https://ghproxy.net/$rawPath", ""),
            Source("https://gh-proxy.com/$rawPath", ""),
            Source("https://cdn.jsdelivr.net/gh/$OWNER_REPO@main/$CACHE_NAME", ""),
            Source(rawPath, "")
        )
    }

    private fun fetch(): String? {
        for (s in sources()) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(s.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    if (s.accept.isNotEmpty()) setRequestProperty("Accept", s.accept)
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "目录源 http=$code ${s.url}")
                    continue
                }
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    val sb = StringBuilder(4096)
                    val buf = CharArray(4096)
                    while (sb.length < MAX_CHARS) {
                        val n = r.read(buf)
                        if (n <= 0) break
                        sb.append(buf, 0, n)
                    }
                    sb.toString()
                }
                // 镜像挂了常回一个 HTML 错误页，先看形状再交给解析器
                if (!text.trimStart().startsWith("{")) {
                    Log.w(TAG, "目录源返回的不是 JSON：${s.url}")
                    continue
                }
                return text
            } catch (e: Exception) {
                Log.w(TAG, "目录源失败 ${s.url}：${e.message}")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return null
    }

    private companion object {
        const val OWNER_REPO = "ywc1991/xiaoheiTV"
        const val CACHE_NAME = "podcasts.json"
        const val TTL_MS = 30 * 60 * 1000L
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_CHARS = 64 * 1024
        const val UA = "XiaoHeiTV/2.10 (Android TV)"
        const val TAG = "XiaoHeiTV-podcast"
    }
}
