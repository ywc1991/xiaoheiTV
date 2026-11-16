package com.xiaohei.tv.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 台标覆盖守护。
 *
 * 港澳台的台标原来全靠清单里的 `tvg-logo` 网络地址，而那个 CDN（live.fanmingming.com）
 * 已经整体失效，于是整组 21 个台在列表里全是空位；广播则从来没有配过台标。
 * 现在两个分组都改为打包进 APK 的内置台标（assets/logos + map.txt 映射）。
 *
 * 这条用例锁死两件事：
 * 1. 这两个分组的每个频道都能在 map.txt 里查到文件；
 * 2. 查到的文件真的存在于 assets/logos 下。
 *
 * 只要有人改了频道名却忘了改映射（或反之），构建就会失败——这比等用户反馈「台标又白了」便宜得多。
 */
class LogoCoverageTest {

    private fun assetDir(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets"))
            .firstOrNull { it.exists() }
            ?: error("找不到内置资源目录")

    private fun parse(): Pair<List<Pair<String, String>>, Map<String, String>> {
        val f = File(assetDir(), "channels_main.m3u")
        assertTrue("找不到内置频道清单", f.exists())
        val rows = mutableListOf<Pair<String, String>>()
        var group = ""
        var name = ""
        for (line in f.readLines()) {
            val s = line.trim()
            when {
                s.startsWith("#EXTINF") -> {
                    val m = Regex("""group-title="([^"]*)"""").find(s)
                    group = m?.groupValues?.get(1) ?: ""
                    name = s.substringAfterLast(',', "").trim()
                }
                s.isNotEmpty() && !s.startsWith("#") -> rows.add(group to name)
            }
        }
        val map = mutableMapOf<String, String>()
        val mf = File(assetDir(), "logos/map.txt")
        assertTrue("找不到台标映射表", mf.exists())
        mf.readLines().forEach { ln ->
            val i = ln.indexOf('=')
            if (i > 0) map[ln.substring(0, i).trim()] = ln.substring(i + 1).trim()
        }
        return rows to map
    }

    @Test
    fun `港澳台与广播的每个频道都有内置台标文件`() {
        val (rows, map) = parse()
        val logoDir = File(assetDir(), "logos")
        val files = logoDir.list()?.toSet() ?: emptySet()

        val targets = rows.filter { it.first == "港澳台" || it.first == "广播" }
        assertTrue("清单里没有港澳台/广播频道，测试前提不成立", targets.isNotEmpty())

        val noMapping = mutableListOf<String>()
        val noFile = mutableListOf<String>()
        for ((g, n) in targets) {
            val file = map[n]
            when {
                file == null -> noMapping.add("$g/$n")
                file !in files -> noFile.add("$g/$n -> $file")
            }
        }
        assertTrue("这些频道在 map.txt 里没有映射：$noMapping", noMapping.isEmpty())
        assertTrue("映射指向的台标文件不存在：$noFile", noFile.isEmpty())
    }

    @Test
    fun `台标文件名必须是纯 ASCII`() {
        val files = File(assetDir(), "logos").list()?.filter { it.endsWith(".png") } ?: emptyList()
        assertTrue("台标目录为空", files.isNotEmpty())
        // 部分老电视的 APK 解析器遇到非 ASCII 条目名会报「解析软件包时出现问题」
        val bad = files.filter { f -> f.any { it.code > 127 } }
        assertTrue("台标文件名必须纯 ASCII，违规：$bad", bad.isEmpty())
    }
}
