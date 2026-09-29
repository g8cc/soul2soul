package com.soul2soul.app.signaling

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/** 信令消息的进程内分发总线：PresenceService 收到后，UI/服务各自收集自己关心的 type */
object SignalBus {
    private val _events = MutableSharedFlow<JSONObject>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<JSONObject> = _events

    fun emit(json: JSONObject) {
        _events.tryEmit(json)
    }
}
