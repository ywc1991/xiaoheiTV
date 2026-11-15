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
    val desc: String = ""
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
    val pubDateMs: Long
) {
    /** 续播位置的存取键：同一档里同一个音频地址就是同一集 */
    val id: String get() = "$showId#${url.hashCode()}"
}
