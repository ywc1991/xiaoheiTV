package com.xiaohei.tv.player

import android.util.Log
import java.net.URI

/**
 * HLS 播放列表广告过滤器 —— 针对「节目中间插播」。
 *
 * 只处理媒体播放列表（含分片的 m3u8）；master 列表原样放行，等播放器拉子列表时再逐个过滤。
 *
 * 与旧版的区别：不再逐片看「这个域名占比低不低」，而是先找出**被正片夹在中间的连续异质块**
 * 再整块剔除。节目中间插播时广告往往占窗口三四成，旧版 25% 的少数派阈值会整个漏掉；
 * 成块判定既能抓到它，又不会把 CDN 负载均衡的零散异域名分片误删。
 *
 * 判定信号（强信号可单片成立，弱信号必须连续成块 ≥2 片）：
 *   - 强：CUE-OUT/IN 区间（含 DATERANGE/SCTE35）、分片路径广告词、广告域名、下发规则命中、
 *         以及占比 ≤25% 的少数派异域名
 *   - 弱：占比 ≤50% 的其他异域名、或 DISCONTINUITY + 分片时长明显偏离正片中位数
 *
 * 三道安全阀，宁可看广告也不让画面没了：删除 ≤50% 且 <60%、至少留 2 个分片、
 * 保留正片总时长 ≥1.5 秒（直播起播门槛只有 300ms，留足余量，不拖慢切台）。
 *
 * 全程只做 O(n) 线性扫描（n 通常 3~20），无 IO、无网络、无额外线程；计时写进统计，
 * 后台可用 logcat 或 BenchReceiver 的 stats 动作查看。
 */
object AdFilter {

    private const val TAG = "XiaoHeiTV"
    private const val MIN_KEPT_MS = 1_500L
    private const val MIN_KEPT_SEGS = 2
    /** 直播清单通常几 KB；异常大的文本直接放行，避免解析拖住加载线程 */
    private const val MAX_PLAYLIST_CHARS = 256 * 1024

    /** 路径中出现这些「独立词段」基本可确定是广告分片 */
    private val AD_TOKEN = Regex(
        "(^|[/_.-])(ad|ads|adv|advert|adverts|adserver|adsystem|preroll|midroll|mid-roll|pre-roll|" +
            "promo|promos|sponsor|guanggao)([/_.-]|$)",
        RegexOption.IGNORE_CASE
    )

    /** 域名用的词更保守：只认明确属于广告服务的名字，避免把正常 CDN 里的 ad 字样误杀 */
    private val AD_HOST = Regex(
        "(^|[._-])(adserver|adsystem|adservice|adnetwork|doubleclick|googlesyndication|adnxs|guanggao)([._-]|$)",
        RegexOption.IGNORE_CASE
    )

    /** 广告区间标记，过滤后不再需要，输出时丢弃 */
    private val CUE_TAGS = listOf(
        "#EXT-X-CUE-OUT", "#EXT-X-CUE-IN", "#EXT-X-CUE-OUT-CONT",
        "#EXT-OATCLS-SCTE35", "#EXT-X-SCTE35", "#EXT-X-SCTE35-OUT", "#EXT-X-ASSET"
    )

    private data class Seg(
        val tags: List<String>,
        val uriLine: String,
        val uri: URI?,
        val durationMs: Long,
        val inCue: Boolean,
        val disc: Boolean,
        val host: String?,
        val path: String
    )

    /** reason=null 表示正片；strong=true 表示这个信号单独就能成立 */
    private data class Verdict(val reason: String?, val strong: Boolean)

    /**
     * 冷启动预热：ART 上首次调用会触发正则编译与一堆类加载（实测 10~18ms）。
     * 在 Activity 启动时用一段假清单跑一次，把这笔开销挪出播放/切台链路；
     * 走 filterInner 而不是 filter，因此不会污染统计、也不打日志。
     */
    fun warmUp() {
        runCatching {
            filterInner(
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:5.000,\na.ts\n" +
                    "#EXTINF:5.000,\nb.ts\n#EXTINF:5.000,\nc.ts\n",
                "http://warmup.invalid/index.m3u8"
            )
        }
    }

