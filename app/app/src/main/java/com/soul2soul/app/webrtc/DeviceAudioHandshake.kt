package com.soul2soul.app.webrtc

import org.json.JSONObject

/**
 * 设备内录（FR-16）的能力握手（纯逻辑，JVM 可单测）。
 *
 * 为什么不能沿用 `controlSupported` 那条"通道非空即支持"：aud 是**共享端自己**创建的，
 * 本端看到它非空只能证明"我建了"，证明不了对面那台手机认得它。
 * 所以开闸的唯一依据是观看端亲口回一条 audok；旧观看端不会回，内录就永远不起
 * （旧观看端的 `onDataChannel` 还是"后到者覆盖"，但它对二进制帧一律 `return`，
 * 因此这条通道对它是纯惰性载荷，不会把 anno 顶掉——anno 建在最后）。
 */
object DeviceAudioHandshake {

    /** 观看端 → 共享端（ctl，有序可靠）：我这边有 AAC 解码器并愿意收这路声音 */
    const val K_ACK = "audok"

    /** 共享端 → 观看端（ctl）：内录开关与状态；发送方在采集那一步接上 */
    const val K_STATE = "aud"

    const val CODEC_AAC = "aac"

    /** 只在 aud 通道确认 OPEN 之后发：通道没通就 ack，对面会起一个送不出去的采集 */
    fun ackJson(): JSONObject = JSONObject()
        .put("k", K_ACK)
        .put("codec", CODEC_AAC)
        .put("sr", AudioFrameCodec.SAMPLE_RATE)
        .put("ch", AudioFrameCodec.CHANNELS)

    /**
     * 参数逐项核对：任何一项对不上都不算 ack。
     * 认错了的代价是"她起了采集、我解不出"——那正是本机能测、真机听不出的那类假成功。
     */
    fun parseAck(json: JSONObject): Boolean =
        json.optString("codec") == CODEC_AAC &&
            json.optInt("sr") == AudioFrameCodec.SAMPLE_RATE &&
            json.optInt("ch") == AudioFrameCodec.CHANNELS

    fun stateJson(on: Boolean, state: String): JSONObject = JSONObject()
        .put("k", K_STATE)
        .put("on", if (on) 1 else 0)
        .put("state", state)
}
