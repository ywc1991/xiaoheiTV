package com.xiaohei.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备画像的判定是纯逻辑，必须在 JVM 单测里钉死——尤其要防止
 * 「TCL 75T6 这种 2GB Android 8 电视被误判为资源充足」再次发生。
 */
class DeviceProfileTest {

    private fun facts(
        manufacturer: String = "Xiaomi",
        model: String = "MiTV",
        release: String = "10",
        sdk: Int = 29,
        totalMemMb: Long = 4096L,
        memoryClassMb: Int = 256,
        lowRam: Boolean = false,
        decoders: Int = 12
    ) = DeviceProfile.Facts(manufacturer, model, release, sdk, totalMemMb, memoryClassMb, lowRam, decoders)

    @Test
    fun `TCL 75T6 Android8 2GB 必须进保守档`() {
        val v = DeviceProfile.classify(
            facts(
                manufacturer = "TCL", model = "75T6", release = "8.0.0",
                sdk = 26, totalMemMb = 2048L, memoryClassMb = 128, decoders = 8
            )
        )
        assertEquals(DeviceProfile.Tier.CONSERVATIVE, v.tier)
        assertTrue("应命中 API26", v.reason.contains("API26"))
        assertTrue("应命中 2GB 内存", v.reason.contains("RAM2048"))
        assertTrue("应命中堆上限", v.reason.contains("memClass128"))
        assertTrue("应命中厂商固件", v.reason.contains("老电视固件"))
    }

    @Test
    fun `即使 isLowRamDevice 为 false 仍能识别 2GB 老电视`() {
        // v2.6.0 的漏洞：只查 isLowRamDevice 与 memoryClass<=64，这台两条都不命中
        val v = DeviceProfile.classify(
            facts(sdk = 26, totalMemMb = 2048L, memoryClassMb = 192, lowRam = false)
        )
        assertEquals(DeviceProfile.Tier.CONSERVATIVE, v.tier)
    }

    @Test
    fun `新电视 4GB Android10 保持标准档_切台速度不受影响`() {
        val v = DeviceProfile.classify(facts())
        assertEquals(DeviceProfile.Tier.STANDARD, v.tier)
        assertEquals("资源充足", v.reason)
    }

    @Test
    fun `低内存标志单独也进保守档`() {
        val v = DeviceProfile.classify(facts(sdk = 30, totalMemMb = 4096L, memoryClassMb = 256, lowRam = true))
        assertEquals(DeviceProfile.Tier.CONSERVATIVE, v.tier)
        assertTrue(v.reason.contains("lowRam"))
    }

    @Test
    fun `硬解码器只有两路时进保守档`() {
        val v = DeviceProfile.classify(facts(sdk = 30, decoders = 2))
        assertEquals(DeviceProfile.Tier.CONSERVATIVE, v.tier)
        assertTrue(v.reason.contains("硬解2路"))
    }

    @Test
    fun `堆上限偏低进保守档`() {
        val v = DeviceProfile.classify(facts(sdk = 30, memoryClassMb = 128))
        assertEquals(DeviceProfile.Tier.CONSERVATIVE, v.tier)
        assertTrue(v.reason.contains("memClass128"))
    }

    @Test
    fun `采集信息全缺失时不误判`() {
        val v = DeviceProfile.classify(
            DeviceProfile.Facts(sdk = 0, totalMemMb = 0L, memoryClassMb = 0, hardwareVideoDecoders = 0)
        )
        assertEquals(DeviceProfile.Tier.STANDARD, v.tier)
    }

    @Test
    fun `TCL 新固件 Android9 以上不因厂商名被误降档`() {
        val v = DeviceProfile.classify(
            facts(manufacturer = "TCL", model = "75T6", release = "9", sdk = 28, totalMemMb = 4096L)
        )
        assertEquals(DeviceProfile.Tier.STANDARD, v.tier)
    }
}
