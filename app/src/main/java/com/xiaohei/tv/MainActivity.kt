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
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.xiaohei.tv.data.AudiobookCatalog
import com.xiaohei.tv.data.ChannelGroups
import com.xiaohei.tv.data.EpgEntry
import com.xiaohei.tv.data.EpgRepository
import com.xiaohei.tv.data.LineOrder
import com.xiaohei.tv.data.PodcastCatalog
import com.xiaohei.tv.data.PodcastDirStore
import com.xiaohei.tv.data.PodcastStore
import com.xiaohei.tv.data.PodcastTranscript
import com.xiaohei.tv.data.SourceStore
import com.xiaohei.tv.data.TranscriptCue
import com.xiaohei.tv.data.TranscriptStore
import com.xiaohei.tv.model.Channel
import com.xiaohei.tv.model.PodcastEpisode
import com.xiaohei.tv.model.PodcastShow
import com.xiaohei.tv.player.AdFilter
import com.xiaohei.tv.player.AdFilterDataSourceFactory
import com.xiaohei.tv.player.AdaptiveLoadControl
import com.xiaohei.tv.player.NetProbe
import com.xiaohei.tv.player.AdRules
import com.xiaohei.tv.player.IcyDataSourceFactory
import com.xiaohei.tv.player.TlsRelax
import com.xiaohei.tv.ui.BookAdapter
import com.xiaohei.tv.ui.ChannelAdapter
import com.xiaohei.tv.ui.EpisodeAdapter
import com.xiaohei.tv.ui.GroupAdapter
import com.xiaohei.tv.ui.GroupEntry
import com.xiaohei.tv.ui.RadioBackdropView
import com.xiaohei.tv.ui.SettingsActivity
import com.xiaohei.tv.ui.ZapPredict
import java.util.Locale

