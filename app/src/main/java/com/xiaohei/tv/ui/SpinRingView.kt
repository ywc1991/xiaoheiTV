package com.xiaohei.tv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 换台反馈圈：一个持续旋转的五彩渐变圆环。
 *
 * 为什么不用系统 indeterminate ProgressBar：
 *  - 它是单色，做不出「五彩」；
 *  - 它的旋转动画依赖厂商固件里的 indeterminate drawable，部分老电视 / 定制 ROM
 *    上要么不转、要么转得极慢，达不到「明显在转」的反馈效果。
 * 这里用 SweepGradient 自己画渐变弧，ValueAnimator 驱动角度（API 11+，minSdk19 安全），
 * 动画由代码控制，跟固件无关，所有品牌表现一致。
 *
 * 耗电：只在 View 可见时才跑动画（onAttached / onVisibilityChanged 里启停），
 * 换台瞬间显示一两秒就隐藏，老电视上也不会常驻占用主线程绘制。
 */
class SpinRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = STROKE_DP * resources.displayMetrics.density
    }
    private val arc = RectF()
    private var gradient: SweepGradient? = null
    private var size = 0

    // 一圈里五色闭环：青 → 蓝 → 紫 → 粉 → 橙 → 回到青，留一点缺口才看得出在转。
    private val colors = intArrayOf(
        Color.parseColor("#22D3EE"), // 青
        Color.parseColor("#3B82F6"), // 蓝
        Color.parseColor("#A855F7"), // 紫
        Color.parseColor("#EC4899"), // 粉
        Color.parseColor("#F59E0B"), // 橙
        Color.parseColor("#22D3EE")  // 闭合成环
    )

    private var animator: ValueAnimator? = null
    private var rotation = 0f
    private var running = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        size = minOf(w, h)
        val pad = paint.strokeWidth / 2f + 1f
        arc.set(pad, pad, w - pad, h - pad)
        gradient = SweepGradient(arc.centerX(), arc.centerY(), colors, null)
        paint.shader = gradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (size <= 0) return
        canvas.save()
        canvas.rotate(rotation, arc.centerX(), arc.centerY())
        // 290° 的弧，留 70° 缺口——旋转方向一眼可见
        canvas.drawArc(arc, -90f, 290f, false, paint)
        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                rotation = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        running = false
        animator?.cancel()
        animator = null
    }

    companion object {
        private const val STROKE_DP = 3.5f
    }
}
