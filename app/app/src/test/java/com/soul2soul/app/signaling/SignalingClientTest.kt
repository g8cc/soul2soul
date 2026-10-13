package com.soul2soul.app.signaling

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalingClientTest {
    @Test
    fun `远端关闭时回应关闭握手`() {
        val socket = RecordingSocket()
        SignalingClient().onClosing(socket, 1000, "shutdown")
        assertEquals(listOf(1000 to "shutdown"), socket.closes)
    }

    private class RecordingSocket : WebSocket {
        val closes = mutableListOf<Pair<Int, String?>>()
        override fun request(): Request = Request.Builder().url("http://localhost/").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean {
            closes += code to reason
            return true
        }
        override fun cancel() = Unit
    }
}
