package com.xiaohei.tv.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * 网络速度自适应：
 * 主播放器与所有预播槽共享一个带宽估计器（挂在最底层 HTTP 数据源的
 * TransferListener 上，所有拉流字节都计入）；按估计带宽把网络分成三档，
 * 弱网时放宽「出图门槛」「卡顿后恢复门槛」，并把预播槽预算压到 2 路，
 * 把带宽让给正在看的画面。
 *
 * 设计原则（按「未被数据验证为正向不计入」的项目规则）：
 *  - FAST 档行为与 v2.18.1 完全一致（LoadControl 完全委托原实现，槽位不降级），
 *    回归面为零——所有观察到的行为差异只可能来自 MED/SLOW 档；
 *  - MED/SLOW 只会比现状更保守（要求更多缓冲才起播），不会比现状更快起播，
 *    因此最坏情况是「弱网下多等几百毫秒再出图」，换来更少的断粮卡顿；
 *  - 不调最大缓冲：弱网下 22s 的目标本来就填不满，加大只吃内存。
 *
 * 回退方式：把 [ENABLED] 置 false，所有路径回到 v2.18.1 静态参数。
 */
object NetProbe {

    private const val TAG = "XiaoHeiTV"

    /** 总开关。false 时 buildLoadControl 直接返回原 DefaultLoadControl、槽位不降级 */
    const val ENABLED = true

    enum class Tier { FAST, MED, SLOW }

    /** 档位阈值（bps）：>4Mbps 快网；1.5~4Mbps 中速；<1.5Mbps 弱网 */
    const val FAST_BPS = 4_000_000L
    const val MED_BPS = 1_500_000L

    /** 新档位要连续保持这么久才真正生效：带宽的瞬时抖动不应该触发档位切换 */
    const val TIER_HOLD_MS = 5_000L

    private val policy = NetTierPolicy()

    private lateinit var meter: DefaultBandwidthMeter

    @Volatile private var lastLoggedTier: Tier? = null

    /** 初始化共享带宽估计器。必须在任何播放器 prepare 之前调用 */
    fun init(context: Context) {
        if (::meter.isInitialized) return
        meter = DefaultBandwidthMeter.Builder(context.applicationContext)
            // 网络类型切换（WiFi↔有线↔热点）时自动清零重估，避免拿旧值误判好几秒
            .setResetOnNetworkTypeChange(true)
            .build()
        Log.i(TAG, "netProbe init meter=ok")
    }

    /** 共享估计器；未初始化（例如测试环境）返回 null，工厂就不挂 TransferListener */
    fun meterOrNull(): DefaultBandwidthMeter? = if (::meter.isInitialized) meter else null

    /** 当前估计带宽（bps）。估计器还没有数据时返回 -1 */
    fun estimateBps(): Long = if (::meter.isInitialized) meter.bitrateEstimate else -1L

    /** 当前生效档位（带滞回）。主线程与播放器 loader 线程都会调，需同步 */
    @Synchronized
    fun tier(): Tier {
        if (!ENABLED) return Tier.FAST
        val est = estimateBps()
        val t = policy.tier(est)
        if (t != lastLoggedTier) {
            lastLoggedTier = t
            Log.i(TAG, "netTier $t estimate=${est / 1_000_000.0}Mbps")
        }
        return t
    }
}

/**
 * 档位判定 + 滞回。纯 Kotlin 无 Android 依赖（[now] 注入时钟），单测覆盖。
 *
 * 滞回规则：原始档位与当前档位不同时，先记为候选档；候选档连续保持 [holdMs]
 * 才真正切换；中途原始档位抖回旧值则候选作废。这样带宽瞬时抖动不会导致
 * 缓冲参数来回横跳。
 */
class NetTierPolicy(
    private val fastBps: Long = NetProbe.FAST_BPS,
    private val medBps: Long = NetProbe.MED_BPS,
    private val holdMs: Long = NetProbe.TIER_HOLD_MS,
    private val now: () -> Long = SystemClock::uptimeMillis,
) {

    /** 无滞回的原始档位。-1 表示估计器还没有数据：按快网处理（与 v2.18.1 行为一致） */
    fun rawTier(bps: Long): NetProbe.Tier = when {
        bps < 0 -> NetProbe.Tier.FAST
        bps >= fastBps -> NetProbe.Tier.FAST
        bps >= medBps -> NetProbe.Tier.MED
        else -> NetProbe.Tier.SLOW
    }

    private var current = NetProbe.Tier.FAST
    private var candidate = NetProbe.Tier.FAST
    private var candidateSince = -1L

    @Synchronized
    fun tier(bps: Long): NetProbe.Tier {
        val raw = rawTier(bps)
        if (raw == current) {
            candidate = raw
            candidateSince = -1L
            return current
        }
        if (raw == candidate) {
            if (candidateSince < 0) {
                candidateSince = now()
            } else if (now() - candidateSince >= holdMs) {
                current = raw
                candidateSince = -1L
            }
        } else {
            candidate = raw
            candidateSince = now()
        }
        return current
    }
}
