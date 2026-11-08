package com.xiaohei.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : Activity() {

    private data class Row(val title: String, val desc: String, val action: () -> Unit)

    private lateinit var store: SourceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = SourceStore(this)

        val recycler = findViewById<RecyclerView>(R.id.settingsList)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = SettingsAdapter(buildRows())
    }

    private fun sourceLabel(): String = when (store.sourceKey) {
        "backup" -> "iptv-org 中国"
        "custom" -> "自定义源"
        else -> "小黑推荐源"
    }

    private fun buildRows(): List<Row> = listOf(
        Row(
            if (store.adBlock) "广告过滤：已开启" else "广告过滤：已关闭",
            "自动剔除直播流里插播的广告分片（画面烧录型广告无法去除）；个别源过滤后异常可关掉"
        ) {
            store.adBlock = !store.adBlock
            toast(if (store.adBlock) "已开启广告过滤" else "已关闭广告过滤")
            recreate()
        },
        Row("播放源：小黑推荐源", "内置央视 / 卫视 / 港澳台频道，自动测速多线路，附高清台标与节目单") {
            store.sourceKey = "main"
            toast("已切换到小黑推荐源")
            finish()
        },
        Row("播放源：iptv-org 中国", "内置 30 个频道，全球最大公开直播源项目的央视切片") {
            store.sourceKey = "backup"
            toast("已切换到 iptv-org 中国")
            finish()
        },
        Row("播放源：自定义源", if (store.customFile().exists()) "已下载：" + store.customUrl else "未下载，请先点下面的在线更新") {
            store.sourceKey = "custom"
            if (store.customFile().exists()) {
                finish()
            } else {
                toast("自定义源未下载，请先在线更新")
            }
        },
        Row("自定义源地址", store.customUrl) {
            showUrlDialog()
        },
        Row("在线更新小黑推荐源", "从 guovin.github.io 拉取最新 result.m3u（覆盖缓存）") {
            download(MAIN_URL, store.mainCacheFile(), "小黑推荐源")
        },
        Row("在线更新自定义源", "下载自定义地址的 M3U 到本机") {
            download(store.customUrl, store.customFile(), "自定义源")
        },
        Row("关于", "小黑TV 1.9.1 · 广告过滤 + 高清台标 + 节目单 · 频道均为公开免费直播源") {
            toast("小黑TV 1.9.1")
        }
    )

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

    private class SettingsAdapter(private val rows: List<Row>) :
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
