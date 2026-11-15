package com.xiaohei.tv.data

import com.xiaohei.tv.model.Channel

object M3uParser {

    private val logoRegex = Regex("""tvg-logo="([^"]*)"""")
    private val groupRegex = Regex("""group-title="([^"]*)"""")

    /** 明确标成广播的分组名；兼容自定义源里常见的几种写法 */
    private val radioGroups = setOf("广播", "广播电台", "网络电台", "电台", "radio", "fm")

    private fun isRadioGroup(title: String): Boolean = title.trim().lowercase() in radioGroups

    fun parse(text: String): List<Channel> {
        val lines = text.lines()
        val map = LinkedHashMap<String, Channel>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                val name = line.substringAfterLast(",").trim().ifEmpty { "未知频道" }
                val logo = logoRegex.find(line)?.groupValues?.get(1) ?: ""
                val declaredGroup = groupRegex.find(line)?.groupValues?.get(1)?.trim() ?: ""
                val audio = isRadioGroup(declaredGroup)
                // 只有显式标了广播的条目才进电台分组；其余仍按台名规则归类
                // （内置视频源的 group-title 不可靠，不能直接采信）。
                // 台名规则都不认识时，才沿用清单自带的分组名；清单自己写着「其他」
                // 或压根没写，就并到「卫视」——界面上不再有「其他」，但一个台都不丢。
                val group = if (audio) {
                    ChannelGroups.RADIO
                } else {
                    ChannelGroups.groupOf(name).ifEmpty {
                        if (declaredGroup.isEmpty() || declaredGroup == "其他") ChannelGroups.SATELLITE
                        else declaredGroup
                    }
                }
                val urls = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size) {
                    val u = lines[j].trim()
                    if (u.startsWith("#")) break
                    if (u.isNotEmpty() && (u.startsWith("http://") || u.startsWith("https://"))) urls.add(u)
                    j++
                }
                if (urls.isNotEmpty()) {
                    // key 带媒体类型：万一自定义源里有同名电视台，也不会和电台合并成一条
                    val key = "$audio|$name"
                    val existing = map[key]
                    if (existing == null) {
                        map[key] = Channel(name, group, logo, urls.distinct(), audio)
                    } else {
                        map[key] = existing.copy(urls = (existing.urls + urls).distinct())
                    }
                }
                i = j
            } else {
                i++
            }
        }
        return map.values.toList()
    }
}
