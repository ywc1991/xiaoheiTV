package com.xiaohei.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xiaohei.tv.R
import com.xiaohei.tv.model.PodcastEpisode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分集列表。
 *
 * 只负责显示，按键全部由 MainActivity 处理：方向键交给 RecyclerView 自己挪焦点
 * （和左栏/右栏的焦点风格保持一致），OK/返回在 Activity 里判。
 */
class EpisodeAdapter(
    private val onPlay: (PodcastEpisode) -> Unit,
    /** 已听到哪儿（毫秒），用来在第二行显示进度 */
    private val positionOf: (PodcastEpisode) -> Long
) : RecyclerView.Adapter<EpisodeAdapter.EpisodeVH>() {

    private val items = mutableListOf<PodcastEpisode>()
    private var playingId: String? = null

    fun submit(list: List<PodcastEpisode>, playing: String? = null) {
        items.clear()
        items.addAll(list)
        playingId = playing
        notifyDataSetChanged()
    }

    fun setPlaying(id: String?) {
        if (playingId == id) return
        playingId = id
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

        init {
            itemView.setOnFocusChangeListener { _, hasFocus ->
                meta.setTextColor(
                    if (hasFocus) androidx.core.content.ContextCompat.getColor(meta.context, R.color.text_primary)
                    else androidx.core.content.ContextCompat.getColor(meta.context, R.color.text_tertiary)
                )
            }
        }

        fun bind(e: PodcastEpisode) {
            title.text = e.title
            title.maxLines = 2
            meta.text = metaText(e)
            meta.visibility = if (meta.text.isEmpty()) View.GONE else View.VISIBLE
            itemView.isActivated = e.id == playingId
            itemView.setOnClickListener { onPlay(e) }
        }

        private fun metaText(e: PodcastEpisode): String {
            val parts = mutableListOf<String>()
            if (e.pubDateMs > 0) {
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
