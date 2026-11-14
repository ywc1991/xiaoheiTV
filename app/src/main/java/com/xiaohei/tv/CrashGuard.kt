package com.xiaohei.tv

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 稳定性守护：给「别人家的电视」用的兜底。
 *
 * 别厂盒子/老电视的解码器实例数、内存、MediaCodec 实现差异很大，
 * 同一份代码在小米电视上稳如泰山，在廉价盒子上换台就可能直接崩。
 * 现场日志又拿不到，所以做三件事：
 *
 *  1. 全局未捕获异常落盘：机型、系统、版本、最近频道、完整堆栈写到应用私有目录，
 *     下次可用后台广播导出（BenchReceiver action=crash），不上传任何数据。
 *  2. 反复崩溃自动降级：10 分钟内崩 2 次就把「双槽预播」降成单槽，再崩就干脆关掉预播，
 *     回到一台一个播放器的最稳路径。正常设备完全不受影响，切台速度也不变。
 *  3. 按设备画像（Android 版本 / 物理内存 / 堆上限 / 硬解码器数）启动即收敛预播槽数量，
 *     别等它崩了才降级；TCL 75T6 这类 Android 8 + 2GB 老电视默认只开 1 槽。
 *
 * 这是兜底而不是修复：真正的原因仍要靠导出的堆栈定位。
 */
object CrashGuard {

    private const val TAG = "XiaoHeiTV"
    private const val PREF = "xiaohei_stability"
    private const val KEY_LEVEL = "level"
    private const val KEY_COUNT = "count"
    private const val KEY_LAST = "lastAt"
    private const val KEY_MANUAL = "manual"
    private const val KEY_DECODER_FAIL = "decoderFail"
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val CRASHES_PER_STEP = 2
    private const val MAX_LEVEL = 2
    private const val MAX_FILES = 3

