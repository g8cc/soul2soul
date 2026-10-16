package com.soul2soul.app.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairQrTest {

    @Test
    fun `build 标准载荷`() {
        assertEquals("soul2soul://pair?code=123456", PairQr.build("123456"))
    }

    @Test
    fun `build 拒绝非 6 位数字`() {
        assertNull(PairQr.build("12345"))
        assertNull(PairQr.build("1234567"))
        assertNull(PairQr.build("abcdef"))
        assertNull(PairQr.build(""))
        assertNull(PairQr.build(" 12345 6"))
    }

    @Test
    fun `build 容忍首尾空格`() {
        assertEquals("soul2soul://pair?code=000001", PairQr.build(" 000001 "))
    }

    @Test
    fun `parse 识别标准载荷`() {
        assertEquals("123456", PairQr.parse("soul2soul://pair?code=123456"))
    }

    @Test
    fun `parse 容忍大小写与空格`() {
        assertEquals("123456", PairQr.parse("  SOUL2SOUL://PAIR?CODE=123456 "))
    }

    @Test
    fun `parse 兼容裸 6 位码`() {
        assertEquals("823901", PairQr.parse("823901"))
    }

    @Test
    fun `parse 多参数时取 code`() {
        assertEquals("123456", PairQr.parse("soul2soul://pair?a=1&code=123456&b=2"))
    }

    @Test
    fun `parse 拒绝陌生载荷`() {
        assertNull(PairQr.parse("https://example.com/pair?code=123456"))
        assertNull(PairQr.parse("soul2soul://other?code=123456"))
        assertNull(PairQr.parse("soul2soul://pair?token=123456"))
        assertNull(PairQr.parse("soul2soul://pair?code=notacode"))
        assertNull(PairQr.parse("soul2soul://pair?code=12345"))
        assertNull(PairQr.parse(""))
        assertNull(PairQr.parse(null))
        assertNull(PairQr.parse("12345"))
    }
}
