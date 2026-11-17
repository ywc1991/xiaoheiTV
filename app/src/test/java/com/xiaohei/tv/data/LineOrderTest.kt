package com.xiaohei.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LineOrderTest {
    private val urls = listOf(
        "http://a/one.m3u8",
        "http://b/two.m3u8",
        "http://c/three.m3u8",
        "http://[::1]/v6.m3u8",
        "http://d/four.m3u8"
    )

    @Test
    fun `good line goes first and every line appears exactly once`() {
        val order = LineOrder.order(urls, goodUrl = urls[2], slowUrl = urls[1], isDead = { false })
        assertEquals(listOf(2, 0, 4, 1, 3), order)
        assertEquals(urls.indices.toSet(), order.toSet())
        assertEquals(order.size, order.distinct().size)
    }

    @Test
    fun `dead and ipv6 lines sink to the tail without duplicates`() {
        val order = LineOrder.order(
            urls,
            goodUrl = urls[0],
            slowUrl = urls[3], // 慢线同时又是 IPv6：只能出现一次
            isDead = { it == urls[1] }
        )
        assertEquals(listOf(0, 2, 4, 3, 1), order)
        assertEquals(order.size, order.distinct().size)
    }

    @Test
    fun `dead good line is not allowed to stay at the head`() {
        val order = LineOrder.order(
            urls,
            goodUrl = urls[1],
            slowUrl = null,
            isDead = { it == urls[1] }
        )
        assertEquals(listOf(0, 2, 4, 1, 3), order)
        assertEquals(0, order.first())
    }

    @Test
    fun `empty and single line lists stay valid`() {
        assertEquals(emptyList<Int>(), LineOrder.order(emptyList(), null, null, { false }))
        assertEquals(listOf(0), LineOrder.order(listOf(urls[0]), urls[0], urls[0], { true }))
    }
}
