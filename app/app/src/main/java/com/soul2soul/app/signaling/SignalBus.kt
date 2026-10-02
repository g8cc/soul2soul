package com.soul2soul.app.signaling

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/** 信令消息的进程内分发总线：PresenceService 收到后，UI/服务各自收集自己关心的 type */
object SignalBus {
    // 容量 512：ICE 重连风暴+trickle candidate 可轻松超过 64 条，
    // DROP_OLDEST 丢掉的若是一条 offer/sdp 就会白屏——缓冲换不来的事故
    private val _events = MutableSharedFlow<JSONObject>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<JSONObject> = _events

    fun emit(json: JSONObject) {
        _events.tryEmit(json)
    }
}
