package com.xiaohei.tv.player

import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * 按网络档位动态放宽「起播 / 卡顿后恢复」门槛的 LoadControl 包装。
 *
 * 为什么用包装而不是直接改参数：LoadControl 在播放器创建时固定，运行期改不了；
 * 而 [LoadControl.Parameters] 每次缓冲推进都会送进来，是唯一的动态决策点。
 *
 * 设计原则：
 *  - FAST 档完全委托 inner——行为与 v2.18.1 一字不差，回归面为零；
 *  - MED/SLOW 的门槛（见常量）都比 inner 里的 1200/2500ms 更大，
 *    因此任何情况下都比现状更保守：多攒一点缓冲再出画，换取更少的断粮卡顿；
 *  - 该改动最终是否保留，以弱网回归数据为准（详见验证报告）。
 */
class AdaptiveLoadControl(private val inner: LoadControl) : LoadControl {

    companion object {
        /** 中速档出图门槛：比快网多等 0.6s，让分片多下 1MB 左右再出画 */
        const val START_MED_MS = 1_800L

        /** 弱网档出图门槛：1.5Mbps 下 2.5s ≈ 470KB，接近一个 10s 分片的头 */
        const val START_SLOW_MS = 2_500L

        /** 中速档卡顿后恢复门槛 */
        const val REBUFFER_MED_MS = 3_500L

        /** 弱网档卡顿后恢复门槛：比快网翻倍，避免「恢复→立刻再断」的抖动 */
        const val REBUFFER_SLOW_MS = 5_000L
    }

    override fun getAllocator(): Allocator = inner.allocator

    // 生命周期回调必须显式委托：LoadControl 接口上这几个的默认实现是
    // 「直接抛 IllegalStateException(not implemented)」，不覆写会在
    // 播放器 stop / release 的瞬间把整个进程掀掉（已真机踩过一次）。
    override fun onPrepared(playerId: PlayerId) = inner.onPrepared(playerId)

    override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)

    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        renderers: Array<Renderer>,
        trackGroups: TrackGroupArray,
        selections: Array<ExoTrackSelection>
    ) = inner.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, selections)

    override fun getBackBufferDurationUs(playerId: androidx.media3.exoplayer.analytics.PlayerId): Long =
        inner.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: androidx.media3.exoplayer.analytics.PlayerId): Boolean =
        inner.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        inner.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val tier = NetProbe.tier()
        // 快网档：与 v2.18.1 行为完全一致
        if (tier == NetProbe.Tier.FAST) return inner.shouldStartPlayback(parameters)
        val needMs = if (parameters.rebuffering) rebufferMs(tier) else startMs(tier)
        return parameters.bufferedDurationUs >= Util.msToUs(needMs)
    }

    private fun startMs(tier: NetProbe.Tier): Long = when (tier) {
        NetProbe.Tier.SLOW -> START_SLOW_MS
        else -> START_MED_MS
    }

    private fun rebufferMs(tier: NetProbe.Tier): Long = when (tier) {
        NetProbe.Tier.SLOW -> REBUFFER_SLOW_MS
        else -> REBUFFER_MED_MS
    }
}
