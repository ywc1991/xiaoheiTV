package com.xiaohei.tv

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue

/**
 * 手机 / 电视的界面模式判定与布局尺寸策略。
 *
 * 背景：小黑TV 原本只面向 Android TV——锁横屏、靠遥控器方向键、面板宽 = 屏宽 1/3。
 * 手机上这套尺寸完全不合适（竖屏时 1/3 屏宽放不下两栏），且用户用手指点击而非方向键。
 * 这里把「是不是手机」「面板该多宽」抽成纯函数，便于单测，也避免把这些判断散落在
 * MainActivity 的 3000 行里。
 *
 * 判定原则：宁可把设备当电视（保守），也不要把电视误判成手机——
 * 电视上错误地切到手机布局会把面板拉成竖屏宽度，破坏原本的观看体验。
 * 所以只有当「明确不是电视」时才算手机。
 */
object UiMode {

    enum class Device { PHONE, TV }

    /** 判定是否为 Android TV / 电视盒子。命中任一即可靠电视特征即判 TV。 */
    fun deviceOf(c: Context): Device {
        // 1) Leanback 系统（原生 Android TV）直接判 TV
        val pm = c.packageManager
        if (pm.hasSystemFeature("android.software.leanback")) return Device.TV
        // 2) 无触摸 + 有物理方向键 = 电视/盒子（手机都有触摸屏）
        if (!pm.hasSystemFeature("android.hardware.touchscreen") &&
            pm.hasSystemFeature("android.hardware.keyboard")) return Device.TV
        // 3) 大屏且非手持：sw>=600dp 且 24h 未充电也无所谓，这里只取屏幕尺寸特征
        val swDp = c.resources.configuration.smallestScreenWidthDp
        if (swDp >= 600) return Device.TV
        return Device.PHONE
    }

    fun isPhone(c: Context): Boolean = deviceOf(c) == Device.PHONE

    /** 当前是否竖屏（配置层面判断，与 Activity 是否锁死无关） */
    fun isPortrait(c: Context): Boolean =
        c.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    /**
     * 列表面板宽度（像素）。
     * - 电视：屏宽 33%（2026-11-23 按用户要求从 38% 收窄；再宽的档位在窄屏电视上会
     *   压缩右侧画面，而放宽面板并不能让字号更大，只会让列表看起来更空）。
     * - 手机横屏：屏宽 52%，比电视比例略宽，手指点列表项不至于太窄。
     * - 手机竖屏：屏宽 68%——之前 96% 几乎铺满整屏，列表显得过大、画面几乎看不到；
     *   收窄后右侧留出一条画面，面板仍然够两栏点击（左 36% / 右 64% 分摊）。
     */
    fun panelWidthPx(c: Context): Int {
        val w = c.resources.displayMetrics.widthPixels
        val device = deviceOf(c)
        val portrait = isPortrait(c)
        val ratio = when {
            device == Device.PHONE && portrait -> 0.68f
            device == Device.PHONE -> 0.52f
            else -> 0.33f
        }
        return (w * ratio).toInt()
    }

    /** 面板左右下 margin（像素）：手机竖屏收窄边距，给两栏多留空间 */
    fun panelMarginPx(c: Context): Int {
        val dp = 22f
        return if (isPhone(c) && isPortrait(c)) (10f * c.resources.displayMetrics.density).toInt()
        else TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.resources.displayMetrics).toInt()
    }

    /** 底部提示栏文案：手机强调触摸，电视强调遥控器 */
    fun hintText(phone: Boolean, isPodcast: Boolean, isAudiobook: Boolean): String = when {
        isPodcast -> if (phone) "点节目打开分集　·　横竖屏可切换" else "OK 打开书架　→ 浏览列表　← 返回"
        isAudiobook -> if (phone) "点封面打开书架　·　横竖屏可切换" else "OK 打开书架　→ 浏览列表　← 返回"
        phone -> "点分组切换　点频道播放　·　画面点击收起列表"
        else -> "↑↓ 选择　OK 播放　← 返回\n长按 OK 收藏　菜单 设置"
    }
}
