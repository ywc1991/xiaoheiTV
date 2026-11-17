package com.xiaohei.tv.model

/**
 * 一档播客节目（音频专辑）。
 *
 * 只有「节目单」是内置的：RSS 地址写死在 [com.xiaohei.tv.data.PodcastCatalog] 里，
 * 具体分集在打开面板时才去拉 RSS。这样内置清单不会因为某天某一集下架而失效，
 * 也不用把成百上千条音频地址塞进 APK。
 */
data class PodcastShow(
    /** 稳定 id，用于缓存文件名与「听到哪儿」的键 */
    val id: String,
    val name: String,
    val author: String,
    /** RSS 地址 */
    val feed: String,
    val desc: String = "",
    /** 专辑封面地址。书架、右栏列表行、分集面板都用它 */
    val cover: String = "",
    /**
     * 分类：书架按它分节显示。播客用 [com.xiaohei.tv.data.PodcastCatalog.CATEGORIES] 里的名字，
     * 有声书用 [com.xiaohei.tv.data.AudiobookCatalog.CATEGORIES] 里的名字。
     * 填了表里没有的值也不会丢，会落到最后一节「其他」。
     */
    val category: String = "",
    /**
     * 是不是一本有正文顺序的书（有声书）。
     *
     * 播客和书走同一条 RSS 链路，但预期相反：书要从第一章往后排、集数前缀要切掉、
     * 文案说「章」；播客要保持源里的「最新在前」、标题原样、文案说「集」。
     *
     * 这件事**不能拿 [category] 是否为空来推断**——播客现在也有分类了，
     * 那样会把整档播客倒过来播。所以单独一个显式标记，由 [com.xiaohei.tv.data.AudiobookCatalog] 置位。
     */
    val isBook: Boolean = false,
    /**
     * 最多取多少集/章。播客 40 集够了；书是整本，动辄几百回，
     * 截断会让用户永远听不到结尾。
     */
    val maxEpisodes: Int = 40
)

/** 一集播客。音频是 mp3 / m4a 直链，走同一个播放器，不需要 HLS 那套预播流水线。 */
data class PodcastEpisode(
    val showId: String,
    val showName: String,
    val title: String,
    val url: String,
    /** 时长；RSS 里没写就是 0，播放时改用播放器回报的时长 */
    val durationMs: Long,
    /** 发布时间；解析失败就是 0，列表里那一栏直接不显示 */
    val pubDateMs: Long,
    /** WebVTT 字幕地址；只有少数节目提供（目前是科技乱炖），空串表示这一集没有字幕 */
    val transcriptUrl: String = ""
) {
    /** 续播位置的存取键：同一档里同一个音频地址就是同一集 */
    val id: String get() = "$showId#${url.hashCode()}"
}
