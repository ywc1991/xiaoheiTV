package com.xiaohei.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
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
import com.xiaohei.tv.ui.SettingsActivity

class MainActivity : Activity() {

    private lateinit var store: SourceStore
    private lateinit var adapter: ChannelAdapter
    private lateinit var list: RecyclerView
    private lateinit var listPanel: LinearLayout
    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var infoBar: View
    private lateinit var infoName: TextView
    private lateinit var infoMeta: TextView
    private lateinit var infoProgram: TextView
    private lateinit var errorHint: TextView
    private lateinit var loadingBar: ProgressBar

    private var player: ExoPlayer? = null
    private var channels: List<Channel> = emptyList()
    private var currentChannel: Channel? = null
    private var urlIndex = 0
    private var loadedKey = ""
    private var fullscreen = false
    private var epgMap: Map<String, EpgEntry> = emptyMap()
    private var epgLoading = false
    private val startTime = SystemClock.uptimeMillis()

    /** 启动后 1 秒内忽略点击/按键，防止系统残留事件误触最大化或折叠 */
    private fun justLaunched(): Boolean = SystemClock.uptimeMillis() - startTime < 1000

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoBar.visibility = View.GONE }
    /** 列表可见时，30 秒无操作自动隐藏（进入最大化播放） */
    private val autoHide = Runnable {
        if (!fullscreen) applyFullscreen(true, persist = false)
    }
    private var digitBuffer = ""
    private val digitTimeout = Runnable {
        digitBuffer = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = SourceStore(this)

        listPanel = findViewById(R.id.listPanel)
        list = findViewById(R.id.channelList)
        playerView = findViewById(R.id.playerView)
        infoBar = findViewById(R.id.infoBar)
        infoName = findViewById(R.id.infoChannelName)
        infoMeta = findViewById(R.id.infoMeta)
        infoProgram = findViewById(R.id.infoProgram)
        errorHint = findViewById(R.id.errorHint)
        loadingBar = findViewById(R.id.loadingBar)

        adapter = ChannelAdapter(
            onClick = { channel ->
                if (justLaunched()) return@ChannelAdapter
                // 再次点击正在播放的频道 = 切换最大化/恢复列表
                if (channel.name == currentChannel?.name) {
                    toggleFullscreen()
                } else {
                    playChannel(channel)
                }
            },
            onToggleGroup = { name ->
                if (justLaunched()) return@ChannelAdapter
                store.toggleCollapsed(name)
                adapter.toggleGroup(name)
                val pos = adapter.positionOfGroup(name)
                if (pos >= 0) {
                    list.postDelayed({
                        list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    }, 50)
                }
                resetAutoHide()
            },
            onLongClick = { channel ->
                if (!justLaunched()) toggleFavorite(channel)
            }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        // 焦点始终在频道列表（PlayerView 已设为不可聚焦）
        list.requestFocus()

        player = ExoPlayer.Builder(this).build().also {
            playerView.useController = false
            playerView.player = it
            it.addListener(playerListener)
        }

        // 恢复上次的列表显隐偏好
        applyFullscreen(store.fullscreen, persist = false)

        loadChannels()
    }

    /** 切换播放最大化：隐藏/显示频道列表面板 */
    private fun toggleFullscreen() {
        applyFullscreen(!fullscreen, persist = true)
        showInfo()
        if (fullscreen) {
            Toast.makeText(this, "已最大化播放，按 OK 键打开频道列表", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "已显示频道列表，按 OK 键全屏播放", Toast.LENGTH_SHORT).show()
            restoreListFocus()
        }
    }

    private fun applyFullscreen(on: Boolean, persist: Boolean) {
        fullscreen = on
        listPanel.visibility = if (on) View.GONE else View.VISIBLE
        if (persist) store.fullscreen = on
        if (on) {
            handler.removeCallbacks(autoHide)
        } else {
            resetAutoHide()
        }
    }

    /** 重置自动隐藏计时（列表可见时开始 30 秒倒计时） */
    private fun resetAutoHide() {
        handler.removeCallbacks(autoHide)
        if (!fullscreen) handler.postDelayed(autoHide, 30_000)
    }

    /** 退出全屏后把焦点移回当前播放的频道 */
    private fun restoreListFocus() {
        val pos = adapter.positionOf(currentChannel?.name ?: return)
        if (pos >= 0) {
            list.postDelayed({
                list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
            }, 80)
        }
    }

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
                channels = loaded
                if (channels.isEmpty()) {
                    errorHint.visibility = View.VISIBLE
                    errorHint.text = "频道列表为空，按菜单键检查播放源"
                    return@runOnUiThread
                }
                errorHint.visibility = View.GONE
                val last = store.lastChannelName
                val target = channels.firstOrNull { it.name == last } ?: channels.first()
                // 首次加载：只展开「当前频道」所在分组，其余收起，避免一屏铺满长列表
                if (!store.groupsInitialized) {
                    val allGroups = channels.map { it.group }.toSet()
                    store.collapsedGroups = (allGroups - target.group) - ChannelGroups.FAVORITE
                    store.groupsInitialized = true
                }
                adapter.submit(channels, store.collapsedGroups, store.favorites)
                // playChannel 内部会滚动列表并把焦点找回到当前频道
                playChannel(target)
                resetAutoHide()
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
            // 播放成功时记住当前线路，下次重启同一频道直接用它
            if (playbackState == Player.STATE_READY) {
                store.lastUrlIndex = urlIndex
            }
        }
    }

    private fun playChannel(channel: Channel) {
        currentChannel = channel
        // 重启后续播：同一频道直接跳到上次播放成功的线路
        urlIndex = if (channel.name == store.lastChannelName && store.lastUrlIndex in channel.urls.indices) {
            store.lastUrlIndex
        } else {
            0
        }
        store.lastChannelName = channel.name
        adapter.setPlaying(channel.name)
        errorHint.visibility = View.GONE
        val pos = adapter.positionOf(channel.name)
        if (pos >= 0) list.smoothScrollToPosition(pos)
        showInfo()
        playCurrentUrl()
        resetAutoHide()
        // setPlaying 会全量刷新列表（丢失焦点），这里把焦点找回到当前频道
        restoreListFocus()
    }

    private fun playCurrentUrl() {
        val ch = currentChannel ?: return
        if (urlIndex >= ch.urls.size) return
        val url = ch.urls[urlIndex]
        Log.i("XiaoHeiTV", "play ${ch.name} line[${urlIndex + 1}/${ch.urls.size}] = $url")
        val item = MediaItem.fromUri(url)
        player?.setMediaItem(item)
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

    /** 后台加载节目单（6.8MB XMLTV 流式解析），就绪后刷新列表与信息栏 */
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
                adapter.setEpg(map)
                updateInfoProgram()
                if (!fullscreen) restoreListFocus()
            }
        }.start()
    }

    /** 信息栏第二行：当前频道的「正在播 / 接下来」 */
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

    private fun switchChannel(offset: Int) {
        // 只在可见（未折叠）频道间切换
        val visible = adapter.visibleChannels()
        if (visible.isEmpty()) return
        val idx = visible.indexOfFirst { it.name == currentChannel?.name }
        val next = if (idx < 0) 0 else ((idx + offset) + visible.size) % visible.size
        // playChannel 内部会滚动列表并把焦点找回到当前频道
        playChannel(visible[next])
    }

    /** 长按 OK：收藏 / 取消收藏当前频道 */
    private fun toggleFavorite(ch: Channel) {
        store.toggleFavorite(ch.name)
        adapter.updateFavorites(store.favorites)
        val added = ch.name in store.favorites
        Toast.makeText(
            this,
            if (added) "已收藏：${ch.name}" else "已取消收藏：${ch.name}",
            Toast.LENGTH_SHORT
        ).show()
        resetAutoHide()
        // 焦点留在刚操作的频道上（不要跳回正在播放的台）
        list.postDelayed({
            val pos = adapter.positionOf(ch.name)
            if (pos >= 0) {
                list.scrollToPosition(pos)
                list.postDelayed({
                    list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                }, 50)
            }
        }, 50)
    }

    /** 手动移动列表焦点（TV 上 RecyclerView 默认焦点导航在此环境不可靠） */
    private fun moveListFocus(offset: Int) {
        val count = adapter.itemCount
        if (count == 0) return
        if (!list.hasFocus()) list.requestFocus()
        val focused = list.focusedChild
        val pos = if (focused != null && list.getChildAdapterPosition(focused) >= 0) {
            list.getChildAdapterPosition(focused)
        } else {
            adapter.positionOf(currentChannel?.name ?: "").coerceAtLeast(0)
        }
        val next = ((pos + offset) % count + count) % count
        list.scrollToPosition(next)
        list.postDelayed({
            list.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus()
        }, 50)
        // 焦点停留 800ms 自动播放该频道（电视直播常见交互；再次移动则取消）
        handler.removeCallbacks(focusPlay)
        handler.postDelayed(focusPlay, 800)
    }

    /** 列表焦点停留超时：播放聚焦的频道（分组头上则忽略） */
    private val focusPlay = Runnable {
        val focused = list.focusedChild ?: return@Runnable
        val pos = list.getChildAdapterPosition(focused)
        if (pos < 0) return@Runnable
        val ch = adapter.channelAt(pos) ?: return@Runnable
        if (ch.name != currentChannel?.name) playChannel(ch)
    }

    @SuppressLint("SetTextI18n")
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 启动后 1 秒内吞掉所有系统残留按键（防止误开设置页/误换台/误退出）
        if (justLaunched()) return true
        Log.d("XiaoHeiTV", "onKeyDown keyCode=$keyCode listFocus=${list.hasFocus()}")
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                handler.removeCallbacks(autoHide)
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (fullscreen) {
                    applyFullscreen(false, persist = true)
                    restoreListFocus()
                    return true
                }
                if (infoBar.visibility == View.VISIBLE) {
                    infoBar.visibility = View.GONE
                    handler.removeCallbacks(hideInfo)
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val up = keyCode == KeyEvent.KEYCODE_DPAD_UP
                // 全屏时按上下键唤出频道列表（焦点落在当前播放频道）
                if (fullscreen) {
                    applyFullscreen(false, persist = false)
                    restoreListFocus()
                    return true
                }
                moveListFocus(if (up) -1 else 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 左键：上一频道；右键：下一频道
                switchChannel(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // OK 键：全屏最大化 / 恢复频道列表（全局切换）
                toggleFullscreen()
                return true
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                digitBuffer += (keyCode - KeyEvent.KEYCODE_0).toString()
                handler.removeCallbacks(digitTimeout)
                handler.postDelayed(digitTimeout, 3000)
                val n = digitBuffer.toIntOrNull()
                val visible = adapter.visibleChannels()
                if (n != null && n in 1..visible.size) {
                    playChannel(visible[n - 1])
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        handler.removeCallbacks(autoHide)
    }

    override fun onResume() {
        super.onResume()
        if (loadedKey != store.sourceKey) {
            loadChannels()
        } else {
            player?.play()
            resetAutoHide()
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
