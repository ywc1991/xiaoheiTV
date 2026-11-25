package com.xiaohei.tv

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import kotlin.math.max
import kotlin.math.min

/**
 * 显示兼容：把各品牌电视 / 盒子 / 投影仪上差异很大的「字体缩放」和「屏幕密度」收敛到安全区间。
 *
 * 背景：清单里虽然声明了 fontScale 变更不重建 Activity，但国产电视 / 老盒子的「老人模式 /
 * 大字体」常把 fontScale 设到 1.5~2.0，甚至部分投影仪出厂就是 1.3。本应用的频道面板宽度是按
 * 屏幕像素的 33% 写死、行高按 dp 固定，sp 文字一旦被放大 1.5 倍就会撑破固定行高，出现文字
 * 截断、重叠、台标错位——这正是用户反复反馈过的问题类型。这里在 attachBaseContext 阶段把
 * fontScale 钳到 [MIN, MAX]，既保留轻微放大的可读性，又杜绝撑破布局。
 *
 * 抽成纯函数 [clampFontScale] / [clampDensity] 便于 JVM 单测钉死边界，不依赖 Android 运行时。
 *
 * 设计原则：取值在正常范围（1.0）时原样返回——绝大多数设备零变化；只钳制异常值。
 */
object DisplayCompat {

    /** 字体缩放下限：小于 1.0 的字在电视远距离观看下看不清，兜到 1.0 */
    const val MIN_FONT_SCALE = 1.0f

    /** 字体缩放上限：超过 1.25 的 sp 文字会撑破固定的列表行高 */
    const val MAX_FONT_SCALE = 1.25f

    /** 系统 density 下限（dp→px 缩放），过低会让固定 dp 的图标/边距几乎不可见 */
    private const val MIN_DENSITY = 0.85f

    /** 系统 density 上限，过高会让按 px 写的面板宽度比例失调 */
    private const val MAX_DENSITY = 3.5f

    /** 把字体缩放钳到 [MIN_FONT_SCALE, MAX_FONT_SCALE]；NaN / 非正数按 1.0 处理 */
    fun clampFontScale(scale: Float): Float {
        if (scale.isNaN() || scale <= 0f) return 1.0f
        return max(MIN_FONT_SCALE, min(MAX_FONT_SCALE, scale))
    }

    /** 把 density 钳到合理区间，异常值按 1.0 处理 */
    fun clampDensity(density: Float): Float {
        if (density.isNaN() || density <= 0f) return 1.0f
        return max(MIN_DENSITY, min(MAX_DENSITY, density))
    }

    /**
     * 生成用于包装 Context 的 Configuration：只在 fontScale 越界时创建新对象，
     * 正常设备返回 null，调用方据此决定是否真的 wrap，避免无谓的 Configuration 拷贝。
     */
    fun correctedConfig(base: Configuration): Configuration? {
        val scale = base.fontScale
        val fixed = clampFontScale(scale)
        if (fixed == scale) return null
        val out = Configuration(base)
        out.fontScale = fixed
        return out
    }

    /** 调试摘要：把实际生效的缩放写进启动日志，方便别人家的电视回传现场信息 */
    fun summary(c: Context): String {
        val r = c.resources
        return "fontScale=${r.configuration.fontScale} density=${r.displayMetrics.density} " +
            "sw=${r.configuration.smallestScreenWidthDp}dp"
    }
}
