package com.xiaohei.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 目录文件是人手写的，容错点在解析器这层：多一个逗号、少一个引号，都得明确报错而不是猜。 */
class MiniJsonTest {

    @Test
    fun `基本类型`() {
        val o = MiniJson.parse("""{"a":"x","b":2,"c":true,"d":null,"e":[1,2]}""") as Map<*, *>
        assertEquals("x", o["a"])
        assertEquals(2.0, o["b"])
        assertEquals(true, o["c"])
        assertNull(o["d"])
        assertEquals(listOf(1.0, 2.0), o["e"])
    }

    @Test
    fun `转义与 uXXXX`() {
        val o = MiniJson.parse("""{"t":"a\"b\\c\nd\u4e2d"}""") as Map<*, *>
        assertEquals("a\"b\\c\nd中", o["t"])
    }

    @Test
    fun `中文与换行直接写在字符串里也没问题`() {
        val o = MiniJson.parse("{\"n\":\"故事FM\"}") as Map<*, *>
        assertEquals("故事FM", o["n"])
    }

    @Test
    fun `嵌套`() {
        val o = MiniJson.parse("""{"v":1,"shows":[{"id":"a"},{"id":"b"}]}""") as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val shows = o["shows"] as List<Map<*, *>>
        assertEquals(2, shows.size)
        assertEquals("b", shows[1]["id"])
    }

    @Test
    fun `语法错误抛异常`() {
        val bads = listOf(
            "",
            "{",
            """{"a":1,}""",
            """{"a" 1}""",
            """[1,]""",
            """{"a":"未闭合}""",
            """{"a":1} 尾巴""",
            """{"a":tru}"""
        )
        for (b in bads) {
            var threw = false
            try {
                MiniJson.parse(b)
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("应当报错：$b", threw)
        }
    }

    @Test
    fun `str 取不到就返回空串`() {
        val o = MiniJson.parse("""{"a":1}""") as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val m = o as Map<String, Any?>
        assertEquals("", MiniJson.str(m, "a"))
        assertEquals("", MiniJson.str(m, "missing"))
    }
}
