package com.xiaohei.tv.data

import com.xiaohei.tv.model.PodcastEpisode

/**
 * 分集的排序规则。
 *
 * 播客和有声书共用一条 RSS 链路，但「顺序」的预期正好相反：
 *  - 播客：最新一期在最前面（照搬源里的顺序）
 *  - 有声书：第一章在最前面（源里通常是最新一章在最前，直接播会把整本倒着放）
 *
 * 单独拎出来是因为这条规则很容易写错，而写错了只会在真机上表现为「书倒着播」，
 * 单测能一句话卡住它。
 */
internal object BookOrder {

    fun apply(isBook: Boolean, list: List<PodcastEpisode>): List<PodcastEpisode> {
        if (!isBook || list.size < 2) return list
        // 有发布时间就用时间正序最稳；极少数源不写日期，
        // 那就整段翻转——按集数顺序上传的源，反转后正好第一集在前。
        return if (list.all { it.pubDateMs > 0 }) list.sortedBy { it.pubDateMs }
        else list.reversed()
    }

    /** 章节名前面那点集数编号：「001_第一回…」「108《安徒生童话》…」「1三国演义第一回…」 */
    private val indexPrefix = Regex("^(\\d{1,3})[\\s._\\-、·:：]*")

    /**
     * 把章节名开头的集数编号切掉。
     *
     * 源里的标题常写成「001_第一回王教头私走延安府」，列表里编号和回目并排是噪音——
     * 顺序本身就是章节顺序，编号是重复信息。
     *
     * 但绝不能瞎切：「1984」「3体」这种书名开头的数字得留着。所以只在两种情况下切——
     * 数字后面跟着分隔符（`001_`、`108 `、`12、`），
     * 或者剩下的部分以书名 / 「第」「序」「楔」这类章节标记开头。
     */
    fun chapterTitle(raw: String, bookName: String = ""): String {
        val t = raw.trim()
        val m = indexPrefix.find(t) ?: return t
        val rest = t.substring(m.range.last + 1).trim()
        if (rest.isEmpty()) return t
        val hadSeparator = m.value.any { !it.isDigit() }
        val looksLikeChapter = rest.startsWith("第") || rest.startsWith("序") ||
            rest.startsWith("楔") || rest.startsWith("引") || rest.startsWith("前") ||
            rest.startsWith("后") || rest.startsWith("终") || rest.startsWith("《") ||
            rest.startsWith("「") || (bookName.isNotEmpty() && rest.startsWith(bookName))
        return if (hadSeparator || looksLikeChapter) rest else t
    }
}
