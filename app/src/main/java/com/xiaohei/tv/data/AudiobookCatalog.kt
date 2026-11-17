package com.xiaohei.tv.data

import com.xiaohei.tv.model.Channel
import com.xiaohei.tv.model.PodcastShow

/**
 * 有声书书架。
 *
 * 和播客共用同一套「RSS → 分集 → 播放」的链路：一本书就是一档音频专辑，
 * 一章就是一集。区别只在外壳——书要用封面墙展示、章节要从第一章往后排、
 * 而且得把整本都拉下来（[PodcastShow.maxEpisodes]），不能像播客那样只取最新 40 集。
 *
 * 只收录**国内网络下实测能播**的来源：喜马拉雅系音频会 403，LibriVox 系的音频
 * 全在 archive.org（大陆不可达），两者都不在这里。名单里的 feed 逐个验证过——
 * enclosure 是真实 mp3/m4a 直链，封面 https 可访问（2026-11-16 实测）。
 *
 * 内容本身是公版名著的原文朗读、或创作者公开发布的播客节目，
 * 和已有的 29 档播客同性质：只放节目单，音频由原平台托管，应用不存储也不分发音频。
 */
object AudiobookCatalog {

    /** 分类的固定展示顺序：书架按这个顺序排，同类里章节多的在前 */
    val CATEGORIES = listOf("古典小说", "童话寓言", "诗词国学", "历史故事")

    /**
     * 一本书最多拉多少章。
     *
     * 取整本而不是截断：书是要从头听到尾的，缺了后半本等于没做。
     * 上限只是防呆（最大的《资治通鉴》1482 章），不会真的卡到这个数。
     */
    private const val CHAPTER_LIMIT = 2000

    private fun book(
        id: String,
        name: String,
        author: String,
        feed: String,
        cover: String,
        category: String,
        desc: String
    ) = PodcastShow(
        id = id,
        name = name,
        author = author,
        feed = feed,
        desc = desc,
        cover = cover,
        category = category,
        isBook = true,
        maxEpisodes = CHAPTER_LIMIT
    )

