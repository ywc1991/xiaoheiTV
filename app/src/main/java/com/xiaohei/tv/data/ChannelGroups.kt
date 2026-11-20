package com.xiaohei.tv.data

import com.xiaohei.tv.model.Channel

/**
 * 频道分组规则。
 *
 * 顺序：收藏 → 央视 → 卫视 → 港澳台 → 广播 → 有声书 → 播客
 *
 * 「广播」是纯音频网络电台，独立成组，不参与视频台的全屏上下键循环。
 * 「有声书」是按本聚合的音频书（一本一行，OK 打开封面书架），
 * 「播客」是点播音频：一档节目一行，按 OK 打开分集面板，两者都不进切台循环。
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
    const val OVERSEAS = "海外"
    const val RADIO = "广播"
    const val AUDIOBOOK = "有声书"
    const val PODCAST = "播客"

    /** 固定展示顺序（不再有「其他」） */
    val ORDER = listOf(FAVORITE, CCTV, SATELLITE, HKMO_TW, OVERSEAS, RADIO, AUDIOBOOK, PODCAST)

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
        // 海外台写成「国家·频道」；先于港澳台关键词判断，避免「日本·CGNTV」被 CGNTV 抢走
        val dot = n.indexOf('·')
        if (dot > 0) {
            val prefix = n.substring(0, dot).trim()
            if (prefix in listOf("日本", "美国", "英国", "德国", "法国", "澳洲", "新加坡", "泰国", "巴西",
                    "韩国", "加拿大", "意大利", "西班牙", "印度", "印尼", "马来西亚", "菲律宾", "越南",
                    "俄罗斯", "荷兰", "瑞士", "瑞典", "挪威", "丹麦", "芬兰", "波兰", "墨西哥", "阿根廷",
                    "南非", "新西兰")) return OVERSEAS
        }
        if (hkmoTwKeywords.any { n.contains(it, ignoreCase = true) }) return HKMO_TW
        return ""
    }

    /**
     * 全屏上下键与预播预测共用的切台序列：只保留和当前同类（视频 / 电台）的频道。
     *
     * 播客永远不在序列里：节目行没有音频地址，混进来会变成「切到一条空频道」。
     * 听广播时只在电台之间循环，看电视时只在视频台之间循环，互不掉坑。
     */
    fun zapList(all: List<Channel>, inRadio: Boolean): List<Channel> {
        val pool = all.filter { !it.isPodcast }
        val same = if (inRadio) pool.filter { it.isAudio } else pool.filter { !it.isAudio }
        // 同类为空时退回「非播客全集」；只剩播客时返回空，让调用方直接不切台，
        // 也好过把用户切进一条没有音频地址的节目行
        return same.ifEmpty { pool }
    }
}
