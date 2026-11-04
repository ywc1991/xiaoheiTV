package com.xiaohei.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.xiaohei.tv.R
import com.xiaohei.tv.data.EpgEntry
import com.xiaohei.tv.data.EpgRepository
import com.xiaohei.tv.model.Channel

/**
 * 右栏「频道」列表：只承载**当前选中分组**下的频道，平铺一层，不再内联展开。
 *
 * 点击与长按由 MainActivity 的按键逻辑统一处理（OK 键被 Activity 拦截），
 * 这里的 OnClickListener 只作为触摸/兜底路径保留，不会与 D-pad 冲突。
 */
class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit = {}
) : RecyclerView.Adapter<ChannelAdapter.ChannelVH>() {

    private val items = mutableListOf<Channel>()
    private var playingName: String? = null
    private var favorites: Set<String> = emptySet()
    private var epg: Map<String, EpgEntry> = emptyMap()

    fun submit(channels: List<Channel>, favoriteNames: Set<String> = emptySet()) {
        items.clear()
        // 按台名去重：同一频道在主源里常有多个镜像条目
        items.addAll(channels.distinctBy { it.name })
        favorites = favoriteNames
        notifyDataSetChanged()
    }

    fun updateFavorites(favoriteNames: Set<String>) {
        favorites = favoriteNames
        notifyDataSetChanged()
    }

    fun setPlaying(name: String?) {
        playingName = name
        notifyDataSetChanged()
    }

    fun setEpg(map: Map<String, EpgEntry>) {
        epg = map
        notifyDataSetChanged()
    }

    fun size(): Int = items.size

    fun channelAt(position: Int): Channel? = items.getOrNull(position)

    fun positionOf(channelName: String): Int =
        items.indexOfFirst { it.name == channelName }

    fun channels(): List<Channel> = items.toList()

    fun isFavorite(name: String): Boolean = name in favorites

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelVH =
        ChannelVH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(holder: ChannelVH, position: Int) {
        holder.bind(items[position])
    }

    inner class ChannelVH(view: View) : RecyclerView.ViewHolder(view) {
        private val logo: ImageView = view.findViewById(R.id.channelLogo)
        private val name: TextView = view.findViewById(R.id.channelName)
        private val meta: TextView = view.findViewById(R.id.channelMeta)
        private val badge: TextView = view.findViewById(R.id.playingBadge)
        private val star: TextView = view.findViewById(R.id.channelStar)

        init {
            // 焦点小字变白：直接用代码控制，比 XML 状态列表更可靠
            itemView.setOnFocusChangeListener { _, hasFocus ->
                meta.setTextColor(metaColor(hasFocus))
            }
        }

        private fun metaColor(focused: Boolean): Int =
            if (focused) androidx.core.content.ContextCompat.getColor(meta.context, R.color.text_primary)
            else androidx.core.content.ContextCompat.getColor(meta.context, R.color.text_tertiary)

        fun bind(c: Channel) {
            val isFav = c.name in favorites
            star.visibility = if (isFav) View.VISIBLE else View.GONE
            name.text = c.name
            // 第二行只放「有用的信息」：优先节目名，其次线路数；都没有就整行隐藏，别留空占位
            val epgTitle = epg[EpgRepository.normalize(c.name)]?.current?.title
            val metaText = when {
                !epgTitle.isNullOrEmpty() -> epgTitle
                c.urls.size > 1 -> "${c.urls.size} 条线路"
                else -> ""
            }
            meta.text = metaText
            meta.visibility = if (metaText.isEmpty()) View.GONE else View.VISIBLE
            // 重绘后按当前焦点状态恢复颜色（重绘不会触发 FocusChangeListener）
            meta.setTextColor(metaColor(itemView.isFocused))
            badge.visibility = if (c.name == playingName) View.VISIBLE else View.GONE
            itemView.isActivated = c.name == playingName
            // 优先使用打包的高清台标，未命中则回退 M3U 里的网络台标
            val src = LogoAssets.logoPath(logo.context, c.name) ?: c.logo
            if (src.isNotEmpty()) {
                logo.visibility = View.VISIBLE
                Glide.with(logo).load(src).into(logo)
            } else {
                logo.visibility = View.GONE
            }
            itemView.setOnClickListener { onClick(c) }
            itemView.setOnLongClickListener {
                onLongClick(c)
                true
            }
        }
    }
}
