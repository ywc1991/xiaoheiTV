package com.xiaohei.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xiaohei.tv.data.ChannelGroups
import com.xiaohei.tv.data.EpgEntry
import com.xiaohei.tv.data.EpgRepository
import com.xiaohei.tv.data.SourceStore
import com.xiaohei.tv.model.Channel
import com.xiaohei.tv.ui.ChannelAdapter
import com.xiaohei.tv.ui.GroupAdapter
import com.xiaohei.tv.ui.GroupEntry
import com.xiaohei.tv.ui.SettingsActivity

/**
 * 两栏式频道浏览：
 *
 *   左栏＝分组（收藏 / 央视 / 卫视 / 港澳台 / 其他）
 *   右栏＝当前选中分组下的频道
 *
 * 按键：
 *   ↑↓      左栏切分组（右栏即时跟随） / 右栏移动高亮（不自动播放）
 *   OK / →  左栏时进入右栏（「向右展开」）；右栏时播放高亮频道
 *   ← / 返回 焦点回到左栏（「收起」）
 *   长按 OK  收藏 / 取消收藏
 *   返回     逐层退出：右栏 → 左栏 → 全屏 → 回到电视首页
 *
 * 所有按键统一在 [dispatchKeyEvent] 里处理，避免 RecyclerView 抢走 OK 与左右键
 * （1.6.3 的 bug 就是 OK/左右键被 onKeyDown 全局拦截成「全屏/切台」，
 *  导致分组永远展不开）。
 */
class MainActivity : Activity() {

    private data class GroupBucket(val name: String, val channels: List<Channel>)

    private lateinit var store: SourceStore

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var groupList: RecyclerView
    private lateinit var channelList: RecyclerView
    private lateinit var listPanel: LinearLayout
    private lateinit var laneTitle: TextView
    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var infoBar: View
    private lateinit var infoName: TextView
    private lateinit var infoMeta: TextView
    private lateinit var infoProgram: TextView
    private lateinit var errorHint: TextView
    private lateinit var loadingBar: ProgressBar

    private var player: ExoPlayer? = null
    private var allChannels: List<Channel> = emptyList()
    private var groups: List<GroupBucket> = emptyList()
    private var groupIndex = 0

    private var currentChannel: Channel? = null
    private var urlIndex = 0
    private var loadedKey = ""
    private var fullscreen = false
    private var epgMap: Map<String, EpgEntry> = emptyMap()
    private var epgLoading = false

    /** true＝焦点在左栏分组，false＝焦点在右栏频道 */
    private var focusInGroups = true

    /** 右栏正在移动焦点的目标位置（连按方向键时用它做基准，避免 30ms 内多次按键算错） */
    private var pendingChannelPos = -1
    private var pendingChannelAt = 0L

    /** 最近一次把焦点移到右栏第几项，用于取消尚未执行完的焦点任务 */
    private var focusChannelTask: Runnable? = null

    /** 长按 OK 已触发（用于区分短按与长按） */
    private var longPressFired = false

    /** OK 按下的时刻，用于在抬起时比较按住时长 */
    private var okDownTime = 0L

    /** 判定为长按的按住时长阈值（毫秒） */
    private val longPressThreshold = 400L

    private val startTime = SystemClock.uptimeMillis()

