package com.xiaohei.tv.player

import android.util.Log
import java.net.URI

/**
 * HLS 播放列表广告过滤器。
 *
 * 只处理「媒体播放列表」（含分片的 m3u8）；master 播放列表（多码率索引）原样放行，
 * 等播放器去拉各码率的子列表时再逐个过滤。
 *
 * 判定为广告分片的依据（命中任一即剔除）：
 *   1. 位于 #EXT-X-CUE-OUT / #EXT-X-CUE-IN 区间内 —— 源的 SCTE-35 广告标记
 *   2. 分片域名与「主内容域名」不同，且该域名分片数占比很低 —— 广告常由另一台服务器插入
 *   3. 分片路径命中广告关键词（/ad/、/ads/、preroll、midroll、promo…）
 *
 * 安全阀：剔除比例过高或剔完不剩分片时，放弃过滤、原样返回，宁可看广告也不让画面没了。
 */
object AdFilter {

    private const val TAG = "XiaoHeiTV"

    /** 路径中出现这些「独立词段」基本可确定是广告分片 */
    private val AD_TOKEN = Regex(
        "(^|[/_.-])(ad|ads|adv|advert|adverts|adserver|adsystem|preroll|midroll|mid-roll|pre-roll|" +
            "promo|promos|sponsor|guanggao)([/_.-]|$)",
        RegexOption.IGNORE_CASE
    )

    /** 广告区间标记，过滤后不再需要，输出时丢弃 */
    private val CUE_TAGS = listOf(
        "#EXT-X-CUE-OUT", "#EXT-X-CUE-IN", "#EXT-OATCLS-SCTE35", "#EXT-X-SCTE35"
    )

    private data class Seg(
        val tags: List<String>,
        val uriLine: String,
        val uri: URI?,
        val inCue: Boolean
    )

    /**
     * @param playlist 原始 m3u8 文本
     * @param baseUrl  播放列表自身的地址（用于把相对分片地址补全）
     * @return 过滤后的文本；未过滤时原样返回同一字符串
     */
    fun filter(playlist: String, baseUrl: String?): String {
        if (playlist.isEmpty() || !playlist.startsWith("#EXTM3U")) return playlist
        // master 列表只包含各码率的子列表地址，不含广告分片
        if (playlist.contains("#EXT-X-STREAM-INF")) return playlist

        val lines = playlist.split('\n').map { it.trimEnd('\r').trim() }.filter { it.isNotEmpty() }
        val base = parse(baseUrl)

        val header = ArrayList<String>()
        var headerDone = false
        val segs = ArrayList<Seg>()
        var pending = ArrayList<String>()
        var cue = 0

        for (line in lines) {
            if (!line.startsWith("#")) {
                segs.add(Seg(pending.toList(), line, resolve(base, line), cue > 0))
                pending = ArrayList()
                continue
            }
            // 第一条 #EXTINF 之前的标签属于播放列表头（#EXTM3U / VERSION / MEDIA-SEQUENCE…）
            if (line.startsWith("#EXTINF") && !headerDone) {
                header.addAll(pending)
                pending.clear()
                headerDone = true
            }
            if (line.startsWith("#EXT-X-CUE-OUT")) {
                cue++
            } else if (line.startsWith("#EXT-X-CUE-IN")) {
                if (cue > 0) cue--
            }
            pending.add(line)
        }

        // 分片太少不判断，避免误杀正常短列表
        if (segs.size < 3) return playlist
        if (header.isEmpty()) header.add("#EXTM3U")

        // 统计各域名出现次数，多数派即为「主内容域名」
        val hostCount = HashMap<String, Int>()
        for (s in segs) {
            val h = s.uri?.host ?: continue
            hostCount[h] = (hostCount[h] ?: 0) + 1
        }
        val mainHost = hostCount.entries.maxByOrNull { it.value }?.key

        var removed = 0
        val kept = ArrayList<Seg>(segs.size)
        var sample: String? = null
        for (s in segs) {
            val h = s.uri?.host
            val path = s.uri?.path ?: ""
            val minorityHost = h != null && mainHost != null && h != mainHost &&
                (hostCount[h] ?: 0) * 4 <= segs.size
            val ad = s.inCue || minorityHost || AD_TOKEN.containsMatchIn(path)
            if (ad) {
                removed++
                if (sample == null) sample = s.uriLine
            } else {
                kept.add(s)
            }
        }

        // 安全阀：没删说明没广告；删太多（≥60%）或删空说明规则失灵，原样放行
        if (removed == 0) return playlist
        if (kept.isEmpty() || removed * 5 >= segs.size * 3) {
            Log.w(TAG, "adFilter 放弃：removed=$removed/${segs.size}（比例过高）")
            return playlist
        }

        val sb = StringBuilder(playlist.length)
        for (h in header) if (!isCueTag(h)) sb.append(h).append('\n')
        for (s in kept) {
            for (t in s.tags) if (!isCueTag(t)) sb.append(t).append('\n')
            sb.append(s.uriLine).append('\n')
        }

        Log.i(TAG, "adFilter 生效：removed=$removed/${segs.size} mainHost=$mainHost 例=$sample")
        return sb.toString()
    }

    private fun isCueTag(line: String): Boolean =
        CUE_TAGS.any { line.startsWith(it) } ||
            (line.startsWith("#EXT-X-DATERANGE") && line.contains("SCTE35", ignoreCase = true))

    private fun parse(url: String?): URI? {
        if (url.isNullOrBlank()) return null
        return try {
            URI(url)
        } catch (_: Exception) {
            null
        }
    }

    /** 把相对分片地址补全成绝对地址 */
    private fun resolve(base: URI?, ref: String): URI? {
        val r = try {
            if (base == null) URI(ref) else base.resolve(ref)
        } catch (_: Exception) {
            return null
        }
        return if (r.host != null) r else null
    }
}
