package com.xiaohei.tv

import com.xiaohei.tv.player.NetProbe
import com.xiaohei.tv.player.NetTierPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网络档位判定 + 滞回逻辑的纯函数单测。
 * 参数与 NetProbe 常量一致：FAST ≥ 4Mbps，MED ≥ 1.5Mbps，其余 SLOW，无数据(-1) = FAST。
 */
class NetTierPolicyTest {

    private class FakeClock {
        var now = 0L
    }

    private fun policy(clock: FakeClock, holdMs: Long = NetProbe.TIER_HOLD_MS) =
        NetTierPolicy(now = { clock.now }, holdMs = holdMs)

    // ---- 原始档位边界 ----

    @Test
    fun `no data means fast`() {
        val p = policy(FakeClock())
        assertEquals(NetProbe.Tier.FAST, p.rawTier(-1L))
    }

    @Test
    fun `tier boundaries`() {
        val p = policy(FakeClock())
        assertEquals(NetProbe.Tier.FAST, p.rawTier(4_000_000L))
        assertEquals(NetProbe.Tier.MED, p.rawTier(3_999_999L))
        assertEquals(NetProbe.Tier.MED, p.rawTier(1_500_000L))
        assertEquals(NetProbe.Tier.SLOW, p.rawTier(1_499_999L))
        assertEquals(NetProbe.Tier.SLOW, p.rawTier(0L))
    }

    // ---- 滞回 ----

    @Test
    fun `slow tier needs to hold before switching`() {
        val clock = FakeClock()
        val p = policy(clock)
        // 快网起步（无数据）
        assertEquals(NetProbe.Tier.FAST, p.tier(-1L))
        // 带宽突然掉到弱网：第一采样仍是 FAST（候选刚登记）
        assertEquals(NetProbe.Tier.FAST, p.tier(1_000_000L))
        // 保持 4.9s：还没满 5s，仍是 FAST
        clock.now = 4_900L
        assertEquals(NetProbe.Tier.FAST, p.tier(1_000_000L))
        // 保持满 5s：切到 SLOW
        clock.now = 5_100L
        assertEquals(NetProbe.Tier.SLOW, p.tier(1_000_000L))
    }

    @Test
    fun `bandwidth jitter does not switch tier`() {
        val clock = FakeClock()
        val p = policy(clock)
        assertEquals(NetProbe.Tier.FAST, p.tier(-1L))
        // 慢-快-慢-快来回抖，每次都不到 holdMs，永远不切档
        assertEquals(NetProbe.Tier.FAST, p.tier(1_000_000L))
        clock.now = 3_000L
        assertEquals(NetProbe.Tier.FAST, p.tier(8_000_000L))
        clock.now = 4_000L
        assertEquals(NetProbe.Tier.FAST, p.tier(1_000_000L))
        clock.now = 6_000L
        assertEquals(NetProbe.Tier.FAST, p.tier(8_000_000L))
        clock.now = 8_000L
        assertEquals(NetProbe.Tier.FAST, p.tier(1_000_000L))
        assertEquals(NetProbe.Tier.FAST, p.tier(1_000_000L))
    }

    @Test
    fun `switching back to fast is immediate once sustained`() {
        val clock = FakeClock()
        val p = policy(clock)
        assertEquals(NetProbe.Tier.FAST, p.tier(-1L))
        assertEquals(NetProbe.Tier.FAST, p.tier(1_000_000L))
        clock.now = 6_000L
        assertEquals(NetProbe.Tier.SLOW, p.tier(1_000_000L))
        // 恢复快网：同样要走滞回，候选持续满 5s 才切回
        clock.now = 7_000L
        assertEquals(NetProbe.Tier.SLOW, p.tier(8_000_000L))
        clock.now = 12_100L
        assertEquals(NetProbe.Tier.FAST, p.tier(8_000_000L))
    }

    @Test
    fun `candidate resets when raw tier jumps between med and slow`() {
        val clock = FakeClock()
        val p = policy(clock)
        assertEquals(NetProbe.Tier.FAST, p.tier(-1L))
        // 先候选 SLOW 4.9s
        assertEquals(NetProbe.Tier.FAST, p.tier(1_000_000L))
        clock.now = 4_900L
        assertEquals(NetProbe.Tier.FAST, p.tier(1_000_000L))
        // 跳到 MED：候选作废重新计时
        clock.now = 5_000L
        assertEquals(NetProbe.Tier.FAST, p.tier(2_000_000L))
        clock.now = 9_500L
        assertEquals(NetProbe.Tier.FAST, p.tier(2_000_000L))
        clock.now = 10_100L
        assertEquals(NetProbe.Tier.MED, p.tier(2_000_000L))
    }
}
