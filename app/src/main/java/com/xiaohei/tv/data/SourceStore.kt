package com.xiaohei.tv.data

import android.content.Context
import com.xiaohei.tv.model.Channel
import java.io.File

class SourceStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("xiaohei_tv", Context.MODE_PRIVATE)

    var sourceKey: String
        get() = prefs.getString("sourceKey", "main") ?: "main"
        set(v) = prefs.edit().putString("sourceKey", v).apply()

    var customUrl: String
        get() = prefs.getString("customUrl", "https://guovin.github.io/iptv-api/result.m3u") ?: ""
        set(v) = prefs.edit().putString("customUrl", v).apply()

    var lastChannelName: String?
        get() = prefs.getString("lastChannel", null)
        set(v) = prefs.edit().putString("lastChannel", v).apply()

    /** 上次频道实际播放成功的线路序号（重启后跳过失效线路直接续播） */
    var lastUrlIndex: Int
        get() = prefs.getInt("lastUrlIndex", 0)
        set(v) = prefs.edit().putInt("lastUrlIndex", v).apply()

    /** 被折叠（收起）的分组名集合 */
    var collapsedGroups: Set<String>
        get() = prefs.getStringSet("collapsedGroups", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("collapsedGroups", v).apply()

    /** 切换某个分组的折叠状态 */
    fun toggleCollapsed(group: String) {
        val s = collapsedGroups.toMutableSet()
        if (!s.add(group)) s.remove(group)
        collapsedGroups = s
    }

    /** 是否已按「当前频道所在分组」初始化过折叠状态（只在首次加载时做一次） */
    var groupsInitialized: Boolean
        get() = prefs.getBoolean("groupsInitialized", false)
        set(v) = prefs.edit().putBoolean("groupsInitialized", v).apply()

    /** 收藏的频道名集合（置顶「收藏」分组的数据源） */
    var favorites: Set<String>
        get() = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("favorites", v.toSet()).apply()

    /** 切换收藏状态 */
    fun toggleFavorite(name: String) {
        val s = favorites.toMutableSet()
        if (!s.add(name)) s.remove(name)
        favorites = s
    }

    /** 是否处于播放最大化（隐藏频道列表）模式 */
    var fullscreen: Boolean
        get() = prefs.getBoolean("fullscreen", false)
        set(v) = prefs.edit().putBoolean("fullscreen", v).apply()

    /** 是否开启直播流的广告过滤（1.9.0 起默认开启） */
    var adBlock: Boolean
        get() = prefs.getBoolean("adBlock", true)
        set(v) = prefs.edit().putBoolean("adBlock", v).apply()

    fun filesDir(): File = appContext.filesDir

    fun customFile() = File(appContext.filesDir, "custom.m3u")

    fun mainCacheFile() = File(appContext.filesDir, "main.m3u")

    fun loadChannels(): List<Channel> {
        val text: String = when (sourceKey) {
            "backup" -> appContext.assets.open("channels_backup.m3u").bufferedReader().readText()
            "custom" -> {
                val f = customFile()
                if (f.exists()) f.readText() else appContext.assets.open("channels_main.m3u").bufferedReader().readText()
            }
            else -> {
                val f = mainCacheFile()
                if (f.exists()) f.readText() else appContext.assets.open("channels_main.m3u").bufferedReader().readText()
            }
        }
        return M3uParser.parse(text)
    }
}
