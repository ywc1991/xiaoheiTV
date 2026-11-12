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

    // ---------------------------------------------------------------- 线路直通表
    // 只记「上次真正出过图的那条线路」，让下次起播直接跳过慢线路/死线路。
    // 不做失败计数、不屏蔽任何线路、无 UI，和已移除的避坑模块无关。

    fun goodLine(name: String): String? = prefs.getString("goodLine:$name", null)

    fun setGoodLine(name: String, url: String) =
        prefs.edit().putString("goodLine:$name", url).apply()

    fun clearGoodLine(name: String) = prefs.edit().remove("goodLine:$name").apply()

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
                // 主源始终以 APK 内置的 assets 为准。
                // 历史上留过一个 main.m3u 缓存读取分支，但全工程没有任何地方写入它；
                // 若旧版本在设备私有目录残留过旧文件，会让新内置源永远不生效。
                appContext.assets.open("channels_main.m3u").bufferedReader().readText()
            }
        }
        return M3uParser.parse(text)
    }
}
