package com.xiaohei.tv.data

import com.xiaohei.tv.model.Channel

/**
 * 频道分组规则。
 *
 * 顺序：收藏 → 央视 → 卫视 → 体育 → 戏曲 → 港澳台 → 广播 → 有声书 → 播客
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
    const val SPORTS = "体育"
    const val OPERA = "戏曲"
    const val HKMO_TW = "港澳台"
    const val OVERSEAS = "海外"
    const val RADIO = "广播"
    const val AUDIOBOOK = "有声书"
    const val PODCAST = "播客"

    /** 固定展示顺序（不再有「其他」） */
    val ORDER = listOf(
        FAVORITE, CCTV, SATELLITE, SPORTS, OPERA, HKMO_TW, OVERSEAS, RADIO, AUDIOBOOK, PODCAST
    )

    private val cctvRegex = Regex("""^\s*(CCTV|CETV)""", RegexOption.IGNORE_CASE)

    /**
     * 体育台识别：必须在央视规则之前判断，让 CCTV-5 / CCTV-5+ 归到「体育」而非「央视」。
     * 台湾体育台（纬来体育等）排除，仍留在「港澳台」。
     */
    private val sportsKeywords = listOf(
        "体育", "體育", "高尔夫网球", "高爾夫網球", "风云足球", "風雲足球",
        "央视台球", "央視台球", "睛彩篮球", "睛彩籃球", "足球频道", "足球頻道"
    )
    // CCTV-5 / CCTV-5+ 台名不含「体育」二字，单独列入，确保归体育组而非央视组
    private val cctvSportsRegex = Regex("""^CCTV-5\+?$""", RegexOption.IGNORE_CASE)
    private val sportsExclude = listOf("纬来", "緯來", "TVBS", "東森", "东森", "凤凰", "鳳凰")

    private fun isSports(name: String): Boolean =
        (sportsKeywords.any { name.contains(it, ignoreCase = true) } ||
            cctvSportsRegex.matches(name.trim())) &&
            sportsExclude.none { name.contains(it, ignoreCase = true) }

    /**
     * 央视系体育频道：归到「体育」组的同时，也要在「央视」组里露面。
     * 只包含 CCTV-5/5+ 和几个央视自办体育数字频道；地方台（广东/天津体育等）不算。
     * 注意：这里的名字判断只针对已归体育组的频道，避免误伤「央视一套」等普通央视台。
     */
    private val cctvSportsNames = listOf(
        "央视台球", "央視台球", "高尔夫网球", "高爾夫網球",
        "风云足球", "風雲足球", "精品体育", "精品體育"
    )

    fun isCctvSports(name: String): Boolean {
        val n = name.trim()
        return cctvSportsRegex.matches(n) || cctvSportsNames.any { n.contains(it, ignoreCase = true) }
    }

    /**
     * 央视组内的展示排序键：CCTV-1..17 按数字连续，CCTV-5+ 紧跟 CCTV-5，
     * CETV 与其余自办频道排在央视数字台之后。仅用于央视桶展示顺序，
     * 不改变频道归属、切台序列与收藏记忆。
     */
    fun cctvDisplayKey(name: String): Long {
        val n = name.trim()
        Regex("""^CCTV-(\d+)(\+)?$""", RegexOption.IGNORE_CASE).matchEntire(n)?.let {
            val num = it.groupValues[1].toInt()
            val plus = if (it.groupValues[2].isNotEmpty()) 1 else 0
            return num * 10L + plus
        }
        Regex("""^CETV-(\d+)$""", RegexOption.IGNORE_CASE).matchEntire(n)?.let {
            return 1000L + it.groupValues[1].toInt()
        }
        if (n.equals("CGTN", ignoreCase = true)) return 2000L
        if (n.startsWith("CCTV+", ignoreCase = true)) return 3000L
        if (n == "发现中国") return 3100L
        return 9000L
    }

    /**
     * 戏曲频道：戏曲组是一个「聚合视图」，这些频道在各自主分组（卫视/广播等）
     * 里照常保留，另外在戏曲组里也放一份引用，方便集中找戏。
     *
     * 只收录实测能播的频道。三立戏剧的公网线路已全部失效（2026-11-24 全网实测），
     * 暂不纳入；仍留在港澳台原分组。
     *
     * CCTV-11 于 2026-11-25 找到两条可用线路（111.61.236.247 / 222.223.41.27，
     * 电视实测均为正版戏曲内容），现纳入戏曲组；它本身仍在央视组保留。
     *
     * 注意：戏曲组不改变频道本身的 group，也不进切台序列去重逻辑，
     * 由 MainActivity.buildGroups 单独把这份引用塞进戏曲桶。
     */
    private val operaNames = listOf(
        "CCTV-11", "梨园频道", "上海戏剧曲艺广播"
    )

    fun isOpera(name: String): Boolean {
        val n = name.trim()
        return operaNames.any { n.equals(it, ignoreCase = true) }
    }

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
     * 体育(含 CCTV-5/5+) → 央视(CCTV/CETV) → 卫视 → 港澳台；都不命中返回空串，
     * 交给 M3uParser 用清单自己的分组名兜底（不再产生「其他」）。
     */
    fun groupOf(name: String): String {
        val n = name.trim()
        if (isSports(n)) return SPORTS
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
