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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xiaohei.tv.data.ChannelGroups
import com.xiaohei.tv.data.EpgEntry
import com.xiaohei.tv.data.EpgRepository
import com.xiaohei.tv.data.SourceStore
import com.xiaohei.tv.model.Channel
import com.xiaohei.tv.player.AdFilter
import com.xiaohei.tv.player.AdFilterDataSourceFactory
import com.xiaohei.tv.player.AdRules
import com.xiaohei.tv.player.TlsRelax
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

    // ---------------------------------------------------------------- 预播流水线
    //
    // 单个预播播放器有个天花板：实测一条 HLS 流从零冷启动到 STATE_READY 要
    // 2.4~2.6 秒，而用户在上一台出图后 1.3~2.4 秒就按了下一次——预播永远差一口气，
    // 再把调度提前也追不上。所以改成流水线：同时备好「未来两个台」。
    //
    // 连续按 ↓ 时，换完画面后另一个槽里装的正是「下一次要切的台」，
    // 它比原来多缓冲了一整个切台周期，于是从第 2 次起一路都是百毫秒级秒切。
    // 每个槽都是活的播放器，自己刷新清单、跟着直播边缘走，不会喂过期清单。

    /** 一个预播槽：一台静音、不接画面的播放器 + 它当前缓冲的目标 */
    private class PreSlot(
        val index: Int,
        var player: ExoPlayer,
        var channel: Channel? = null,
        var url: String? = null,
        var failed: Boolean = false,
        var tries: Int = 0
    )

    /** 两个槽轮换；主播放器永远不在列表里，swap 时和某个槽互换 */
    private val preSlots = ArrayList<PreSlot>(2)

    private fun slotOf(p: ExoPlayer): PreSlot? = preSlots.firstOrNull { it.player === p }

    /** 最近一次切台的方向，用来决定预播哪一边 */
    private var lastZapDir = 1

    /**
     * 主播放器里**真正装着**的频道。
     *
     * 它和 [currentChannel]（用户想看的）在切台过程中会短暂不一致：
     * 新台还在预播播放器里缓冲时，屏幕上放的还是上一个台。
     * 判断「重复点同一个台」必须看这个，否则用户连按两次 OK 会被误判成「已经在播了」而没反应。
     */
    private var activeChannel: Channel? = null

    /** 换画面发生的时刻，用来量「从切过去到真正出图」的耗时 */
    private var swapAt = 0L

    /**
     * 线路直通表：频道名 → 最近一次真正出过图的线路。
     *
     * 只记「成功过的那条」，不做失败计数、不屏蔽线路、不进设置页、没有任何界面，
     * 和之前移除的「线路自动避坑模块」不是一回事。
     * 这次顺手写进 SharedPreferences：实测 App 冷启动第一次切台会重新踩到
     * CCTV-7 那条 6 秒慢源，重启后不该再交一遍学费。
     */
    private val goodLine = HashMap<String, String>()

    private fun goodUrlFor(name: String): String? = goodLine[name] ?: store.goodLine(name)

    private fun rememberGoodLine(name: String, url: String) {
        goodLine[name] = url
        store.setGoodLine(name, url)
    }

    private fun forgetGoodLine(name: String) {
        goodLine.remove(name)
        store.clearGoodLine(name)
    }

    /** 用户按下切台键的时刻，用来量「从按键到画面真正变成新台」的端到端耗时 */
    private var zapStartedAt = 0L

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

    /** Activity 是否还活着：所有延迟任务执行前都查它，避免销毁后访问 View / 已释放的播放器 */
    private var destroyed = false

    /** 是否处于可交互前台：退到后台绝不启预播，也不让残留回调再把流拉起来 */
    private var resumed = true

    /** 极快连按时把「需要冷启动」的请求合并成最后一次，见 [playDirect] */
    private var pendingDirect: Runnable? = null
    private var lastDirectAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { if (!destroyed) infoBar.visibility = View.GONE }
    private val longPressRunnable = Runnable {
        longPressFired = true
        val ch = focusedChannel()
        if (ch != null) toggleFavorite(ch)
    }
    private var digitBuffer = ""
    private val digitTimeout = Runnable { digitBuffer = "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.notePhase("onCreate")
        relaxTls()
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = SourceStore(this)
        // 只在冷启动读一次本地规则（内存快照，清单解析时零 IO）；不改线路、不动画质
        AdRules.update(store.adRules)
        // 把首次正则编译/类加载的 10~18ms 挪到启动阶段，播放中不出现首次开销
        AdFilter.warmUp()

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

        player = newPlayer().also {
            playerView.useController = false
            playerView.player = it
            attach(it)
        }
        // 预播槽数量由设备能力与历史崩溃决定：正常电视仍是 2 个（切台秒切不变），
        // 低内存或反复崩溃过的盒子自动降到 1 个甚至 0 个，避免解码器实例打满而闪退。
        val slots = CrashGuard.slotCount(this)
        Log.i("XiaoHeiTV", "stability level=${CrashGuard.level(this)} slots=$slots ${CrashGuard.deviceInfo(this)}")
        repeat(slots) { i ->
            val p = newPlayer().also {
                it.volume = 0f
                setAudioEnabled(it, false)
                attach(it)
            }
            preSlots.add(PreSlot(i, p))
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
        // 统一挂在主线程 Handler 上：onDestroy 的 removeCallbacksAndMessages 才能一并撤掉，
        // 否则 Activity 销毁后 View 自己的延时任务还会回来找已经无效的 ViewHolder。
        handler.postDelayed({
            if (!destroyed) groupList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
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
            if (destroyed) return@Runnable
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

    // ---------------------------------------------------------------- 预热（焦点停稳就预播）
    // 切台慢的一大半不在解码，而在「新台那条流要从零建立、取清单、取分片」。
    // 这些都发生在按下 OK 之后，用户看到的就是黑屏等待。
    //
    // 但焦点停在某个台上时，用户下一步十有八九就是播它。所以焦点停稳 220ms 后，
    // 就让静音的预播播放器把这个台真正拉起来缓冲（不是只探一下连接，而是完整起流）。
    // 等真正按下 OK 时，数据已经在内存里，只换画面不重建流。
    //
    // 只在列表界面做，全屏时不掺和——那时预播该留给「下一个台」。

    private val warmTask = Runnable { warmUpFocusedChannel() }

    private fun scheduleWarmUp() {
        handler.removeCallbacks(warmTask)
        if (fullscreen) return
        handler.postDelayed(warmTask, 220)
    }

    private fun warmUpFocusedChannel() {
        if (destroyed || !resumed) return
        val ch = groups.getOrNull(groupIndex)?.channels?.getOrNull(focusedChannelPosition()) ?: return
        if (ch.name == currentChannel?.name) return
        // 已经有槽在缓冲它了（可能是方向预测提前备的），别把现成数据丢掉
        if (preSlots.any { it.channel?.name == ch.name }) return
        val url = primaryUrl(ch) ?: return
        val slot = preSlots.firstOrNull { it.channel == null } ?: preSlots.firstOrNull() ?: return
        startPrebuffer(slot, ch, url)
    }

    /** 首选线路：上次真正出过图的那条优先，其次才是源里的第一条非 IPv6 线路 */
    private fun primaryUrl(ch: Channel): String? =
        goodUrlFor(ch.name)?.takeIf { ch.urls.contains(it) }
            ?: ch.urls.firstOrNull { !it.contains("://[") }
            ?: ch.urls.firstOrNull()

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
        // 记住切台方向：下次预播就按这个方向猜「下一个台」，连按 ↓ 时会一路命中
        if (offset != 0) lastZapDir = if (offset > 0) 1 else -1
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
            safeToast("全屏播放：↑↓ 切台，← → 或返回打开频道列表")
        } else {
            safeToast("已显示频道列表")
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
        if (on) {
            // 进全屏＝准备一口气看下去：提前把「下一个台」拉起来，第一次 ↓ 就能秒切
            schedulePrebufferNext()
            return
        }
        // 退出全屏：列表自带频道信息，OSD 必须收起，否则会和面板标题叠在一起
        handler.removeCallbacks(hideInfo)
        infoBar.visibility = View.GONE
        backToGroups()
        // 列表里不需要「下下个台」了，重排一次把第二槽的流量省下来
        schedulePrebufferNext()
    }

    // ---------------------------------------------------------------- 加载

    private fun loadChannels() {
        loadedKey = store.sourceKey
        loadingBar.visibility = View.VISIBLE
        val t0 = SystemClock.uptimeMillis()
        Thread {
            val loaded = try {
                store.loadChannels()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (destroyed || isFinishing) return@runOnUiThread
                Log.i("XiaoHeiTV", "channelsLoaded +${SystemClock.uptimeMillis() - t0}ms ${loaded.size} 频道")
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
                    safeToast("继续播放：${target.name}")
                }
            }
        }.start()
    }

    private fun newPlayer(): ExoPlayer =
        ExoPlayer.Builder(
            this,
            // 老盒子 / 厂商定制固件上主解码器经常初始化失败，允许回退到备用解码器，
            // 不至于让一次切台直接把 App 带崩。码流没变，画质不受影响。
            DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        )
            // 关键：不接管音频焦点。否则预播播放器一开始缓冲就会把焦点抢走，
            // 正在看的那个台会「莫名其妙静音」——这正是双播放器最容易踩的坑。
            // 声音由我们自己用音量控制：预播的恒为 0，在播的恒为 1。
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
            .setLoadControl(buildLoadControl())
            .setMediaSourceFactory(
                // 播放列表先经广告过滤器再交给解码器：只改清单，不动画面
                DefaultMediaSourceFactory(AdFilterDataSourceFactory(this) { store.adBlock })
                    // 免费源的死线路占比很高，而库默认会重试 3 次、每次还退避等待：
                    // 实测一条连不上的线路要白等 4.3 秒才轮到下一条，用户看到的就是「点了没反应」。
                    // 这里改成不重试——失败立刻回来，由错误回调直接换下一条，
                    // 同样的死线路 0.6 秒就能跳过去。场上还有 4 条备选，重试没有意义。
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            )
            .build()

    /** 两个播放器各挂一份监听，回调里靠实例身份区分是「在播的」还是「预播的」 */
    private fun attach(p: ExoPlayer) {
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (p === player) onActiveError(error)
                else slotOf(p)?.let { onPreError(it, error) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (p === player) onActiveState(playbackState)
                else slotOf(p)?.let { onPreState(it, playbackState) }
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                // 换画面之后真正出图的时间：这是用户实际感受到的那一段。
                // 只认当前挂在画面上的播放器，预播槽的首帧不算数。
                if (p === player && swapAt > 0L) {
                    Log.i("XiaoHeiTV", "firstFrameOnScreen +${SystemClock.uptimeMillis() - swapAt}ms")
                    swapAt = 0L
                    if (zapStartedAt > 0L) {
                        Log.i("XiaoHeiTV", "zapDone +${SystemClock.uptimeMillis() - zapStartedAt}ms（预播命中）")
                        zapStartedAt = 0L
                    }
                    clearZapPending()
                }
            }
        })
    }

    // ---------------------------------------------------------------- 在播的播放器

    private fun onActiveError(error: PlaybackException) {
        if (destroyed) return
        val ch = currentChannel ?: return
        // 切台等待时主播放器里装的还是旧台，它的报错不该算到新台头上，
        // 否则会误换线路、把正在缓冲的新台重来一遍。
        if (activeChannel?.name != ch.name) {
            Log.i("XiaoHeiTV", "playError 忽略（旧播放器，正在切到 ${ch.name}）")
            return
        }
        Log.w(
            "XiaoHeiTV",
            "playError ${ch.name} line[${urlIndex + 1}/${ch.urls.size}] = " +
                "${ch.urls.getOrNull(urlIndex)} cause=${error.cause?.javaClass?.simpleName}:${error.cause?.message}"
        )
        // 上次能用、这次连不上了：把直通记录作废，别让下一个台继续踩它
        if (goodUrlFor(ch.name) == ch.urls.getOrNull(urlIndex)) forgetGoodLine(ch.name)
        // 线路失败不再记账，直接换下一条试
        if (autoTries < ch.urls.size - 1) {
            autoTries++
            if (lineOrder.isEmpty()) buildLineOrder(ch)
            // 自定义源可能给出空线路表，取模 0 会直接抛 ArithmeticException
            if (lineOrder.isEmpty()) {
                errorHint.visibility = View.VISIBLE
                errorHint.text = "该频道没有可用线路：${ch.name}"
                cancelPrebuffer()
                return
            }
            orderPos = (orderPos + 1) % lineOrder.size
            urlIndex = lineOrder[orderPos]
            playCurrentUrl()
        } else {
            errorHint.visibility = View.VISIBLE
            errorHint.text = "所有线路均无法播放：${ch.name}"
            infoMeta.text = "${ch.group} · 线路全部失败"
            cancelPrebuffer()
        }
    }

    private fun onActiveState(playbackState: Int) {
        if (destroyed) return
        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
            errorHint.visibility = View.GONE
        }
        if (playbackState == Player.STATE_READY) {
            // 切台等待期间主播放器仍是旧台：它的 READY 不能清「切换中」，
            // 更不能把「还没出图」的新台线路提前写成已验证。
            val ch = currentChannel
            if (ch == null || activeChannel?.name != ch.name) return
            autoTries = 0
            // 真正出过图的线路才有资格进直通表（同时写盘，重启后不用重踩慢源）
            ch.urls.getOrNull(urlIndex)?.let { rememberGoodLine(ch.name, it) }
            // 只打第一次出图的耗时，换线路重试时不重复打
            if (playStartedAt > 0L) {
                Log.i("XiaoHeiTV", "firstFrame +${SystemClock.uptimeMillis() - playStartedAt}ms")
                playStartedAt = 0L
            }
            if (zapStartedAt > 0L) {
                Log.i("XiaoHeiTV", "zapDone +${SystemClock.uptimeMillis() - zapStartedAt}ms（直接起播）")
                zapStartedAt = 0L
            }
            clearZapPending()
            // 画面稳住了，立刻把下一个台提前拉起来（原来等 1.2s，用户 1s 内切台就命中不了）
            schedulePrebufferNext()
        }
    }

    // ---------------------------------------------------------------- 预播的播放器

    private fun onPreError(slot: PreSlot, error: PlaybackException) {
        Log.i(
            "XiaoHeiTV",
            "preError[${slot.index}] ${slot.channel?.name} cause=${error.cause?.javaClass?.simpleName}:${error.cause?.message}"
        )
        // 预播不接画面，试错完全不打扰用户：先自己换条线路再拉一次。
        val failed = slot.channel
        if (failed != null && slot.tries < 2 && failed.urls.size > 1) {
            val order = lineOrderFor(failed)
            val cur = order.indexOfFirst { failed.urls[it] == slot.url }
            val nextIdx = if (order.isEmpty()) -1 else ((cur + 1).coerceAtLeast(0)) % order.size
            val nextUrl = if (nextIdx >= 0) failed.urls.getOrNull(nextIdx) else null
            if (nextUrl != null && nextUrl != slot.url) {
                slot.tries++
                Log.i("XiaoHeiTV", "preRetry[${slot.index}] ${failed.name} -> line[${nextIdx + 1}/${failed.urls.size}]")
                startPrebuffer(slot, failed, nextUrl, resetTries = false)
                return
            }
        }
        slot.failed = true
        // 这条线路刚验证过是死的，直通记录也得作废
        if (failed != null && goodUrlFor(failed.name) == slot.url) forgetGoodLine(failed.name)
        val ch = currentChannel ?: return
        if (slot.channel?.name != ch.name || activeChannel?.name == ch.name) return
        // 另一个槽可能已经把用户要的台缓冲好了，先别急着回退
        val other = preSlots.firstOrNull {
            it !== slot && !it.failed && it.channel?.name == ch.name &&
                it.player.playbackState == Player.STATE_READY
        }
        if (other != null) {
            syncUrlIndexFromPre(other, ch)
            swapToPre(other)
            return
        }
        Log.i("XiaoHeiTV", "preFailed -> 回退到直接起播 ${ch.name}")
        playDirect(ch, primaryUrl(ch) ?: return)
    }

    private fun onPreState(slot: PreSlot, playbackState: Int) {
        if (playbackState != Player.STATE_READY) return
        val ch = currentChannel ?: return
        // 只比台名：预播可能已经自己换过线路，拿旧的 urlIndex 去比会误判成「没命中」
        if (slot.channel?.name != ch.name || slot.failed) return
        if (activeChannel?.name == ch.name) return // 已经切过去了，别重复换画面
        syncUrlIndexFromPre(slot, ch)
        swapToPre(slot)
    }

    /** 预播可能换过线路，落地时把线路索引跟上，免得后续换源与日志指错人 */
    private fun syncUrlIndexFromPre(slot: PreSlot, ch: Channel) {
        val u = slot.url ?: return
        val i = ch.urls.indexOfFirst { it == u }
        if (i >= 0) urlIndex = i
    }

    /**
     * 把某个预播槽换到台前。
     *
     * 只换播放器实例和画面归属，不重新建流、不重新缓冲——
     * 数据早就在内存里，所以是「切过去立刻有画面」而不是「切过去开始等」。
     * allowBuffering=true 用于超时兜底：宁可先把还在缓冲的画面接过来，
     * 也不要把已经下了两三秒的数据丢掉、再从头冷启动一次。
     */
    private fun swapToPre(slot: PreSlot, allowBuffering: Boolean = false) {
        if (destroyed) return
        CrashGuard.notePhase("swap")
        val np = slot.player
        val old = player ?: return
        if (!allowBuffering && np.playbackState != Player.STATE_READY) return
        val ch = slot.channel ?: return
        if (activeChannel?.name == ch.name) return
        Log.i(
            "XiaoHeiTV",
            if (allowBuffering) "swap 提前接管 ${ch.name}（仍在缓冲）"
            else "swap 预播命中[${slot.index}] ${ch.name}，省掉整趟网络"
        )
        // 从按键到「终于把画面换过去」等了多久，和首帧耗时分开量
        if (zapStartedAt > 0L) {
            Log.i("XiaoHeiTV", "preWait +${SystemClock.uptimeMillis() - zapStartedAt}ms（按键→换画面）")
        }

        // 换画面这几步一旦抛异常，必须还能回到「在老播放器上重新起播」，
        // 否则会留下一个黑屏且什么都不播的死局
        runCatching {
            slot.player = old
            player = np
            activeChannel = ch
            slot.url?.let { rememberGoodLine(ch.name, it) }
            slot.tries = 0
            swapAt = SystemClock.uptimeMillis()
            old.volume = 0f
            setAudioEnabled(old, false)
            setAudioEnabled(np, true)
            np.volume = 1f
            playerView.player = np
            np.playWhenReady = true
            // 老播放器立刻停掉并清掉媒体项：既省带宽，也把解码器实例还回去。
            // 低配盒子上第三个解码器申请失败是换台闪退最常见的原因。
            old.stop()
            runCatching { old.clearMediaItems() }
            slot.channel = null
            slot.url = null
            slot.failed = false
            errorHint.visibility = View.GONE
            playStartedAt = 0L
            handler.removeCallbacks(prebufferTimeout)
        }.onFailure {
            Log.w("XiaoHeiTV", "swap 失败，回退直接起播 ${it.javaClass.simpleName}")
            swapAt = 0L
            currentChannel?.let { c -> playDirect(c, c.urls.getOrElse(urlIndex) { "" }) }
            return
        }

        // 换完马上重新排流水线：另一个槽里可能已经备好了「下一次要切的台」
        schedulePrebufferNext()
    }

    /**
     * 预播播放器只留视频轨、彻底关掉音频。
     *
     * 只把音量设 0 是不够的：解码器照样在往扬声器写静音，两个播放器同时写同一个
     * AudioTrack，在廉价电视上很容易听到爆音或者出现声音断断续续。
     * 干脆让它连音频轨都不选，既没这问题，也省一份解码开销。
     */
    private fun setAudioEnabled(p: ExoPlayer, enabled: Boolean) {
        runCatching {
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !enabled)
                .build()
        }
    }

    /** 在某个预播槽上把某条线路拉起来（静音、无画面，不会打扰正在看的节目） */
    private fun startPrebuffer(slot: PreSlot, ch: Channel, url: String, resetTries: Boolean = true) {
        if (destroyed || !resumed) return
        val p = slot.player
        if (slot.channel?.name == ch.name && slot.url == url && p.playbackState != Player.STATE_IDLE) return
        // 预播是「加分项」，任何异常都不该把正在看的节目带下水
        runCatching {
            if (resetTries) slot.tries = 0
            slot.channel = ch
            slot.url = url
            slot.failed = false
            p.stop()
            p.volume = 0f
            setAudioEnabled(p, false)
            p.playWhenReady = true
            p.setMediaItem(mediaItemOf(url), true)
            p.prepare()
            Log.i("XiaoHeiTV", "prebuffer[${slot.index}] ${ch.name} = $url")
        }.onFailure {
            Log.w("XiaoHeiTV", "prebuffer 启动失败 ${it.javaClass.simpleName}")
            slot.failed = true
            slot.channel = null
            slot.url = null
        }
    }

    private fun stopSlot(slot: PreSlot) {
        slot.channel = null
        slot.url = null
        slot.failed = false
        slot.tries = 0
        // 停一个已经 release 的播放器会抛 IllegalStateException，不能让它冒到切台主线程
        runCatching { slot.player.stop() }
    }

    private fun cancelPrebuffer() {
        handler.removeCallbacks(prebufferTimeout)
        pendingPrebufferStarts.values.forEach { handler.removeCallbacks(it) }
        pendingPrebufferStarts.clear()
        preSlots.forEach(::stopSlot)
    }

    private val prebufferTimeout = Runnable {
        if (destroyed || !resumed) return@Runnable
        // 预播迟迟不就绪（多半是死线路），别让用户一直盯着静帧。
        // 判断依据必须是「主播放器里装的还是不是这个台」：等待预播时主播放器可是
        // STATE_READY 的（它还在放旧台），拿它做条件会让这个兜底永远不触发。
        val ch = currentChannel ?: return@Runnable
        if (activeChannel?.name == ch.name) return@Runnable
        val slot = preSlots.firstOrNull { !it.failed && it.channel?.name == ch.name }
        if (slot != null) {
            // 旧做法是丢掉预播、在主播放器上从零起播，实测反而更慢（6.2s）。
            // 已经缓冲几秒的数据不扔：直接把画面接过来，让它边下边出图。
            Log.i("XiaoHeiTV", "prebuffer 超时，提前接管 ${ch.name}")
            syncUrlIndexFromPre(slot, ch)
            swapToPre(slot, allowBuffering = true)
        } else {
            Log.i("XiaoHeiTV", "prebuffer 超时，改为直接起播 ${ch.name}")
            playDirect(ch, primaryUrl(ch) ?: return@Runnable)
        }
    }

    // 画面稳了之后，按最近一次切台方向提前备好「未来两个台」。
    // 全屏连续按同一个方向时，流水线从第 2 次切台起就能一路命中。
    private val prebufferNextTask = Runnable { prebufferNext() }

    /** 槽位 → 错峰启动任务；切台/暂停时一次全撤掉，避免给旧目标乱装流 */
    private val pendingPrebufferStarts = HashMap<Int, Runnable>()

    private fun schedulePrebufferNext() {
        if (destroyed || !resumed) return
        handler.removeCallbacks(prebufferNextTask)
        // 不在这里清错峰任务：swap 与 STATE_READY 会连着调度两次，
        // 清掉会让第二次把第二槽当成「第一个要启动的」而立刻冷启动两路流。
        // 原来是出图后 300ms 才启动；实测用户 1.3 秒就切台，这 300ms 也得抠出来。
        handler.post(prebufferNextTask)
    }

    private fun prebufferNext() {
        if (destroyed || !resumed) return
        val cur = currentChannel ?: return
        if (preSlots.isEmpty()) return
        runCatching {
            val flat = flatChannels()
            val n = flat.size
            if (n < 2) return
            val i = flat.indexOfFirst { it.name == cur.name }
            if (i < 0) return
            val dir = lastZapDir
            val targets = ArrayList<Channel>(2)
            targets += flat[((i + dir) % n + n) % n]
            if (fullscreen && n >= 3) targets += flat[((i + 2 * dir) % n + n) % n]
            targets.removeAll { it.name == cur.name }
            if (targets.isEmpty()) return

            // 已经在缓冲目标台的槽一律原样保留：停一下就得重新冷启动 2.5 秒，太亏。
            // 这正是流水线能生效的关键——swap 后另一个槽里装的恰好是新的第一目标。
            val used = HashSet<Int>()
            val covered = HashSet<String>()
            preSlots.forEach { s ->
                val c = s.channel
                if (c == null || s.failed) return@forEach
                if (targets.any { it.name == c.name }) {
                    used += s.index
                    covered += c.name
                } else if (c.name == cur.name && activeChannel?.name != cur.name) {
                    // 这是「用户已经按键、正在为他缓冲的目标台」，绝不能被流水线重排停掉
                    used += s.index
                }
            }

            for (target in targets) {
                if (target.name in covered) continue
                val slot = preSlots.firstOrNull { it.index !in used } ?: break
                val url = primaryUrl(target) ?: continue
                used += slot.index
                // 两路流别同时冷启动：错峰固定在槽位上，
                // 即便被重复调度也只会「替换」第二槽的任务，不会把它提前。
                val delay = SLOT_STAGGER_MS * slot.index
                if (delay == 0L) {
                    startPrebuffer(slot, target, url)
                } else {
                    pendingPrebufferStarts[slot.index]?.let { handler.removeCallbacks(it) }
                    val task = Runnable { startPrebuffer(slot, target, url) }
                    pendingPrebufferStarts[slot.index] = task
                    handler.postDelayed(task, delay)
                }
            }
            preSlots.filter { it.index !in used }.forEach(::stopSlot)
        }.onFailure { Log.d("XiaoHeiTV", "prebufferNext 跳过 ${it.javaClass.simpleName}") }
    }

    private fun flatChannels(): List<Channel> =
        groups.flatMap { it.channels }.distinctBy { it.name }

    private fun mediaItemOf(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            // 明确告诉播放器这是 HLS：否则它要靠嗅探去猜格式，猜错还要重来一遍，
            // 这一来一回就是起播里最冤的那几百毫秒。
            .apply { if (isHls(url)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()


    private fun playChannel(channel: Channel) {
        if (destroyed) return
        if (channel.urls.isEmpty()) {
            errorHint.visibility = View.VISIBLE
            errorHint.text = "该频道没有可用线路：${channel.name}"
            return
        }
        CrashGuard.noteChannel(channel.name)
        CrashGuard.notePhase("zap")
        // 同一个台再点一次（比如手滑连按 OK）不该重新连一遍流：
        // 网络重连动辄两三秒，画面还会黑一下，纯粹是负优化。
        // 注意这里看的是「主播放器里真正装着谁」，不是「用户想看谁」——
        // 新台还在预播缓冲时两者不一样，此时再按一次应该继续等它，而不是当成已经在播。
        val same = activeChannel?.name == channel.name && currentChannel?.name == channel.name
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
        zapStartedAt = playStartedAt
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
                handler.postDelayed({ if (!destroyed) focusGroupAt(groupIndex) }, 60)
            } else {
                if (pos >= 0) handler.postDelayed({ if (!destroyed) focusChannelAt(pos) }, 60)
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
     *
     * 规则放在 [TlsRelax] 里共用：预热线程走的是 HttpURLConnection，
     * 两条路必须用同一套规则，否则会出现「播放器能连、预热连不上」。
     */
    private fun relaxTls() = TlsRelax.apply()

    /** 部分厂商 TV 的 Toast 在 Activity 收尾时会抛 BadTokenException，提示类操作不值得让它崩 */
    private fun safeToast(msg: String) {
        if (destroyed || isFinishing) return
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- 线路顺序
    // 不再记录「哪条线路广告多 / 老失败」——那是上一版的换源避坑逻辑，已按需求移除。
    // 现在只保留一条纯静态的排序规则：IPv6 字面量地址在多数家庭网络根本连不通，
    // 排到最后，免得每次点台都先在它身上耗掉一个连接超时。

    /**
     * 线路的尝试顺序：上次成功过的那条排第一 → 其余保持源里的原始顺序 → IPv6 沉底。
     * 纯函数，不改成员变量，预播换线路时也用它（不会把正在播的台的顺序搞乱）。
     */
    private fun lineOrderFor(ch: Channel): List<Int> {
        val base = ch.urls.indices.sortedWith(
            compareBy<Int>(
                { if (ch.urls[it].contains("://[")) 1 else 0 },
                { it }
            )
        )
        val good = goodUrlFor(ch.name)
        val goodIdx = good?.let { g -> ch.urls.indexOfFirst { it == g } } ?: -1
        return if (goodIdx >= 0 && !ch.urls[goodIdx].contains("://[")) {
            listOf(goodIdx) + base.filter { it != goodIdx }
        } else base
    }

    private fun buildLineOrder(ch: Channel) {
        lineOrder = lineOrderFor(ch)
        orderPos = 0
        urlIndex = lineOrder.getOrElse(0) { 0 }
    }

    /** 起播门槛：库默认按点播调（缓冲够 2.5 秒才开播），直播切台时这几秒就白等了 */
    private fun buildLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,          // 5s  最低缓冲
                MAX_BUFFER_MS,          // 20s 最高缓冲
                BUFFER_FOR_START_MS,    // 0.5s 够就开播
                BUFFER_AFTER_REBUFFER_MS // 1s  卡顿后恢复的门槛
            )
            // 时间优先于字节数：网速一般时先凑够「能出图的时长」，而不是非等够字节量
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    private fun playCurrentUrl() {
        val ch = currentChannel ?: return
        if (urlIndex < 0 || urlIndex >= ch.urls.size) return
        val url = ch.urls[urlIndex]
        if (player == null) return
        Log.i("XiaoHeiTV", "play ${ch.name} line[${urlIndex + 1}/${ch.urls.size}] = $url")

        infoMeta.text = "${ch.group} · 线路 ${orderPos + 1}/${ch.urls.size.coerceAtLeast(1)}"

        // ① 有槽已经把这个台缓冲好了：直接换画面，这是「秒切」的那条路。
        //    流水线里两个槽都可能命中，谁先 READY 用谁。
        val ready = preSlots.firstOrNull { s ->
            !s.failed && s.channel?.name == ch.name && s.player.playbackState == Player.STATE_READY
        }
        if (ready != null) {
            syncUrlIndexFromPre(ready, ch)
            swapToPre(ready)
            return
        }

        // ② 没有现成画面可换：旧画面停在最后一帧，把带宽全让给正在缓冲的新台。
        //    原来旧台会继续播、继续拉流，和新台抢带宽，把 2.5 秒拖得更长。
        if (preSlots.isNotEmpty() && player?.playbackState == Player.STATE_READY) {
            val buffering = preSlots.firstOrNull { !it.failed && it.channel?.name == ch.name }
            if (buffering == null) {
                // 没有任何槽在管这个台：拿一个空闲槽重新起流，别把已备好的预测台都丢掉
                val slot = preSlots.firstOrNull { it.channel == null || it.failed } ?: preSlots.last()
                startPrebuffer(slot, ch, url)
            }
            runCatching { player?.playWhenReady = false }
            // 新台还在缓冲时画面是静帧，不给提示用户会以为按键没生效、又按一次
            showZapPending(ch)
            handler.removeCallbacks(prebufferTimeout)
            handler.postDelayed(prebufferTimeout, PREBUFFER_TIMEOUT_MS)
            return
        }

        // ③ 没有在播的画面（刚启动 / 上一个台彻底失败）：只能在主播放器上直接起播
        playDirect(ch, url)
    }

    /** 直接在主播放器上起播：没有可保留的旧画面时的兜底路径 */
    private fun playDirect(ch: Channel, url: String) {
        if (destroyed) return
        // 极快连按（间隔 <120ms）时把「需要冷启动」的请求合并成最后一次：
        // 实测 1 秒内连按 40 次会建 40 条流，老盒子的解码器实例和内存直接被打满。
        // 预播命中的热切换不经过这里；正常速度的切台（间隔 >120ms）也完全不受影响。
        val now = SystemClock.uptimeMillis()
        val wait = DIRECT_COALESCE_MS - (now - lastDirectAt)
        if (lastDirectAt > 0L && wait > 0L) {
            pendingDirect?.let { handler.removeCallbacks(it) }
            val task = Runnable { playDirectNow(ch, url) }
            pendingDirect = task
            handler.postDelayed(task, wait)
            if (zapStartedAt > 0L) showZapPending(ch)
            return
        }
        playDirectNow(ch, url)
    }

    private fun playDirectNow(ch: Channel, url: String) {
        // 合并任务可能在 onPause 之后才到期；后台绝不重新起流
        if (destroyed || !resumed) return
        val p = player ?: return
        lastDirectAt = SystemClock.uptimeMillis()
        pendingDirect = null
        Log.i("XiaoHeiTV", "directStart ${ch.name}（冷启动一次）")
        cancelPrebuffer()
        if (zapStartedAt > 0L) showZapPending(ch)
        // 这是所有切台路径的最后一道兜底：解码器或播放器状态异常时只记日志、不让它冒到主线程
        runCatching {
            activeChannel = ch
            p.volume = 1f
            setAudioEnabled(p, true)
            // playWhenReady 先置位，prepare 一完成立刻出画面，不用再等一次 play() 的回调
            p.playWhenReady = true
            p.setMediaItem(mediaItemOf(url), true)
            p.prepare()
        }.onFailure {
            Log.w("XiaoHeiTV", "direct 起播失败 ${it.javaClass.simpleName}:${it.message}")
            CrashGuard.notePhase("directError")
            if (!destroyed) {
                errorHint.visibility = View.VISIBLE
                errorHint.text = "播放出错，请再按一次切台"
            }
        }
    }

    /** 地址里带 m3u8 就按 HLS 处理；没有后缀的（罕见）留给播放器自己判断 */
    private fun isHls(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    /**
     * 新台还在预播里缓冲时，屏幕放的仍是上一个台。
     * 这里立刻给出明确的「已经切了」反馈：OSD 与加载圈马上出现，出图后自动收起。
     * 画面不黑、旧台继续播，但用户不会再以为遥控没响应。
     */
    private fun showZapPending(ch: Channel) {
        if (fullscreen) {
            infoBar.visibility = View.VISIBLE
            infoName.text = ch.name
            infoMeta.text = "切换中…"
            handler.removeCallbacks(hideInfo)
        }
        loadingBar.visibility = View.VISIBLE
    }

    private fun clearZapPending() {
        loadingBar.visibility = View.GONE
        val ch = currentChannel ?: return
        infoMeta.text = "${ch.group} · 线路 ${orderPos + 1}/${ch.urls.size.coerceAtLeast(1)}"
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
                if (destroyed || isFinishing || map.isEmpty()) return@runOnUiThread
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
        safeToast(if (added) "已收藏：${ch.name}" else "已取消收藏：${ch.name}")
        handler.postDelayed({
            if (destroyed) return@postDelayed
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

    override fun onStart() {
        super.onStart()
        resumed = true
        CrashGuard.notePhase("onStart")
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        // 不做后台播放：一旦不可交互就暂停，回到前台再继续。
        // 预播播放器 playWhenReady 恒为 true 且不接画面，只停主播放器是没用的：
        // 实测退到别的 App 之后它还会一路刷新直播清单，十几分钟不停，白吃带宽。
        handler.removeCallbacks(prebufferNextTask)
        handler.removeCallbacks(prebufferTimeout)
        handler.removeCallbacks(warmTask)
        pendingPrebufferStarts.values.forEach { handler.removeCallbacks(it) }
        pendingPrebufferStarts.clear()
        pendingDirect?.let { handler.removeCallbacks(it) }
        pendingDirect = null
        runCatching { player?.pause() }
        cancelPrebuffer()
        runCatching { loadingBar.visibility = View.GONE }
    }

    override fun onStop() {
        super.onStop()
        resumed = false
        // 双保险：彻底不可见时停播并释放解码器，退出后不占用后台资源。
        // 每个播放器单独兜住异常：其中一个已被释放不能拖垮其余的清理。
        runCatching {
            player?.stop()
            player?.clearMediaItems()
        }
        preSlots.forEach { s -> runCatching { s.player.stop(); s.player.clearMediaItems() } }
        cancelPrebuffer()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        CrashGuard.notePhase("onResume")
        if (loadedKey != store.sourceKey) {
            loadChannels()
            return
        }
        // onStop 里 stop()+clearMediaItems() 会把播放器打回 IDLE，
        // 回来时只置 playWhenReady 会黑屏，必须重新起播（有线路直通表，很快）。
        val ch = currentChannel
        if (ch != null && ch.urls.isNotEmpty() && player?.playbackState == Player.STATE_IDLE) {
            buildLineOrder(ch)
            val url = ch.urls.getOrElse(urlIndex) { "" }
            if (url.isNotEmpty()) playDirect(ch, url)
        } else {
            runCatching { player?.playWhenReady = true }
        }
        schedulePrebufferNext()
    }

    override fun onDestroy() {
        // 先立标志再动手：释放播放器会同步回调监听，此时绝不能再碰 View
        destroyed = true
        resumed = false
        handler.removeCallbacksAndMessages(null)
        pendingDirect = null
        pendingPrebufferStarts.clear()
        runCatching { playerView.player = null }
        runCatching { player?.release() }
        player = null
        preSlots.forEach { s -> runCatching { s.player.release() } }
        preSlots.clear()
        super.onDestroy()
    }

    private companion object {
        /** 5s：低于这个量再出图，网一抖就卡 */
        const val MIN_BUFFER_MS = 5_000

        /** 15s：直播不需要囤 20 秒，囤多了反而换台时旧数据丢得慢 */
        const val MAX_BUFFER_MS = 15_000

        /** 0.3s：凑够 300ms 就出图（原来 500ms），直播首帧能再省一点 */
        const val BUFFER_FOR_START_MS = 300

        /** 1s：卡顿后只要补回 1s 就恢复播放，不用等回满 */
        const val BUFFER_AFTER_REBUFFER_MS = 1_000

        /** 3.5s：预播迟迟不就绪就别让用户盯着静帧了（超时改为提前接管，不丢弃已缓冲数据） */
        const val PREBUFFER_TIMEOUT_MS = 3_500L

        /** 第二槽错峰启动，避免两路流同时冷启动互相抢带宽 */
        const val SLOT_STAGGER_MS = 700L

        /** 极快连按时冷启动合并窗口：只影响 <120ms 的连按，预播命中不经过这里 */
        const val DIRECT_COALESCE_MS = 120L
    }
}

