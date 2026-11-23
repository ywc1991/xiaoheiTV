package com.xiaohei.tv

import android.os.Build

/**
 * 鸿蒙（HarmonyOS）识别与兼容开关。
 *
 * **设计原则：只做加法，不做替换。**
 *  - 识别不出来 → 所有开关保持 false，走的还是原来那条代码路径，
 *    任何安卓设备（包括华为 EMUI 安卓机、小米/创维/TCL 电视）行为零变化；
 *  - 只有确认跑在鸿蒙上，才额外打开对应的兼容项。
 *
 * 为什么单独一个类、而不并进 [DeviceProfile]：
 * DeviceProfile 判的是「这台机器能不能多开解码」，一改档位就会波及所有设备；
 * 鸿蒙要处理的是另一类差异（包可见性、自研 MediaCodec 的异步队列实现），
 * 混在一起会让老设备的既有行为变得不可预测。
 *
 * 鸿蒙各版本的 AOSP 基线：2.x≈Android 10、3.x≈11、4.x≈12。
 * 也就是说鸿蒙会命中 Android 11+ 的包可见性过滤、Android 12 的组件导出校验等新规则。
 *
 * 识别顺序按「成本从低到高」排，非华为设备第 1 步就结束，不做任何多余反射。
 */
object HarmonyCompat {

    /** 鸿蒙版本号可能落在这几个系统属性里，按可信度排序 */
    private val VERSION_KEYS = listOf(
        "ro.build.version.harmony",
        "hw_sc.build.platform.version",
        "ro.build.harmony.version",
        "persist.sys.harmony.version"
    )

    /** 识别结果。version 为空表示「确认是鸿蒙但没读到版本号」。 */
    data class Detect(val harmony: Boolean, val version: String)

    /**
     * 纯函数：给一组系统属性 + 构建描述字符串 + 厂商品牌标识，判断是不是鸿蒙。
     * 抽出来是为了能在 JVM 单测里钉死——真机上读属性的反射在 CI 里跑不起来。
     *
     * @param osBrand 华为 BuildEx.getOsBrand() 的返回值（"harmony" / "Android"），拿不到传空串
     */
    fun detect(props: Map<String, String>, buildMarks: String, osBrand: String = ""): Detect {
        // 最可靠的一路：华为在鸿蒙 2+ 提供了 com.huawei.system.BuildEx#getOsBrand，
        // 真鸿蒙返回 "harmony"，EMUI 安卓机返回 "Android"。
        if (osBrand.contains("harmony", true)) return Detect(true, propsVersion(props))
        for (k in VERSION_KEYS) {
            val v = props[k]?.trim().orEmpty()
            // 少数 ROM 会把属性填成 "unknown"/"0"，当没读到处理
            if (v.isNotEmpty() && v != "unknown" && v != "0") return Detect(true, v)
        }
        // 有的机型不写 harmony 属性，只在 Build.DISPLAY / RELEASE 里带 "HarmonyOS"
        val marks = buildMarks.lowercase()
        if (marks.contains("harmony") || marks.contains("hmos")) return Detect(true, "")
        return Detect(false, "")
    }

    /** 只取版本号，不重复判定 */
    private fun propsVersion(props: Map<String, String>): String {
        for (k in VERSION_KEYS) {
            val v = props[k]?.trim().orEmpty()
            if (v.isNotEmpty() && v != "unknown" && v != "0") return v
        }
        return ""
    }

    /**
     * 华为官方的鸿蒙品牌位：com.huawei.system.BuildEx#getOsBrand()。
     * 非公开 SDK，但鸿蒙各版本都带着；非华为设备上这里抛 ClassNotFound 直接返回空串。
     */
    private fun readOsBrand(): String = runCatching {
        val clz = Class.forName("com.huawei.system.BuildEx")
        val m = clz.getMethod("getOsBrand")
        val v = runCatching { m.invoke(null) }.getOrNull()
            ?: runCatching { m.invoke(clz.getDeclaredConstructor().newInstance()) }.getOrNull()
        (v as? String).orEmpty()
    }.getOrDefault("")

    /** 读系统属性的反射句柄；读不到就是 null（Android 9+ 有隐藏 API 限制） */
    private val systemProperties: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        }.getOrNull()
    }

    private fun readProps(): Map<String, String> {
        val m = systemProperties ?: return emptyMap()
        val out = HashMap<String, String>(VERSION_KEYS.size)
        for (k in VERSION_KEYS) {
            runCatching { m.invoke(null, k) as? String }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { out[k] = it }
        }
        return out
    }

    private val buildMarksText: String by lazy {
        listOf(Build.DISPLAY, Build.VERSION.RELEASE, Build.VERSION.INCREMENTAL, Build.ID, Build.HOST)
            .joinToString(" ")
    }

    /** 鸿蒙只跑在华为 / 荣耀硬件上，别的品牌没必要再往下查属性 */
    private val isHuaweiFamily: Boolean by lazy {
        val s = (Build.MANUFACTURER.orEmpty() + " " + Build.BRAND.orEmpty()).lowercase()
        s.contains("huawei") || s.contains("honor") || s.contains("华为") || s.contains("荣耀")
    }

    @Volatile
    private var cached: Detect? = null

    private fun compute(): Detect {
        val brand = readOsBrand()
        if (brand.contains("harmony", true)) return Detect(true, propsVersion(readProps()))
        val marks = buildMarksText.lowercase()
        if (marks.contains("harmony") || marks.contains("hmos")) return Detect(true, propsVersion(readProps()))
        if (!isHuaweiFamily) return Detect(false, "")
        return detect(readProps(), marks, brand)
    }

    val info: Detect
        get() = cached ?: compute().also { cached = it }

    val isHarmony: Boolean get() = info.harmony

    /** 鸿蒙版本号（如 "4.0.0"），非鸿蒙或未读到则为空串 */
    val version: String get() = info.version

    /**
     * 是否对本机强制「同步解码队列」。
     *
     * 只在鸿蒙上为 true：华为自研的 MediaCodec 在异步队列模式下，切台时
     * 两个解码器交接 Surface 偶发 CodecException / 回调线程异常；同步队列慢一点但稳得多。
     * 安卓设备（含 EMUI）恒为 false —— 它们仍由 DeviceProfile 的保守档决定，
     * 既有行为一个字节都不变。
     */
    val needsSyncCodec: Boolean get() = isHarmony

    /** 写日志用的一行摘要 */
    fun summary(): String = if (isHarmony) {
        "harmony=${version.ifEmpty { "?" }} syncCodec=$needsSyncCodec"
    } else {
        "harmony=no"
    }
}
