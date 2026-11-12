package com.xiaohei.tv.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.xiaohei.tv.player.AdFilterDataSourceFactory
import com.xiaohei.tv.player.TlsRelax

/**
 * 后台基准测量：给「起播到出图」计时，但**完全不碰电视画面**。
 *
 * 另起一个独立播放器，画面渲到 1×1 离屏纹理（或用 surface=none 干脆不接画面），
 * 音量关到 0。解码、下载、渲染都是真的，但屏幕上什么都不会变。
 *
 * 用法（电脑上执行）：
 * ```
 * adb shell am broadcast -a com.xiaohei.tv.BENCH -n com.xiaohei.tv/.bench.BenchReceiver \
 *     --es url "http://…/live.m3u8" --es surface none
 * ```
 * 结果看 `adb logcat -s XiaoHeiTV`。
 */
class BenchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra("url") ?: run {
            Log.w(TAG, "bench 缺少 url")
            return
        }
        val surfaceMode = intent.getStringExtra("surface") ?: "offscreen"
        val ctx = context.applicationContext
        TlsRelax.apply()
        val url2 = intent.getStringExtra("url2")
        Handler(Looper.getMainLooper()).post {
            if (url2 != null) {
                measureSwap(ctx, url, url2)
            } else {
                measure(ctx, url, surfaceMode)
            }
        }
    }

    /**
     * 模拟切台：A 台正在放，B 台在另一个播放器里静音预播；
     * B 就绪后把画面接上去，量「接画面到出第一帧」用多久。
     *
     * 这一段正是用户按下切台后看到黑一下的时间，也是这次优化的核心指标。
     */
    private fun measureSwap(ctx: Context, urlA: String, urlB: String) {
        val texA = SurfaceTexture(0)
        val surfA = Surface(texA)
        val texB = SurfaceTexture(0)
        val surfB = Surface(texB)

        val a = build(ctx)
        a.volume = 0f
        a.setVideoSurface(surfA)
        val b = build(ctx)
        b.volume = 0f

        var t0 = 0L
        var swapped = false

        b.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                if (!swapped) return
                Log.i(TAG, "bench[swap] 出图 +${SystemClock.uptimeMillis() - t0}ms（预播已就绪，只等解码器接管）")
                runCatching {
                    a.release(); b.release(); surfA.release(); surfB.release()
                    texA.release(); texB.release()
                }
            }
        })

        b.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !swapped) {
                    Log.i(TAG, "bench[swap] B 预播就绪，现在接画面")
                    swapped = true
                    t0 = SystemClock.uptimeMillis()
                    b.setVideoSurface(surfB)
                    b.playWhenReady = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "bench[swap] B 失败 ${error.cause?.message}")
            }
        })

        a.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_READY) return
                if (b.playbackState == Player.STATE_IDLE && b.mediaItemCount == 0) {
                    Log.i(TAG, "bench[swap] A 已就绪，开始预播 B")
                    b.setMediaItem(MediaItem.fromUri(urlB))
                    b.playWhenReady = true
                    b.prepare()
                }
            }
        })

        a.setMediaItem(MediaItem.fromUri(urlA))
        a.playWhenReady = true
        a.prepare()

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { a.release(); b.release(); surfA.release(); surfB.release() }
        }, 30_000)
    }

    private fun build(ctx: Context): ExoPlayer =
        ExoPlayer.Builder(ctx)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5_000, 20_000, 500, 1_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(AdFilterDataSourceFactory(ctx, { false }))
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            )
            .build()

    private fun measure(ctx: Context, url: String, surfaceMode: String) {
        val texture = if (surfaceMode == "none") null else SurfaceTexture(0)
        val surface = texture?.let { Surface(it) }

        val player = ExoPlayer.Builder(ctx)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5_000, 20_000, 500, 1_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(AdFilterDataSourceFactory(ctx, { false }))
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            )
            .build()
        player.volume = 0f
        surface?.let { player.setVideoSurface(it) }

        val t0 = SystemClock.uptimeMillis()
        var finished = false

        fun done() {
            if (finished) return
            finished = true
            runCatching {
                player.release()
                surface?.release()
                texture?.release()
            }
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_BUFFERING -> "缓冲中"
                    Player.STATE_READY -> "可播"
                    Player.STATE_ENDED -> "结束"
                    else -> "空闲"
                }
                Log.i(TAG, "bench[$surfaceMode] $name +${SystemClock.uptimeMillis() - t0}ms")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(
                    TAG,
                    "bench[$surfaceMode] 失败 +${SystemClock.uptimeMillis() - t0}ms " +
                        "${error.cause?.javaClass?.simpleName}:${error.cause?.message}"
                )
                done()
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                Log.i(TAG, "bench[$surfaceMode] 首帧 +${SystemClock.uptimeMillis() - t0}ms")
                done()
            }
        })

        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (url.substringBefore('?').endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item)
        player.playWhenReady = true
        player.prepare()

        // 兜底：不接画面时不会出首帧，20 秒后收工
        Handler(Looper.getMainLooper()).postDelayed({ done() }, 20_000)
    }

    private companion object {
        const val TAG = "XiaoHeiTV"
    }
}
