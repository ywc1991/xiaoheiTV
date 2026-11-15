package com.xiaohei.tv.model

data class Channel(
    val name: String,
    val group: String,
    val logo: String,
    val urls: List<String>,
    /** true＝纯音频网络电台：没有视频轨，切台时不能走禁用音轨的预播流水线 */
    val isAudio: Boolean = false,
    /**
     * true＝播客节目（一档节目一条，本身没有音频地址）。
     * 按 OK 不是起播，而是打开这一档的分集面板；也不参与上下键切台。
     */
    val isPodcast: Boolean = false,
    /** 列表第二行的补充说明：播客用来放作者，其余频道留空走原有逻辑 */
    val subtitle: String = ""
)
