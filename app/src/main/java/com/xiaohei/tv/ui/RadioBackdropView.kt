package com.xiaohei.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 广播页的动态壁纸：深色底 + 缓慢漂移的彩色光晕 + 流动声波 + 极缓慢上浮的微粒。
 *
 * 为什么是「自绘」而不是一段循环视频 / GIF：
 * 1. 电视上再开一路视频解码会直接挤占 MediaCodec，正是 v2.6/v2.7 花两版才
 *    压下去的换台闪退根因；自绘只用 GPU 合成，不申请解码器、不占带宽。
 * 2. 没有新依赖、没有权限、没有音频采集（Visualizer 要 RECORD_AUDIO，电视上
 *    常被拒且拿不到 offload 音轨），所以不会引入新的兼容性问题。
 * 3. 动画只在「正在播电台 + Activity 在前台」时跑。切回视频台父容器立刻 GONE，
 *    [setAnimating] 同步停表，视频播放/切台路径一帧都不会多画。
 *
 * 每帧只做几次 drawCircle 和一条 Path 描边：shader、Matrix、Path 全部预建复用，
 * 不在 onDraw 里分配对象。保守档（老电视 / 2GB）自动降到 15fps、2 个光晕、
 * 少一半波形采样、不画微粒。
 */
class RadioBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wavePaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePath = Path()
    private val wavePath2 = Path()
    private val glowShaders = arrayOfNulls<RadialGradient>(RadioBackdropMath.MAX_GLOWS)
    private val glowMatrices = Array(RadioBackdropMath.MAX_GLOWS) { Matrix() }

    private var conservative = false
    private var palette = 0
    private var shadersReady = false
    private var vw = 0f
    private var vh = 0f
    private var glowRadius = 0f

    /** 只有 MainActivity 切进/切出电台态才会改，避免容器可见性变化把动画偷偷拉起来。 */
    private var animationEnabled = false
    private var animating = false
    private var baseUptime = 0L
    private var frozenT = 0f

    private val ticker = object : Runnable {
        override fun run() {
            if (!animating) return
            invalidate()
            postDelayed(this, RadioBackdropMath.frameMs(conservative))
        }
    }

    /** 由 DeviceProfile 决定：老电视走低帧率、少光晕。 */
    fun setConservative(value: Boolean) {
        if (conservative == value) return
        conservative = value
        if (animating) {
            removeCallbacks(ticker)
            postDelayed(ticker, RadioBackdropMath.frameMs(conservative))
        }
        if (isShown) invalidate()
    }

    /** 切台时换一套配色：同一个台永远同一套，不会每帧跳色。 */
    fun setStation(name: String) {
        val p = RadioBackdropMath.paletteFor(name)
        if (p == palette && shadersReady) return
        palette = p
        buildShaders()
        if (isShown) invalidate()
    }

    /** 电台态 true / 视频态或退后台 false。幂等。 */
    fun setAnimating(on: Boolean) {
        animationEnabled = on
        sync()
    }

    private fun sync() {
        val shouldRun = animationEnabled && isAttachedToWindow &&
            visibility == VISIBLE && windowVisibility == VISIBLE
        if (shouldRun) start() else stop()
    }

    private fun start() {
        if (animating) return
        animating = true
        // 从上次停住的画面继续，切台/退后台回来不会突然跳一下
        baseUptime = SystemClock.uptimeMillis() - (frozenT * 1000f).toLong()
        removeCallbacks(ticker)
        postDelayed(ticker, 0L)
    }

    private fun stop() {
        if (!animating) return
        frozenT = RadioBackdropMath.phase((SystemClock.uptimeMillis() - baseUptime) / 1000f)
        animating = false
        removeCallbacks(ticker)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        vw = w.toFloat()
        vh = h.toFloat()
        buildShaders()
    }

    private fun buildShaders() {
        if (vw <= 0f || vh <= 0f) return
        val c = RadioBackdropMath.PALETTES[palette]
        glowRadius = min(vw, vh) * 0.62f
        for (i in 0 until RadioBackdropMath.MAX_GLOWS) {
            val rgb = c[1 + i]
            glowShaders[i] = RadialGradient(
                0f, 0f, glowRadius,
                intArrayOf(argb(0.32f, rgb), argb(0.11f, rgb), 0x00000000),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, vh,
            argb(1f, c[0]), argb(1f, brighten(c[0], 10)),
            Shader.TileMode.CLAMP
        )
        val line = c[4]
        wavePaint.color = argb(0.62f, line)
        wavePaint.strokeWidth = 2.2f * density
        wavePaint2.color = argb(0.20f, line)
        wavePaint2.strokeWidth = 1.2f * density
        haloPaint.color = argb(0.16f, line)
        haloPaint.strokeWidth = 1.2f * density
        particlePaint.color = argb(0.42f, line)
        shadersReady = true
    }

    override fun onDraw(canvas: Canvas) {
        if (!shadersReady || vw <= 0f || vh <= 0f) return
        val t = if (animating) {
            RadioBackdropMath.phase((SystemClock.uptimeMillis() - baseUptime) / 1000f)
        } else frozenT

        canvas.drawRect(0f, 0f, vw, vh, bgPaint)

        val n = RadioBackdropMath.glowCount(conservative)
        for (i in 0 until n) {
            val shader = glowShaders[i] ?: continue
            val x = RadioBackdropMath.glowX(t, i, vw)
            val y = RadioBackdropMath.glowY(t, i, vh)
            glowMatrices[i].setTranslate(x, y)
            shader.setLocalMatrix(glowMatrices[i])
            glowPaint.shader = shader
            glowPaint.alpha = (205 + 45 * Math.sin((t * 0.5f + i).toDouble())).toInt()
            canvas.drawCircle(x, y, glowRadius, glowPaint)
        }
        glowPaint.alpha = 255

        // 台名外围一圈极淡的呼吸光环，给居中的文字一点依托
        val haloR = min(vw, vh) * 0.245f * RadioBackdropMath.haloScale(t)
        canvas.drawCircle(vw / 2f, vh / 2f, haloR, haloPaint)

        drawWave(canvas, t, wavePath, wavePaint, 0f)
        drawWave(canvas, t, wavePath2, wavePaint2, 2.3f)

        val pc = RadioBackdropMath.particleCount(conservative)
        for (i in 0 until pc) {
            val x = RadioBackdropMath.particleX(i, vw)
            val y = RadioBackdropMath.particleY(t, i, vh)
            particlePaint.alpha = (50 + 70 * (0.5 + 0.5 * Math.sin((t * 0.8f + i).toDouble()))).toInt()
            canvas.drawCircle(x, y, (1.0f + (i % 3) * 0.6f) * density, particlePaint)
        }
        particlePaint.alpha = 255
    }

    private fun drawWave(canvas: Canvas, t: Float, path: Path, paint: Paint, phaseShift: Float) {
        val samples = RadioBackdropMath.waveSamples(conservative)
        val midY = vh * 0.70f
        val amp = min(vw, vh) * 0.065f
        path.reset()
        for (i in 0..samples) {
            val x = i.toFloat() / samples
            val px = x * vw
            val py = midY - RadioBackdropMath.wave(t + phaseShift, x) *
                amp * RadioBackdropMath.envelope(x)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        stop()
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        sync()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        sync()
    }

    private fun argb(alpha: Float, rgb: Int): Int = Color.argb(
        (alpha * 255f).toInt().coerceIn(0, 255),
        (rgb shr 16) and 0xFF,
        (rgb shr 8) and 0xFF,
        rgb and 0xFF
    )

    private fun brighten(rgb: Int, delta: Int): Int {
        fun ch(shift: Int): Int = (((rgb shr shift) and 0xFF) + delta).coerceAtMost(255)
        return Color.rgb(ch(16), ch(8), ch(0))
    }
}
