package com.xiaohei.tv

/**
 * 遥控器按键归一：把不同品牌遥控器上「语义相同、键值不同」的键归到一类。
 *
 * 为什么单独抽成不依赖 Android 类型的纯函数（和 [DeviceProfile.classify] 一个思路）：
 *  - 这里漏认一个键，在用户那台机器上就是「方向能动、某个键按了没反应」，
 *    而且机顶盒 / 投影仪 / 蓝牙遥控的键值比标准电视乱得多，远程极难定位；
 *  - 抽出来后能像设备分级一样在 JVM 单测里钉死，以后加品牌映射不会误伤别的键。
 *
 * 常量直接写 KeyEvent.KEYCODE_* 的官方数值，避免单测加载 android.jar。
 * 每个值都在注释里标了对应键名，改之前请先核对 KeyEvent 文档。
 */
object RemoteKeys {
    // —— 确认 / OK ——
    const val DPAD_CENTER = 23          // 标准 OK
    const val ENTER = 66                // 部分蓝牙遥控（极米 / 坚果一类）
    const val NUMPAD_ENTER = 160        // 带数字键盘的遥控
    const val BUTTON_A = 96             // 手柄 / 空中鼠标
    const val BUTTON_SELECT = 109       // 手柄

    // —— 菜单 / 设置 ——
    const val MENU = 82                 // 小米 / 当贝 / 多数盒子
    const val SETTINGS = 176            // 海信 / 创维部分型号
    const val TV_CONTENTS_MENU = 256    // 索尼 / 飞利浦「主菜单」
    const val TV_MEDIA_CONTEXT_MENU = 257 // 索尼 / 飞利浦「选项」
    const val INFO = 165                // 老遥控的「信息」复用键
    const val PROG_RED = 183            // 机顶盒常把菜单做在彩键上
    const val PROG_GREEN = 184
    const val PROG_YELLOW = 185
    const val PROG_BLUE = 186

    // —— 频道 +/-（机顶盒遥控器最常见的物理换台键）——
    const val CHANNEL_UP = 166          // CH+ / P+：下一个台
    const val CHANNEL_DOWN = 167        // CH- / P-：上一个台

    // —— 播放控制 ——
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127
    const val MEDIA_NEXT = 87           // 下一曲 / 下一个台
    const val MEDIA_PREVIOUS = 88       // 上一曲 / 上一个台

    // —— 翻页键（部分盒子 / 投影仪把上一页/下一页当切台用）——
    const val PAGE_UP = 93              // PGUP：上一个台 / 上一集
    const val PAGE_DOWN = 94            // PGDN：下一个台 / 下一集

    // —— 快进 / 快退键（部分蓝牙遥控没有独立的上一/下一，只有这两个）——
    const val MEDIA_FAST_FORWARD = 90   // FF：下一个台 / 下一集
    const val MEDIA_REWIND = 89         // REW：上一个台 / 上一集

    // —— 返回 ——
    const val BACK = 4
    const val ESCAPE = 111              // 部分蓝牙遥控 / 投影仪的返回键

    // —— 数字键（主键盘区 + 小键盘区）——
    const val DIGIT_0 = 7               // KEYCODE_0 .. KEYCODE_9 = 7 .. 16
    const val DIGIT_9 = 16
    const val NUMPAD_0 = 144            // KEYCODE_NUMPAD_0 .. _9 = 144 .. 153
    const val NUMPAD_9 = 153

    /** 确认键。少认一个，那台机器上就是「方向能动、按 OK 没反应」。 */
    fun isOk(code: Int): Boolean = when (code) {
        DPAD_CENTER, ENTER, NUMPAD_ENTER, BUTTON_A, BUTTON_SELECT -> true
        else -> false
    }

    /**
     * 菜单 / 设置键。只收「在本应用里本来没有语义」的键，
     * 绝不和方向 / OK / 返回 / 换台抢键。
     */
    fun isSettings(code: Int): Boolean = when (code) {
        MENU, SETTINGS, TV_CONTENTS_MENU, TV_MEDIA_CONTEXT_MENU,
        INFO, PROG_RED, PROG_GREEN, PROG_YELLOW, PROG_BLUE -> true
        else -> false
    }

    /**
     * 物理换台键（CH+/-、P+/-）。返回 +1 下一个台 / -1 上一个台 / 0 不是换台键。
     * 注意方向和方向键相反：CH+ 是「频道号 +1」＝下一个，对应下键而非上键。
     */
    fun channelStep(code: Int): Int = when (code) {
        CHANNEL_UP -> 1
        CHANNEL_DOWN -> -1
        else -> 0
    }

    /** 播放 / 暂停物理键。 */
    fun isPlayPause(code: Int): Boolean = when (code) {
        MEDIA_PLAY_PAUSE, MEDIA_PLAY, MEDIA_PAUSE -> true
        else -> false
    }

    /**
     * 媒体上一 / 下一键。语义同换台 / 换集：+1 下一个 / -1 上一个 / 0 不是。
     * 同时收纳翻页键（PGUP/PGDN）与快进/快退键——部分盒子 / 投影仪的遥控器
     * 没有独立的「上一个/下一个」，只有这几组物理键，漏认就是「按了没反应」。
     */
    fun mediaSkip(code: Int): Int = when (code) {
        MEDIA_NEXT, MEDIA_FAST_FORWARD, PAGE_DOWN -> 1
        MEDIA_PREVIOUS, MEDIA_REWIND, PAGE_UP -> -1
        else -> 0
    }

    /** 返回键：标准 BACK 之外，部分品牌把 ESCAPE 当返回。 */
    fun isBack(code: Int): Boolean = when (code) {
        BACK, ESCAPE -> true
        else -> false
    }

    /**
     * 数字键 → 0..9；非数字键返回 -1。
     * 同时认主键盘区（7..16）与小键盘区（144..153）——不少机顶盒遥控器走小键盘区。
     */
    fun digit(code: Int): Int = when (code) {
        in DIGIT_0..DIGIT_9 -> code - DIGIT_0
        in NUMPAD_0..NUMPAD_9 -> code - NUMPAD_0
        else -> -1
    }
}
