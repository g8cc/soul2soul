package com.soul2soul.app.signaling

import android.util.Log
import com.soul2soul.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 信令 WebSocket 封装：连接/发送/收到即进 SignalBus */
class SignalingClient : WebSocketListener() {

    interface StateListener {
        fun onState(connected: Boolean)
    }

    var stateListener: StateListener? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .also { com.soul2soul.app.util.TlsTrust.apply(it, com.soul2soul.app.R.raw.isrgrootx1) }
            .build()
    }

    @Volatile
    private var ws: WebSocket? = null
    @Volatile
    private var socketOpen = false
    private var deviceId: String? = null
    private var pairToken: String? = null

    /** 只有 WebSocket 已完成握手后才算可发送，CONNECTING 状态不能作为在线依据。 */
    val isConnected: Boolean get() = socketOpen && ws != null

    /** 更新配对令牌（配对成功/启动时注入）；若已连接，立即携带新令牌重连 */
    fun setPairToken(token: String?) {
        if (pairToken == token) return
        pairToken = token
        if (token != null && ws != null) {
            val id = deviceId
            close()
            id?.let { connect(it) }
        }
    }

    fun connect(id: String) {
        deviceId = id
        if (ws != null) return
        socketOpen = false
        Log.d(TAG, "connecting ${BuildConfig.SIGNALING_URL}")
        val request = Request.Builder().url(BuildConfig.SIGNALING_URL).build()
        ws = client.newWebSocket(request, this)
    }

    /** 组一条消息发送 */
    fun send(type: String, block: JSONObject.() -> Unit = {}): Boolean {
        val json = SignalEnvelope.message(type)
        json.block()
        return sendRaw(json)
    }

    fun sendRaw(json: JSONObject): Boolean {
        val socket = ws
        if (!socketOpen || socket == null) return false
        return socket.send(json.toString())
    }

    fun close() {
        val socket = ws
        ws = null
        socketOpen = false
        socket?.close(1000, "bye")
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        // token 更新/重连时，旧 socket 的回调可能晚于新 socket 到达；旧连接不能污染当前状态。
        if (ws !== webSocket) {
            webSocket.close(1000, "stale")
            return
        }
        Log.d(TAG, "ws open")
        val id = deviceId
        socketOpen = true
        if (id != null) {
            webSocket.send(SignalEnvelope.hello(id, pairToken).toString())
        }
        stateListener?.onState(true)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (ws !== webSocket) return
        try {
            SignalBus.emit(JSONObject(text))
        } catch (e: Exception) {
            Log.w(TAG, "bad signal: $text", e)
        }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (ws !== webSocket) return
        ws = null
        socketOpen = false
        stateListener?.onState(false)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (ws !== webSocket) return
        Log.w(TAG, "ws failed: ${t.message}")
        ws = null
        socketOpen = false
        stateListener?.onState(false)
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}
