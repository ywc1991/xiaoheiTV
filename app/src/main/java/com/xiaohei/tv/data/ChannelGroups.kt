package com.xiaohei.tv.data

import com.xiaohei.tv.model.Channel

/**
 * 频道分组规则。
 *
 * 顺序：收藏 → 央视 → 卫视 → 港澳台 → 广播
 *
 * 「广播」是纯音频网络电台，独立成组，不参与视频台的全屏上下键循环。
 * 注意：卫视规则排在港澳台之前，所以「香港卫视」「蓮花衛視」「世界卫视」
 * 会归入「卫视」，而不是「港澳台」。
 *
 * 没有「其他」分组：台名规则都不命中的频道，由解析器沿用清单里自带的
 * group-title（如「地方台」）；清单也没写才并到「卫视」，频道一个都不会丢。
 */
object ChannelGroups {

    const val FAVORITE = "收藏"
    const val CCTV = "央视"
    const val SATELLITE = "卫视"
    const val HKMO_TW = "港澳台"
    const val RADIO = "广播"

    /** 固定展示顺序（不再有「其他」） */
    val ORDER = listOf(FAVORITE, CCTV, SATELLITE, HKMO_TW, RADIO)

    private val cctvRegex = Regex("""^\s*(CCTV|CETV)""", RegexOption.IGNORE_CASE)

    /** 港澳台识别关键词（卫视类已被上一条规则截走） */
    private val hkmoTwKeywords = listOf(
        "翡翠", "亞洲", "亚洲", "美亞", "美亚", "鳳凰", "凤凰",
        "港台", "TVBS", "東森", "东森", "公視", "公视", "原民",
        "大立", "CGNTV", "唯心", "美好生活", "KIX", "NewsWorld", "Pet Club",
        "TVB", "無線", "无线", "澳門", "澳门", "台灣", "台湾",
        "香港", "澳亞", "澳亚", "濠"
    )

    /**
     * 按台名归类，命中即停：
     * 央视(CCTV/CETV) → 卫视 → 港澳台；都不命中返回空串，
     * 交给 M3uParser 用清单自己的分组名兜底（不再产生「其他」）。
     */
    fun groupOf(name: String): String {
        val n = name.trim()
        if (cctvRegex.containsMatchIn(n)) return CCTV
        if (n.contains("卫视") || n.contains("衛視")) return SATELLITE
        if (hkmoTwKeywords.any { n.contains(it, ignoreCase = true) }) return HKMO_TW
        return ""
    }

    /**
     * 全屏上下键与预播预测共用的切台序列：只保留和当前同类（视频 or 电台）的频道。
     * 看电视时永远不会掉进黑屏有声的电台；只有整份清单只剩一种类型时才退回全量。
     */
    fun zapList(all: List<Channel>, inRadio: Boolean): List<Channel> =
        all.filter { it.isAudio == inRadio }.ifEmpty { all }
}