    /** 崩溃时写进报告：知道是在切哪个台、走到哪一步挂的 */
    @Volatile var lastChannel: String = "-"
    @Volatile var lastPhase: String = "-"

    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var installed = false
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun install(app: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previousHandler = previous
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 写盘失败也不能掩盖原始崩溃；最后一定交回系统默认处理（保持正常闪退行为）
            runCatching { record(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
        val c = app.applicationContext
        Log.i(TAG, "stability level=${level(c)} slots=${slotCount(c)} ${deviceInfo(c)}")
    }

    private fun record(app: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val stack = sw.toString()

        val p = prefs(app)
        val now = System.currentTimeMillis()
        val withinWindow = now - p.getLong(KEY_LAST, 0L) <= WINDOW_MS
        var count = (if (withinWindow) p.getInt(KEY_COUNT, 0) else 0) + 1
        var lv = p.getInt(KEY_LEVEL, 0).coerceIn(0, MAX_LEVEL)
        if (count >= CRASHES_PER_STEP && lv < MAX_LEVEL) {
            lv++
            count = 0
            Log.e(TAG, "stability 连续崩溃，自动降级到 level=$lv（预播槽 ${if (lv >= 2) 0 else 1}）")
        }
        // 崩溃路径必须同步落盘，apply() 是异步的，进程马上就没了
        p.edit()
            .putInt(KEY_COUNT, count)
            .putLong(KEY_LAST, now)
            .putInt(KEY_LEVEL, lv)
            .commit()

        val text = buildString {
            append("time=").append(fmt.format(Date(now))).append('\n')
            append("device=").append(DeviceProfile.summary(app)).append('\n')
            append("slots=").append(slotCount(app)).append('\n')
            append("thread=").append(thread.name).append('\n')
            append("channel=").append(lastChannel).append('\n')
            append("phase=").append(lastPhase).append('\n')
            append("level=").append(lv).append(" crashInWindow=").append(count).append('\n')
            append('\n').append(stack.take(60_000))
        }

        runCatching {
            val dir = File(app.filesDir, "crashes")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "crash-${now}.txt").writeText(text)
            val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            if (files.size > MAX_FILES) {
                files.take(files.size - MAX_FILES).forEach { it.delete() }
            }
        }
        Log.e(TAG, "CRASH ${error.javaClass.name}: ${error.message}\n${stack.lineSequence().take(8).joinToString("\n")}")
    }

    /** 自动降级等级：0 正常 / 1 单槽 / 2 无预播 */
    fun level(c: Context): Int = prefs(c).getInt(KEY_LEVEL, 0).coerceIn(0, MAX_LEVEL)

    /** 手动指定等级，-1 表示跟随自动值（用于「关于」里恢复正常模式） */
    fun manual(c: Context): Int = prefs(c).getInt(KEY_MANUAL, -1)

    fun setManual(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_MANUAL, value).apply()

    fun reset(c: Context) =
        prefs(c).edit()
            .putInt(KEY_LEVEL, 0)
            .putInt(KEY_COUNT, 0)
            .putInt(KEY_DECODER_FAIL, 0)
            .putInt(KEY_MANUAL, -1)
            .apply()

    fun crashCount(c: Context): Int = prefs(c).getInt(KEY_COUNT, 0)

    /**
     * 本次启动要创建几个预播槽。
     *
     * 手动 level=0 是「强制标准」，忽略设备画像；其余情况按档位收敛：
     * 保守档（Android 8.1 及以下 / ≤2.5GB / 硬解 ≤2 路，例如 TCL 75T6）最多 1 槽，
     * 这样同时最多 2 路解码，不再出现 3 个解码器抢实例的换台闪退。
     */
    fun slotCount(c: Context): Int {
        val m = manual(c)
        val lv = if (m >= 0) m.coerceIn(0, MAX_LEVEL) else level(c)
        val base = when {
            lv >= 2 -> 0
            lv >= 1 -> 1
            else -> 2
        }
        if (base == 0) return 0
        if (m == 0) return 2 // 用户在「关于」里主动恢复标准模式
        return if (DeviceProfile.isConservative(c)) minOf(base, 1) else base
    }

    /**
     * 解码器运行时失败（不是线路问题）是「这台机器撑不住」的强信号。
     * 累计 2 次就自动降一级，不等进程真的崩掉。
     */
    fun noteDecoderFailure(c: Context) {
        val p = prefs(c)
        val n = p.getInt(KEY_DECODER_FAIL, 0) + 1
        var lv = p.getInt(KEY_LEVEL, 0).coerceIn(0, MAX_LEVEL)
        var next = n
        if (n >= 2 && lv < MAX_LEVEL) {
            lv = if (lv >= 1) MAX_LEVEL else 1
            next = 0
            Log.w(TAG, "解码器连续失败，自动降级到 level=$lv（预播槽 ${if (lv >= 2) 0 else 1}）")
        }
        p.edit().putInt(KEY_DECODER_FAIL, next).putInt(KEY_LEVEL, lv).commit()
    }

    fun deviceInfo(c: Context): String = DeviceProfile.summary(c)

    /** 最近一次崩溃的摘要，给「关于」页显示 */
    fun recentSummary(c: Context): String {
        val f = newest(c) ?: return ""
        val head = runCatching { f.readText().lineSequence().take(8).toList() }.getOrDefault(emptyList())
        val time = head.firstOrNull { it.startsWith("time=") }?.substringAfter('=') ?: f.name
        val err = head.firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?.trim()?.take(90) ?: "未知异常"
        val ch = head.firstOrNull { it.startsWith("channel=") }?.substringAfter('=') ?: "-"
        return "$time · $err · 频道 $ch"
    }

    /** 最近一次崩溃的完整文本，给后台广播导出 */
    fun dump(c: Context): String {
        val f = newest(c) ?: return "暂无崩溃记录"
        return runCatching { f.readText() }.getOrDefault("崩溃记录读取失败")
    }

    private fun newest(c: Context): File? = runCatching {
        File(c.filesDir, "crashes").listFiles()?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    fun noteChannel(name: String) {
        lastChannel = name
    }

    fun notePhase(phase: String) {
        lastPhase = phase
    }
}
