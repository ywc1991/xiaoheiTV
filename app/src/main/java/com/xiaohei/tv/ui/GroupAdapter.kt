package com.xiaohei.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xiaohei.tv.R
import com.xiaohei.tv.data.ChannelGroups

/** 左栏的一项：分组名 + 频道数 */
data class GroupEntry(val name: String, val count: Int)

/**
 * 左栏「分组」列表。
 *
 * 只负责显示与选中态，点击/按键行为由 MainActivity 统一处理
 * （OK 与左右键在 Activity 里拦截，避免被 RecyclerView 抢走）。
 */
class GroupAdapter(
    private val onGroupClick: (Int) -> Unit
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    private val items = mutableListOf<GroupEntry>()
    private var selected = 0

    fun submit(list: List<GroupEntry>, selectedIndex: Int) {
        items.clear()
        items.addAll(list)
        selected = clamp(selectedIndex)
        notifyDataSetChanged()
    }

    /** 只改选中项，避免整列重绘导致焦点丢失 */
    fun setSelected(index: Int) {
        val old = selected
        selected = clamp(index)
        if (old in items.indices) notifyItemChanged(old)
        if (selected in items.indices && selected != old) notifyItemChanged(selected)
    }

    private fun clamp(index: Int): Int = when {
        items.isEmpty() -> 0
        index < 0 -> 0
        index >= items.size -> items.size - 1
        else -> index
    }

    fun size(): Int = items.size

    fun selectedIndex(): Int = selected

    fun nameAt(index: Int): String? = items.getOrNull(index)?.name

    fun indexOf(name: String): Int = items.indexOfFirst { it.name == name }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_group_lane, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position == selected)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.groupName)
        private val count: TextView = view.findViewById(R.id.groupCount)
        private val arrow: TextView = view.findViewById(R.id.groupArrow)
        private val indicator: View = view.findViewById(R.id.groupIndicator)

        fun bind(item: GroupEntry, isSelected: Boolean) {
            name.text = item.name
            count.text = "${item.count}"
            // 收藏分组用星标代替数字前缀，视觉上一眼能认出来
            arrow.text = if (item.name == ChannelGroups.FAVORITE) "★" else "►"
            itemView.isActivated = isSelected
            indicator.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            itemView.setOnClickListener { onGroupClick(bindingAdapterPosition) }
        }
    }
}
