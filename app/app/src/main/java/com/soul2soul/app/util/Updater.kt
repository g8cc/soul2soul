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
import java.io.File

/**
 * 自动更新：检查服务器版本清单 → 下载 APK → 交给系统安装。
 * 版本清单: https://soul.lumi666.cloud/apk/version.json
 * {"versionCode":4,"versionName":"0.2.4","url":"https://soul.lumi666.cloud/apk/soul2soul-latest.apk","sha256":"..."}
 */
object Updater {

    private const val TAG = "Updater"
    private const val VERSION_URL = "https://soul.lumi666.cloud/apk/version.json"

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String = "",
    )

    private val http by lazy {
        OkHttpClient.Builder().also { TlsTrust.apply(it, com.soul2soul.app.R.raw.isrgrootx1) }.build()
    }

    /** 拉取服务器版本信息；服务器不可达/解析失败返回 null（静默，不打扰用户） */
    fun check(): Info? = runCatching {
        http.newCall(Request.Builder().url(VERSION_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            VersionManifest.parse(resp.body!!.string())?.takeIf { isTrustedDownloadUrl(it.url) }
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

    /** Only accept the production APK host; the URL comes from a remotely served manifest. */
    fun isTrustedDownloadUrl(url: String): Boolean = runCatching {
        val parsed = java.net.URI(url)
        parsed.scheme.equals("https", ignoreCase = true) &&
            parsed.host.equals("soul.lumi666.cloud", ignoreCase = true) &&
            (parsed.port == -1 || parsed.port == 443) &&
            parsed.path.startsWith("/apk/") &&
            parsed.path.endsWith(".apk", ignoreCase = true) &&
            parsed.userInfo == null && parsed.query == null && parsed.fragment == null
    }.getOrDefault(false)

    /** Download APK to a temporary sibling, verify it, then atomically replace the destination. */
    fun download(
        url: String,
        dest: File,
        onProgress: (Int) -> Unit,
        expectedSha256: String? = null,
    ): File? = runCatching {
        check(isTrustedDownloadUrl(url)) { "untrusted download url" }
        val expected = expectedSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        check(expected == null || VersionManifest.isSha256(expected)) { "invalid expected sha256" }
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "${dest.name}.part")
        if (part.exists() && !part.delete()) error("cannot replace partial download")
        // Do not follow redirects: an HTTPS manifest URL must not redirect an unhashed legacy
        // update to an arbitrary host. A published SHA-256 remains the content-level check.
        val downloadClient = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        downloadClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "download http ${resp.code}" }
            check(isTrustedDownloadUrl(resp.request.url.toString())) { "untrusted response url" }
            val body = resp.body ?: error("empty body")
            val total = body.contentLength()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        done += read
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                    out.flush()
                }
            }
            onProgress(100)
            check(part.length() > 0L) { "empty download" }
            val actual = digest.digest().joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            check(expected == null || expected == actual) { "sha256 mismatch" }
            // 不能用 java.nio.file.Files（API 26+，minSdk 23 的安卓7设备会
            // NoClassDefFoundError，且被 runCatching 吞掉→更新永远静默失败）
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            dest
        }
    }.onFailure {
        Log.w(TAG, "download failed", it)
        val part = File(dest.parentFile, "${dest.name}.part")
        if (part.exists()) part.delete()
    }.getOrNull()

    /** 后台线程执行检查（协程包装） */
    suspend fun checkAsync(): Info? = withContext(Dispatchers.IO) { check() }
}
