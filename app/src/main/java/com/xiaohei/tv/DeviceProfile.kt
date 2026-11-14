package com.xiaohei.tv

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build

/**
 * 设备画像：把「这台电视到底撑不撑得住多路解码」变成可判定、可导出、可单测的事实。
 *
 * 背景：TCL 75T6（TV+OS 4.0 / Android 8.0 / 2GB / A73×2+A53×2）换台闪退。
 * v2.6.0 的低内存判断是 `isLowRamDevice || memoryClass <= 64`：
 * 2GB 的电视 isLowRamDevice=false、memoryClass 通常 128~192，**两条都不命中**，
 * 于是它照样开满 2 个预播槽 + 主播放器 = 3 路解码，等于没做保护。
 *
 * 真正决定能不能多开的是：Android 版本（老固件 MediaCodec 异步回调实现有缺陷）、
 * 物理内存总量、单进程堆上限、硬件视频解码器数量。这里把这些都采出来，
 * 命中任意一条就进「保守档」：只开 1 个预播槽（最多 2 路解码），并关闭异步解码队列。
 *
 * 判定逻辑刻意抽成不依赖 Android 类型的纯函数 [classify]，方便 JVM 单元测试覆盖具体机型。
 */
object DeviceProfile {

    private const val PREF = "xiaohei_profile"
    private const val KEY_FORCE = "force"

    enum class Tier { STANDARD, CONSERVATIVE }

    /** 采集到的事实。全部是基础类型，[classify] 可在 JVM 单测里直接构造。 */
    data class Facts(
        val manufacturer: String = "",
        val model: String = "",
        val release: String = "",
        val sdk: Int = 0,
        val totalMemMb: Long = 0L,
        val memoryClassMb: Int = 0,
        val lowRam: Boolean = false,
        val hardwareVideoDecoders: Int = 0
    )

    /** 判定结果：档位 + 命中原因（写进日志与崩溃报告，方便别人家的电视回传） */
    data class Verdict(val tier: Tier, val reason: String)

    /**
     * 保守档判定。任一条件成立即保守——宁可少一路预播，也不要在老电视上闪退。
     * 预播只是「加分项」，少一路最多损失连续切第 3 个台时的秒切，用户可手动恢复标准档。
     */
    fun classify(f: Facts): Verdict {
        val reasons = ArrayList<String>(5)
        if (f.lowRam) reasons += "lowRam"
        // API 26/27（Android 8.0/8.1）厂商固件的 MediaCodec 异步队列实现最容易出现
        // CodecException / 回调线程崩溃；同步队列更慢一点但稳得多。
        if (f.sdk in 21..27) reasons += "API${f.sdk}(<=8.1)"
        if (f.totalMemMb in 1..2560) reasons += "RAM${f.totalMemMb}MB(<=2.5G)"
        if (f.memoryClassMb in 1..160) reasons += "memClass${f.memoryClassMb}(<=160)"
        if (f.hardwareVideoDecoders in 1..2) reasons += "硬解${f.hardwareVideoDecoders}路(<=2)"
        val knownTvRom = (f.manufacturer.contains("TCL", true) ||
            f.model.contains("75T6", true) ||
            f.release.contains("TV+OS", true)) && f.sdk in 21..27
        if (knownTvRom) reasons += "老电视固件"
        return if (reasons.isEmpty()) Verdict(Tier.STANDARD, "资源充足")
        else Verdict(Tier.CONSERVATIVE, reasons.joinToString("+"))
    }

    @Volatile
    private var cached: Pair<Facts, Verdict>? = null

    private fun factsOf(c: Context): Facts = cached?.first ?: read(c).also { f ->
        cached = f to classify(f)
    }

    fun facts(c: Context): Facts = factsOf(c)

    fun verdict(c: Context): Verdict {
        // 强制档位只用于诊断/验证：让新设备也能真实跑一遍老电视的代码路径。
        // 例如 `--es action profile --es force conservative`，重启 App 生效。
        return when (forced(c)) {
            0 -> Verdict(Tier.STANDARD, "手动强制标准")
            1 -> Verdict(Tier.CONSERVATIVE, "手动强制保守")
            else -> cached?.second ?: classify(factsOf(c))
        }
    }

    /** -1 跟随自动判定 / 0 强制标准 / 1 强制保守 */
    fun forced(c: Context): Int =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_FORCE, -1)

    fun setForced(c: Context, mode: Int) =
        // 诊断场景常常「发完广播立刻 force-stop」，apply() 异步写盘可能丢；
        // 用同步 commit 保证设置真正生效。
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FORCE, mode.coerceIn(-1, 1)).commit()

    fun tier(c: Context): Tier = verdict(c).tier

    fun isConservative(c: Context): Boolean = tier(c) == Tier.CONSERVATIVE

    private fun read(c: Context): Facts = runCatching {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        var totalMb = 0L
        var memClass = 0
        var lowRam = false
        if (am != null) {
            val mi = ActivityManager.MemoryInfo()
            runCatching { am.getMemoryInfo(mi); totalMb = mi.totalMem / (1024L * 1024L) }
            runCatching { memClass = am.memoryClass }
            runCatching { lowRam = am.isLowRamDevice }
        }
        Facts(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            release = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            totalMemMb = totalMb,
            memoryClassMb = memClass,
            lowRam = lowRam,
            hardwareVideoDecoders = hardwareVideoDecoderCount()
        )
    }.getOrElse {
        Facts(sdk = Build.VERSION.SDK_INT, release = Build.VERSION.RELEASE.orEmpty())
    }

    /** 只枚举不实例化，开销可忽略；软解（google/swiftshader）不算硬件解码器 */
    private fun hardwareVideoDecoderCount(): Int = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.count { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.startsWith("video/", ignoreCase = true) } &&
                !info.name.contains("google", true) &&
                !info.name.contains("swiftshader", true) &&
                !info.name.contains("software", true)
        }
    }.getOrDefault(0)

    /** 一行摘要：启动日志、关于页、崩溃报告、后台广播共用 */
    fun summary(c: Context): String {
        val f = factsOf(c)
        val v = verdict(c)
        return buildString {
            append(f.manufacturer).append(' ').append(f.model)
            append(" / Android ").append(f.release).append(" (API ").append(f.sdk).append(')')
            append(" / RAM ").append(f.totalMemMb).append("MB")
            append(" / memClass ").append(f.memoryClassMb)
            append(" / lowRam=").append(f.lowRam)
            append(" / 硬解 ").append(f.hardwareVideoDecoders).append(" 路")
            append(" / ").append(if (v.tier == Tier.CONSERVATIVE) "保守档" else "标准档")
            append(" (").append(v.reason).append(')')
        }
    }
}
