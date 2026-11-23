package com.xiaohei.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 鸿蒙识别必须满足两个方向：
 *  1) 真鸿蒙能认出来（否则兼容开关永远不生效，等于没做）；
 *  2) 华为 EMUI 安卓机 / 普通安卓机不能被误认（否则等于偷偷改了它们的解码路径）。
 */
class HarmonyCompatTest {

    @Test
    fun `ro_build_version_harmony 能识别并带出版本号`() {
        val d = HarmonyCompat.detect(
            mapOf("ro.build.version.harmony" to "4.0.0"),
            "MRA-AL00-user 12 HUAWEIMRA-AL00 4.0.0.112 release-keys"
        )
        assertTrue(d.harmony)
        assertEquals("4.0.0", d.version)
    }

    @Test
    fun `智慧屏常用 hw_sc_build_platform_version 也能识别`() {
        val d = HarmonyCompat.detect(
            mapOf("hw_sc.build.platform.version" to "4.2.0"),
            ""
        )
        assertTrue(d.harmony)
        assertEquals("4.2.0", d.version)
    }

    @Test
    fun `只在 Build 字符串里带 HarmonyOS 时也认`() {
        val d = HarmonyCompat.detect(emptyMap(), "HarmonyOS 3.0.0 / HUAWEI P50")
        assertTrue(d.harmony)
        // 没读到属性，版本号允许为空，但绝不能因此判定为非鸿蒙
        assertEquals("", d.version)
    }

    @Test
    fun `属性写成 unknown 或 0 时当作没读到`() {
        val d = HarmonyCompat.detect(
            mapOf("ro.build.version.harmony" to "unknown", "hw_sc.build.platform.version" to "0"),
            "Xiaomi 2211133C-user 13 TKQ1 release-keys"
        )
        assertFalse(d.harmony)
    }

    @Test
    fun `华为 EMUI 安卓机不能被误判为鸿蒙`() {
        // EMUI 10（Android 10）既没有 harmony 属性，Build 里也只有 EMUI 字样
        val d = HarmonyCompat.detect(
            mapOf("ro.build.version.emui" to "EmotionUI_10.0.0"),
            "ELS-AN00-user 10 HUAWEIELS-AN00 10.1.0.132 release-keys"
        )
        assertFalse("EMUI 安卓机必须走原来的安卓路径", d.harmony)
    }

    @Test
    fun `华为 BuildEx 报 harmony 时即使读不到属性也能认出`() {
        // 鸿蒙 4 上 SystemProperties 反射可能被隐藏 API 限制拦掉，只能靠 BuildEx
        val d = HarmonyCompat.detect(emptyMap(), "MRA-AL00-user 12 release-keys", "harmony")
        assertTrue(d.harmony)
    }

    @Test
    fun `华为 BuildEx 报 Android 时判定为否`() {
        val d = HarmonyCompat.detect(emptyMap(), "ELS-AN00-user 10 release-keys", "Android")
        assertFalse("EMUI 必须继续走安卓路径", d.harmony)
    }

    @Test
    fun `普通安卓设备判定为否`() {
        val d = HarmonyCompat.detect(
            emptyMap(),
            "MiTV-user 9 PI 4567 release-keys"
        )
        assertFalse(d.harmony)
    }
}
