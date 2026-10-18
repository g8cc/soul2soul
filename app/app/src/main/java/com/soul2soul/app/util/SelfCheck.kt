package com.soul2soul.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.soul2soul.app.App
import com.soul2soul.app.BuildConfig
import com.soul2soul.app.session.Presence
import com.soul2soul.app.session.RemoteControlService
import org.json.JSONObject

/**
 * 会话能力自检上报：机型/系统/权限矩阵随呼叫建立自动上报，
 * 对方手机不在手边也能从信令服务器日志（或本端 logcat）定位"这是 ROM 问题还是代码问题"。
 * 只报能力布尔值与机型公开信息，不含任何用户数据。
 */
object SelfCheck {

    fun report(ctx: Context, role: String, extra: JSONObject? = null) {
        val json = JSONObject().apply {
            put("role", role)
            put("model", Build.MANUFACTURER + " " + Build.MODEL)
            put("brand", Build.BRAND)
            put("os", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("rom", Build.DISPLAY)
            put("app", BuildConfig.VERSION_NAME + "(" + BuildConfig.VERSION_CODE + ")")
            put("notif", androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled())
            put("overlay", Settings.canDrawOverlays(ctx))
            put("acc", RemoteControlService.isReady())
            put("accConnected", RemoteControlService.hasConnectedInstance())
            put("accGestureCap", RemoteControlService.hasGestureCapability())
            put("mic", ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED)
            put("battery", batteryIgnoring(ctx))
            extra?.let { e ->
                e.keys().forEach { k -> put(k, e.get(k)) }
            }
        }
        Log.d("S2S-Diag", json.toString())
        runCatching {
            if (Presence.client.isConnected) {
                Presence.client.send("app.diag") {
                    json.keys().forEach { k -> put(k, json.get(k)) }
                }
            }
        }
    }

    /** 是否已豁免电池优化（国产 ROM 后台杀的首要逃生门；API 23+） */
    fun batteryIgnoring(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
    }

    /**
     * 会话结束自报。服务器看不到 WebRTC 媒体，"谁把这条会话关掉的、为什么"只能靠双端自报；
     * 没有它，对方手机不在手边时断连就只能猜（v0.2.54 后 Mi15 发起 4~6 秒即断，就是卡在这一步）。
     * 只报枚举名与标量，不含任何用户内容。
     */
    fun reportSession(
        ctx: Context,
        role: String,
        reason: String,
        ice: String,
        setupMs: Long,
        liveMs: Long,
    ) = sendDiag(
        ctx, "diag.session", JSONObject()
            .put("role", role).put("reason", reason).put("ice", ice)
            .put("setupMs", setupMs).put("liveMs", liveMs)
            .put("fg", foreground(ctx)).put("screenOn", screenOn(ctx))
            .put("app", BuildConfig.VERSION_NAME)
    )

    /** 会话中途的关键事件（锁屏、采集成败、画面卡死、surface 重建）：与 reportSession 同一通道 */
    fun reportEvent(
        ctx: Context, role: String, event: String, ice: String, detail: String? = null
    ) = sendDiag(
        ctx, "diag.event", JSONObject()
            .put("role", role).put("event", event).put("ice", ice)
            .put("detail", (detail ?: "-").take(64))
            .put("fg", foreground(ctx)).put("screenOn", screenOn(ctx))
            .put("app", BuildConfig.VERSION_NAME)
    )

    private fun sendDiag(ctx: Context, type: String, body: JSONObject) {
        Log.d("S2S-Diag", "$type $body")
        val sent = runCatching {
            if (Presence.client.isConnected) {
                Presence.client.send(type) {
                    body.keys().forEach { k -> put(k, body.get(k)) }
                }
            } else false
        }.getOrDefault(false)
        // 发不出去就进本地补报队列（DiagBacklog 只写文件，不碰 socket、不触发重连）。
        // 断连那一刻正是唯一需要证据的时刻：v0.2.57 之前这里直接 return，
        // 于是"米15 锁屏后干了什么"在生产日志里是空的。
        if (!sent) DiagBacklog.enqueue(ctx, type, body)
    }

    /** 本 App 是否有 Activity 在前台：Android 14+ 后台起屏幕采集会被系统拒绝，这是断连排查的分叉点 */
    fun foreground(ctx: Context): Boolean = runCatching {
        App.instance.resumedActivities > 0
    }.getOrDefault(false)

    private fun screenOn(ctx: Context): Boolean =
        runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        }.getOrDefault(true)
}
