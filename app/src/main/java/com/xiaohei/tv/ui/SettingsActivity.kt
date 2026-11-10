package com.xiaohei.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xiaohei.tv.R
import com.xiaohei.tv.update.UpdateChecker

/**
 * 设置页。刻意只留两项——检查更新、关于。
 *
 * 之前这里堆了播放源切换、自定义源地址、在线更新、广告过滤开关、线路避坑记录……
 * 结果是把「看电视」这件事的开关混进了配置面板：用户想找的永远是被埋在最后一行。
 * 现在只保留「要不要更新」和「这是什么应用」，其余按默认行为走，不再暴露。
 */
class SettingsActivity : Activity() {

    private data class Row(val title: String, val desc: String, val action: () -> Unit)

    private var adapter: SettingsAdapter? = null

    /** 检测到的、比当前安装版本更新的版本；null 表示没有。 */
    private var latest: UpdateChecker.Release? = null
    private var checking = false
    private var lastError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val recycler = findViewById<RecyclerView>(R.id.settingsList)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = SettingsAdapter(buildRows())
        recycler.adapter = adapter
        // 进来先静默查一次，有新版本直接体现在「检查更新」那一行上，不打断用户
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) doCheck(silent = true)
        }, 400L)
    }

    private fun buildRows(): List<Row> = listOf(
        // 检查更新：检测到新版本后，这一行本身就是「下载并安装」的入口
        Row(updateTitle(), updateDesc()) { onCheckUpdateClicked() },
        Row("关于", "小黑TV ${UpdateChecker.currentVersion(this)} · 版本信息与说明") { showAbout() }
    )

    /* ---------------- 版本检测 ---------------- */

    private fun updateTitle(): String = when {
        checking -> "正在检查更新…"
        latest != null -> "发现新版本 v${latest!!.version}，点此下载安装"
        else -> "检查更新 · 当前 v${UpdateChecker.currentVersion(this)}"
    }

    private fun updateDesc(): String {
        val cur = UpdateChecker.currentVersion(this)
        val r = latest
        if (r != null) {
            val size = UpdateChecker.sizeText(r.apkSize)
            val brief = UpdateChecker.cleanNotes(r.notes)
                .lineSequence()
                .map { it.trim().trimStart('*', '-', ' ').trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.take(48)
                ?: "修复问题、优化播放体验"
            return buildString {
                append("当前 v").append(cur)
                if (size.isNotEmpty()) append(" · 新包 ").append(size)
                append(" · ").append(brief)
            }
        }
        lastError?.let { return "上次检查失败：$it · 点此重试" }
        return "当前 v$cur · 从 GitHub 检查最新版本，发现新版本可直接下载并安装"
    }

    private fun onCheckUpdateClicked() {
        latest?.let {
            showUpdateDialog(it)
            return
        }
        if (checking) return
        doCheck(silent = false)
    }

    private fun doCheck(silent: Boolean) {
        if (checking) return
        checking = true
        refreshRows()
        UpdateChecker.check(this) { result ->
            if (isFinishing) return@check
            checking = false
            val cur = UpdateChecker.currentVersion(this)
            val r = result.release
            if (r != null && UpdateChecker.isNewer(r.version, cur)) {
                latest = r
                lastError = null
                refreshRows()
                if (silent) toast("发现新版本 v${r.version}，可在设置里更新") else showUpdateDialog(r)
            } else {
                latest = null
                lastError = result.error
                refreshRows()
                if (!silent) {
                    toast(result.error?.let { "检查更新失败：$it" } ?: "已是最新版本 v$cur")
                }
            }
        }
    }

    private fun showUpdateDialog(r: UpdateChecker.Release) {
        val cur = UpdateChecker.currentVersion(this)
        val size = UpdateChecker.sizeText(r.apkSize)
        val body = buildString {
            append("当前 v").append(cur).append("  →  新版本 v").append(r.version)
            if (size.isNotEmpty()) append("（").append(size).append("）")
            val notes = UpdateChecker.cleanNotes(r.notes)
            if (notes.isNotEmpty()) {
                append("\n\n")
                append(notes.take(600))
                if (notes.length > 600) append("…")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v${r.version}")
            .setMessage(body)
            .setPositiveButton("下载并安装") { _, _ ->
                UpdateChecker.downloadAndInstall(this, r) {
                    UpdateChecker.showPermissionDialog(this)
                }
            }
            .setNegativeButton("以后再说", null)
            .show()
    }

    private fun showAbout() {
        val v = UpdateChecker.currentVersion(this)
        AlertDialog.Builder(this)
            .setTitle("关于小黑TV")
            .setMessage(
                "版本 v$v\n\n" +
                    "一个只用遥控器就能看完的电视直播应用：左栏分组、右栏频道，" +
                    "全屏上下键直接切台，返回键只做界面回退、不会退出。\n\n" +
                    "内置央视 / 卫视 / 港澳台频道，同一频道带多条备用线路；" +
                    "播放清单里插播的广告分片会被自动剔除。\n\n" +
                    "所有频道均为公开免费直播源，内容版权归各电视台所有，本应用不存储、不分发任何视频内容。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun refreshRows() {
        val a = adapter ?: return
        a.rows = buildRows()
        // 只有两项，整表刷新即可，不必算索引
        a.notifyDataSetChanged()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private class SettingsAdapter(var rows: List<Row>) :
        RecyclerView.Adapter<SettingsAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.settingTitle)
            val desc: TextView = view.findViewById(R.id.settingDesc)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.title.text = row.title
            holder.desc.text = row.desc
            holder.itemView.setOnClickListener { row.action() }
        }

        override fun getItemCount(): Int = rows.size
    }
}
