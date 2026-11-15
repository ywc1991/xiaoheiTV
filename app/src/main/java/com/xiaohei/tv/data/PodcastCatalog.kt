package com.xiaohei.tv.data

import com.xiaohei.tv.model.Channel
import com.xiaohei.tv.model.PodcastShow

/**
 * 内置播客目录。
 *
 * 只内置「节目单 + RSS 地址」，分集在打开面板时按 RSS 实时拉取并缓存 12 小时——
 * 播客是持续更新的，把分集地址写死在 APK 里过几周就全是旧的。
 *
 * 这 11 个 RSS 都是 2026-11-15 在本机逐个实测过的：返回 200、能解析出分集、
 * 首集音频 Range 请求返回 206（mp3 / m4a 直链）。名单里不写需要登录、
 * 或者只有喜马拉雅专辑页（RSS 里没有 enclosure）的源。
 */
object PodcastCatalog {

    val shows: List<PodcastShow> = listOf(
        PodcastShow(
            "storyfm", "故事FM", "故事 FM",
            "https://feeds.storyfm.cn/storyfm.xml",
            "亲历者口述的真实故事"
        ),
        PodcastShow(
            "huzuohuyou", "忽左忽右", "JustPod",
            "https://feed.xyzfm.space/cv4bkgpuglwp",
            "一档关于历史、社会与思想的对谈节目"
        ),
        PodcastShow(
            "shengdongjixi", "声东击西", "ETW Studio",
            "https://feeds.fireside.fm/shengdongjixi/rss",
            "把世界另一端的声音带到耳边"
        ),
        PodcastShow(
            "daneimitan", "大内密谈", "大内密谈",
            "https://rss.lizhi.fm/rss/14275.xml",
            "生活方式与脑洞漫谈"
        ),
        PodcastShow(
            "wenhuayouxian", "文化有限", "文化有限",
            "https://s1.proxy.wavpub.com/weknownothing.xml",
            "三个爱读书的人聊书"
        ),
        PodcastShow(
            "bowuzhi", "博物志", "小黄鱼播客",
            "https://bowuzhi.typlog.io/feed/audio.xml",
            "博物馆、展览与文物里的细节"
        ),
        PodcastShow(
            "liangyongan", "梁永安的播客", "梁永安",
            "https://feed.xyzfm.space/tbalbybjc3ev",
            "复旦教授聊文学与年轻人的处境"
        ),
        PodcastShow(
            "ritanwuyu", "日谈物语", "日谈公园",
            "https://feed.xyzfm.space/87x9n4x77fpy",
            "日谈公园出品的纪实故事"
        ),
        PodcastShow(
            "dongyaguancha", "东亚观察局", "东亚观察局",
            "https://feed.xyzfm.space/eye-on-east-asia",
            "东亚时事与社会观察"
        ),
        PodcastShow(
            "mianji", "面基", "面基",
            "https://feed.xyzfm.space/6hpdgggtxpxb",
            "和各行各业的人聊聊他们怎么想"
        ),
        PodcastShow(
            "rizhilu", "日知录", "日谈公园",
            "https://feed.xyzfm.space/6fqtp9h4u8vm",
            "日常里那些值得记下来的知识"
        )
    )

    /**
     * 播客在左右两栏里就是「一档节目」这一行：
     * 没有线路表（音频地址要等打开面板才知道），所以 urls 为空，
     * 靠 isPodcast 让按键逻辑走「打开分集面板」而不是「直接起播」。
     */
    fun asChannel(show: PodcastShow): Channel = Channel(
        name = show.name,
        group = ChannelGroups.PODCAST,
        logo = "",
        urls = emptyList(),
        isAudio = true,
        isPodcast = true,
        subtitle = show.author
    )

    fun byName(name: String): PodcastShow? = shows.firstOrNull { it.name == name }
}
