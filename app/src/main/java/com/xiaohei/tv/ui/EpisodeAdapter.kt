package com.xiaohei.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xiaohei.tv.R
import com.xiaohei.tv.data.BookOrder
import com.xiaohei.tv.model.PodcastEpisode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分集列表。
 *
 * 只负责显示，按键全部由 MainActivity 处理：方向键交给 RecyclerView 自己挪焦点
 * （和左栏/右栏的焦点风格保持一致），OK/返回在 Activity 里判。
 *
 * 右侧播放键是「状态」也是「提示」：这一集在播 → 暂停图标（按 OK 会暂停）；
 * 播放键被暂停 → 播放图标；其余集 → 淡一点的播放图标（按 OK 会起播这一集）。
 */
class EpisodeAdapter(
    private val onPlay: (PodcastEpisode) -> Unit,
    /** 已听到哪儿（毫秒），用来在第二行显示进度 */
    private val positionOf: (PodcastEpisode) -> Long
) : RecyclerView.Adapter<EpisodeAdapter.EpisodeVH>() {

    private val items = mutableListOf<PodcastEpisode>()
    private var playingId: String? = null
    /** 当前集是否被暂停：true 时播放键要画成「▶」而不是「⏸」 */
    private var paused = false
    /** 有声音书模式：章节名去掉集数前缀，日期那栏不显示（书的发布时间没有意义） */
    private var bookMode = false

    fun submit(
        list: List<PodcastEpisode>,
        playing: String? = null,
        paused: Boolean = false,
        book: Boolean = bookMode
    ) {
        items.clear()
        items.addAll(list)
        playingId = playing
        this.paused = paused
        bookMode = book
        notifyDataSetChanged()
    }

    fun setPlaying(id: String?, paused: Boolean = false) {
        if (playingId == id && this.paused == paused) return
        playingId = id
        this.paused = paused
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): PodcastEpisode? = items.getOrNull(position)

    fun positionOf(id: String?): Int {
        if (id == null) return -1
        return items.indexOfFirst { it.id == id }
    }

    fun size(): Int = items.size

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeVH =
        EpisodeVH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

    override fun onBindViewHolder(holder: EpisodeVH, position: Int) {
        holder.bind(items[position])
    }

    inner class EpisodeVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.episodeTitle)
        private val meta: TextView = view.findViewById(R.id.episodeMeta)
        private val playIcon: ImageView = view.findViewById(R.id.episodePlayIcon)

        init {
            itemView.setOnFocusChangeListener { _, hasFocus ->
                meta.setTextColor(
                    if (hasFocus) androidx.core.content.ContextCompat.getColor(meta.context, R.color.text_primary)
                    else androidx.core.content.ContextCompat.getColor(meta.context, R.color.text_tertiary)
                )
            }
        }

        fun bind(e: PodcastEpisode) {
            val isPlaying = e.id == playingId
            title.text = if (bookMode) BookOrder.chapterTitle(e.title, e.showName) else e.title
            title.maxLines = 2
            meta.text = metaText(e)
            meta.visibility = if (meta.text.isEmpty()) View.GONE else View.VISIBLE
            itemView.isActivated = isPlaying
            itemView.setOnClickListener { onPlay(e) }

            // 正在播且没暂停 → ⏸（按 OK 暂停）；其余都是 ▶，当前集暂停时更亮一点
            playIcon.setImageResource(
                if (isPlaying && !paused) R.drawable.ic_episode_pause else R.drawable.ic_episode_play
            )
            playIcon.alpha = if (isPlaying) 1f else 0.62f
        }

        private fun metaText(e: PodcastEpisode): String {
            val parts = mutableListOf<String>()
            // 书的「发布时间」其实是上传时间，跟第几回没有关系，列出来只会干扰
            if (!bookMode && e.pubDateMs > 0) {
                parts.add(SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(e.pubDateMs)))
            }
            if (e.durationMs > 0) parts.add("${e.durationMs / 60000} 分钟")
            val listened = positionOf(e)
            if (listened > 0 && e.durationMs > 0) {
                val pct = (listened * 100 / e.durationMs).coerceIn(1, 99)
                parts.add("已听 $pct%")
            } else if (listened > 0) {
                parts.add("已听 ${listened / 60000} 分钟")
            }
            return parts.joinToString(" · ")
        }
    }
}
