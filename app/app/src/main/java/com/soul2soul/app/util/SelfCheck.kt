package com.soul2soul.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
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
}
