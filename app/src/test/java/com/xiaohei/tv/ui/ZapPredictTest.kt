package com.xiaohei.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预播预测的纯 JVM 单测。
 *
 * 这部分逻辑没有 UI，错了不会崩、只会「悄悄变慢」，正是最容易漏测的地方：
 * 一个下标算错，槽里装的就是隔壁台，用户每切一次都白等 4 秒。
 */
class ZapPredictTest {

    @Test
    fun `两个槽时优先同方向下一台，其次来时的台`() {
        // 5 个台，当前在下标 2，往后切过：优先 3，其次 1（刚离开的台）
        assertEquals(listOf(3, 1), ZapPredict.targets(2, 5, 1, 2))
        // 往前切过：优先 1，其次 3
        assertEquals(listOf(1, 3), ZapPredict.targets(2, 5, -1, 2))
    }

    @Test
    fun `只有一个槽时只猜同方向下一台`() {
        assertEquals(listOf(3), ZapPredict.targets(2, 5, 1, 1))
        assertEquals(listOf(1), ZapPredict.targets(2, 5, -1, 1))
    }

    @Test
    fun `下标绕环不能出现负数`() {
        // 在 0 号台往前切，上一台应该是末尾
        assertEquals(listOf(4, 1), ZapPredict.targets(0, 5, -1, 2))
        // 在末尾往后切，下一台应该是 0
        assertEquals(listOf(0, 3), ZapPredict.targets(4, 5, 1, 2))
    }

    @Test
    fun `只有两个台时来回切只返回另一个`() {
        assertEquals(listOf(1), ZapPredict.targets(0, 2, 1, 2))
        assertEquals(listOf(0), ZapPredict.targets(1, 2, -1, 2))
    }

    @Test
    fun `结果里绝不包含当前台`() {
        for (n in 2..8) {
            for (i in 0 until n) {
                for (dir in intArrayOf(1, -1)) {
                    val t = ZapPredict.targets(i, n, dir, 3)
                    assertTrue("i=$i n=$n dir=$dir 返回了当前台", t.none { it == i })
                    assertEquals("i=$i n=$n dir=$dir 有重复", t.size, t.toSet().size)
                    assertTrue("i=$i n=$n dir=$dir 结果为空", t.isNotEmpty())
                    t.forEach { assertTrue("i=$i n=$n 下标越界 $it", it in 0 until n) }
                }
            }
        }
    }

    @Test
    fun `槽位为 0 或序列过短时不做预测`() {
        assertTrue(ZapPredict.targets(0, 5, 1, 0).isEmpty())
        assertTrue(ZapPredict.targets(0, 1, 1, 2).isEmpty())
        assertTrue(ZapPredict.targets(0, 0, 1, 2).isEmpty())
    }

    @Test
    fun `下标越界时返回空而不是崩`() {
        assertTrue(ZapPredict.targets(-1, 5, 1, 2).isEmpty())
        assertTrue(ZapPredict.targets(5, 5, 1, 2).isEmpty())
    }
}
