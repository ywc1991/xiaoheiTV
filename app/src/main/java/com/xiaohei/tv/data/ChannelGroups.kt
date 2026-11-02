package com.xiaohei.tv.data

/**
 * 频道分组规则（固定四个分组）。
 *
 * 顺序：收藏 → 央视 → 卫视 → 港澳台
 *
 * 注意：卫视规则排在港澳台之前，所以「香港卫视」「蓮花衛視」「世界卫视」
 * 会归入「卫视」，而不是「港澳台」。
 *
 * 规则外的频道兜底归入「其他」（仅在存在时才显示，正常情况不会出现）。
 */
object ChannelGroups {

    const val FAVORITE = "收藏"
    const val CCTV = "央视"
    const val SATELLITE = "卫视"
    const val HKMO_TW = "港澳台"
    const val OTHER = "其他"

    /** 固定展示顺序 */
    val ORDER = listOf(FAVORITE, CCTV, SATELLITE, HKMO_TW, OTHER)

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
     * 央视(CCTV/CETV) → 卫视 → 港澳台 → 其他
     */
    fun groupOf(name: String): String {
        val n = name.trim()
        if (cctvRegex.containsMatchIn(n)) return CCTV
        if (n.contains("卫视") || n.contains("衛視")) return SATELLITE
        if (hkmoTwKeywords.any { n.contains(it, ignoreCase = true) }) return HKMO_TW
        return OTHER
    }
}
