package com.soul2soul.app.msg

import android.os.Handler
import android.os.Looper
import com.soul2soul.app.App
import com.soul2soul.app.BuildConfig
import com.soul2soul.app.R
import com.soul2soul.app.util.Prefs
import com.soul2soul.app.util.TlsTrust
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 语音留言文件的 HTTP 通道（协议见 docs/PROTOCOL.md §2.4）。
 * 鉴权复用配对令牌：query 里带 deviceId+token，收件人只能下载指向自己留言箱的语音。
 */
object VoiceClient {

    private val main = Handler(Looper.getMainLooper())

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .also { TlsTrust.apply(it, R.raw.isrgrootx1) }
            .build()
    }

    private fun base(): String = BuildConfig.SIGNALING_URL
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")

    private fun authQuery(): String {
        val ctx = App.instance
        val id = URLEncoder.encode(Prefs.deviceId(ctx), "UTF-8")
        val token = URLEncoder.encode(Prefs.pairToken(ctx) ?: "", "UTF-8")
        return "deviceId=$id&token=$token"
    }

    /** 上传录音（≤1MB）；成功回调 voiceId，失败回调 null。回调固定在主线程 */
    fun upload(file: File, cb: (String?) -> Unit) {
        val request = Request.Builder()
            .url("${base()}/voice?${authQuery()}")
            .post(file.asRequestBody("audio/mp4".toMediaType()))
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { cb(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val id = runCatching {
                    response.use { resp ->
                        if (!resp.isSuccessful) return@use null
                        JSONObject(resp.body?.string() ?: "{}").optString("id")
                            .takeIf { it.isNotEmpty() }
                    }
                }.getOrNull()
                main.post { cb(id) }
            }
        })
    }

    /** 下载语音到缓存文件；命中本地缓存直接回。回调固定在主线程 */
    fun download(voiceId: String, cb: (File?) -> Unit) {
        val ctx = App.instance
        val cache = File(ctx.cacheDir, "voice_$voiceId.m4a")
        if (cache.exists() && cache.length() > 0) {
            main.post { cb(cache) }
            return
        }
        val request = Request.Builder()
            .url("${base()}/voice/$voiceId?${authQuery()}")
            .get()
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { cb(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val file = runCatching {
                    response.use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val bytes = resp.body?.bytes() ?: return@use null
                        cache.writeBytes(bytes)
                        cache
                    }
                }.getOrNull()
                main.post { cb(file) }
            }
        })
    }
}