    val books: List<PodcastShow> = listOf(
        // ---------------- 古典小说 ----------------
        book(
            "bk-sanguo", "三国演义", "曦爸读经典",
            "https://papi.qingting.fm/podcast/channels/10845",
            "https://pic.qtfm.cn/device/bb9ef22479b44578bd1fc6f7868cf7e3/1632470089243_3_z6XDdSx.jpeg",
            "古典小说", "原著逐回朗读，全一百二十回"
        ),
        book(
            "bk-shuihu", "水浒传", "旺仔有声小说",
            "https://papi.qingting.fm/podcast/channels/12133",
            "https://pic.qtfm.cn/device/2393e33ae4f44cef8b45bc5abd2b7c37/1779440046084_1TsMWFOFR.png",
            "古典小说", "原著一百二十回，逐回播讲"
        ),
        book(
            "bk-honglou", "红楼梦", "旺仔有声小说",
            "https://papi.qingting.fm/podcast/channels/12132",
            "https://pic.qtfm.cn/device/86b2685d28154821811a097d26fdce2e/1779363168651_vJqVkWeLc.jpg",
            "古典小说", "原著一百二十回，逐回播讲"
        ),
        book(
            "bk-rulin", "儒林外史", "小马蹦蹦达",
            "https://papi.qingting.fm/podcast/channels/11282",
            "https://upload-up.qingting.fm/user/84748bf9b5384dc68fe044375a19ff22/1633247278_0001-372697103.png",
            "古典小说", "全五十六回，原文朗读"
        ),
        book(
            "bk-liaozhai", "聊斋夜谭", "Craft智匠",
            "https://feed.xyzfm.space/bmfnquknq9m7",
            "https://image.xyzcdn.net/FgT-9J8X3gzfiJXqZugT7OzFc8WB.png",
            "古典小说", "《聊斋志异》名篇新讲"
        ),

        // ---------------- 童话寓言 ----------------
        book(
            "bk-andersen", "安徒生童话", "鱼汤不太星",
            "https://feed.xyzfm.space/hrkbw9k3cal9",
            "https://image.xyzcdn.net/Fr7xkZaNVDoEZ5N1MqrJtEI0uKt-.jpg",
            "童话寓言", "一百零八篇，逐个讲给孩子听"
        ),
        book(
            "bk-grimm", "格林童话", "鱼汤不太星",
            "https://feed.xyzfm.space/pfllftrmjtny",
            "https://image.xyzcdn.net/Fsuj26p6NfvWtlRjFfMD_3tBOJdz.jpg",
            "童话寓言", "五十个经典故事"
        ),
        book(
            "bk-aesop", "伊索寓言", "鱼汤不太星",
            "https://feed.xyzfm.space/u6mfvvhbegbg",
            "https://image.xyzcdn.net/FokE-meTt16bhAet5LmptyQpCaXs.jpg",
            "童话寓言", "九十个短寓言，睡前一段正好"
        ),
        book(
            "bk-prince", "小王子", "Jasmine0501",
            "https://feed.xyzfm.space/yhl9pqa9yjtc",
            "https://image.xyzcdn.net/lswr_s-O-ylV8hL5elRQ3VfTXvZL.jpg",
            "童话寓言", "二十七章全本朗读"
        ),

        // ---------------- 诗词国学 ----------------
        book(
            "bk-poetry", "中国古诗词", "橙子的幸福说",
            "https://papi.qingting.fm/podcast/channels/10570",
            "https://upload-up.qingting.fm/user/e15012b260da455f2f1c3045377e3f82/1592901703_0001-128538390.png",
            "诗词国学", "原文与译文对照，一首一集"
        ),
        book(
            "bk-lunyu", "品读论语", "品读论语",
            "https://rss.lizhi.fm/rss/1662080.xml",
            "https://cdn.lizhi.fm/podcast_cover/2018/02/27/2655322823371670087.jpg",
            "诗词国学", "逐章讲透《论语》二十篇"
        ),
        book(
            "bk-xiyou", "趣解西游记", "大蜜小糖",
            "https://papi.qingting.fm/podcast/channels/10202",
            "http://pic.qingting.fm/device/3275cd8ed0a2401cb37a0006a4ae670e/1579162119753_ajIOLfJt.jpeg",
            "诗词国学", "一百回原著，逐回细说"
        ),

        // ---------------- 历史故事 ----------------
        book(
            "bk-zizhi", "资治通鉴", "芮淇讲透资治通鉴",
            "https://feed.xyzfm.space/46hbtj3jkmwx",
            "https://image.xyzcdn.net/FpB-uDFWBLqWUng7G7E_pT8T5H1T.png",
            "历史故事", "从战国讲到五代，一讲一段"
        ),
        book(
            "bk-lishi", "中国历史故事 130 讲", "秦宇熙",
            "https://feed.xyzfm.space/6ufc83jqq3ad",
            "https://image.xyzcdn.net/FrlAk2hn1AIQShJbRppi7Wk_8QCl.jpg",
            "历史故事", "按朝代串起中国史"
        ),
        book(
            "bk-yeshi", "野史下酒", "恶霸波",
            "https://s1.proxy.wavpub.com/yeshixiajiu.xml",
            "https://fdfs.xmcdn.com/storages/241f-audiofreehighqps/48/9E/GMCoOScGx_DkAAJ8HQGQL6OH.jpeg",
            "历史故事", "正史之外的边角料与人物"
        )
    )

    /**
     * 书架展示顺序：按分类的固定顺序排，分类内部保持 [books] 里的手写顺序。
     * 手写顺序是刻意排的（先整本长篇、再短篇集），不交给字符串排序——
     * 中文按 Unicode 排出来是乱的（「三国演义」会跑到「儒林外史」后面）。
     */
    val shelf: List<PodcastShow> = CATEGORIES.flatMap { c -> books.filter { it.category == c } } +
        books.filter { it.category !in CATEGORIES }

    fun byName(name: String): PodcastShow? = books.firstOrNull { it.name == name }

    fun byId(id: String): PodcastShow? = books.firstOrNull { it.id == id }

    /** 书名 → 频道行。isPodcast=true 让按键与播放链路完全复用播客那一套 */
    fun asChannel(show: PodcastShow): Channel = Channel(
        name = show.name,
        group = ChannelGroups.AUDIOBOOK,
        logo = show.cover,
        urls = emptyList(),
        isAudio = true,
        isPodcast = true,
        subtitle = "${show.category} · ${show.author}"
    )
}
