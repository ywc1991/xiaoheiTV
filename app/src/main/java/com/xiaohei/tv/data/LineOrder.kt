package com.xiaohei.tv.data

/**
 * 线路优先顺序的纯逻辑，抽出来方便单测。
 *
 * 顺序：上次真正出过图的线 → 正常线（源里顺序）→ 会卡的线 → 确认连不上的线 → IPv6 字面量。
 * 坏线不删除，只沉底：免费源本来就不多，源修好以后还能当兜底用。
 */
object LineOrder {
    fun order(
        urls: List<String>,
        goodUrl: String?,
        slowUrl: String?,
        isDead: (String) -> Boolean
    ): List<Int> {
        if (urls.isEmpty()) return emptyList()
        val goodIdx = goodUrl?.let(urls::indexOf) ?: -1
        val slowIdx = slowUrl?.let(urls::indexOf) ?: -1
        fun isV6(i: Int) = urls[i].contains("://[")

        val head = if (goodIdx >= 0 && goodIdx != slowIdx && !isV6(goodIdx) && !isDead(urls[goodIdx])) {
            listOf(goodIdx)
        } else emptyList()

        val bad = urls.indices
            .filter { it !in head && it != slowIdx && (isV6(it) || isDead(urls[it])) }
            .sortedWith(compareBy<Int>({ if (isV6(it)) 1 else 0 }, { it }))

        val normal = urls.indices.filter { it !in head && it != slowIdx && it !in bad }.sortedBy { it }
        val slow = if (slowIdx >= 0 && slowIdx !in head && slowIdx !in bad) listOf(slowIdx) else emptyList()
        return head + normal + slow + bad
    }
}
