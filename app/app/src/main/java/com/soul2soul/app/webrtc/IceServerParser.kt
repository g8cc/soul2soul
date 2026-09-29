package com.soul2soul.app.webrtc

import org.json.JSONArray
import org.webrtc.PeerConnection

/** 把服务器下发的 iceServers JSON 转成 libwebrtc 配置 */
object IceServerParser {

    fun parse(arr: JSONArray?): List<PeerConnection.IceServer> {
        if (arr == null) return emptyList()
        val result = mutableListOf<PeerConnection.IceServer>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val username = obj.optString("username", "")
            val credential = obj.optString("credential", "")
            val urls = obj.optJSONArray("urls") ?: continue
            for (j in 0 until urls.length()) {
                val url = urls.optString(j)
                if (url.isNullOrEmpty()) continue
                val builder = PeerConnection.IceServer.builder(url)
                if (username.isNotEmpty()) builder.setUsername(username)
                if (credential.isNotEmpty()) builder.setPassword(credential)
                result.add(builder.createIceServer())
            }
        }
        return result
    }
}
