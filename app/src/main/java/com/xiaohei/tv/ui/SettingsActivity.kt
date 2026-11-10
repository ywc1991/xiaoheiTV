package com.xiaohei.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xiaohei.tv.R
import com.xiaohei.tv.data.SourceStore
import com.xiaohei.tv.update.UpdateChecker
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : Activity() {

    private data class Row(val title: String, val desc: String, val action: () -> Unit)

    private lateinit var store: SourceStore
    private var adapter: SettingsAdapter? = null

    /** 检测到的、比当前安装版本更新的版本；null 表示没有。 */
    private var latest: UpdateChecker.Release? = null
    private var checking = false
    private var lastError: String? = null
    private var updateRowIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = SourceStore(this)

        val recycler = findViewById<RecyclerView>(R.id.settingsList)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = SettingsAdapter(buildRows())
        recycler.adapter = adapter
        // 进来先静默查一次，有新版本直接体现在「检查更新」那一行上，不打断用户
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) doCheck(silent = true)
        }, 400L)
    }

    private fun sourceLabel(): String = when (store.sourceKey) {
        "backup" -> "iptv-org 中国"
        "custom" -> "自定义源"
        else -> "小黑推荐源"
    }

    private fun buildRows(): List<Row> = mutableListOf<Row>().apply {
        add(
            Row(
                "线路自动避坑：已避开 ${store.totalSkipCount} 次",
                "某个台的线路播不出来时，会自动换下一条试，并把这条记下来沉到后面——同一个台下次直接用能播的那条。" +
                    "记录太多可以点这里清空重来（菜单键换源功能已关闭，避免看片时误触）"
            ) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("清空线路记忆？")
                    .setMessage("会忘掉所有「哪条线路不好用」的记录，之后重新从源里的第一条开始试。")
                    .setPositiveButton("清空") { _, _ ->
                        store.resetLineScores()
                        store.resetTotalSkips()
                        toast("已清空线路记忆")
                        recreate()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
        add(
            Row(
                if (store.adBlock) "清单广告过滤：已开启" else "清单广告过滤：已关闭",
                "自动剔除直播流清单里插播的广告分片；画面里烧录进去的广告无法从画面上抠掉，只能靠别的手段。个别源过滤后异常可关掉"
            ) {
                store.adBlock = !store.adBlock
                toast(if (store.adBlock) "已开启广告过滤" else "已关闭广告过滤")
                recreate()
            }
        )
        add(Row("播放源：小黑推荐源", "内置央视 / 卫视 / 港澳台频道，同一频道多个备用线路，附高清台标与节目单") {
            store.sourceKey = "main"
            toast("已切换到小黑推荐源")
            finish()
        })
        add(Row("播放源：iptv-org 中国", "内置 30 个频道，全球最大公开直播源项目的央视切片") {
            store.sourceKey = "backup"
            toast("已切换到 iptv-org 中国")
            finish()
        })
        add(
            Row(
                "播放源：自定义源",
                if (store.customFile().exists()) "已下载：" + store.customUrl else "未下载，请先点下面的在线更新"
            ) {
                store.sourceKey = "custom"
                if (store.customFile().exists()) {
                    finish()
                } else {
                    toast("自定义源未下载，请先在线更新")
                }
            }
        )
        add(Row("自定义源地址", store.customUrl) { showUrlDialog() })
        add(Row("在线更新小黑推荐源", "从 guovin.github.io 拉取最新 result.m3u（覆盖缓存）") {
            download(MAIN_URL, store.mainCacheFile(), "小黑推荐源")
        })
        add(Row("在线更新自定义源", "下载自定义地址的 M3U 到本机") {
            download(store.customUrl, store.customFile(), "自定义源")
        })

        // 检查更新：检测到新版本后，这一行本身就是「下载并安装」的入口
        updateRowIndex = size
        add(Row(updateTitle(), updateDesc()) { onCheckUpdateClicked() })

        add(
            Row(
                "关于",
                "小黑TV ${UpdateChecker.currentVersion(this@SettingsActivity)} · 在线更新 + 线路自动避坑 + 清单过滤 + 高清台标 + 节目单 · 频道均为公开免费直播源"
            ) {
                toast("小黑TV ${UpdateChecker.currentVersion(this@SettingsActivity)}")
            }
        )
    }

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

    private fun refreshRows() {
        val a = adapter ?: return
        a.rows = buildRows()
        if (updateRowIndex >= 0) a.notifyItemChanged(updateRowIndex) else a.notifyDataSetChanged()
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            setText(store.customUrl)
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("自定义 M3U 地址")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                store.customUrl = input.text.toString().trim()
                toast("已保存，请点击在线更新自定义源")
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun download(url: String, target: File, label: String) {
        toast("正在下载$label…")
        Thread {
            var ok = false
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (tmp.length() > 0) {
                        tmp.renameTo(target)
                        ok = true
                    } else {
                        tmp.delete()
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
            runOnUiThread {
                toast(if (ok) "$label 更新成功" else "$label 下载失败，请检查网络或地址")
                if (ok) recreate()
            }
        }.start()
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

    companion object {
        const val MAIN_URL = "https://guovin.github.io/iptv-api/result.m3u"
    }
}
