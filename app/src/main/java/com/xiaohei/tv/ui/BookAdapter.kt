package com.xiaohei.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.xiaohei.tv.R
import com.xiaohei.tv.model.PodcastShow

/**
 * 书架：封面墙 + 分类分节。
 *
 * 有声书和播客共用这一个适配器——两边的条目形状完全一样（封面、名字、一行说明），
 * 区别只在数据从哪来。分类用「整行标题」插在网格里（见 [isHeader]），
 * 由外层的 SpanSizeLookup 让它占满一行，所以看起来是分段的，而不是一锅乱炖。
 *
 * 只负责「一张卡片长什么样」，按键与打开章节都由 MainActivity 统一处理
 * （OK 被 Activity 拦截，这里的 OnClickListener 只是触摸/兜底路径）。
 */
class BookAdapter(
    private val onOpen: (PodcastShow) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 分类标题行。只占位显示，不可聚焦，方向键会直接跳过去。 */
    data class Header(val title: String, val count: Int)

    private val items = mutableListOf<Any>()
    private var playingName: String? = null
    /** 播客书架里第三行显示分类，有声书里显示作者——由外面决定 */
    private var metaOf: (PodcastShow) -> String = { it.author }

    /**
     * @param sections 分类名 → 该分类下的条目；空分类会被跳过，不会留一个空标题
     */
    fun submitSections(
        sections: List<Pair<String, List<PodcastShow>>>,
        playingName: String?,
        metaOf: (PodcastShow) -> String = { it.author }
    ) {
        items.clear()
        this.playingName = playingName
        this.metaOf = metaOf
        sections.forEach { (title, list) ->
            if (list.isEmpty()) return@forEach
            items.add(Header(title, list.size))
            items.addAll(list)
        }
        notifyDataSetChanged()
    }

    fun setPlaying(name: String?) {
        if (playingName == name) return
        playingName = name
        notifyDataSetChanged()
    }

    /** 分类标题占整行，其余是书/节目卡片 */
    fun isHeader(position: Int): Boolean = items.getOrNull(position) is Header

    fun size(): Int = items.size

    fun itemAt(position: Int): PodcastShow? = items.getOrNull(position) as? PodcastShow

    /** 第一档真正的条目（跳过开头的分类标题），焦点意外落在标题上时用它兜底 */
    fun firstBook(): PodcastShow? = items.firstOrNull { it is PodcastShow } as? PodcastShow

    fun positionOf(name: String): Int = items.indexOfFirst { it is PodcastShow && it.name == name }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position] is Header) TYPE_HEADER else TYPE_BOOK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_book_header, parent, false))
        } else {
            BookVH(inflater.inflate(R.layout.item_book, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Header -> (holder as HeaderVH).bind(item)
            is PodcastShow -> (holder as BookVH).bind(item, item.name == playingName, metaOf(item))
        }
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.sectionTitle)
        private val count: TextView = view.findViewById(R.id.sectionCount)

        fun bind(header: Header) {
            title.text = header.title
            count.text = header.count.toString()
        }
    }

    inner class BookVH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.bookCover)
        private val initial: TextView = view.findViewById(R.id.bookInitial)
        private val title: TextView = view.findViewById(R.id.bookTitle)
        private val meta: TextView = view.findViewById(R.id.bookMeta)
        /** 正在听的那一档，说明行常亮，不随焦点变回灰色 */
        private var playing = false

        private fun metaColor(focused: Boolean): Int = androidx.core.content.ContextCompat.getColor(
            meta.context,
            if (focused || playing) R.color.text_primary else R.color.text_tertiary
        )

        fun bind(show: PodcastShow, playing: Boolean, metaText: String) {
            this.playing = playing
            title.text = show.name
            initial.text = show.name.take(1)
            meta.text = if (playing) "● 正在听" else metaText
            itemView.isActivated = playing
            // 焦点态卡片底色变亮，灰色小字在那上面会糊；跟着换白（和频道卡片同一套做法）
            itemView.setOnFocusChangeListener { _, hasFocus ->
                meta.setTextColor(metaColor(hasFocus))
            }
            meta.setTextColor(metaColor(itemView.isFocused))
            // 封面是方图：给 12dp 圆角，和下面那块占位玻璃同一个弧度
            val radius = (cover.resources.displayMetrics.density * 12f).toInt()
            Glide.with(cover)
                .load(show.cover)
                .apply(
                    RequestOptions.bitmapTransform(RoundedCorners(radius))
                        .placeholder(android.R.color.transparent)
                        .error(android.R.color.transparent)
                )
                .into(cover)
            itemView.setOnClickListener { onOpen(show) }
        }
    }

    private companion object {
        const val TYPE_BOOK = 0
        const val TYPE_HEADER = 1
    }
}