    /**
     * @param playlist 原始 m3u8 文本
     * @param baseUrl  播放列表自身的地址（用于把相对分片地址补全）
     * @return 过滤后的文本；未过滤时返回内容完全一致的原文
     */
    fun filter(playlist: String, baseUrl: String?): String {
        val t0 = System.nanoTime()
        try {
            return try {
                filterInner(playlist, baseUrl)
            } catch (t: Throwable) {
                // 这段跑在 ExoPlayer 的加载线程上，抛出去会直接掀掉整个进程。
                // 畸形清单、厂商 ROM 的 URI 实现差异、异常大文本都可能触发；
                // 宁可原样放行（顶多看到广告），也不能让切台变成闪退。
                Log.w(TAG, "adFilter 异常放行 ${t.javaClass.simpleName}:${t.message}")
                playlist
            }
        } finally {
            // 统计本身也不能把原始异常覆盖掉
            runCatching {
                val costUs = (System.nanoTime() - t0) / 1000L
                // 30 秒一条，双槽预播同时刷清单也不会把 logcat 冲掉
                if (AdRules.noteCost(costUs)) {
                    Log.d(TAG, "adFilter diag ${AdRules.snapshot()}")
                }
            }
        }
    }

    private fun filterInner(playlist: String, baseUrl: String?): String {
        if (playlist.isEmpty() || !playlist.startsWith("#EXTM3U")) return playlist
        // master 列表只包含各码率的子列表地址，不含广告分片
        if (playlist.contains("#EXT-X-STREAM-INF")) return playlist
        if (playlist.length > MAX_PLAYLIST_CHARS) return playlist

        val lines = ArrayList<String>(playlist.length / 40 + 8)
        for (raw in playlist.split('\n')) {
            val t = raw.trimEnd('\r').trim()
            if (t.isNotEmpty()) lines.add(t)
        }
        val base = parse(baseUrl)

        val header = ArrayList<String>()
        var headerDone = false
        val segs = ArrayList<Seg>()
        var pending = ArrayList<String>()
        var cue = 0

        for (line in lines) {
            if (!line.startsWith("#")) {
                var dur = 0L
                var disc = false
                for (t in pending) {
                    if (t.startsWith("#EXTINF", ignoreCase = true)) {
                        val v = t.substringAfter(':', "").substringBefore(',').trim().toDoubleOrNull()
                        if (v != null && v > 0) dur = (v * 1000).toLong()
                    }
                    if (t.startsWith("#EXT-X-DISCONTINUITY")) disc = true
                }
                val u = resolve(base, line)
                segs.add(Seg(pending.toList(), line, u, dur, cue > 0, disc, u?.host, u?.path ?: ""))
                pending = ArrayList()
                continue
            }
            // 第一条 #EXTINF 之前的标签属于播放列表头（#EXTM3U / VERSION / MEDIA-SEQUENCE…）
            if (line.startsWith("#EXTINF") && !headerDone) {
                header.addAll(pending)
                pending.clear()
                headerDone = true
            }
            when {
                line.startsWith("#EXT-X-CUE-OUT") && !line.startsWith("#EXT-X-CUE-OUT-CONT") -> cue++
                line.startsWith("#EXT-X-CUE-IN") -> if (cue > 0) cue--
                line.startsWith("#EXT-X-DATERANGE") && line.contains("SCTE35", ignoreCase = true) ->
                    if (line.contains("SCTE35-IN", ignoreCase = true)) {
                        if (cue > 0) cue--
                    } else cue++
            }
            pending.add(line)
        }

        // 分片太少不判断，避免误杀正常短列表
        if (segs.size < 3) return playlist
        if (header.isEmpty()) header.add("#EXTM3U")

        // 统计各域名出现次数，多数派即为「主内容域名」
        val hostCount = HashMap<String, Int>()
        for (s in segs) {
            val h = s.host ?: continue
            hostCount[h] = (hostCount[h] ?: 0) + 1
        }
        val mainHost = hostCount.entries.maxByOrNull { it.value }?.key
        val mainCount = mainHost?.let { hostCount[it] ?: 0 } ?: 0
        if (mainHost != null && mainCount < 2) return playlist

        val durations = segs.map { it.durationMs }.filter { it > 0 }.sorted()
        val median = if (durations.isEmpty()) 0L else durations[durations.size / 2]

        val verdicts = Array(segs.size) { i ->
            val s = segs[i]
            val foreign = s.host != null && mainHost != null && s.host != mainHost
            val minority = foreign && (hostCount[s.host] ?: 0) * 4 <= segs.size
            val timing = median > 0 && s.durationMs > 0 &&
                (s.durationMs >= median * 2 || s.durationMs * 2 <= median)
            // 空 host / 空 path 直接短路，别让两次正则白跑（直播清单每 10 秒刷一次 × 3 个播放器）
            val pathHit = s.path.isNotEmpty() && AD_TOKEN.containsMatchIn(s.path)
            val hostHit = !s.host.isNullOrEmpty() && AD_HOST.containsMatchIn(s.host)
            when {
                s.inCue -> Verdict("cue", true)
                pathHit || hostHit || AdRules.tokenHit(s.path) -> Verdict("path", true)
                AdRules.hostBlocked(s.host) -> Verdict("host", true)
                minority -> Verdict("foreign", true)
                foreign -> Verdict("foreign", false)
                timing -> Verdict("timing", false)
                else -> Verdict(null, false)
            }
        }

        // 找极大连续异质块：强信号可单片，弱信号必须 ≥2 片成块；
        // 块两侧至少有一侧是正片，且块长不超过整份清单的一半。
        val drop = BooleanArray(segs.size)
        var blockCount = 0
        var i = 0
        while (i < segs.size) {
            if (verdicts[i].reason == null) {
                i++
                continue
            }
            var j = i
            while (j + 1 < segs.size && verdicts[j + 1].reason != null) j++
            val len = j - i + 1
            var strong = false
            var hasForeign = false
            for (k in i..j) {
                if (verdicts[k].strong) strong = true
                if (verdicts[k].reason == "foreign") hasForeign = true
            }
            val cleanBefore = i > 0 && verdicts[i - 1].reason == null
            val cleanAfter = j + 1 < segs.size && verdicts[j + 1].reason == null
            // 弱信号要成块才动手：同一段广告往往只有第一片带 DISCONTINUITY，
            // 后续分片只靠时长或域名异常，所以断点看「块起点」而不是逐片看。
            val weakOk = len >= 2 && (segs[i].disc || hasForeign)
            val ok = strong || weakOk
            if (ok && (cleanBefore || cleanAfter) && len * 2 <= segs.size) {
                for (k in i..j) drop[k] = true
                blockCount++
            }
            i = j + 1
        }

        var removed = 0
        var cCue = 0
        var cPath = 0
        var cHost = 0
        var cForeign = 0
        var cTiming = 0
        var sample: String? = null
        for (k in segs.indices) {
            if (!drop[k]) continue
            removed++
            if (sample == null) sample = segs[k].uriLine
            when (verdicts[k].reason) {
                "cue" -> cCue++
                "path" -> cPath++
                "host" -> cHost++
                "foreign" -> cForeign++
                "timing" -> cTiming++
            }
        }

        // 安全阀：没删说明没广告；删太多说明规则失灵，原样放行
        if (removed == 0) return playlist
        val kept = ArrayList<Seg>(segs.size)
        for (k in segs.indices) if (!drop[k]) kept.add(segs[k])
        if (kept.isEmpty() || removed * 5 >= segs.size * 3) {
            Log.w(TAG, "adFilter 放弃：removed=$removed/${segs.size}（比例过高）")
            return playlist
        }
        var keptMs = 0L
        for (s in kept) keptMs += s.durationMs
        if (kept.size < MIN_KEPT_SEGS || (keptMs > 0 && keptMs < MIN_KEPT_MS)) {
            Log.w(TAG, "adFilter 放弃：kept=${kept.size} 片/${keptMs}ms（正片太少，保护起播）")
            return playlist
        }

        val sb = StringBuilder(playlist.length)
        for (h in header) if (!isCueTag(h)) sb.append(h).append('\n')
        var pendingDisc = false
        for (s in kept) {
            for (t in s.tags) {
                if (isCueTag(t)) continue
                // 广告块删掉后，它两侧的 DISCONTINUITY 会撞在一起，去重避免解码器空转
                if (t.startsWith("#EXT-X-DISCONTINUITY")) {
                    if (pendingDisc) continue
                    pendingDisc = true
                }
                sb.append(t).append('\n')
            }
            sb.append(s.uriLine).append('\n')
            pendingDisc = false
        }

        AdRules.noteFilter(removed, blockCount, cCue, cPath, cHost, cForeign, cTiming)
        Log.i(
            TAG,
            "adFilter 生效：removed=$removed/${segs.size} blocks=$blockCount " +
                "cue=$cCue path=$cPath host=$cHost foreign=$cForeign timing=$cTiming " +
                "mainHost=$mainHost 例=$sample"
        )
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
