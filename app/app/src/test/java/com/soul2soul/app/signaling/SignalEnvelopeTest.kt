package com.soul2soul.app.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** hello/消息包络构造回归：未配对时 token 字段必须整体缺省（服务端以 token 有无区分鉴权路径） */
class SignalEnvelopeTest {

    @Test
    fun hello_withToken() {
        val j = SignalEnvelope.hello("dev-1", "tok-abc")
        assertEquals("hello", j.getString("type"))
        assertEquals("dev-1", j.getString("deviceId"))
        assertEquals("tok-abc", j.getString("token"))
    }

    @Test
    fun hello_nullToken_omitsField() {
        val j = SignalEnvelope.hello("dev-1", null)
        assertEquals("hello", j.getString("type"))
        assertEquals("dev-1", j.getString("deviceId"))
        assertFalse(j.has("token"))
    }

    @Test
    fun hello_emptyToken_isKept() {
        // 空串 token 是调用方显式传入的，语义上仍要带字段（由服务端判 bad_token）
        val j = SignalEnvelope.hello("dev-1", "")
        assertTrue(j.has("token"))
        assertEquals("", j.getString("token"))
    }

    @Test
    fun message_carriesOnlyType() {
        val j = SignalEnvelope.message("pair.enter")
        assertEquals("pair.enter", j.getString("type"))
        assertEquals(1, j.length())
    }
}