/**
 * 两栏式频道浏览：
 *
 *   左栏＝分组（收藏 / 央视 / 卫视 / 港澳台 / 广播 / 播客，自定义源自带分组追加在后）
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
    /** 列表面板下面那层压暗：玻璃浮起来需要背景够沉 */
    private lateinit var listScrim: View
    /** 底部操作提示：不同分组下 OK 的含义不一样（播放 / 看分集 / 开书架） */
    private lateinit var laneHint: TextView
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
     * 当前播的是不是纯音频网络电台。
     * 预播槽为了防爆音把音轨整个禁用了，纯音频流进去会变成「没有轨可播」，
     * 所以电台一律走直接起播；这个值还被清单过滤器跨线程读取，故用 volatile。
     */
    @Volatile
    private var radioMode = false

    private lateinit var radioOverlay: View
    private lateinit var radioName: TextView
    private lateinit var radioKicker: TextView
    private lateinit var radioNowPlaying: TextView
    private lateinit var radioBackdrop: RadioBackdropView

    // ---------------------------------------------------------------- 播客
    // 播客是点播：一档节目一行，按 OK 展开这一档的分集，选中哪集播哪集。
    // 分集不在 APK 里，打开面板时才按 RSS 拉取（PodcastStore 会缓存 12 小时）。
    private lateinit var podcastStore: PodcastStore
    /** 远端节目单（GitHub 上的 podcasts.json）：启动时合并，改节目不用发版 */
    private lateinit var podcastDir: PodcastDirStore
    private lateinit var episodePanel: View
    private lateinit var episodeList: RecyclerView
    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var episodeShowName: TextView
    private lateinit var episodeShowMeta: TextView
    /** 有声书才显示的封面；播客留 GONE，标题仍从面板最上面开始 */
    private lateinit var episodeCover: ImageView
    private lateinit var episodeLoading: View
    private lateinit var episodeEmpty: TextView
    private lateinit var podcastProgress: View
    private lateinit var podcastBar: ProgressBar
    private lateinit var podcastTime: TextView
    /** 字幕只在播客播放页出现；电台和视频台永远不显示 */
    private lateinit var podcastSubtitle: TextView
    private lateinit var transcriptStore: TranscriptStore
    /** 当前这一集的字幕表，只在主线程读写 */
    private var activeCues: List<TranscriptCue> = emptyList()
    /** 换集时让上一集还没下完的字幕结果作废 */
    private var transcriptSeq = 0

    /** 分集面板是否展开（展开时方向键交给分集列表，返回逐层退出） */
    private var panelOpen = false
    /** 章节面板是从书架打开的：返回时回书架，而不是回两栏列表 */
    private var panelFromShelf = false
    private var panelShow: PodcastShow? = null
    private var panelEpisodes: List<PodcastEpisode> = emptyList()

    // ---------------------------------------------------------------- 书架
    // 有声书与播客共用同一层封面墙：封面 + 名字 + 一行说明，按分类分节。
    // 两者的条目本身就是一档 RSS 专辑，所以播放、续播、字幕全部复用同一套链路。
    private lateinit var shelfPanel: View
    private lateinit var shelfTitle: TextView
    private lateinit var shelfGrid: RecyclerView
    private lateinit var shelfAdapter: BookAdapter
    private lateinit var shelfMeta: TextView
    private lateinit var shelfHint: TextView
    /** 书架是否展开 */
    private var shelfOpen = false
    /** 当前书架装的是哪一类：[ChannelGroups.AUDIOBOOK] 或 [ChannelGroups.PODCAST] */
    private var shelfKind = ChannelGroups.AUDIOBOOK

    /** 当前在播的那一集；为 null 说明在播视频或电台 */
    private var currentEpisode: PodcastEpisode? = null
    /** 用户主动按 OK 暂停了：退后台再回来要尊重这个意图，不能擅自续播 */
    private var podcastPaused = false

    /** 续播待跳转的位置：等播放器 READY 之后再 seek，太早会被 prepare 覆盖 */
    private var pendingSeekMs = 0L

    /** 分集加载的序号，防止用户换档后旧结果把新列表冲掉 */
    private var podcastLoadSeq = 0

    /** 进度落盘节流：每 5 秒写一次，不每秒都写 SharedPreferences */
    private var tickSaveCounter = 0

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

    /** 本次卡顿开始的时刻，0 表示当前不在卡顿中（只统计「出过图之后又断粮」） */
    private var stutterStart = 0L

    /** 当前这条线是否已经正常出过图：只有出过图之后的 BUFFERING 才算真卡顿 */
    private var settledChannel: String? = null

    /** 一个预播槽：一台静音、不接画面的播放器 + 它当前缓冲的目标 */
    private class PreSlot(
        val index: Int,
        var player: ExoPlayer,
        var channel: Channel? = null,
        var url: String? = null,
        var failed: Boolean = false,
        var tries: Int = 0,
        /** 这次起流的时刻，用来量「预播多久能到 READY」，是切台命中率的核心指标 */
        var startedAt: Long = 0L
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
        // 同一条线不可能既好又慢：好线一旦被认可，就把慢线记录撤掉
        if (slowLine[name] == url) {
            slowLine.remove(name)
            store.clearSlowLine(name)
        }
    }

    private fun forgetGoodLine(name: String) {
        goodLine.remove(name)
        store.clearGoodLine(name)
    }

    // ---------------------------------------------------------------- 线路偏好
    // 换线只由真正的报错驱动（onActiveError / onPreError）：
    // 播放中单纯缓冲卡顿不再静默换线，避免把只是慢的好线误判成坏线、来回折腾。

    /** 报错换线时把坏过的线路排到最后，下次起播优先用验证过的好线 */
    private val slowLine = HashMap<String, String>()

    private fun slowUrlFor(name: String): String? = slowLine[name] ?: store.slowLine(name)

    private fun rememberSlowLine(name: String, url: String) {
        slowLine[name] = url
        store.setSlowLine(name, url)
    }

    /** 用户按下切台键的时刻，用来量「从按键到画面真正变成新台」的端到端耗时 */
    private var zapStartedAt = 0L

    private var loadedKey = ""
    private var fullscreen = false
    private var epgMap: Map<String, EpgEntry> = emptyMap()
    private var epgLoading = false

    /** 已经给用户看过的节目名：换档提示靠它判断「是不是真的换了」，没换就一直安静 */
    private var programTitleShown: String? = null

    /** true＝焦点在左栏分组，false＝焦点在右栏频道 */
    private var focusInGroups = true

    /** 右栏正在移动焦点的目标位置（连按方向键时用它做基准，避免 30ms 内多次按键算错） */
    private var pendingChannelPos = -1
    private var pendingChannelAt = 0L

    /** 最近一次把焦点移到右栏第几项，用于取消尚未执行完的焦点任务 */
    private var focusChannelTask: Runnable? = null

    /** 左栏分组焦点任务：连按时取消上一次未执行的，避免焦点滞后/跳错 */
    private var groupFocusTask: Runnable? = null

    /** 长按 OK 已触发（用于区分短按与长按） */
    private var longPressFired = false

    /** OK 按下的时刻，用于在抬起时比较按住时长 */
    private var okDownTime = 0L

    /** 最外层双击返回退出的判定窗口：两次按下间隔小于它才算双击 */
    private val exitDoubleTapMs = 2_000L

    /** 最外层第一次按下返回键的时刻（0＝当前窗口内还没按过） */
    private var lastBackExitAt = 0L

    /** 判定为长按的按住时长阈值（毫秒） */
    private val longPressThreshold = 400L

    private val startTime = SystemClock.uptimeMillis()

    /** 启动后 1 秒内忽略按键，防止系统残留事件误触 */
    private fun justLaunched(): Boolean = SystemClock.uptimeMillis() - startTime < 1000

    /** 设备画像判定的保守档：老电视 / 小内存 / 少硬解，少开一路预播并关掉异步解码队列 */
    private var conservative = false

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
        conservative = DeviceProfile.isConservative(this)
        Log.i("XiaoHeiTV", "device ${DeviceProfile.summary(this)}")
        // 网络速度自适应：先建共享带宽估计器，后面所有播放器的拉流字节都计入它。
        // 必须在任何 prepare 之前——估计器没有数据时会按快网档处理（=v2.18.1 行为）。
        NetProbe.init(this)
        relaxTls()
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = SourceStore(this)
        // 只在冷启动读一次本地规则（内存快照，清单解析时零 IO）；不改线路、不动画质
        AdRules.update(store.adRules)
        // 把首次正则编译/类加载的 10~18ms 挪到启动阶段，播放中不出现首次开销
        AdFilter.warmUp()

        listPanel = findViewById(R.id.listPanel)
        listScrim = findViewById(R.id.listScrim)
        // 整块列表占屏宽 1/3。宽度按屏幕算而不是写死 320dp：
        // 换 4K 电视或改显示模式时仍是同一个观感比例，两栏内部的 weight 会自动跟着缩。
        (listPanel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = (resources.displayMetrics.widthPixels / 3f).toInt()
            listPanel.layoutParams = lp
        }
        groupList = findViewById(R.id.groupList)
        channelList = findViewById(R.id.channelList)
        laneHint = findViewById(R.id.laneHint)
        playerView = findViewById(R.id.playerView)
        infoBar = findViewById(R.id.infoBar)
        infoName = findViewById(R.id.infoChannelName)
        infoMeta = findViewById(R.id.infoMeta)
        infoProgram = findViewById(R.id.infoProgram)
        errorHint = findViewById(R.id.errorHint)
        loadingBar = findViewById(R.id.loadingBar)
        radioOverlay = findViewById(R.id.radioOverlay)
        radioName = findViewById(R.id.radioName)
        radioBackdrop = findViewById(R.id.radioBackdrop)
        // 老电视（DeviceProfile 保守档）自动降帧、少光晕；标准档用完整效果
        radioBackdrop.setConservative(conservative)

        radioKicker = findViewById(R.id.radioKicker)
        radioNowPlaying = findViewById(R.id.radioNowPlaying)
        podcastProgress = findViewById(R.id.podcastProgress)
        podcastBar = findViewById(R.id.podcastBar)
        podcastTime = findViewById(R.id.podcastTime)
        podcastSubtitle = findViewById(R.id.podcastSubtitle)
        podcastSubtitle.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        episodePanel = findViewById(R.id.episodePanel)
        episodeList = findViewById(R.id.episodeList)
        episodeShowName = findViewById(R.id.episodeShowName)
        episodeShowMeta = findViewById(R.id.episodeShowMeta)
        episodeCover = findViewById(R.id.episodeCover)
        episodeLoading = findViewById(R.id.episodeLoading)
        episodeEmpty = findViewById(R.id.episodeEmpty)
        shelfPanel = findViewById(R.id.shelfPanel)
        shelfTitle = findViewById(R.id.shelfTitle)
        shelfGrid = findViewById(R.id.shelfGrid)
        shelfMeta = findViewById(R.id.shelfMeta)
        shelfHint = findViewById(R.id.shelfHint)
        podcastStore = PodcastStore(this)
        transcriptStore = TranscriptStore(this)
        podcastDir = PodcastDirStore(this)
        episodeAdapter = EpisodeAdapter(
            onPlay = { ep -> panelShow?.let { playEpisode(it, ep) } },
            positionOf = { ep -> store.podcastPosition(ep.id) }
        )
        episodeList.layoutManager = LinearLayoutManager(this)
        episodeList.adapter = episodeAdapter

        // 书架：6 列。最后一列留一点余量，封面不会被屏幕边缘切掉。
        shelfAdapter = BookAdapter { show -> openBook(show) }
        val grid = GridLayoutManager(this, BOOK_COLUMNS)
        // 分类标题占满整行，封面卡片各占一格——分节就是靠这个撑出「一条横带」的
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (shelfAdapter.isHeader(position)) BOOK_COLUMNS else 1
        }
        shelfGrid.layoutManager = grid
        shelfGrid.adapter = shelfAdapter
        // 一次性铺开，进出书架不需要逐项动画（老电视上反而会卡一下）
        shelfGrid.itemAnimator = null

        groupAdapter = GroupAdapter { index ->
            if (justLaunched()) return@GroupAdapter
            selectGroup(index)
            enterChannels()
        }
        channelAdapter = ChannelAdapter(
            onClick = { channel ->
                if (justLaunched()) return@ChannelAdapter
                when {
                    // 播客节目行：点开分集，而不是起播
                    channel.isPodcast -> openEpisodePanel(channel)
                    channel.name == currentChannel?.name -> toggleFullscreen()
                    else -> playChannel(channel)
                }
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
        // 关掉 RecyclerView 默认的 change 动画：切分组/切台时反白本该瞬时跟随，
        // 默认的淡入淡出约 250ms，快速连按遥控器时会叠加成明显的迟滞感。
        // 书架网格早就这么做了，两个主列表保持一致。
        groupList.itemAnimator = null
        channelList.itemAnimator = null
        groupList.requestFocus()

        player = newPlayer().also {
            playerView.useController = false
            playerView.player = it
            attach(it)
        }
        // 预播槽数量由设备能力与历史崩溃决定：正常电视仍是 2 个（切台秒切不变），
        // 低内存或反复崩溃过的盒子自动降到 1 个甚至 0 个，避免解码器实例打满而闪退。
        val slots = CrashGuard.slotCount(this)
        Log.i("XiaoHeiTV", "stability level=${CrashGuard.level(this)} slots=$slots conservative=$conservative ${CrashGuard.deviceInfo(this)}")
        repeat(slots) { i ->
            val p = newPlayer(forPrebuffer = true).also {
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
        for (g in ChannelGroups.ORDER.filter { it != ChannelGroups.FAVORITE }) {
            val list = byGroup[g] ?: continue
            if (list.isEmpty()) continue
            buckets.add(GroupBucket(g, list))
        }
        // 清单自带的分组名（如「地方台」）不在固定顺序里，兜底追加，避免丢频道
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
        laneHint.text = when (bucket.name) {
            ChannelGroups.AUDIOBOOK -> "OK 打开书架　→ 浏览列表　← 返回"
            ChannelGroups.PODCAST -> "OK 打开书架　↑↓ 直接选档　← 返回\n长按 OK 收藏　菜单 设置"
            else -> "↑↓ 选择　OK 播放　← 返回\n长按 OK 收藏　菜单 设置"
        }
        // 若当前播放台属于该组，滚到它
        val pos = channelAdapter.positionOf(currentChannel?.name ?: "")
        if (pos >= 0) channelList.scrollToPosition(pos)
        // 焦点还在左栏时不要预热：用户此刻只是在 央视/卫视 之间翻分组，
        // 预热会把一条用不上的流拉起来，和正在播的台抢带宽，正是「一切分组就卡」的来源。
        // 按 → 进入右栏时 enterChannels() 会重新排预热。
        if (!focusInGroups) scheduleWarmUp()
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
        groupFocusTask?.let { handler.removeCallbacks(it) }
        groupList.scrollToPosition(index)
        // 先立刻抢焦点：绝大多数时候目标就在屏内、ViewHolder 已存在，
        // 删掉原来固定的 30ms 等待，连按分组时反白/右栏跟随才是即时的。
        if (groupList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() == true) return
        // 只有刚 scrollToPosition、目标尚未布局时才退一帧兜底；
        // 统一挂在主线程 Handler 上，onDestroy 才能随 removeCallbacksAndMessages 一并撤掉。
        val task = Runnable {
            if (!destroyed) groupList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }
        groupFocusTask = task
        handler.postDelayed(task, 16)
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
        // 目标已在屏内就直接给焦点，省掉固定 30ms；滚动后尚未布局时才退一帧兜底。
        // 这里必须按 position 精确取 ViewHolder，不能用 getChildAt(0) 抢错项。
        if (channelList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() == true) return
        val task = Runnable {
            if (destroyed) return@Runnable
            val vh = channelList.findViewHolderForAdapterPosition(index)
            val target = vh?.itemView ?: channelList.getChildAt(0)
            target?.requestFocus()
        }
        focusChannelTask = task
        handler.postDelayed(task, 16)
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
        if (fullscreen || panelOpen) return
        // 保守档把列表悬停预热放慢一点：快速刷频道时不至于每划过一个台就重建一路解码器。
        // 只是延迟变化，预热本身仍在，点台的起播链路没动。
        handler.postDelayed(warmTask, if (conservative) 500L else 220L)
    }

    private fun warmUpFocusedChannel() {
        if (destroyed || !resumed || panelOpen) return
        val ch = groups.getOrNull(groupIndex)?.channels?.getOrNull(focusedChannelPosition()) ?: return
        if (ch.name == currentChannel?.name) return
        // 电台不走预播：槽里音轨是禁用的，纯音频流永远 READY 不了
        if (ch.isAudio) return
        // 已经有槽在缓冲它了（可能是方向预测提前备的），别把现成数据丢掉
        if (preSlots.any { it.channel?.name == ch.name }) return
        val url = primaryUrl(ch) ?: return
        val slot = preSlots.firstOrNull { it.channel == null } ?: preSlots.firstOrNull() ?: return
        startPrebuffer(slot, ch, url)
    }

    /**
     * 首选线路：上次真正出过图的那条优先，其次源里第一条非 IPv6、非「卡过」的线路。
     * 慢线只在没有别的选择时才被拿出来，保证预播不会又把用户送进同一条会卡的线。
     */
    private fun primaryUrl(ch: Channel): String? {
        val slow = slowUrlFor(ch.name)
        goodUrlFor(ch.name)?.takeIf { it != slow && ch.urls.contains(it) && !store.isDeadLine(it) }?.let { return it }
        ch.urls.firstOrNull { it != slow && !it.contains("://[") && !store.isDeadLine(it) }?.let { return it }
        return ch.urls.firstOrNull()
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
        val flat = zapChannels()
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

    /**
     * 打开设置页。
     *
     * 打开前先把 OSD 收掉：设置页要停留一会儿，回来时如果顶部还挂着一条
     * 离开前的台名，会让人以为「刚才那个台已经播了这么久」。
     */
    private fun openSettings() {
        handler.removeCallbacks(hideInfo)
        infoBar.visibility = View.GONE
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * 列表面板与它下面那层压暗永远同进同出。
     * 单独藏一个就会露馅：要么面板没了还留着一层灰，要么玻璃直接叠在亮画面上。
     */
    private fun setListPanelVisible(visible: Boolean) {
        listPanel.visibility = if (visible) View.VISIBLE else View.GONE
        listScrim.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun applyFullscreen(on: Boolean, persist: Boolean) {
        fullscreen = on
        // 列表一旦重新出现，「隐藏列表后双击退出」的窗口就作废
        if (!on) lastBackExitAt = 0L
        setListPanelVisible(!on)
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
            // 远端节目单先落定：播客那几行是频道列表的一部分，
            // 等列表建好再插进来就得重排 UI。读的是本地缓存（几 KB），不联网。
            podcastDir.applyCache()
            val loaded = try {
                store.loadChannels()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (destroyed || isFinishing) return@runOnUiThread
                Log.i("XiaoHeiTV", "channelsLoaded +${SystemClock.uptimeMillis() - t0}ms ${loaded.size} 频道")
                loadingBar.visibility = View.GONE
                // 播客节目单：内置清单 + GitHub 上的远端目录（启动时合并）。
                // 一档节目就是列表里的一行，分集等打开面板再拉。
                // 它排在视频源之后，且切台序列里会被排除，所以不影响原有频道顺序与切台。
                allChannels = loaded + catalogChannels()
                if (allChannels.isEmpty()) {
                    errorHint.visibility = View.VISIBLE
                    errorHint.text = "频道列表为空，按菜单键检查播放源"
                    return@runOnUiThread
                }
                if (loaded.isEmpty()) {
                    // 视频源挂了但播客还在：照样能进去听，只提示一句
                    errorHint.visibility = View.VISIBLE
                    errorHint.text = "频道列表为空，按菜单键检查播放源"
                } else {
                    errorHint.visibility = View.GONE
                }
                val last = store.lastChannelName
                // 上次听的是播客也不能在冷启动时直接落进分集面板：起点仍放在视频台上
                val target = allChannels.firstOrNull { it.name == last && !it.isPodcast }
                    ?: allChannels.firstOrNull { !it.isPodcast }
                    ?: allChannels.first()

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
                // 界面已经能用了，再去后台问一次远端节目单：这一步慢、可以慢
                refreshPodcastCatalog()
            }
        }.start()
    }

    /**
     * 后台刷新远端播客目录。
     *
     * 只在节目单**真的变了**（加了一档、下线了一档、改了名）时才动列表：
     * 平时这一步就是一次几 KB 的 GET，用户完全感觉不到。
     */
    private fun refreshPodcastCatalog() {
        Thread {
            val changed = runCatching { podcastDir.refresh() }.getOrDefault(false)
            if (!changed) return@Thread
            runOnUiThread {
                if (destroyed || isFinishing) return@runOnUiThread
                onPodcastCatalogChanged()
            }
        }.start()
    }

    /**
     * 节目单变了以后重排「播客」那几行。
     *
     * 视频源顺序、当前在播的台、分组位置都不动；焦点原来停在哪个频道就还回哪个
     * （那一档被删了才退回当前在播的台）。正在播的那一集不会被打断——
     * 它自己的音频地址早已拿在手上，和节目单是否还有这一档无关。
     */
    private fun onPodcastCatalogChanged() {
        val video = allChannels.filter { !it.isPodcast }
        allChannels = video + catalogChannels()
        Log.i("XiaoHeiTV", "podcastCatalog 更新：${PodcastCatalog.shows.size} 档播客 / ${AudiobookCatalog.books.size} 本书")
        reflowKeepingFocus()
    }

    /**
     * 重排列表但不动用户的位置：分组留在原地，焦点原来停在哪个频道就还回哪个
     * （那个频道没了才退回当前在播的台）。正在播的流不会被打断。
     */
    private fun reflowKeepingFocus() {
        val keepGroup = groupIndex
        val focusName = if (!focusInGroups && !panelOpen) {
            channelList.focusedChild?.let { channelList.getChildAdapterPosition(it) }
                ?.let { channelAdapter.channelAt(it)?.name }
        } else null

        buildGroups(store.favorites)
        selectGroup(keepGroup.coerceIn(0, (groups.size - 1).coerceAtLeast(0)))
        if (panelOpen || fullscreen) return
        if (focusInGroups) {
            groupList.requestFocus()
            focusGroupAt(groupIndex)
        } else {
            channelList.requestFocus()
            val pos = channelAdapter.positionOf(focusName ?: currentChannel?.name ?: "")
            focusChannelAt(if (pos >= 0) pos else 0)
        }
    }

    private fun newPlayer(forPrebuffer: Boolean = false): ExoPlayer =
        ExoPlayer.Builder(
            this,
            // 老盒子 / 厂商定制固件上主解码器经常初始化失败，允许回退到备用解码器，
            // 不至于让一次切台直接把 App 带崩。码流没变，画质不受影响。
            DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .apply {
                    // Android 8.0/8.1（TCL TV+OS 4.0 就在此列）厂商 MediaCodec 的异步回调
                    // 实现普遍有缺陷：切台时两个解码器交接 Surface，异步队列里残留的 buffer
                    // 会触发 CodecException 甚至 native crash。强制走同步队列更稳。
                    // 标准档不动，切台速度不受影响。
                    if (conservative) forceDisableMediaCodecAsynchronousQueueing()
                }
        )
            // 关键：不接管音频焦点。否则预播播放器一开始缓冲就会把焦点抢走，
            // 正在看的那个台会「莫名其妙静音」——这正是双播放器最容易踩的坑。
            // 声音由我们自己用音量控制：预播的恒为 0，在播的恒为 1。
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
            .setLoadControl(buildLoadControl(forPrebuffer))
            .setMediaSourceFactory(
                // 播放列表先经广告过滤器再交给解码器：只改清单，不动画面
                // 视频清单才做广告剔除；电台清单分片时长均匀、没有视频插播块，
                // 走过滤器既无收益又可能误删分片，所以整组直通。
                DefaultMediaSourceFactory(
                    // 最外层是 ICY 曲目读取：只在电台态、且服务器真的带曲目信息时才介入，
                    // 视频与 HLS 切片原样直通（响应头里没有 icy-metaint 就不会被碰）。
                    IcyDataSourceFactory(
                        AdFilterDataSourceFactory(
                            this,
                            enabled = { store.adBlock && !radioMode },
                            icyMeta = { radioMode },
                            // 共享带宽估计器：主播放器与预播槽的所有拉流字节都计入同一个 meter
                            transferListener = NetProbe.meterOrNull()
                        ),
                        enabled = { radioMode },
                        onTitle = ::onRadioTitle
                    )
                )
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
        if (ch.isPodcast) {
            // 播客没有「换线路」这一说：一集就一个音频地址，失败就是这一集的问题
            Log.w("XiaoHeiTV", "podcastError ${currentEpisode?.title} = ${currentEpisode?.url} " +
                "cause=${error.cause?.javaClass?.simpleName}:${error.cause?.message}")
            errorHint.visibility = View.VISIBLE
            errorHint.text = "这一集暂时播不了，换一集试试"
            stopPodcastTick()
            return
        }
        Log.w(
            "XiaoHeiTV",
            "playError ${ch.name} line[${urlIndex + 1}/${ch.urls.size}] = " +
                "${ch.urls.getOrNull(urlIndex)} cause=${error.cause?.javaClass?.simpleName}:${error.cause?.message}"
        )
        // 解码器本身失败（不是网络/线路问题）说明这台机器的 MediaCodec 撑不住。
        // 连续两次就自动降到更保守的档位，不用等进程真的崩掉。
        if (isDecoderFailure(error)) {
            Log.w("XiaoHeiTV", "decoderFailure code=${error.errorCode} cause=${error.cause?.javaClass?.name}")
            CrashGuard.noteDecoderFailure(this)
        }
        // 上次能用、这次连不上了：把直通记录作废，别让下一个台继续踩它
        if (goodUrlFor(ch.name) == ch.urls.getOrNull(urlIndex)) forgetGoodLine(ch.name)
        // 死线记 12 小时：同类免费源隔天再坏的几率很高，
        // 记住它就能让下次切台直接跳过一次连接超时，而不是每次都从头试。
        // 解码器失败是本机能力问题，不是线路问题，不能连累这条线。
        if (!isDecoderFailure(error)) {
            ch.urls.getOrNull(urlIndex)?.let { store.markDeadLine(it) }
        }
        // 记下这次坏掉的是哪条，换线后把它排到最后
        val curFailedIdx = urlIndex
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
            // 失败过的那条排到最后：下次打开这个台别再先踩一遍同一条死线
            ch.urls.getOrNull(curFailedIdx)?.let { rememberSlowLine(ch.name, it) }
            playCurrentUrl()
        } else {
            errorHint.visibility = View.VISIBLE
            errorHint.text = "所有线路均无法播放：${ch.name}"
            infoMeta.text = "${ch.group} · 线路全部失败"
            cancelPrebuffer()
        }
    }

    /** 区分「解码器不兼容」与「线路连不上」：前者要降档，后者只需换线路 */
    private fun isDecoderFailure(e: PlaybackException): Boolean {
        when (e.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> return true
        }
        var t: Throwable? = e.cause
        var depth = 0
        while (t != null && depth++ < 6) {
            val n = t.javaClass.name
            if (n.contains("MediaCodec") || n.contains("DecoderInitialization")) return true
            t = t.cause
        }
        return false
    }

    private fun onActiveState(playbackState: Int) {
        if (destroyed) return
        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
            errorHint.visibility = View.GONE
        }
        if (playbackState == Player.STATE_BUFFERING) {
            // 只有「已经正常出过图之后又断粮」才算真卡顿；起播/切台那一次不算
            val ch = currentChannel
            if (ch != null && settledChannel == ch.name && stutterStart == 0L) {
                stutterStart = SystemClock.uptimeMillis()
                Log.i("XiaoHeiTV", "stutterStart ${ch.name}")
            }
            return
        }
        if (playbackState == Player.STATE_READY) {
            // 切台等待期间主播放器仍是旧台：它的 READY 不能清「切换中」，
            // 更不能把「还没出图」的新台线路提前写成已验证。
            val ch = currentChannel
            if (ch == null || activeChannel?.name != ch.name) return
            // 卡顿结算：出一条可对比的指标行
            if (stutterStart > 0L) {
                Log.i("XiaoHeiTV", "stutterEnd ${ch.name} +${SystemClock.uptimeMillis() - stutterStart}ms")
                stutterStart = 0L
            }
            settledChannel = ch.name
            // 换线只由报错驱动：缓冲卡顿不再计次、不再触发静默换线或备用线救援
            autoTries = 0
            // 播客续播：等播放器真 READY 再跳，太早 seek 会被 prepare 覆盖掉
            if (pendingSeekMs > 0L) {
                val target = pendingSeekMs
                pendingSeekMs = 0L
                runCatching { player?.seekTo(target) }
                updatePodcastProgress()
            }
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
        // 预播里确认连不上的线路也记账：下一次连这个台时直接跳过，
        // 不用再等一次同样的连接超时。解码器失败不算线路问题。
        if (!isDecoderFailure(error)) slot.url?.let { store.markDeadLine(it) }
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
        if (playbackState != Player.STATE_READY) {
            // 诊断：3=ENDED / 1=IDLE 的槽是「僵尸槽」，永远等不到 READY，切过去会黑屏
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                Log.w(
                    "XiaoHeiTV",
                    "preZombie[${slot.index}] ${slot.channel?.name} state=$playbackState" +
                        " age=${if (slot.startedAt > 0L) SystemClock.uptimeMillis() - slot.startedAt else -1}ms"
                )
            }
            return
        }
        // 预播就绪耗时：这是「切台能不能命中」的关键指标，一条流 2s 内就绪才算健康
        if (slot.startedAt > 0L && slot.channel != null) {
            Log.i(
                "XiaoHeiTV",
                "preReady[${slot.index}] ${slot.channel?.name} +${SystemClock.uptimeMillis() - slot.startedAt}ms"
            )
            slot.startedAt = 0L
        }
        val ch = currentChannel ?: return
        // 只比台名：预播可能已经自己换过线路，拿旧的 urlIndex 去比会误判成「没命中」
        if (slot.channel?.name != ch.name || slot.failed) return
        // 同名时不能一律跳过。
        //
        // playDirectNow（直接起播）会在 prepare 前就把 activeChannel 设成目标台，
        // 但那一刻主播放器还在缓冲慢线路、画面根本没出来（playStartedAt > 0）。
        // 若此时预播槽先 READY，旧的「同名即已切过去」判断会把它拦掉，
        // 用户只能干等慢线路，而手边明明有一份缓冲好的数据——实测这就是
        // preWait 1000~1900ms 的主要来源。
        //
        // 唯一该跳过的情况是：主播放器里这个台已经真正 READY（正常切换已完成）。
        // swap 完成后 slot.channel 会被清空，下一行就能拦住重复回调，不会来回换。
        if (activeChannel?.name == ch.name && player?.playbackState == Player.STATE_READY) return
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
        // 同名时只在「主播放器已真正 READY」才跳过（正常切换已完成）。
        // 直接起播还在缓冲慢线路时，预播槽抢先 READY 正是要接管的场景，不能拦。
        if (activeChannel?.name == ch.name && old.playbackState == Player.STATE_READY) return
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
            // 「卡不卡」是「这条线在这个台上」的属性：换台必须清账，
            // 否则上一台的卡顿计时会算到新台头上（实测虚报过 29 秒）
            stutterStart = 0L
            settledChannel = null
            slot.url?.let { rememberGoodLine(ch.name, it) }
            slot.tries = 0
            swapAt = SystemClock.uptimeMillis()
            old.volume = 0f
            setAudioEnabled(old, false)
            // 顺序很关键：先把旧解码器连同它占着的 Surface 一起还回去，再把新播放器接到画面。
            // 原来是先 playerView.player = np 再 stop 旧播放器——在 Android 8 厂商固件上，
            // 这一瞬间两个 MediaCodec 同时绑同一个 Surface，会抛 CodecException / native crash。
            // 这正是 TCL 75T6 这类老电视「换台闪退」最典型的现场。
            old.stop()
            runCatching { old.clearMediaItems() }
            setAudioEnabled(np, true)
            np.volume = 1f
            playerView.player = np
            np.playWhenReady = true
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
    private fun startPrebuffer(
        slot: PreSlot,
        ch: Channel,
        url: String,
        resetTries: Boolean = true,
        /** true＝无视「同台同址正在缓冲」的早退，强制重起（用于救卡死的僵尸槽） */
        force: Boolean = false
    ) {
        if (destroyed || !resumed) return
        val p = slot.player
        if (!force && slot.channel?.name == ch.name && slot.url == url && p.playbackState != Player.STATE_IDLE) return
        // 预播是「加分项」，任何异常都不该把正在看的节目带下水
        runCatching {
            if (resetTries) slot.tries = 0
            slot.channel = ch
            slot.url = url
            slot.failed = false
            slot.startedAt = SystemClock.uptimeMillis()
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
        if (slot != null && slot.player.playbackState == Player.STATE_READY) {
            Log.i("XiaoHeiTV", "prebuffer 超时但已就绪，直接接管 ${ch.name}")
            syncUrlIndexFromPre(slot, ch)
            swapToPre(slot)
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
        // 听广播时不开预播：槽里禁用了音轨，而且广播不需要「秒切画面」，
        // 多开两路流只是白吃带宽和解码器。
        if (cur.isAudio) {
            if (preSlots.any { it.channel != null }) cancelPrebuffer()
            return
        }
        if (preSlots.isEmpty()) return
        runCatching {
            // 弱网档把预播预算压到 2 路：总带宽 1.5Mbps 时 4 路并发谁也喂不饱
            // （基线实测第三槽永远等不到 READY），少一路并发把带宽让给正在看的画面。
            // 快网档 budget = preSlots.size，行为与 v2.18.1 完全一致。
            val budget =
                if (NetProbe.ENABLED && NetProbe.tier() == NetProbe.Tier.SLOW) minOf(2, preSlots.size)
                else preSlots.size
            val flat = prebufferSequence()
            val n = flat.size
            if (n < 2) return
            val found = flat.indexOfFirst { it.name == cur.name }
            // 当前播放台不在本组（刚从别的组切过来看一眼）：从本组第一台开始备，
            // 别因为找不到索引就整条流水线停摆。
            val i = if (found >= 0) found else if (!fullscreen) 0 else return
            // 预测顺序见 ZapPredict：同方向下一台 → 来时的上一台 → 同方向再下一台。
            // 把「来时那个台」提到第二位是为「广告时间切走、广告结束切回」准备的——
            // 这个动作在电视上比连按同向常见得多，而老顺序会让它每次都冷启动 4 秒起步。
            val targets = ZapPredict.targets(i, n, lastZapDir, budget).map { flat[it] }
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
                // 弱网档降级：只用前 budget 个槽，多余的槽由末尾的 stopSlot 停掉让路
                val slot = preSlots.firstOrNull { it.index < budget && it.index !in used } ?: break
                val url = primaryUrl(target) ?: continue
                used += slot.index
                // 两路流别同时冷启动：错峰固定在槽位上，
                // 即便被重复调度也只会「替换」第二槽的任务，不会把它提前。
                val delay = staggerMs() * slot.index
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

    /**
     * 预播预测用的频道序列。
     *
     * 全屏上下键是跨组连切（央视最后一台 → 卫视第一台），必须用全台序列；
     * 但列表里按 OK 播台时，用户接下来几乎一定还在当前分组里挑，
     * 这时候拿全台序列去猜，预播槽装的常常是隔壁组的台，命中率白丢一半。
     */
    private fun prebufferSequence(): List<Channel> {
        if (!fullscreen) {
            val inGroup = groups.getOrNull(groupIndex)?.channels.orEmpty()
            if (inGroup.size >= 2) return inGroup
        }
        return zapChannels()
    }

    /**
     * 全屏上下键与预播预测共用的频道序列。
     *
     * 看电视时只循环视频台，听广播时只循环电台：按一下 ↓ 不会从电视掉进
     * 黑屏有声的广播，听广播时也不会偷偷预播两路视频。
     *
     */
    private fun zapChannels(): List<Channel> {
        val all = groups.flatMap { it.channels }
            .distinctBy { "${it.isAudio}:${it.name}" }
        if (all.isEmpty()) return emptyList()
        return ChannelGroups.zapList(all, currentChannel?.isAudio == true)
    }

    private fun mediaItemOf(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            // 明确告诉播放器这是 HLS：否则它要靠嗅探去猜格式，猜错还要重来一遍，
            // 这一来一回就是起播里最冤的那几百毫秒。
            .apply { if (isHls(url)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()


    private fun playChannel(channel: Channel) {
        if (destroyed) return
        // 播客节目行本身没有音频地址：按 OK 是「打开这一档的分集」，不是起播
        if (channel.isPodcast) {
            openEpisodePanel(channel)
            return
        }
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
        // 从播客切回直播：先把这一集的进度落盘，再清掉分集上下文
        if (currentEpisode != null) savePodcastPosition()
        currentEpisode = null
        radioMode = channel.isAudio
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
    /**
     * 线路优先顺序：好线置顶 → 其余按源里顺序 → 卡过的线垫底 → IPv6 最后。
     *
     * 慢线不删除、只是排到最后：免费源本来就少，一条能出图的线就算会卡也留着兜底，
     * 但不再让它挡在第一条，省得每次打开都先卡一遍。
     */
    private fun lineOrderFor(ch: Channel): List<Int> = LineOrder.order(
        urls = ch.urls,
        goodUrl = goodUrlFor(ch.name),
        slowUrl = slowUrlFor(ch.name),
        isDead = { store.isDeadLine(it) }
    )

    private fun buildLineOrder(ch: Channel) {
        lineOrder = lineOrderFor(ch)
        orderPos = 0
        urlIndex = lineOrder.getOrElse(0) { 0 }
    }

    /** 起播门槛：库默认按点播调（缓冲够 2.5 秒才开播），直播切台时这几秒就白等了 */
    private fun buildLoadControl(forPrebuffer: Boolean): LoadControl {
        val inner = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,          // 5s  最低缓冲
                // 保守档的预播只是「备用」，别让它囤 15 秒视频占满 2GB 内存；
                // 主播放器仍是 15s，用户正在看的画面一点不受影响。
                if (forPrebuffer && conservative) PRE_MAX_BUFFER_MS else MAX_BUFFER_MS,
                BUFFER_FOR_START_MS,    // 0.3s 够就开播
                BUFFER_AFTER_REBUFFER_MS // 1s  卡顿后恢复的门槛
            )
            // 时间优先于字节数：网速一般时先凑够「能出图的时长」，而不是非等够字节量
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        if (!NetProbe.ENABLED) return inner
        // 弱网自适应包装：快网档行为与 v2.18.1 一字不差（直接委托 inner），
        // MED/SLOW 档只会要求更多缓冲才起播，不会比现状更快出画。
        return AdaptiveLoadControl(inner)
    }

    private fun playCurrentUrl() {
        val ch = currentChannel ?: return
        if (urlIndex < 0 || urlIndex >= ch.urls.size) return
        val url = ch.urls[urlIndex]
        if (player == null) return
        Log.i("XiaoHeiTV", "play ${ch.name} line[${urlIndex + 1}/${ch.urls.size}] = $url")

        infoMeta.text = lineLabel(ch)
        applyRadioUi(ch)

        // 音频台（以及刚从音频台切回视频）一律直接起播：
        // 预播槽把音轨整个禁用了，纯音频流进去会没有轨可播；而电台只有几十 KB
        // 的清单和 64k 分片，冷启动本来就在 1 秒内，不需要秒切流水线。
        if (ch.isAudio || activeChannel?.isAudio == true) {
            playDirect(ch, url)
            return
        }

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

        // ② 没有现成画面可换：直接在主播放器上起播，不再「停下来等预播」。
        //
        // 原做法是把旧画面冻住、等预播槽变 READY（最多 3.5 秒）。实测这笔等待基本是白等：
        // 12 次切台里 3~4 次等到超时也没等到画面，超时后接管一个仍在缓冲的播放器还是黑的，
        // 用户要么盯着静帧、要么再按一次键。改成直接起播后，一次冷启动约 2.4~3 秒就有画面，
        // 最坏情况反而更短、而且结果确定（不再出现「切过去是黑的」）。
        //
        // 预播槽继续留在后台跑：它一旦就绪，onPreState 会在用户还没按下一键时把画面热切过去，
        // 所以慢源台也能在 1~2 秒后自动「补」成秒切。
        // 刚启动 / 上一个台彻底失败时也走这条路：主播放器直接起播，槽照旧留在后台。
        playDirect(ch, url, keepPrebuffer = true)
    }

    /**
     * 直接在主播放器上起播：没有可保留的旧画面时的兜底路径。
     *
     * [keepPrebuffer] true 时保留预播槽：切台未命中后直接起播，槽里那条正在缓冲的同一路流
     * 一旦 READY，onPreState 会立刻把画面热切过去（用户等 1~2 秒后自动「补」成秒切）。
     * 若这里把槽全停掉，下一次切台必然又没命中，形成「一 miss 到底」的连锁。
     */
    private fun playDirect(ch: Channel, url: String, keepPrebuffer: Boolean = false) {
        if (destroyed) return
        // 极快连按（间隔 <120ms）时把「需要冷启动」的请求合并成最后一次：
        // 实测 1 秒内连按 40 次会建 40 条流，老盒子的解码器实例和内存直接被打满。
        // 预播命中的热切换不经过这里；正常速度的切台（间隔 >120ms）也完全不受影响。
        val now = SystemClock.uptimeMillis()
        val wait = coalesceMs() - (now - lastDirectAt)
        if (lastDirectAt > 0L && wait > 0L) {
            pendingDirect?.let { handler.removeCallbacks(it) }
            val task = Runnable { playDirectNow(ch, url, keepPrebuffer) }
            pendingDirect = task
            handler.postDelayed(task, wait)
            if (zapStartedAt > 0L) showZapPending(ch)
            return
        }
        playDirectNow(ch, url, keepPrebuffer)
    }

    private fun playDirectNow(ch: Channel, url: String, keepPrebuffer: Boolean = false) {
        // 合并任务可能在 onPause 之后才到期；后台绝不重新起流
        if (destroyed || !resumed) return
        val p = player ?: return
        lastDirectAt = SystemClock.uptimeMillis()
        pendingDirect = null
        Log.i("XiaoHeiTV", "directStart ${ch.name}（冷启动一次）")
        if (!keepPrebuffer) cancelPrebuffer()
        if (zapStartedAt > 0L) showZapPending(ch)
        // 这是所有切台路径的最后一道兜底：解码器或播放器状态异常时只记日志、不让它冒到主线程
        runCatching {
            activeChannel = ch
            stutterStart = 0L
            settledChannel = null
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
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
        // 少数台（翡翠台 https://…/163189/fct/）清单地址是目录形式，没有扩展名。
        // 不给 MIME 提示时 ExoPlayer 会用提取器逐个嗅探，最后一个都对不上，
        // 直接抛 UnrecognizedInputFormatException —— 实测就是「翡翠台永远黑屏」。
        // 渐进式流（mp4/ts/flv/mkv）一定带扩展名，所以「末段没有点」判为 HLS 是安全的；
        // 内置清单里目前只有翡翠台命中这一条。
        val last = path.trimEnd('/').substringAfterLast('/')
        return !last.contains('.')
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
        // 画面稳住了才开始盯节目单：切台过程中节目名本来就不可信
        handler.removeCallbacks(programTick)
        handler.postDelayed(programTick, PROGRAM_TICK_MS)
        val ch = currentChannel ?: return
        infoMeta.text = lineLabel(ch)
    }

    private fun lineLabel(ch: Channel): String = when {
        // 播客/有声书没有「线路」，只有「这一档一共多少集（章）」
        ch.isPodcast && isAudiobook(ch) ->
            if (currentEpisode != null && panelEpisodes.isNotEmpty()) "有声书 · 共 ${panelEpisodes.size} 章"
            else "有声书 · 点播"
        ch.isPodcast -> if (currentEpisode != null && panelEpisodes.isNotEmpty()) {
            "播客 · 共 ${panelEpisodes.size} 集"
        } else {
            "播客 · 点播"
        }
        ch.isAudio -> "网络电台 · 线路 ${orderPos + 1}/${ch.urls.size.coerceAtLeast(1)}"
        else -> "${ch.group} · 线路 ${orderPos + 1}/${ch.urls.size.coerceAtLeast(1)}"
    }

    /** 电台没有画面：动态壁纸铺底 + 台名居中，避免黑屏被误认为「没播出来」 */
    /** 流里报上来的曲目；null 表示这个台还没给出（多数台本来就不发） */
    private var nowPlayingTitle: String? = null

    /** 等了 [NOW_PLAYING_WAIT_MS] 还没有曲目就别一直空着——大多数台确实没有这个信息 */
    private val nowPlayingFallback = Runnable {
        if (!destroyed && radioMode && nowPlayingTitle == null) {
            radioNowPlaying.text = "直播中 · 该台未提供曲目信息"
            radioNowPlaying.visibility = View.VISIBLE
        }
    }

    /**
     * ICY 曲目回调。注意它跑在播放器的**加载线程**上：
     * 一是必须切回主线程改界面，二是绝不能往上抛异常（会把电台直接打断）。
     */
    private fun onRadioTitle(title: String) {
        handler.post {
            if (destroyed || !radioMode) return@post
            handler.removeCallbacks(nowPlayingFallback)
            nowPlayingTitle = title
            // 后台核对用：曲目来自流内元数据，不是猜的
            Log.d("XiaoHeiTV", "曲目 ${currentChannel?.name}: $title")
            radioNowPlaying.text = title
            radioNowPlaying.visibility = View.VISIBLE
        }
    }

    private fun applyRadioUi(ch: Channel) {
        val audio = ch.isAudio
        radioOverlay.visibility = if (audio) View.VISIBLE else View.GONE
        if (audio) {
            val ep = currentEpisode
            val podcast = ch.isPodcast && ep != null
            val book = podcast && isAudiobook(ch)
            radioKicker.text = when {
                book -> "有声书 · 正在播放"
                podcast -> "播客 · 正在播放"
                else -> "网络电台 · 正在播放"
            }
            if (podcast) {
                if (book) {
                    // 听书时屏幕中间放书名：章节名下面单独一行，两者都不至于挤成两行半
                    radioName.text = ch.name
                    radioName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 30f)
                    radioName.maxLines = 1
                    radioName.ellipsize = android.text.TextUtils.TruncateAt.END
                    radioName.maxWidth = (resources.displayMetrics.widthPixels * 0.62f).toInt()
                } else {
                    // 分集标题普遍很长：小一号、最多两行、限宽，避免横穿整个屏幕
                    radioName.text = ep!!.title
                    radioName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
                    radioName.maxLines = 2
                    radioName.ellipsize = android.text.TextUtils.TruncateAt.END
                    radioName.maxWidth = (resources.displayMetrics.widthPixels * 0.62f).toInt()
                }
                podcastProgress.visibility = View.VISIBLE
                updatePodcastProgress()
            } else {
                radioName.text = ch.name
                radioName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 34f)
                radioName.maxLines = 1
                radioName.ellipsize = null
                radioName.maxWidth = Int.MAX_VALUE
                podcastProgress.visibility = View.GONE
                // 从播客切到普通电台：字幕和上一集一起退场
                transcriptSeq++
                clearSubtitle()
            }
            // 换台换配色，同一个台永远同一套
            radioBackdrop.setStation(ch.name)

            // 上一台的曲目必须先清掉，否则「中国之声」下面会挂着上海台的歌
            nowPlayingTitle = null
            handler.removeCallbacks(nowPlayingFallback)
            if (podcast) {
                if (book) {
                    // 书：中间是书名，这行正好放「第几回」，听长书时很有用
                    radioNowPlaying.text = ep!!.title
                    radioNowPlaying.visibility = View.VISIBLE
                } else {
                    // 播客的标题上面已经写了这一集的名字，这行留着只是占位
                    radioNowPlaying.visibility = View.GONE
                }
            } else {
                radioNowPlaying.text = ""
                // 占位而不是 GONE：曲目一到就填字，布局不会跟着跳一下
                radioNowPlaying.visibility = View.INVISIBLE
                handler.postDelayed(nowPlayingFallback, NOW_PLAYING_WAIT_MS)
            }
        } else {
            nowPlayingTitle = null
            handler.removeCallbacks(nowPlayingFallback)
            podcastProgress.visibility = View.GONE
            stopPodcastTick()
        }
        // 视频态一帧都不画；退后台（resumed=false）同样停表
        radioBackdrop.setAnimating(audio && resumed)
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
        // 带节目名时多停 2 秒：「正在播 XXX　·　接下来 YYY」一行读完需要的时间比单看台名长
        handler.postDelayed(
            hideInfo,
            if (infoProgram.visibility == View.VISIBLE) INFO_HOLD_PROGRAM_MS else INFO_HOLD_MS
        )
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
            !cur.isNullOrEmpty() && !nx.isNullOrEmpty() -> "正在播 $cur　·　接下来 $nx"
            !cur.isNullOrEmpty() -> "正在播 $cur"
            !nx.isNullOrEmpty() -> "接下来 $nx"
            else -> ""
        }
        // 记下已经露过面的节目名，换档提示才知道「这个用户已经知道了吗」
        if (!cur.isNullOrEmpty()) programTitleShown = cur
        infoProgram.text = text
        infoProgram.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * 节目换档提示。
     *
     * 全屏看直播时没人会主动按遥控器去查「现在到底在播什么」，而节目单每半小时就会翻一页。
     * 这里每 30 秒对一次表，发现节目名变了就把顶部信息浮出来 6 秒（台名 + 新节目 + 接下来），
     * 然后自己收起来。只有节目真的换了才打扰，没换就一直安静。
     */
    private val programTick = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if (destroyed || !resumed) return
            val ch = currentChannel ?: return
            if (ch.isAudio || ch.isPodcast) return
            handler.postDelayed(this, PROGRAM_TICK_MS)
            val cur = epgMap[EpgRepository.normalize(ch.name)]?.current?.title
            if (cur.isNullOrEmpty() || cur == programTitleShown) return
            programTitleShown = cur
            if (fullscreen) showInfo()
        }
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

    // ---------------------------------------------------------------- 有声书

    /** 播客与有声书都是点播内容：统一排在视频源之后，切台序列里会被排除 */
    private fun catalogChannels(): List<Channel> =
        PodcastCatalog.shows.map { PodcastCatalog.asChannel(it) } +
            AudiobookCatalog.books.map { AudiobookCatalog.asChannel(it) }

    /** 这本书（频道行）是不是有声书 */
    private fun isAudiobook(ch: Channel?): Boolean =
        ch != null && ch.group == ChannelGroups.AUDIOBOOK

    /**
     * 打开书架。
     *
     * 有声书和播客共用这一层：都是「一档 RSS 专辑 = 一个封面」，
     * 区别只在数据源与文案，所以合成一个入口，靠 [kind] 分。
     * 书架是全屏的一层，和分集面板同级：右栏只有屏宽 1/3，摆不下封面墙。
     * 记住刚才在听的是哪一档，焦点直接落在它上面。
     */
    private fun openShelf(kind: String = ChannelGroups.AUDIOBOOK) {
        if (destroyed || shelfOpen) return
        shelfOpen = true
        shelfKind = kind
        setListPanelVisible(false)
        handler.removeCallbacks(hideInfo)
        infoBar.visibility = View.GONE
        shelfPanel.visibility = View.VISIBLE

        val isBook = kind == ChannelGroups.AUDIOBOOK
        val playing = currentChannel?.takeIf {
            if (isBook) isAudiobook(it) else it.isPodcast && !isAudiobook(it)
        }?.name

        if (isBook) {
            shelfTitle.text = "有声书"
            shelfAdapter.submitSections(
                sectionsOf(AudiobookCatalog.CATEGORIES, AudiobookCatalog.shelf),
                playing
            ) { it.author }
            shelfMeta.text = "${AudiobookCatalog.shelf.size} 本 · " +
                AudiobookCatalog.CATEGORIES.joinToString(" · ")
            shelfHint.text = "↑↓←→ 选书　OK 打开章节目录　返回键 回分组"
        } else {
            shelfTitle.text = "播客"
            shelfAdapter.submitSections(
                sectionsOf(PodcastCatalog.CATEGORIES, PodcastCatalog.shelf),
                playing
            ) { it.author }
            shelfMeta.text = "${PodcastCatalog.shelf.size} 档 · " +
                PodcastCatalog.CATEGORIES.joinToString(" · ")
            shelfHint.text = "↑↓←→ 选节目　OK 查看分集　返回键 回分组"
        }
        val idx = (if (playing != null) shelfAdapter.positionOf(playing) else -1).coerceAtLeast(0)
        focusBookAt(idx)
    }

    /**
     * 把清单按分类切成「分节」。
     *
     * 分类顺序按传入的固定表，不按字符串排（中文按 Unicode 排出来是乱的）；
     * 远端目录里没写分类或写了个不认识的，统一落到最后一节「其他」，一档都不丢。
     */
    private fun sectionsOf(
        categories: List<String>,
        items: List<PodcastShow>
    ): List<Pair<String, List<PodcastShow>>> {
        val byCategory = items.groupBy { it.category }
        val ordered = categories.map { it to byCategory[it].orEmpty() } +
            ("其他" to items.filter { it.category !in categories })
        return ordered.filter { it.second.isNotEmpty() }
    }

    /** 关掉书架：回到打开它的那个分组（有声书 / 播客） */
    private fun closeShelf() {
        if (!shelfOpen) return
        val kind = shelfKind
        shelfOpen = false
        shelfPanel.visibility = View.GONE
        applyFullscreen(false, persist = false)
        val gi = groups.indexOfFirst { it.name == kind }
        if (gi >= 0) {
            selectGroup(gi)
            backToGroups()
        }
    }

    private fun focusBookAt(index: Int) {
        // 分类标题不可聚焦：要落的位置正好是个标题，就往下挪到本节第一张卡片
        var i = index.coerceAtLeast(0)
        while (i < shelfAdapter.size() && shelfAdapter.isHeader(i)) i++
        if (i >= shelfAdapter.size()) return
        val target = i
        // 直接滚到卡片会把上一行的分类标题顶出屏幕，打开书架第一眼就看不到「故事纪实」这类分段。
        // 上面紧挨着标题时，按标题对齐，标题连同卡片一起露出来。
        if (i - 1 >= 0 && shelfAdapter.isHeader(i - 1)) {
            (shelfGrid.layoutManager as? GridLayoutManager)
                ?.scrollToPositionWithOffset(i - 1, 0)
                ?: shelfGrid.scrollToPosition(target)
        } else {
            shelfGrid.scrollToPosition(target)
        }
        // 书架刚从 GONE 变 VISIBLE，同一帧里还没有 ViewHolder，直接 requestFocus 会落空。
        // 这里重试几轮，最坏退回「把焦点给网格」——至少方向键还能用。
        var tries = 0
        val task = object : Runnable {
            override fun run() {
                if (destroyed || !shelfOpen || panelOpen) return
                val view = shelfGrid.findViewHolderForAdapterPosition(target)?.itemView
                if (view != null && view.requestFocus()) return
                if (++tries >= 6) {
                    shelfGrid.requestFocus()
                    return
                }
                handler.postDelayed(this, 50L)
            }
        }
        handler.postDelayed(task, 30L)
    }

    private fun bookFocusPosition(): Int {
        val focused = shelfGrid.focusedChild ?: return -1
        return shelfGrid.getChildAdapterPosition(focused)
    }

    private fun openFocusedBook() {
        val pos = bookFocusPosition()
        // 焦点理论上不会停在分类标题上（它不可聚焦），保险还是兜一层：跳过标题找第一档
        val show = shelfAdapter.itemAt(if (pos >= 0) pos else 0)
            ?: shelfAdapter.firstBook()
            ?: return
        openBook(show)
    }

    private fun openBook(show: PodcastShow) {
        val kind = shelfKind
        // 只在自己这一层里找：书和播客可能有同名条目，跨层取会打开错的东西
        val ch = allChannels.firstOrNull { it.isPodcast && it.group == kind && it.name == show.name }
            ?: if (kind == ChannelGroups.PODCAST) PodcastCatalog.asChannel(show)
            else AudiobookCatalog.asChannel(show)
        openEpisodePanel(ch)
    }

    // ---------------------------------------------------------------- 播客

    /**
     * 打开某一档的分集面板。
     *
     * 内存里有缓存就直接画（切换档目 0 延迟），没有就先转圈再去拉 RSS：
     * 单档 RSS 常有几百 KB 到几 MB，放在主线程解析会直接把界面卡住。
     */
    private fun openEpisodePanel(ch: Channel) {
        if (destroyed) return
        val show = PodcastCatalog.byName(ch.name) ?: return
        // 从书架点进来：返回时回书架。从两栏列表点进来：返回时回列表
        panelFromShelf = shelfOpen
        panelShow = show
        panelOpen = true
        episodePanel.visibility = View.VISIBLE
        setListPanelVisible(false)
        handler.removeCallbacks(hideInfo)
        infoBar.visibility = View.GONE
        episodeShowName.text = show.name
        episodeShowMeta.text = showMetaText(show, 0)
        applyEpisodeCover(show)

        val cached = podcastStore.memory(show.id)
        if (cached != null) {
            submitEpisodes(cached)
        } else {
            panelEpisodes = emptyList()
            episodeAdapter.submit(
                emptyList(), currentEpisode?.id,
                book = show.isBook
            )
            episodeEmpty.visibility = View.GONE
            episodeLoading.visibility = View.VISIBLE
            episodeShowMeta.text = showMetaText(show, 0) +
                (if (show.isBook) " · 正在获取章节…" else " · 正在获取分集…")
            loadEpisodes(show)
        }
        handler.postDelayed({
            if (destroyed || !panelOpen) return@postDelayed
            val pos = episodeAdapter.positionOf(currentEpisode?.id)
            focusEpisodeAt(if (pos >= 0) pos else 0)
        }, 80)
    }

    private fun loadEpisodes(show: PodcastShow) {
        val seq = ++podcastLoadSeq
        Thread {
            val list = runCatching { podcastStore.episodes(show) }.getOrElse { emptyList() }
            runOnUiThread {
                if (destroyed || isFinishing) return@runOnUiThread
                // 用户可能已经退出或者换了一档：回来的是旧结果就丢掉
                if (!panelOpen || panelShow?.id != show.id || seq != podcastLoadSeq) return@runOnUiThread
                submitEpisodes(list)
            }
        }.start()
    }

    private fun submitEpisodes(list: List<PodcastEpisode>) {
        panelEpisodes = list
        episodeLoading.visibility = View.GONE
        episodeAdapter.submit(
            list, currentEpisode?.id,
            book = panelShow?.isBook == true
        )
        val show = panelShow
        episodeShowMeta.text = if (show == null) "" else showMetaText(show, list.size)
        if (list.isEmpty()) {
            val book = show?.isBook == true
            episodeEmpty.text = if (book) "这本书暂时取不到章节\n检查网络后再进来试试"
            else "这一档暂时取不到分集\n检查网络后再进来试试"
            episodeEmpty.visibility = View.VISIBLE
            return
        }
        episodeEmpty.visibility = View.GONE
        val pos = episodeAdapter.positionOf(currentEpisode?.id)
        focusEpisodeAt(if (pos >= 0) pos else 0)
    }

    /** 章节面板的副标题：书用「分类 · 播讲 · 共 N 章」，播客用「分类 · 作者 · 共 N 集」 */
    private fun showMetaText(show: PodcastShow, count: Int): String {
        val head = listOf(show.category, show.author).filter { it.isNotEmpty() }.joinToString(" · ")
        val unit = if (show.isBook) "章" else "集"
        return when {
            count <= 0 -> head
            head.isEmpty() -> "共 $count $unit"
            else -> "$head · 共 $count $unit"
        }
    }

    /** 封面只给书：播客没有封面字段，这排留 GONE，标题照旧贴着顶边 */
    private fun applyEpisodeCover(show: PodcastShow) {
        if (show.cover.isEmpty()) {
            episodeCover.visibility = View.GONE
            episodeCover.setImageDrawable(null)
            return
        }
        episodeCover.visibility = View.VISIBLE
        val radius = (resources.displayMetrics.density * 10f).toInt()
        Glide.with(episodeCover)
            .load(show.cover)
            .apply(RequestOptions.bitmapTransform(RoundedCorners(radius)))
            .into(episodeCover)
    }

    private fun focusEpisodeAt(index: Int) {
        if (index < 0 || index >= episodeAdapter.size()) return
        (episodeList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
            index, episodeList.height / 3
        )
        handler.postDelayed({
            if (destroyed || !panelOpen) return@postDelayed
            episodeList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }, 40)
    }

    /** 关掉分集面板：回书架（如果是从书架进来的），否则回两栏列表 */
    private fun closeEpisodePanel() {
        if (!panelOpen) return
        panelOpen = false
        episodePanel.visibility = View.GONE
        if (panelFromShelf && shelfOpen) {
            panelFromShelf = false
            shelfPanel.visibility = View.VISIBLE
            val idx = shelfAdapter.positionOf(panelShow?.name ?: "")
            focusBookAt(if (idx >= 0) idx else 0)
            return
        }
        panelFromShelf = false
        applyFullscreen(false, persist = false)
        // 播客和书都可能不在当前分组里（听了一本书，列表却停在央视）：先把组切过去
        val group = allChannels.firstOrNull { it.isPodcast && it.name == panelShow?.name }?.group
        val gi = groups.indexOfFirst { it.name == group }
        if (gi >= 0 && gi != groupIndex) selectGroup(gi)
        val pos = channelAdapter.positionOf(panelShow?.name ?: "")
        if (pos >= 0) {
            focusInGroups = false
            channelList.requestFocus()
            focusChannelAt(pos)
        }
    }

    /**
     * 分集面板按 OK：
     *   焦点就在正在播的那一集 → 暂停 / 继续（不再从头播一遍）
     *   焦点在别的集         → 起播那一集
     */
    private fun playFocusedEpisode() {
        val show = panelShow ?: return
        val pos = episodeList.focusedChild?.let { episodeList.getChildAdapterPosition(it) } ?: -1
        val ep = episodeAdapter.itemAt(if (pos >= 0) pos else 0) ?: return
        if (ep.id == currentEpisode?.id && panelShow?.name == currentChannel?.name) {
            togglePodcastPlayback()
        } else {
            playEpisode(show, ep)
        }
    }

    /** 播客播放 / 暂停：只翻 playWhenReady，不重建播放器，进度和字幕原地停住 */
    private fun togglePodcastPlayback() {
        val p = player
        if (p == null || currentEpisode == null) return
        // IDLE（比如刚从后台回来还没起播）时 playWhenReady 翻了也没用，重新起播
        if (p.playbackState == Player.STATE_IDLE) {
            val ch = currentChannel ?: return
            val ep = currentEpisode ?: return
            val show = PodcastCatalog.byName(ch.name) ?: return
            playEpisode(show, ep)
            return
        }
        val play = !p.playWhenReady
        podcastPaused = !play
        runCatching { p.playWhenReady = play }
        episodeAdapter.setPlaying(currentEpisode?.id, paused = !play)
        updatePodcastProgress()
        if (!play) savePodcastPosition()
        showInfo()
    }

    /**
     * 播一集。
     *
     * 和电台共用同一条「纯音频直通」的起播路径：不走预播双槽（槽里音轨是禁用的），
     * 也不做清单广告过滤（m4a/mp3 不是 HLS，过滤器本来也帮不上忙）。
     * 视频台的切台流水线一行没动。
     */
    private fun playEpisode(show: PodcastShow, ep: PodcastEpisode) {
        if (destroyed) return
        val ch = allChannels.firstOrNull { it.isPodcast && it.name == show.name }
            ?: PodcastCatalog.asChannel(show)
        CrashGuard.noteChannel(ch.name)
        CrashGuard.notePhase("podcast")
        // 换到另一集之前先把上一集的进度存下来，不然最多会丢 5 秒
        if (currentEpisode != null) savePodcastPosition()
        currentChannel = ch
        currentEpisode = ep
        podcastPaused = false
        radioMode = true
        autoTries = 0
        playStartedAt = SystemClock.uptimeMillis()
        zapStartedAt = 0L
        store.lastChannelName = ch.name
        channelAdapter.setPlaying(ch.name)
        errorHint.visibility = View.GONE
        panelOpen = false
        episodePanel.visibility = View.GONE
        // 开始播放就退出书架/章节这两层：全屏看的是播放页，书架留在后面会挡住画面
        panelFromShelf = false
        shelfOpen = false
        shelfPanel.visibility = View.GONE
        infoMeta.text = lineLabel(ch)
        applyFullscreen(true, persist = true)
        applyRadioUi(ch)
        showInfo()
        // 续播：听到一半的集接着上次的位置；只听了几秒、或已经快听完了就从头开始
        val saved = store.podcastPosition(ep.id)
        val dur = ep.durationMs
        pendingSeekMs = if (saved > RESUME_MIN_MS && (dur <= 0 || saved < dur - RESUME_TAIL_MS)) saved else 0L
        episodeAdapter.setPlaying(ep.id)
        playDirect(ch, ep.url)
        schedulePodcastTick()
        loadTranscript(ep)
    }

    /**
     * 字幕加载：下载 VTT 可能要几百毫秒，绝不能卡住起播。
     * 先清掉上一集的字幕，加载完再按当前播放位置立即对上一句。
     */
    private fun loadTranscript(ep: PodcastEpisode) {
        val seq = ++transcriptSeq
        clearSubtitle()
        val url = ep.transcriptUrl
        if (url.isEmpty()) return
        Thread {
            val cues = runCatching { transcriptStore.cues(url) }.getOrDefault(emptyList())
            handler.post {
                if (destroyed || seq != transcriptSeq || currentEpisode?.id != ep.id) return@post
                activeCues = cues
                if (cues.isEmpty()) clearSubtitle() else updateSubtitle(player?.currentPosition ?: 0L)
            }
        }.start()
    }

    /** 每一秒的进度刷新顺带对一句字幕；快进、续播 seek 后也会立刻走到这里 */
    private fun updateSubtitle(posMs: Long) {
        val cue = PodcastTranscript.cueAt(activeCues, posMs)
        if (cue == null || !radioMode) {
            podcastSubtitle.visibility = View.GONE
            return
        }
        if (podcastSubtitle.text.toString() != cue.text) podcastSubtitle.text = cue.text
        podcastSubtitle.visibility = View.VISIBLE
    }

    private fun clearSubtitle() {
        activeCues = emptyList()
        podcastSubtitle.text = ""
        podcastSubtitle.visibility = View.GONE
    }

    /** 全屏上下键：上一集 / 下一集 */
    private fun stepEpisode(dir: Int) {
        val show = panelShow ?: PodcastCatalog.byName(currentChannel?.name ?: "") ?: return
        val cur = currentEpisode ?: return
        val list = panelEpisodes
        if (list.size < 2) {
            safeToast("这一档只取到一集")
            return
        }
        val idx = list.indexOfFirst { it.id == cur.id }
        val next = if (idx < 0) 0 else ((idx + dir) % list.size + list.size) % list.size
        playEpisode(show, list[next])
    }

    /** 全屏左右键：快退 / 快进 */
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0L } ?: currentEpisode?.durationMs ?: 0L
        if (dur <= 0L) return
        val target = (p.currentPosition + deltaMs).coerceIn(0L, dur)
        runCatching { p.seekTo(target) }
        updatePodcastProgress()
        safeToast(if (deltaMs > 0) "快进 15 秒" else "快退 15 秒")
        showInfo()
    }

    private fun updatePodcastProgress() {
        val p = player ?: return
        val ep = currentEpisode ?: return
        val pos = p.currentPosition.coerceAtLeast(0L)
        val dur = p.duration.takeIf { it > 0L } ?: ep.durationMs
        podcastBar.progress = if (dur > 0L) ((pos * 1000) / dur).toInt().coerceIn(0, 1000) else 0
        podcastTime.text = fmtTime(pos) + " / " + (if (dur > 0L) fmtTime(dur) else "--:--") +
            if (p.playWhenReady) "　← → 前后 15 秒" else "　已暂停 · OK 继续"
        updateSubtitle(pos)
    }

    private val podcastTick = object : Runnable {
        override fun run() {
            if (destroyed || !resumed || currentEpisode == null) return
            updatePodcastProgress()
            if (++tickSaveCounter >= 5) {
                tickSaveCounter = 0
                savePodcastPosition()
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private fun schedulePodcastTick() {
        handler.removeCallbacks(podcastTick)
        handler.postDelayed(podcastTick, 1000L)
    }

    private fun stopPodcastTick() {
        handler.removeCallbacks(podcastTick)
        transcriptSeq++
        clearSubtitle()
        savePodcastPosition()
    }

    private fun savePodcastPosition() {
        val ep = currentEpisode ?: return
        val pos = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
        if (pos > 0L) store.setPodcastPosition(ep.id, pos)
    }

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    // ---------------------------------------------------------------- 按键

    private fun handleOkShort() {
        // 书架里 OK＝打开这本书的章节目录
        if (shelfOpen) {
            openFocusedBook()
            return
        }
        if (fullscreen) {
            // 播客全屏按 OK＝播放 / 暂停（和遥控器上的播放键一个心智）；
            // 想回分集用返回键，逐层退出更稳，不会在「暂停」和「回列表」之间反复横跳
            if (currentEpisode != null) {
                togglePodcastPlayback()
                return
            }
            showListOnCurrentChannel()
            return
        }
        if (focusInGroups) {
            // 停在「有声书」/「播客」上按 OK：直接铺开封面墙（右栏太窄，摆不下书架）
            val g = groups.getOrNull(groupIndex)?.name
            if (g == ChannelGroups.AUDIOBOOK || g == ChannelGroups.PODCAST) openShelf(g)
            else enterChannels()
            return
        }
        val ch = focusedChannel() ?: return
        if (ch.isPodcast) {
            openEpisodePanel(ch)
            return
        }
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

        // ---- 分集面板：上下键交给 RecyclerView 自己挪焦点，其余按键在这里判 ----
        if (panelOpen) {
            if (isOk) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) playFocusedEpisode()
                return true
            }
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) closeEpisodePanel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                    return super.dispatchKeyEvent(event)
                else -> return true
            }
        }

        // ---- 书架：四向键交给网格自己挪焦点，返回键退出 ----
        if (shelfOpen) {
            if (isOk) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openFocusedBook()
                return true
            }
            when (code) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN) closeShelf()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_DOWN) openSettings()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // 已经在最左一列：再按左就退出书架，和列表里「左键收起」一致
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val pos = bookFocusPosition()
                        if (pos < 0 || pos % BOOK_COLUMNS == 0) {
                            closeShelf()
                            return true
                        }
                    }
                    return super.dispatchKeyEvent(event)
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT -> return super.dispatchKeyEvent(event)
                else -> return true
            }
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
                // 全屏播放时同样有效——以前这里被吞掉，想改设置得先按返回退出全屏，
                // 等于把「设置」藏在了只有退出观看才能到达的地方。
                openSettings()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                // 返回键逐层回退：
                //   全屏 → 唤出频道列表（播客全屏 → 回这一档的分集）
                //   焦点在右栏 → 回到左栏
                //   最外层（列表 + 焦点在左栏）→ 隐藏列表进入纯播放；
                //                                 紧接着 2 秒内再按一次才退出应用
                // 过滤长按产生的 repeat，避免一次长按被当成两次点击。
                if (event.repeatCount == 0) {
                    val now = SystemClock.uptimeMillis()
                    if (lastBackExitAt != 0L && now - lastBackExitAt < exitDoubleTapMs) {
                        finish()
                        return true
                    }
                }
                if (fullscreen) {
                    // 播客全屏按返回＝回到这一档的分集，而不是跑去频道列表
                    val show = currentChannel
                    if (currentEpisode != null && show != null) {
                        openEpisodePanel(show)
                        return true
                    }
                    showListOnCurrentChannel()
                    return true
                }
                if (!focusInGroups) {
                    backToGroups()
                    return true
                }
                // 最外层：把列表面板连同压暗层一起收掉，只留正在播的画面。
                // 这一步不写进偏好，重启应用仍是带列表进入；再按一次返回才真正退出。
                if (event.repeatCount == 0) {
                    applyFullscreen(true, persist = false)
                    lastBackExitAt = SystemClock.uptimeMillis()
                    safeToast("已隐藏列表，再按一次返回键退出应用")
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val up = code == KeyEvent.KEYCODE_DPAD_UP
                if (fullscreen) {
                    // 播客是点播：上下键＝上一集 / 下一集，比在空频道里切台有用
                    if (currentEpisode != null) {
                        stepEpisode(if (up) -1 else 1)
                        return true
                    }
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
                    // 播客全屏：左右键是快退/快进，不是唤出列表（返回键依然唤列表）
                    if (currentEpisode != null) {
                        seekBy(SEEK_STEP_MS)
                        return true
                    }
                    showListOnCurrentChannel()
                    return true
                }
                // 右键＝展开：从分组栏进入右栏（有声书进的是「一本一行」的列表，
                // 想看书架墙就按 OK——两条路都能到章节目录，谁快用谁）
                if (focusInGroups) enterChannels()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (fullscreen) {
                    if (currentEpisode != null) {
                        seekBy(-SEEK_STEP_MS)
                        return true
                    }
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
        handler.removeCallbacks(programTick)
        pendingPrebufferStarts.values.forEach { handler.removeCallbacks(it) }
        pendingPrebufferStarts.clear()
        pendingDirect?.let { handler.removeCallbacks(it) }
        pendingDirect = null
        runCatching { player?.pause() }
        cancelPrebuffer()
        // 播客进度落盘 + 停表：退后台绝不继续跑 1 秒一跳的进度刷新
        stopPodcastTick()
        // 壁纸动画只在前台播电台时存在，退后台绝不空转
        radioBackdrop.setAnimating(false)
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
        stopPodcastTick()
        radioBackdrop.setAnimating(false)
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
        val ep = currentEpisode
        if (ch != null && ch.isPodcast && ep != null) {
            // 播客：重新起播这一集，并按上次听到的位置续播
            if (player?.playbackState == Player.STATE_IDLE) {
                pendingSeekMs = store.podcastPosition(ep.id)
                playDirect(ch, ep.url)
                // playDirect 会置 playWhenReady=true；用户暂停过的要立刻按回去
                if (podcastPaused) runCatching { player?.playWhenReady = false }
            } else if (!podcastPaused) {
                runCatching { player?.playWhenReady = true }
            }
            episodeAdapter.setPlaying(ep.id, paused = podcastPaused)
            schedulePodcastTick()
            // 退后台时字幕被清掉了；回来重新挂上（VTT 已在内存/磁盘，命中极快）
            loadTranscript(ep)
        } else if (ch != null && ch.urls.isNotEmpty() && player?.playbackState == Player.STATE_IDLE) {
            buildLineOrder(ch)
            val url = ch.urls.getOrElse(urlIndex) { "" }
            if (url.isNotEmpty()) playDirect(ch, url)
        } else {
            runCatching { player?.playWhenReady = true }
        }
        // 回到前台时恢复壁纸（电台态才开，视频态保持关闭）
        radioBackdrop.setAnimating(radioMode)
        schedulePrebufferNext()
    }

    override fun onDestroy() {
        // 先立标志再动手：释放播放器会同步回调监听，此时绝不能再碰 View
        destroyed = true
        resumed = false
        stopPodcastTick()
        radioBackdrop.setAnimating(false)
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

    // 保守档把「同一时刻并存两路流」的窗口压到最短：更早接管、更宽的连按合并、
    // 更长的错峰，减少 2GB 老电视上解码器反复申请/释放的次数。预播命中仍是热切换。
    private fun staggerMs(): Long = if (conservative) 1_200L else SLOT_STAGGER_MS
    private fun coalesceMs(): Long = if (conservative) 200L else DIRECT_COALESCE_MS
    private fun prebufferTimeoutMs(): Long = if (conservative) 2_800L else PREBUFFER_TIMEOUT_MS

    private companion object {
        /** 书架列数：6 列刚好把 15 本书排成三行，一屏能看全 */
        const val BOOK_COLUMNS = 6

        /** 10s：等这么久还没有曲目，就当这个台不发元数据 */
        const val NOW_PLAYING_WAIT_MS = 10_000L

        /** 30s：节目单的最小粒度是分钟级，30 秒对一次表足够，再密就是白白唤醒主线程 */
        const val PROGRAM_TICK_MS = 30_000L

        /** 顶部信息默认停留 4s */
        const val INFO_HOLD_MS = 4_000L

        /** 带节目名时多停 2s：一行「正在播 X · 接下来 Y」比单看台名要多花时间 */
        const val INFO_HOLD_PROGRAM_MS = 6_000L

        /** 5s：低于这个量再出图，网一抖就卡 */
        const val MIN_BUFFER_MS = 8_000

        /** 22s：直播多囤一点抗抖动；免费源抖动频繁，7 秒余量能挡掉大部分「看两秒卡一下」 */
        const val MAX_BUFFER_MS = 22_000

        /**
         * 1.2s：凑够 1.2 秒才出图（原 300ms）。
         *
         * 300ms 连半个分片都不到，很多时候画面刚出来就立刻断粮，用户看到的就是「一开播就卡」。
         * 抬到 1.2 秒，起播大约慢 0.9 秒，但换来的是出图之后能连续播，体感明显更顺。
         * 预播流水线已经提前缓存，真正切台时这 0.9 秒基本感觉不到。
         */
        const val BUFFER_FOR_START_MS = 1_200

        /** 2.5s：卡过之后多补一点再恢复，避免「恢复—立刻再卡」的抽风循环 */
        const val BUFFER_AFTER_REBUFFER_MS = 2_500

        /** 3.5s：预播迟迟不就绪就别让用户盯着静帧了（超时改为提前接管，不丢弃已缓冲数据） */
        const val PREBUFFER_TIMEOUT_MS = 3_500L

        /** 预播播放器在保守档的上限：主播放器仍是 15s */
        const val PRE_MAX_BUFFER_MS = 8_000

        /** 第二槽错峰启动，避免两路流同时冷启动互相抢带宽 */
        const val SLOT_STAGGER_MS = 700L

        /** 极快连按时冷启动合并窗口：只影响 <120ms 的连按，预播命中不经过这里 */
        const val DIRECT_COALESCE_MS = 120L

        /** 播客左右键一次跳 15 秒：一集动辄 40 分钟，跳太短等于没跳 */
        const val SEEK_STEP_MS = 15_000L

        /** 续播门槛：只听了不到 5 秒就当中途没听过，从头开始 */
        const val RESUME_MIN_MS = 5_000L

        /** 离结尾不到 10 秒也当成听完了，下次从头放（否则一进来就结束） */
        const val RESUME_TAIL_MS = 10_000L
    }
}

