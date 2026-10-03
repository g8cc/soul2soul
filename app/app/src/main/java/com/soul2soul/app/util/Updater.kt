package com.soul2soul.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.soul2soul.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 自动更新：检查服务器版本清单 → 下载 APK → 交给系统安装。
 * 版本清单: https://soul.lumi666.cloud/apk/version.json
 * {"versionCode":4,"versionName":"0.2.4","url":"https://soul.lumi666.cloud/apk/soul2soul-latest.apk"}
 */
object Updater {

    private const val TAG = "Updater"
    private const val VERSION_URL = "https://soul.lumi666.cloud/apk/version.json"

    data class Info(val versionCode: Int, val versionName: String, val url: String)

    private val http by lazy {
        OkHttpClient.Builder().also { TlsTrust.apply(it, com.soul2soul.app.R.raw.isrgrootx1) }.build()
    }

    /** 拉取服务器版本信息；服务器不可达/解析失败返回 null（静默，不打扰用户） */
    fun check(): Info? = runCatching {
        http.newCall(Request.Builder().url(VERSION_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body!!.string())
            Info(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.optString("versionName", ""),
                url = json.optString("url", ""),
            )
        }
    }.onFailure { Log.w(TAG, "check failed", it) }.getOrNull()

    /**
     * 运行时真实 versionCode。绝不能用 BuildConfig.VERSION_CODE 做判定：
     * Kotlin 会把 Java static-final 常量内联进调用方字节码，版本升级不触碰判定文件时
     * 增量编译复用旧产物——0.2.12 线上 dex 实锤 hasUpdate 被焊死成 `remote > 8`，全员永久提示更新。
     */
    fun localVersionCode(context: Context): Int = try {
        @Suppress("DEPRECATION")
        val pi: android.content.pm.PackageInfo = context.packageManager
            .getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 26) pi.longVersionCode.toInt() else pi.versionCode
    } catch (e: PackageManager.NameNotFoundException) {
        BuildConfig.VERSION_CODE // 同包直读，理论上不可能发生
    }

    /** 下载 APK 到应用外部缓存目录（onProgress: 0-100） */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit): File? = runCatching {
        dest.parentFile?.mkdirs()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "download http ${resp.code}" }
            val body = resp.body ?: error("empty body")
            val total = body.contentLength()
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                    out.flush()
                }
            }
            dest
        }
    }.onFailure { Log.w(TAG, "download failed", it) }.getOrNull()

    /** 后台线程执行检查（协程包装） */
    suspend fun checkAsync(): Info? = withContext(Dispatchers.IO) { check() }
}
