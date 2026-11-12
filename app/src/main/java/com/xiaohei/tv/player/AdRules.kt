package com.xiaohei.tv.player

import java.util.concurrent.atomic.AtomicLong

/**
 * 广告过滤的规则与命中统计。
 *
 * 故意写成纯 Kotlin、不依赖任何 Android 类：清单解析是播放关键路径，
 * 这里只读内存快照，零磁盘 IO、零网络请求、零锁等待（除了一次 CAS），
 * 所以规则再多也不会拖慢切台。单测可以直接跑在 JVM 上。
 *
 * 规则可以在后台用广播下发（见 BenchReceiver），换广告域名不必发版：
 * 每行一条，`host:xxx` 按域名片段匹配，`token:xxx` 按分片路径匹配，
 * `#` 开头是注释，`clear` 清空已有规则。换行、分号、逗号都可作分隔符
 * （adb shell 会吞掉分号，所以命令行下发时请用逗号）。
 */
object AdRules {

    val playlists = AtomicLong(0)      // 解析过的清单次数
    val filtered = AtomicLong(0)       // 真正剔除过广告的次数
    val removedSegs = AtomicLong(0)    // 累计剔除的分片数
    val blocks = AtomicLong(0)         // 累计剔除的广告块数
    val hitCue = AtomicLong(0)
    val hitPath = AtomicLong(0)
    val hitHost = AtomicLong(0)
    val hitForeign = AtomicLong(0)
    val hitTiming = AtomicLong(0)
    val totalCostUs = AtomicLong(0)
    val maxCostUs = AtomicLong(0)

    @Volatile private var extraHosts: Set<String> = emptySet()
    @Volatile private var extraTokens: Set<String> = emptySet()
    @Volatile private var lastDiagAt = 0L
    @Volatile private var rawRules = ""

    /** 返回生效的规则条数；解析失败也不抛异常，最坏情况就是保持内置规则。 */
    fun update(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val hosts = LinkedHashSet<String>()
        val tokens = LinkedHashSet<String>()
        for (raw in text.split('\n', ';', ',')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            when {
                line.equals("clear", ignoreCase = true) -> {
                    hosts.clear()
                    tokens.clear()
                }
                line.startsWith("host:", ignoreCase = true) -> {
                    val v = line.substring(5).trim().lowercase()
                    if (v.isNotEmpty()) hosts.add(v)
                }
                line.startsWith("token:", ignoreCase = true) -> {
                    val v = line.substring(6).trim().lowercase()
                    if (v.isNotEmpty()) tokens.add(v)
                }
            }
        }
        extraHosts = hosts
        extraTokens = tokens
        rawRules = text.trim()
        return hosts.size + tokens.size
    }

    fun hostBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty() || extraHosts.isEmpty()) return false
        val h = host.lowercase()
        return extraHosts.any { h.contains(it) }
    }

    fun tokenHit(path: String): Boolean {
        if (path.isEmpty() || extraTokens.isEmpty()) return false
        val p = path.lowercase()
        return extraTokens.any { p.contains(it) }
    }

    fun rulesText(): String = rawRules

    /** 每次清单解析结束调用；返回 true 表示「该打一条节流诊断日志了」。 */
    fun noteCost(costUs: Long): Boolean {
        playlists.incrementAndGet()
        totalCostUs.addAndGet(costUs)
        // minSdk 21：AtomicLong.updateAndGet 是 API 24+，这里用 CAS 自己写
        var cur = maxCostUs.get()
        while (costUs > cur && !maxCostUs.compareAndSet(cur, costUs)) {
            cur = maxCostUs.get()
        }
        val now = System.currentTimeMillis()
        if (now - lastDiagAt < 30_000L) return false
        lastDiagAt = now
        return true
    }

    fun noteFilter(
        removed: Int,
        blockCount: Int,
        cue: Int,
        path: Int,
        host: Int,
        foreign: Int,
        timing: Int
    ) {
        filtered.incrementAndGet()
        removedSegs.addAndGet(removed.toLong())
        blocks.addAndGet(blockCount.toLong())
        hitCue.addAndGet(cue.toLong())
        hitPath.addAndGet(path.toLong())
        hitHost.addAndGet(host.toLong())
        hitForeign.addAndGet(foreign.toLong())
        hitTiming.addAndGet(timing.toLong())
    }

    fun snapshot(): String {
        val n = playlists.get()
        return "playlists=$n filtered=${filtered.get()} blocks=${blocks.get()} " +
            "removed=${removedSegs.get()} cue=${hitCue.get()} path=${hitPath.get()} " +
            "host=${hitHost.get()} foreign=${hitForeign.get()} timing=${hitTiming.get()} " +
            "costUs avg=${if (n > 0) totalCostUs.get() / n else 0} max=${maxCostUs.get()} " +
            "rules=${extraHosts.size + extraTokens.size}"
    }

    fun resetStats() {
        playlists.set(0)
        filtered.set(0)
        removedSegs.set(0)
        blocks.set(0)
        hitCue.set(0)
        hitPath.set(0)
        hitHost.set(0)
        hitForeign.set(0)
        hitTiming.set(0)
        totalCostUs.set(0)
        maxCostUs.set(0)
    }
}
