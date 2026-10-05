package com.soul2soul.app.signaling

import org.json.JSONObject

/**
 * 信令包络构造的纯逻辑（无 Android 依赖，可单测）。
 * 线上协议字段全集见 docs/PROTOCOL.md；此处只锁定 hello/带类型消息的构造规则。
 */
object SignalEnvelope {

    /** hello 注册包：pairToken 为 null（未配对）时必须整体缺省该字段 */
    fun hello(deviceId: String, pairToken: String?): JSONObject {
        val hello = JSONObject().put("type", "hello").put("deviceId", deviceId)
        pairToken?.let { hello.put("token", it) }
        return hello
    }

    /** 组一条带 type 的消息（其余字段由调用方填充） */
    fun message(type: String): JSONObject = JSONObject().put("type", type)
}