    /** 启动后 1 秒内忽略按键，防止系统残留事件误触 */
    private fun justLaunched(): Boolean = SystemClock.uptimeMillis() - startTime < 1000

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoBar.visibility = View.GONE }
    private val longPressRunnable = Runnable {
        longPressFired = true
        val ch = focusedChannel()
        if (ch != null) toggleFavorite(ch)
    }
    private var digitBuffer = ""
    private val digitTimeout = Runnable { digitBuffer = "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = SourceStore(this)

        listPanel = findViewById(R.id.listPanel)
        groupList = findViewById(R.id.groupList)
        channelList = findViewById(R.id.channelList)
        laneTitle = findViewById(R.id.laneTitle)
        playerView = findViewById(R.id.playerView)
        infoBar = findViewById(R.id.infoBar)
        infoName = findViewById(R.id.infoChannelName)
        infoMeta = findViewById(R.id.infoMeta)
        infoProgram = findViewById(R.id.infoProgram)
        errorHint = findViewById(R.id.errorHint)
        loadingBar = findViewById(R.id.loadingBar)

        groupAdapter = GroupAdapter { index ->
            if (justLaunched()) return@GroupAdapter
            selectGroup(index)
            enterChannels()
        }
        channelAdapter = ChannelAdapter(
            onClick = { channel ->
                if (justLaunched()) return@ChannelAdapter
                if (channel.name == currentChannel?.name) toggleFullscreen() else playChannel(channel)
            },
            onLongClick = { channel ->
                // 触摸长按本身就是明确意图（只可能发生在频道项上），不必再判断焦点在哪一栏
                if (!justLaunched()) toggleFavorite(channel, force = true)
            }
        )

        groupList.layoutManager = LinearLayoutManager(this)
        groupList.adapter = groupAdapter
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter
        groupList.requestFocus()

        player = ExoPlayer.Builder(this).build().also {
            playerView.useController = false
            playerView.player = it
            it.addListener(playerListener)
        }

        // 启动时始终显示两栏列表：不再恢复「上次是全屏」的状态，
        // 否则用户开机后看不到列表，会以为分组功能没了（按方向键才唤出）
        applyFullscreen(false, persist = false)
        loadChannels()
    }

    // ---------------------------------------------------------------- 分组 / 频道

    /** 按固定顺序把频道装进分组桶；「收藏」为空时不显示 */
    private fun buildGroups(favoriteNames: Set<String>) {
        val buckets = mutableListOf<GroupBucket>()

        val favs = allChannels.filter { it.name in favoriteNames }
        if (favs.isNotEmpty()) buckets.add(GroupBucket(ChannelGroups.FAVORITE, favs))

        val byGroup = allChannels.groupBy { it.group }
        for (g in listOf(ChannelGroups.CCTV, ChannelGroups.SATELLITE, ChannelGroups.HKMO_TW, ChannelGroups.OTHER)) {
            val list = byGroup[g] ?: continue
            if (list.isEmpty()) continue
            buckets.add(GroupBucket(g, list))
        }
        // 规则外的分组兜底，避免丢频道
        for ((g, list) in byGroup) {
            if (g in ChannelGroups.ORDER || list.isEmpty()) continue
            buckets.add(GroupBucket(g, list))
        }
        groups = buckets

        groupAdapter.submit(
            buckets.map { GroupEntry(it.name, it.channels.size) },
            groupIndex.coerceIn(0, (buckets.size - 1).coerceAtLeast(0))
        )
    }

    /** 切换左栏选中分组，右栏即时刷新 */
    private fun selectGroup(index: Int) {
        if (groups.isEmpty()) return
        groupIndex = index.coerceIn(0, groups.size - 1)
        val bucket = groups[groupIndex]
        groupAdapter.setSelected(groupIndex)
        channelAdapter.submit(bucket.channels, store.favorites)
        pendingChannelPos = -1
        laneTitle.text = "${bucket.name} · ${bucket.channels.size}"
        // 若当前播放台属于该组，滚到它
        val pos = channelAdapter.positionOf(currentChannel?.name ?: "")
        if (pos >= 0) channelList.scrollToPosition(pos)
    }

    /** 焦点进入右栏（「向右展开」） */
    private fun enterChannels() {
        if (groups.isEmpty()) return
        val bucket = groups[groupIndex]
        if (bucket.channels.isEmpty()) return
        focusInGroups = false
        channelList.requestFocus()
        var pos = channelAdapter.positionOf(currentChannel?.name ?: "")
        if (pos < 0) pos = 0
        pendingChannelPos = -1
        focusChannelAt(pos)
    }

    /** 焦点回到左栏（「收起」） */
    private fun backToGroups() {
        focusInGroups = true
        pendingChannelPos = -1
        groupList.requestFocus()
        focusGroupAt(groupIndex)
    }

    private fun focusGroupAt(index: Int) {
        groupList.scrollToPosition(index)
        groupList.postDelayed({
            groupList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }, 30)
    }

    private fun focusChannelAt(index: Int) {
        focusChannelTask?.let { handler.removeCallbacks(it) }
        // 只在目标不在可视范围内时滚动，并让它落在偏上位置（留出后续 ↑↓ 的空间）。
        // 直接 scrollToPosition 会让目标贴边，回绕到列表开头时还会算错位置。
        val lm = channelList.layoutManager as? LinearLayoutManager
        if (lm != null) {
            val first = lm.findFirstVisibleItemPosition()
            val last = lm.findLastVisibleItemPosition()
            if (index < first || index > last || first == RecyclerView.NO_POSITION) {
                lm.scrollToPositionWithOffset(index, channelList.height / 3)
            }
        }
        val task = Runnable {
            val vh = channelList.findViewHolderForAdapterPosition(index)
            val target = vh?.itemView ?: channelList.getChildAt(0)
            target?.requestFocus()
        }
        focusChannelTask = task
        handler.postDelayed(task, 30)
    }

    private fun focusedChannel(): Channel? {
        if (focusInGroups) return currentChannel
        return channelAdapter.channelAt(focusedChannelPosition()) ?: currentChannel
    }

    private fun focusedChannelPosition(): Int {
        val focused = channelList.focusedChild
        if (focused != null) {
            val pos = channelList.getChildAdapterPosition(focused)
            if (pos >= 0) return pos
        }
        // 焦点意外丢失时用「上次目标位置」或「正在播放的台」兜底，别复位到 0 导致跳台
        if (pendingChannelPos >= 0) return pendingChannelPos
        val playing = channelAdapter.positionOf(currentChannel?.name ?: "")
        return if (playing >= 0) playing else 0
    }

    private fun moveGroupFocus(offset: Int) {
        val n = groups.size
        if (n == 0) return
        val next = ((groupIndex + offset) % n + n) % n
        selectGroup(next)
        focusGroupAt(next)
    }

    private fun moveChannelFocus(offset: Int) {
        val n = channelAdapter.size()
        if (n == 0) return
        // 连按方向键时 30ms 内的焦点任务还没落地，用上次的目标位置做基准，避免漏格
        val now = SystemClock.uptimeMillis()
        val base = if (pendingChannelPos >= 0 && now - pendingChannelAt < 400) {
            pendingChannelPos
        } else {
            focusedChannelPosition()
        }
        val next = ((base + offset) % n + n) % n
        pendingChannelPos = next
        pendingChannelAt = now
        // 只移动高亮，不自动播放：等用户按 OK 确认后再切台，
        // 避免一路按方向键时把中间每一台都拉起来缓冲
        focusChannelAt(next)
    }

    // ---------------------------------------------------------------- 全屏

    private fun toggleFullscreen() {
        applyFullscreen(!fullscreen, persist = true)
        showInfo()
        if (fullscreen) {
            Toast.makeText(this, "已最大化播放，按方向键打开频道列表", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "已显示频道列表", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyFullscreen(on: Boolean, persist: Boolean) {
        fullscreen = on
        listPanel.visibility = if (on) View.GONE else View.VISIBLE
        if (persist) store.fullscreen = on
        if (!on) backToGroups()
    }

    // ---------------------------------------------------------------- 加载

    private fun loadChannels() {
        loadedKey = store.sourceKey
        loadingBar.visibility = View.VISIBLE
        Thread {
            val loaded = try {
                store.loadChannels()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                loadingBar.visibility = View.GONE
                allChannels = loaded
                if (allChannels.isEmpty()) {
                    errorHint.visibility = View.VISIBLE
                    errorHint.text = "频道列表为空，按菜单键检查播放源"
                    return@runOnUiThread
                }
                errorHint.visibility = View.GONE
                val last = store.lastChannelName
                val target = allChannels.firstOrNull { it.name == last } ?: allChannels.first()

                buildGroups(store.favorites)
                // 打开就停在当前频道所在分组
                val idx = groups.indexOfFirst { b -> b.channels.any { it.name == target.name } }
                selectGroup(if (idx >= 0) idx else 0)

                playChannel(target)
                focusInGroups = true
                groupList.requestFocus()
                focusGroupAt(groupIndex)
                loadEpg()
                if (target.name == last) {
                    Toast.makeText(this, "继续播放：${target.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val ch = currentChannel ?: return
            Log.w(
                "XiaoHeiTV",
                "playError ${ch.name} line[${urlIndex + 1}/${ch.urls.size}] = " +
                    "${ch.urls.getOrNull(urlIndex)} cause=${error.cause?.javaClass?.simpleName}:${error.cause?.message}"
            )
            if (urlIndex < ch.urls.size - 1) {
                urlIndex++
                playCurrentUrl()
            } else {
                errorHint.visibility = View.VISIBLE
                errorHint.text = "所有线路均无法播放：${ch.name}"
                infoMeta.text = "${ch.group} · 线路全部失败"
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                errorHint.visibility = View.GONE
            }
            if (playbackState == Player.STATE_READY) {
                store.lastUrlIndex = urlIndex
            }
        }
    }

    private fun playChannel(channel: Channel) {
        currentChannel = channel
        // 同一个台继续播，直接沿用上次成功的线路
        if (channel.name == store.lastChannelName && store.lastUrlIndex in channel.urls.indices) {
            urlIndex = store.lastUrlIndex
        } else {
            urlIndex = 0
        }
        store.lastChannelName = channel.name
        channelAdapter.setPlaying(channel.name)
        pendingChannelPos = -1
        errorHint.visibility = View.GONE
        val pos = channelAdapter.positionOf(channel.name)
        if (pos >= 0) channelList.smoothScrollToPosition(pos)
        showInfo()
        playCurrentUrl()
        // setPlaying 会整列重绘，把焦点找回来（不打断用户当前所在栏）
        if (focusInGroups) {
            groupList.postDelayed({ focusGroupAt(groupIndex) }, 60)
        } else {
            if (pos >= 0) channelList.postDelayed({ focusChannelAt(pos) }, 60)
        }
    }

    private fun playCurrentUrl() {
        val ch = currentChannel ?: return
        if (urlIndex >= ch.urls.size) return
        val url = ch.urls[urlIndex]
        Log.i("XiaoHeiTV", "play ${ch.name} line[${urlIndex + 1}/${ch.urls.size}] = $url")
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.prepare()
        player?.play()
        infoMeta.text = "${ch.group} · 线路 ${urlIndex + 1}/${ch.urls.size}"
    }

    private fun showInfo() {
        infoBar.visibility = View.VISIBLE
        infoName.text = currentChannel?.name ?: ""
        updateInfoProgram()
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 4000)
    }

    private fun loadEpg() {
        if (epgLoading) return
        epgLoading = true
        Thread {
            val map = try {
                EpgRepository.load(this)
            } catch (_: Exception) {
                emptyMap()
            }
            runOnUiThread {
                epgLoading = false
                if (map.isEmpty()) return@runOnUiThread
                epgMap = map
                channelAdapter.setEpg(map)
                updateInfoProgram()
                if (!fullscreen) {
                    if (focusInGroups) focusGroupAt(groupIndex) else focusChannelAt(focusedChannelPosition())
                }
            }
        }.start()
    }

    private fun updateInfoProgram() {
        val ch = currentChannel
        val entry = if (ch != null) epgMap[EpgRepository.normalize(ch.name)] else null
        val cur = entry?.current?.title
        val nx = entry?.next?.title
        val text = when {
            !cur.isNullOrEmpty() && !nx.isNullOrEmpty() -> "正在播：$cur　接下来：$nx"
            !cur.isNullOrEmpty() -> "正在播：$cur"
            !nx.isNullOrEmpty() -> "接下来：$nx"
            else -> ""
        }
        infoProgram.text = text
        infoProgram.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 长按 OK：收藏 / 取消收藏 */
    private fun toggleFavorite(ch: Channel, force: Boolean = false) {
        // 焦点在分组栏时，长按 OK 不应该收藏「当前正在播的台」（那是误触）
        if (focusInGroups && !force) return
        store.toggleFavorite(ch.name)
        val added = ch.name in store.favorites
        // 收藏变化会影响「收藏」分组的成员，需要重建分组再恢复选中项
        val keepGroup = if (groups.getOrNull(groupIndex)?.name == ChannelGroups.FAVORITE) {
            ChannelGroups.FAVORITE
        } else {
            groups.getOrNull(groupIndex)?.name
        }
        buildGroups(store.favorites)
        val idx = groups.indexOfFirst { it.name == keepGroup }
        selectGroup(if (idx >= 0) idx else 0)
        channelAdapter.updateFavorites(store.favorites)
        Toast.makeText(
            this,
            if (added) "已收藏：${ch.name}" else "已取消收藏：${ch.name}",
            Toast.LENGTH_SHORT
        ).show()
        channelList.postDelayed({
            val pos = channelAdapter.positionOf(ch.name)
            if (pos >= 0) focusChannelAt(pos)
        }, 60)
    }

    // ---------------------------------------------------------------- 按键

    private fun handleOkShort() {
        if (fullscreen) {
            applyFullscreen(false, persist = false)
            return
        }
        if (focusInGroups) {
            enterChannels()
            return
        }
        val ch = focusedChannel() ?: return
        if (ch.name == currentChannel?.name) toggleFullscreen() else playChannel(ch)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER

        // 启动后 1 秒内吞掉方向键与 OK，避免系统残留事件误触
        if (justLaunched() && (isOk || code == KeyEvent.KEYCODE_DPAD_UP ||
                code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT ||
                code == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            return true
        }

        // ---- OK / ENTER：短按与长按分开处理 ----
        if (isOk) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    longPressFired = false
                    okDownTime = event.eventTime
                    // 按住 450ms 立即收藏（即时反馈）；即使这个计时器被主线程拖延，
                    // 抬起时还会用「按住时长」再判一次，双保险
                    handler.postDelayed(longPressRunnable, 450)
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longPressRunnable)
                val held = event.eventTime - okDownTime
                if (held >= longPressThreshold) {
                    // 长按：若计时器没来得及跑，这里补一次
                    if (!longPressFired) {
                        longPressFired = true
                        focusedChannel()?.let {
                            if (!focusInGroups) toggleFavorite(it)
                        }
                    }
                } else if (!longPressFired) {
                    handleOkShort()
                }
                longPressFired = false
                return true
            }
        }

        if (event.action != KeyEvent.ACTION_DOWN) {
            // 已处理过的按键，抬起事件一并吞掉，避免重复触发
            return when (code) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> super.dispatchKeyEvent(event)
            }
        }

        when (code) {
            KeyEvent.KEYCODE_MENU -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // 返回键逐层退出，最后一层回到电视首页：
                //   焦点在右栏 → 回到左栏
                //   焦点在左栏（列表展开）→ 收起面板，全屏播放
                //   已经在全屏 → 退出应用，回到桌面
                if (fullscreen) {
                    finish()
                    return true
                }
                if (!focusInGroups) {
                    backToGroups()
                    return true
                }
                applyFullscreen(true, persist = true)
                showInfo()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val up = code == KeyEvent.KEYCODE_DPAD_UP
                if (fullscreen) {
                    applyFullscreen(false, persist = false)
                    return true
                }
                if (focusInGroups) moveGroupFocus(if (up) -1 else 1) else moveChannelFocus(if (up) -1 else 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (fullscreen) {
                    applyFullscreen(false, persist = false)
                    return true
                }
                // 右键＝展开：从分组栏进入右栏
                if (focusInGroups) enterChannels()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (fullscreen) {
                    applyFullscreen(false, persist = false)
                    return true
                }
                // 左键＝收起：从右栏回到分组栏
                if (!focusInGroups) backToGroups()
                return true
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                digitBuffer += (code - KeyEvent.KEYCODE_0).toString()
                handler.removeCallbacks(digitTimeout)
                handler.postDelayed(digitTimeout, 3000)
                val n = digitBuffer.toIntOrNull()
                if (n != null && n in 1..channelAdapter.size()) {
                    val ch = channelAdapter.channelAt(n - 1)
                    if (ch != null) {
                        focusInGroups = false
                        playChannel(ch)
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (loadedKey != store.sourceKey) {
            loadChannels()
        } else {
            player?.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
    }
}
