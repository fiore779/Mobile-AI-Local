package com.mobileailocal

import com.mobileailocal.domain.JsValueFormatter
import org.junit.Assert.*
import org.junit.Test
import java.util.LinkedHashMap

@OptIn(ExperimentalUnsignedTypes::class)
class JsValueFormatterTest {
    private fun f(v: Any?) = JsValueFormatter.formatReturn(v)

    @Test fun topLevelNull() {
        assertNull(f(null))
    }

    @Test fun topLevelStringIsRaw() {
        assertEquals("hello", f("hello"))
        assertEquals("a\nb", f("a\nb")) // トップレベルはエスケープしない
    }

    @Test fun integers() {
        assertEquals("42", f(42L))
        assertEquals("42", f(42))
        assertEquals("42", f(42.toShort()))
        assertEquals("42", f(42.toByte()))
    }

    @Test fun doubles() {
        assertEquals("1.5", f(1.5))
        assertEquals("1099511627776", f(1.099511627776E12))
        assertEquals("3", f(3.0))
        assertEquals("NaN", f(Double.NaN))
        assertEquals("Infinity", f(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", f(Double.NEGATIVE_INFINITY))
        assertEquals("0", f(-0.0))
        assertEquals("0", f(0.0))
    }

    @Test fun boolean() {
        assertEquals("true", f(true))
        assertEquals("false", f(false))
    }

    @Test fun lists() {
        assertEquals("[1,2,3]", f(listOf(1, 2, 3)))
        assertEquals("[1,2]", f(intArrayOf(1, 2)))
        assertEquals("[1,2]", f(byteArrayOf(1, 2)))
        assertEquals("[1,2]", f(arrayOf(1, 2)))
        assertEquals("[null]", f(listOf(null)))
        assertEquals("[]", f(emptyList<Any?>()))
    }

    @Test fun ubyteArray() {
        assertEquals("[1,2]", f(ubyteArrayOf(1u, 2u)))
    }

    @Test fun maps() {
        val m = LinkedHashMap<String, Any?>()
        m["a"] = 1L
        m["b"] = "x"
        assertEquals("{\"a\":1,\"b\":\"x\"}", f(m))
        assertEquals("{}", f(LinkedHashMap<String, Any?>()))
        val num = LinkedHashMap<Any?, Any?>()
        num[7] = "v"
        assertEquals("{\"7\":\"v\"}", f(num))
    }

    @Test fun nested() {
        val inner = LinkedHashMap<String, Any?>()
        inner["k"] = listOf("s", 2L, null)
        assertEquals("{\"k\":[\"s\",2,null]}", f(inner))
    }

    @Test fun nestedStringIsJsonQuoted() {
        val m = LinkedHashMap<String, Any?>()
        m["k"] = "a\nb\"c\\d"
        assertEquals("{\"k\":\"a\\nb\\\"c\\\\d\"}", f(m))
        m.clear()
        m["k"] = "a\tb\u0001c"
        assertEquals("{\"k\":\"a\\tb\\u0001c\"}", f(m))
    }

    @Test fun circularReference() {
        val m = LinkedHashMap<String, Any?>()
        m["self"] = m
        m["list"] = listOf(m)
        assertEquals("{\"self\":[Circular],\"list\":[[Circular]]}", f(m))
    }

    @Test fun depthLimit() {
        var v: Any? = "leaf"
        repeat(8) { v = listOf(v) } // 一番外側が深さ 1 なので、これは深さ 8 まで
        assertEquals("[[[[[[[[\"leaf\"]]]]]]]]", f(v))
        var deep: Any? = "leaf"
        repeat(9) { deep = listOf(deep) } // 深さ 9 のコンテナは […]
        assertEquals("[[[[[[[[" + "[…]" + "]]]]]]]]", f(deep))
    }

    private class Opaque {
        override fun toString(): String = "opaque!"
    }

    @Test fun unknownTypeUsesToString() {
        assertEquals("opaque!", f(Opaque()))
        assertEquals(java.util.Date(0L).toString(), f(java.util.Date(0L)))
    }

    @Test fun hugeListIsCutAtLimit() {
        val big = List(2_000_000) { 1234567 }
        val s = f(big)!!
        assertTrue(s.length <= JsValueFormatter.MAX_CHARS)
        assertTrue(s.startsWith("[1234567,1234567"))
    }

    @Test fun hugeStringIsCutAtLimit() {
        assertEquals(JsValueFormatter.MAX_CHARS, f("x".repeat(JsValueFormatter.MAX_CHARS * 3))!!.length)
        assertTrue(f(listOf("y".repeat(JsValueFormatter.MAX_CHARS * 3)))!!.length <= JsValueFormatter.MAX_CHARS)
    }

    @Test fun hugeUByteArrayIsCutAtLimit() {
        val s = f(UByteArray(5_000_000) { 200u })!!
        assertTrue(s.length <= JsValueFormatter.MAX_CHARS)
        assertTrue(s.startsWith("[200,200"))
    }

    @Test fun hugeMapIsCutAtLimitAndIsIteratedLazily() {
        var visited = 0
        val map = object : AbstractMap<String, Int>() {
            override val entries: Set<Map.Entry<String, Int>> = object : AbstractSet<Map.Entry<String, Int>>() {
                override val size = 3_000_000
                override fun iterator() = object : Iterator<Map.Entry<String, Int>> {
                    var i = 0
                    override fun hasNext() = i < size
                    override fun next(): Map.Entry<String, Int> { visited++; return java.util.AbstractMap.SimpleEntry("k${i++}", 1) }
                }
            }
        }
        val s = f(map)!!
        assertTrue(s.length <= JsValueFormatter.MAX_CHARS)
        assertTrue(s.startsWith("{\"k0\":1,\"k1\":1"))
        assertTrue("visited=$visited", visited < 500_000)
    }
}
