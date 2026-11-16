package com.xiaohei.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 播客封面守护。
 *
 * 播客原来整组没有图：`PodcastCatalog.asChannel` 里 logo 传的是空串，
 * 行首那个 44dp 的方槽一直是空的，14 档节目在列表里只有文字。
 *
 * 现在 14 张封面（取自各自的 RSS `itunes:image`，Anyway.FM 那张取自 Apple 的节目库）
 * 统一压成 200×200 打包进 assets/logos，和电视台标走同一套 map.txt 映射。
 *
 * 这条用例锁三件事：
 * 1. 14 档节目都能在 map.txt 里查到文件（改名忘了改映射会当场失败）；
 * 2. 文件真的存在；
 * 3. 封面必须是正方形——方槽是 44dp×44dp 的正方形，非正方形会被 fitCenter 缩出黑边。
 *
 * 名单来源：仓库里的 podcasts.json（远端目录）。远端以后加新档，这里的名单也要跟着加。
 */
class PodcastLogoTest {

    /** 与 podcasts.json 的 shows 一一对应，顺序也一致（对应 l100…l113） */
    private val shows = listOf(
        "故事FM", "忽左忽右", "声东击西", "大内密谈", "文化有限",
        "博物志", "梁永安的播客", "日谈物语", "东亚观察局", "面基",
        "日知录", "内核恐慌", "捕蛇者说", "Anyway.FM"
    )

    private fun assetDir(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets"))
            .firstOrNull { it.exists() }
            ?: error("找不到内置资源目录")

    private fun logoMap(): Map<String, String> {
        val mf = File(assetDir(), "logos/map.txt")
        assertTrue("找不到台标映射表", mf.exists())
        val map = mutableMapOf<String, String>()
        mf.readLines().forEach { ln ->
            val i = ln.indexOf('=')
            if (i > 0) map[ln.substring(0, i).trim()] = ln.substring(i + 1).trim()
        }
        return map
    }

    @Test
    fun `14 档播客都有打包好的封面`() {
        val map = logoMap()
        val files = File(assetDir(), "logos").list()?.toSet() ?: emptySet()

        val noMapping = shows.filter { map[it] == null }
        assertTrue("这些播客在 map.txt 里没有映射：$noMapping", noMapping.isEmpty())

        val noFile = shows.mapNotNull { n -> map[n]?.takeIf { it !in files }?.let { "$n -> $it" } }
        assertTrue("映射指向的封面文件不存在：$noFile", noFile.isEmpty())
    }

    /**
     * 直接读 PNG 头里的 IHDR（签名 8 字节 + 长度 4 + "IHDR" 4 + 宽 4 + 高 4）。
     * 不用 ImageIO：单元测试的 classpath 是 android.jar，里面没有 javax.imageio。
     */
    private fun pngSize(f: File): Pair<Int, Int>? {
        val head = ByteArray(24)
        f.inputStream().use { if (it.read(head) != 24) return null }
        val sig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        if (!head.copyOfRange(0, 8).contentEquals(sig)) return null
        val buf = ByteBuffer.wrap(head, 16, 8).order(ByteOrder.BIG_ENDIAN)
        return buf.int to buf.int
    }

    @Test
    fun `播客封面必须是正方形，否则方槽里会缩出黑边`() {
        val map = logoMap()
        val bad = mutableListOf<String>()
        for (n in shows) {
            val f = File(assetDir(), "logos/${map[n] ?: continue}")
            val (w, h) = pngSize(f) ?: run { bad.add("$n 读取失败"); continue }
            if (w != h) bad.add("$n ${w}x$h")
            if (w < 120) bad.add("$n 尺寸过小 ${w}px")
        }
        assertEquals("封面不是正方形或过小：$bad", 0, bad.size)
    }

    @Test
    fun `封面文件名必须是纯 ASCII`() {
        val map = logoMap()
        val bad = shows.mapNotNull { map[it] }.filter { fn -> fn.any { it.code > 127 } }
        assertTrue("封面文件名必须纯 ASCII：$bad", bad.isEmpty())
    }
}
