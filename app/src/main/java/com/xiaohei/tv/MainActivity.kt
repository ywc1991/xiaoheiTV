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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xiaohei.tv.data.ChannelGroups
import com.xiaohei.tv.data.EpgEntry
import com.xiaohei.tv.data.EpgRepository
import com.xiaohei.tv.data.SourceStore
import com.xiaohei.tv.model.Channel
import com.xiaohei.tv.player.AdFilterDataSourceFactory
import com.xiaohei.tv.ui.ChannelAdapter
import com.xiaohei.tv.ui.GroupAdapter
import com.xiaohei.tv.ui.GroupEntry
import com.xiaohei.tv.ui.SettingsActivity
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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

    /**
     * 当前频道的线路优先顺序（存 urls 的原始下标）。
     * 只把 IPv6 字面量地址排到最后，其余保持源里的原始顺序，不再记录「哪条线路不好用」。
     */
    private var lineOrder: List<Int> = emptyList()

    /** 当前用的是 lineOrder 里的第几项 */
    private var orderPos = 0

    /** 本轮自动换源已经试过几条，防止无限循环 */
    private var autoTries = 0

    /** 本次起播的请求时刻，用于打印「从点台到出图」的耗时 */
    private var playStartedAt = 0L

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
        relaxTls()
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

        player = ExoPlayer.Builder(this)
            .setLoadControl(
                // 起播快慢的开关就在这里。库的默认值是按点播调的（缓冲够 2.5 秒才开播），
                // 直播切台时这几秒就白等了。这里把「起播门槛」压到 0.5 秒：
                // 只要解出半秒画面就先出图，剩下继续后台缓冲，卡了再回落一段。
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        MIN_BUFFER_MS,          // 5s  最低缓冲
                        MAX_BUFFER_MS,          // 20s 最高缓冲
                        BUFFER_FOR_START_MS,    // 0.5s 够就开播
                        BUFFER_AFTER_REBUFFER_MS // 1s  卡顿后恢复的门槛
                    )
                    .build()
            )
            .setMediaSourceFactory(
                // 播放列表先经广告过滤器再交给解码器：只改清单，不动画面
                DefaultMediaSourceFactory(
                    AdFilterDataSourceFactory(this) { store.adBlock }
                )
                    // 免费源的死线路占比很高，而库默认会重试 3 次、每次还退避等待：
                    // 实测一条连不上的线路要白等 4.3 秒才轮到下一条，用户看到的就是「点了没反应」。
                    // 这里改成不重试——失败立刻回来，由 onPlayerError 直接换下一条，
                    // 同样的死线路 0.6 秒就能跳过去。场上还有 4 条备选，重试没有意义。
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            )
            .build()
            .also {
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
        scheduleWarmUp()
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
        scheduleWarmUp()
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
        scheduleWarmUp()
    }

    // ---------------------------------------------------------------- 预热
    // 切台慢的一大半不在解码，而在「等连接」：电视到源站往往要 200~800ms 才建好 TCP，
    // 建完还要再等一次播放列表回来。这些都发生在按下 OK 之后，用户看到的就是黑屏等待。
    //
    // 但焦点停在某个台上时，用户下一步十有八九就是播它。所以这里在焦点停稳 260ms 后，
    // 先去把这个台第一条线路的播放列表拉一遍（只有几百字节），把 TCP 连接建好、
    // 读完响应让 socket 回到连接池。等真正按下 OK 时，播放器直接复用这条热连接，
    // 省掉一次握手加一次 RTT。
    //
    // 只在列表界面做，全屏正在播时不掺和，免得和播放抢带宽。

    private val warmHosts = HashSet<String>()
    private val warmTask = Runnable { warmUpFocusedChannel() }

    private fun scheduleWarmUp() {
        handler.removeCallbacks(warmTask)
        if (fullscreen) return
        handler.postDelayed(warmTask, 260)
    }

    private fun warmUpFocusedChannel() {
        val ch = groups.getOrNull(groupIndex)?.channels?.getOrNull(focusedChannelPosition()) ?: return
        if (ch.name == currentChannel?.name) return
        // 和播放器选线路的规则保持一致：IPv6 排最后
        val url = ch.urls.firstOrNull { !it.contains("://[") } ?: ch.urls.firstOrNull() ?: return
        val host = try {
            val u = java.net.URI(url)
            "${u.scheme}://${u.host}:${u.port}"
        } catch (e: Exception) {
            return
        }
        // 同一个 host 只热一次，来回翻列表不会反复发请求
        if (!warmHosts.add(host)) return
        Thread {
            var c: HttpURLConnection? = null
            try {
                c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "XiaoHeiTV-warm")
                c.connect()
                // 必须把响应体读完，socket 才会回到连接池；播放列表就几百字节
                c.inputStream.use { ins ->
                    val buf = ByteArray(8 * 1024)
                    var total = 0
                    while (total < 64 * 1024) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                    }
                }
                Log.i("XiaoHeiTV", "warm $host http=${c.responseCode}")
            } catch (e: Exception) {
                Log.d("XiaoHeiTV", "warm fail $host ${e.javaClass.simpleName}")
            }
            // 刻意不调 disconnect()：那会把刚建好的连接关掉，等于白热
        }.start()
    }

    /**
     * 全屏播放时用上下键直接切台。
     *
     * 顺序按「收藏 → 央视 → 卫视 → 港澳台」拼接成一条全台序列循环，
     * 这样从央视最后一个台继续按 ↓ 会自然进到卫视，符合看电视的习惯；
     * 跨分组时同步把左栏选中项与右栏列表切过去，保证之后按返回唤出列表时
     * 看到的还是当前这个台所在的组。
     */
    private fun zapChannel(offset: Int) {
        if (groups.isEmpty()) return
        val flat = groups.flatMap { it.channels }.distinctBy { it.name }
        if (flat.isEmpty()) return
        val cur = currentChannel
        val idx = flat.indexOfFirst { it.name == cur?.name }
        val next = if (idx < 0) {
            if (offset >= 0) 0 else flat.size - 1
        } else {
            ((idx + offset) % flat.size + flat.size) % flat.size
        }
        val target = flat[next]
        val gi = groups.indexOfFirst { b -> b.channels.any { it.name == target.name } }
        if (gi >= 0 && gi != groupIndex) selectGroup(gi)
        playChannel(target)
    }

    // ---------------------------------------------------------------- 全屏

    private fun toggleFullscreen() {
        applyFullscreen(!fullscreen, persist = true)
        // 只有进全屏才需要顶部 OSD；回到列表时面板自己带信息，OSD 会压在面板上
        if (fullscreen) showInfo()
        if (fullscreen) {
            Toast.makeText(this, "全屏播放：↑↓ 切台，← → 或返回打开频道列表", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "已显示频道列表", Toast.LENGTH_SHORT).show()
        }
    }

    /** 从全屏唤出列表：焦点直接落在正在播的台上，省得用户再找一遍 */
    private fun showListOnCurrentChannel() {
        applyFullscreen(false, persist = false)
        enterChannels()
    }

    private fun applyFullscreen(on: Boolean, persist: Boolean) {
        fullscreen = on
        listPanel.visibility = if (on) View.GONE else View.VISIBLE
        if (persist) store.fullscreen = on
        if (on) return
        // 退出全屏：列表自带频道信息，OSD 必须收起，否则会和面板标题叠在一起
        handler.removeCallbacks(hideInfo)
        infoBar.visibility = View.GONE
        backToGroups()
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
            // 线路失败不再记账，直接换下一条试——少了写盘，出错的那一下也更利索
            if (autoTries < ch.urls.size - 1) {
                autoTries++
                if (lineOrder.isEmpty()) buildLineOrder(ch)
                orderPos = (orderPos + 1) % lineOrder.size
                urlIndex = lineOrder[orderPos]
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
                autoTries = 0
                // 只打第一次出图的耗时，换线路重试时不重复打
                if (playStartedAt > 0L) {
                    Log.i("XiaoHeiTV", "firstFrame +${SystemClock.uptimeMillis() - playStartedAt}ms")
                    playStartedAt = 0L
                }
            }
        }
    }

    private fun playChannel(channel: Channel) {
        // 同一个台再点一次（比如手滑连按 OK）不该重新连一遍流：
        // 网络重连动辄两三秒，画面还会黑一下，纯粹是负优化。
        val same = currentChannel?.name == channel.name
        val state = player?.playbackState
        if (same && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)) {
            channelAdapter.setPlaying(channel.name)
            pendingChannelPos = -1
            errorHint.visibility = View.GONE
            showInfo()
            return
        }

        currentChannel = channel
        autoTries = 0
        playStartedAt = SystemClock.uptimeMillis()
        buildLineOrder(channel)
        store.lastChannelName = channel.name
        channelAdapter.setPlaying(channel.name)
        pendingChannelPos = -1
        errorHint.visibility = View.GONE
        val pos = channelAdapter.positionOf(channel.name)
        if (pos >= 0) channelList.smoothScrollToPosition(pos)
        showInfo()
        playCurrentUrl()
        // setPlaying 会整列重绘，把焦点找回来（不打断用户当前所在栏）。
        // 全屏时列表是 GONE，别再去抢焦点，否则会打断正在进行的切台。
        if (!fullscreen) {
            if (focusInGroups) {
                groupList.postDelayed({ focusGroupAt(groupIndex) }, 60)
            } else {
                if (pos >= 0) channelList.postDelayed({ focusChannelAt(pos) }, 60)
            }
        }
    }

    /**
     * 放宽 TLS 校验。
     *
     * 免费直播源里大量 HTTPS 线路用的是自签证书、过期证书，或者证书链在电视的旧
     * CA 库里不全（实测 CCTV-5 的 6 条线路里有 2 条就死在
     * `SSLHandshakeException: Chain validation failed`）。这些线路内容本身是公开
     * 的直播流，不做任何账号、支付等敏感传输，因此对它们跳过证书校验是合理的取舍：
     * 换来的是可用线路数量成倍增加——而「有足够多的线路可换」正是去广告的前提。
     */
    private fun relaxTls() {
        try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAll, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.w("XiaoHeiTV", "relaxTls failed: ${e.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- 线路顺序
    // 不再记录「哪条线路广告多 / 老失败」——那是上一版的换源避坑逻辑，已按需求移除。
    // 现在只保留一条纯静态的排序规则：IPv6 字面量地址在多数家庭网络根本连不通，
    // 排到最后，免得每次点台都先在它身上耗掉一个连接超时。

    /** IPv6 沉底，其余保持源里的原始顺序（源的第一条通常是维护者验证过的主线路） */
    private fun buildLineOrder(ch: Channel) {
        lineOrder = ch.urls.indices.sortedWith(
            compareBy(
                { if (ch.urls[it].contains("://[")) 1 else 0 },
                { it }
            )
        )
        orderPos = 0
        urlIndex = lineOrder.getOrElse(0) { 0 }
    }

    private fun playCurrentUrl() {
        val ch = currentChannel ?: return
        if (urlIndex >= ch.urls.size) return
        val url = ch.urls[urlIndex]
        val p = player ?: return
        Log.i("XiaoHeiTV", "play ${ch.name} line[${urlIndex + 1}/${ch.urls.size}] = $url")

        // 明确告诉播放器这是 HLS：否则它要靠嗅探去猜格式，猜错还要重来一遍，
        // 这一来一回就是起播里最冤的那几百毫秒。
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (isHls(url)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()

        // playWhenReady 先置位，prepare 一完成立刻出画面，不用再等一次 play() 的回调
        p.playWhenReady = true
        p.setMediaItem(item, true)
        p.prepare()

        infoMeta.text = "${ch.group} · 线路 ${orderPos + 1}/${ch.urls.size.coerceAtLeast(1)}"
    }

    /** 地址里带 m3u8 就按 HLS 处理；没有后缀的（罕见）留给播放器自己判断 */
    private fun isHls(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    private fun showInfo() {
        // 列表模式下顶部 OSD 会压在面板标题上，且面板本身就写了频道名，没必要重复
        if (!fullscreen) {
            handler.removeCallbacks(hideInfo)
            infoBar.visibility = View.GONE
            return
        }
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
            showListOnCurrentChannel()
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
                // 菜单键只做一件事：打开设置。
                // 全屏播放时按菜单键不再换源（该功能已关闭），直接吞掉，避免误触打断观看。
                if (!fullscreen) startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // 返回键只在界面层级内回退，永远不退出应用（要退出用电视的 Home 键）：
                //   焦点在右栏 → 回到左栏
                //   焦点在左栏（列表展开）→ 收起面板，全屏播放
                //   已经在全屏 → 唤出频道列表，焦点落回正在播的台
                if (fullscreen) {
                    showListOnCurrentChannel()
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
                    // 全屏时上下键＝上一个 / 下一个频道（像电视遥控器的 CH+/-），
                    // 不唤出列表，只弹一条顶部 OSD 说明切到了哪个台
                    zapChannel(if (up) -1 else 1)
                    return true
                }
                if (focusInGroups) moveGroupFocus(if (up) -1 else 1) else moveChannelFocus(if (up) -1 else 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (fullscreen) {
                    showListOnCurrentChannel()
                    return true
                }
                // 右键＝展开：从分组栏进入右栏
                if (focusInGroups) enterChannels()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (fullscreen) {
                    showListOnCurrentChannel()
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
        // 不做后台播放：一旦不可交互就暂停，回到前台再继续
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        // 双保险：彻底不可见时停播并释放解码器，退出后不占用后台资源
        player?.stop()
    }

    override fun onResume() {
        super.onResume()
        if (loadedKey != store.sourceKey) {
            loadChannels()
        } else {
            player?.playWhenReady = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
    }

    private companion object {
        /** 5s：低于这个量再出图，网一抖就卡 */
        const val MIN_BUFFER_MS = 5_000

        /** 20s：直播不需要囤太多，囤多了反而换台要等旧数据丢掉 */
        const val MAX_BUFFER_MS = 20_000

        /** 0.5s：凑够半秒就能出图，是这次起播提速的关键 */
        const val BUFFER_FOR_START_MS = 500

        /** 1s：卡顿后只要补回 1s 就恢复播放，不用等回满 */
        const val BUFFER_AFTER_REBUFFER_MS = 1_000
    }
}

