package com.xiaohei.tv.model

data class Channel(
    val name: String,
    val group: String,
    val logo: String,
    val urls: List<String>,
    /** true＝纯音频网络电台：没有视频轨，切台时不能走禁用音轨的预播流水线 */
    val isAudio: Boolean = false
)
