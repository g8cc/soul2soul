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

    private var ws: WebSocket? = null
    private var deviceId: String? = null
    private var pairToken: String? = null

    val isConnected: Boolean get() = ws != null

    /** 更新配对令牌（配对成功/启动时注入）；若已连接，立即携带新令牌重连 */
    fun setPairToken(token: String?) {
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
        Log.d(TAG, "connecting ${BuildConfig.SIGNALING_URL}")
        val request = Request.Builder().url(BuildConfig.SIGNALING_URL).build()
        ws = client.newWebSocket(request, this)
    }

    /** 组一条消息发送 */
    fun send(type: String, block: JSONObject.() -> Unit = {}): Boolean {
        val json = JSONObject().put("type", type)
        json.block()
        return sendRaw(json)
    }

    fun sendRaw(json: JSONObject): Boolean {
        val socket = ws ?: return false
        return socket.send(json.toString())
    }

    fun close() {
        ws?.close(1000, "bye")
        ws = null
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(TAG, "ws open")
        val id = deviceId
        if (id != null) send("hello") {
            put("deviceId", id)
            pairToken?.let { put("token", it) }
        }
        stateListener?.onState(true)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            SignalBus.emit(JSONObject(text))
        } catch (e: Exception) {
            Log.w(TAG, "bad signal: $text", e)
        }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        ws = null
        stateListener?.onState(false)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.w(TAG, "ws failed: ${t.message}")
        ws = null
        stateListener?.onState(false)
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}
