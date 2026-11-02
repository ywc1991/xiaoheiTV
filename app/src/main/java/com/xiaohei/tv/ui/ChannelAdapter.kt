package com.xiaohei.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.xiaohei.tv.R
import com.xiaohei.tv.data.ChannelGroups
import com.xiaohei.tv.data.EpgEntry
import com.xiaohei.tv.data.EpgRepository
import com.xiaohei.tv.model.Channel

sealed class ListItem {
    data class GroupHeader(val name: String, val count: Int, val expanded: Boolean) : ListItem()
    data class ChannelEntry(val channel: Channel, val number: Int) : ListItem()
}

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onToggleGroup: (String) -> Unit,
    private val onLongClick: (Channel) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ListItem>()
    private var playingName: String? = null
    private val collapsed = mutableSetOf<String>()
    private var allChannels: List<Channel> = emptyList()
    private var favorites: Set<String> = emptySet()
    private var epg: Map<String, EpgEntry> = emptyMap()

    fun submit(channels: List<Channel>, collapsedGroups: Set<String>, favoriteNames: Set<String> = emptySet()) {
        allChannels = channels
        favorites = favoriteNames
        collapsed.clear()
        collapsed.addAll(collapsedGroups)
        rebuild()
    }

    /** 收藏变化时增量刷新（保持当前折叠状态） */
    fun updateFavorites(favoriteNames: Set<String>) {
        favorites = favoriteNames
        rebuild()
    }

    private fun rebuild() {
        items.clear()

        // 1) 收藏置顶（有收藏时才出现）
        val favList = allChannels.filter { it.name in favorites }
        if (favList.isNotEmpty()) addGroup(ChannelGroups.FAVORITE, favList)

        // 2) 固定顺序：央视 → 卫视 → 港澳台 → 其他
        val grouped = allChannels.groupBy { it.group }
        for (g in ChannelGroups.ORDER) {
            if (g == ChannelGroups.FAVORITE) continue
            val list = grouped[g] ?: continue
            if (list.isEmpty()) continue
            addGroup(g, list)
        }

        // 3) 兜底：规则外的分组也不丢频道
        for ((g, list) in grouped) {
            if (g in ChannelGroups.ORDER || list.isEmpty()) continue
            addGroup(g, list)
        }

        notifyDataSetChanged()
    }

    private fun addGroup(name: String, list: List<Channel>) {
        val expanded = name !in collapsed
        items.add(ListItem.GroupHeader(name, list.size, expanded))
        if (expanded) {
            for (c in list) items.add(ListItem.ChannelEntry(c, items.size + 1))
        }
    }

    /** 切换某个分组的折叠/展开（只更新数据，不写持久化） */
    fun toggleGroup(name: String) {
        if (!collapsed.add(name)) collapsed.remove(name)
        rebuild()
    }

    fun isGroupExpanded(name: String): Boolean = name !in collapsed

    fun positionOfGroup(name: String): Int =
        items.indexOfFirst { it is ListItem.GroupHeader && it.name == name }

    /** 当前可见（未折叠）的频道，用于左右切台；按台名去重，避免收藏重复项 */
    fun visibleChannels(): List<Channel> =
        items.filterIsInstance<ListItem.ChannelEntry>().map { it.channel }.distinctBy { it.name }

    fun setPlaying(name: String?) {
        playingName = name
        notifyDataSetChanged()
    }

    /** EPG 就绪后刷新列表，副标题显示「正在播：节目名」 */
    fun setEpg(map: Map<String, EpgEntry>) {
        epg = map
        notifyDataSetChanged()
    }

    fun positionOf(channelName: String): Int =
        items.indexOfFirst { it is ListItem.ChannelEntry && it.channel.name == channelName }

    /** 取指定位置的频道（分组头或越界返回 null） */
    fun channelAt(position: Int): Channel? =
        items.getOrNull(position)?.let { it as? ListItem.ChannelEntry }?.channel

    override fun getItemViewType(position: Int): Int =
        if (items[position] is ListItem.GroupHeader) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            GroupVH(inflater.inflate(R.layout.item_group, parent, false))
        } else {
            ChannelVH(inflater.inflate(R.layout.item_channel, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.GroupHeader -> (holder as GroupVH).bind(item)
            is ListItem.ChannelEntry -> (holder as ChannelVH).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class GroupVH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.groupName)
        private val count: TextView = view.findViewById(R.id.groupCount)
        private val arrow: TextView = view.findViewById(R.id.groupArrow)
        fun bind(item: ListItem.GroupHeader) {
            name.text = item.name
            count.text = "${item.count} 个频道"
            arrow.text = if (item.expanded) "▼" else "▶"
            itemView.setOnClickListener { onToggleGroup(item.name) }
        }
    }

    inner class ChannelVH(view: View) : RecyclerView.ViewHolder(view) {
        private val logo: ImageView = view.findViewById(R.id.channelLogo)
        private val name: TextView = view.findViewById(R.id.channelName)
        private val meta: TextView = view.findViewById(R.id.channelMeta)
        private val badge: TextView = view.findViewById(R.id.playingBadge)

        fun bind(item: ListItem.ChannelEntry) {
            val c = item.channel
            val isFav = c.name in favorites
            name.text = if (isFav) "★ ${c.name}" else c.name
            val epgTitle = epg[EpgRepository.normalize(c.name)]?.current?.title
            meta.text = when {
                !epgTitle.isNullOrEmpty() -> "正在播：$epgTitle"
                c.urls.size > 1 -> "${c.urls.size} 条线路"
                else -> "单线路"
            }
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
