package com.xiaohei.tv.ui

import kotlin.math.PI
import kotlin.math.sin

/**
 * 广播动态壁纸的数学层：**不引用任何 Android 类型**，所以能在 JVM 单测里直接跑。
 *
 * 设计目标是「看起来一直在动，但几乎不花 CPU」：
 * - 全部用低频正弦叠加，没有随机数、没有状态、没有对象分配，任意时刻的
 *   画面只由 (时间, 索引, 宽高) 决定，暂停/恢复也不会跳帧；
 * - 所有输出都有界，长时间听广播（t 累积到几万秒）不会出现 NaN 或飞出屏幕；
 * - 帧率、光晕数、采样点数按设备档位给出，老电视自动降到最低开销。
 */
object RadioBackdropMath {

    /** 每套配色：底色、光晕 1/2/3、波形与光环。RGB，不带 alpha。 */
    val PALETTES: Array<IntArray> = arrayOf(
        intArrayOf(0x050A16, 0x0A84FF, 0x5E5CE6, 0x64D2FF, 0x8AB4FF), // 深蓝
        intArrayOf(0x120A08, 0xFF9F0A, 0xFF453A, 0xFFD60A, 0xFFC46B), // 暖金
        intArrayOf(0x04120F, 0x30D158, 0x0A84FF, 0x66D4CF, 0x7BE0C0), // 青绿
        intArrayOf(0x100616, 0xFF375F, 0xBF5AF2, 0xFF6482, 0xE39BFF)  // 品红
    )

    const val MAX_GLOWS = 3

    /** 标准档 ~30fps；保守档 ~15fps。电视上 15fps 的缓慢光晕已经足够顺滑。 */
    fun frameMs(conservative: Boolean): Long = if (conservative) 66L else 33L

    fun glowCount(conservative: Boolean): Int = if (conservative) 2 else MAX_GLOWS

    fun waveSamples(conservative: Boolean): Int = if (conservative) 32 else 64

    fun particleCount(conservative: Boolean): Int = if (conservative) 0 else 18

    /** 同一个台永远同一套配色，切台才有变化；不会每次重绘都跳色。 */
    fun paletteFor(station: String): Int =
        (station.hashCode() and Int.MAX_VALUE) % PALETTES.size

    /**
     * 把运行秒数收敛到一个有限区间。float 的 sin 在数值很大时精度会明显下降，
     * 听几个小时广播会出现「动画卡顿」；一小时轮回一次，肉眼完全看不出来。
     * NaN / 负数统一归零，避免异常时间基准污染绘制。
     */
    fun phase(seconds: Float): Float {
        if (seconds.isNaN() || seconds <= 0f) return 0f
        return seconds % 3600f
    }

    /** 波形：三层低频正弦叠加，返回 [-1, 1]。x 为 0..1 的归一化横坐标。 */
    fun wave(t: Float, x: Float): Float {
        val v = sin(x * 9.0f + t * 0.90f) * 0.55f +
            sin(x * 17.0f - t * 1.35f + 0.7f) * 0.28f +
            sin(x * 3.3f + t * 0.45f) * 0.17f
        return v.coerceIn(-1f, 1f)
    }

    /** 两端收拢到 0，避免波形在屏幕边缘被硬切。x 为 0..1。 */
    fun envelope(x: Float): Float {
        val s = sin(PI.toFloat() * x.coerceIn(0f, 1f))
        return s * s
    }

    fun glowX(t: Float, index: Int, width: Float): Float =
        width * (0.5f + 0.34f * sin(t * (0.110f + 0.017f * index) + index * 2.1f))

    fun glowY(t: Float, index: Int, height: Float): Float =
        height * (0.5f + 0.30f * sin(t * (0.083f + 0.013f * index) + index * 1.37f + 1.1f))

    /** 中心光环的缓慢呼吸，幅度只有 ±3.5%。 */
    fun haloScale(t: Float): Float = 1f + 0.035f * sin(t * 0.70f)

    /** 微粒横坐标：黄金分割伪随机，固定不闪烁。 */
    fun particleX(index: Int, width: Float): Float = ((index * 0.6180339f) % 1f) * width

    /** 微粒纵坐标：极缓慢上浮，到顶后从底部循环回来。 */
    fun particleY(t: Float, index: Int, height: Float): Float {
        val speed = 0.010f + (index % 5) * 0.0035f
        val base = ((index * 0.137f) % 1f) * height
        return ((base - t * speed * height) % height + height) % height
    }
}
