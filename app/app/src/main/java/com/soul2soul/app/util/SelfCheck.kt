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
     *
     * `path`（选路，如 `host->srflx`）与 `proj`（结束时屏幕采集还在不在）是服务器
     * `[diag.session]` 一直在打的两个字段（`server/src/index.js:639,641`），但客户端从未 put 过，
     * 于是 10-18 生产日志 4/4 条都是 `path=_ proj=_`：P2P 还是 TURN 转发、采集有没有被 ROM 拆掉，
     * 全读不出来。默认值 `-` 是故意的——调用方漏传会留下 `-` 而不是假装有数据，
     * 而 `StandbyTelemetryTest` S3 直接锁住 `reportSessionEnd` 必须把两个真值传进来。
     */
    fun reportSession(
        ctx: Context,
        role: String,
        reason: String,
        ice: String,
        setupMs: Long,
        liveMs: Long,
        path: String = "-",
        proj: String = "-",
    ) = sendDiag(
        ctx, "diag.session", JSONObject()
            .put("role", role).put("reason", reason).put("ice", ice)
            .put("path", path).put("proj", proj)
            .put("setupMs", setupMs).put("liveMs", liveMs)
            .put("fg", foreground(ctx)).put("screenOn", screenOn(ctx))
            .put("app", BuildConfig.VERSION_NAME)
    )

    /**
     * 会话中途的关键事件（锁屏、采集成败、画面卡死、surface 重建、待机状态翻转）：
     * 与 reportSession 同一通道，v0.2.59 起 `role` 允许第三个值 `standby`——待机期
     * 既不是 sharer 也不是 viewer，此前 `reportSignaling` 把它报成 `viewer`，
     * 于是"待机掉线"在日志里长得和"观看端掉线"一模一样。
     *
     * 返回实际发出的报文体，调用方（StandbyProbe）据此留副本重放——`send()` 返回 true
     * 只代表帧写进了内核缓冲，不代表对方收到（见 sendDiag）。
     */
    fun reportEvent(
        ctx: Context, role: String, event: String, ice: String, detail: String? = null
    ): JSONObject {
        val body = eventBody(ctx, role, event, ice, detail)
        sendDiag(ctx, "diag.event", body)
        return body
    }

    /**
     * 拆服务那一刻的退出事件：只入队、不发送。
     * `reportEvent` 走的是乐观发送，而 `onDestroy` 里紧跟着就是 `client.close()`——
     * 帧还在 socket 缓冲里就连同连接一起被关掉了（10-19 实测：两端 `code=1000 reason=bye`
     * 都在，`app_exit` 一条没到）。入队后下次连上带 `at_` 重放，时刻不会丢。
     * 代价要说清：这条永远以补报行的形式出现，且她若再也不打开 App 就永远不到。
     */
    fun reportExitQueued(ctx: Context, role: String, detail: String) {
        DiagBacklog.enqueueSync(ctx, "diag.event", eventBody(ctx, role, "app_exit", "-", detail))
    }

    private fun eventBody(
        ctx: Context, role: String, event: String, ice: String, detail: String?
    ) = JSONObject()
        .put("role", role).put("event", event).put("ice", ice)
        .put("detail", (detail ?: "-").take(64))
        .put("fg", foreground(ctx)).put("screenOn", screenOn(ctx))
        .put("app", BuildConfig.VERSION_NAME)

    /** 把一条已经构造好的事件体直接塞进补报队列（不再碰 socket），供死亡窗口重放用 */
    fun queueEvent(ctx: Context, body: JSONObject) = DiagBacklog.enqueue(ctx, "diag.event", body)

    /**
     * 投递的判定口径：`isConnected` 为真时走"乐观发送"，帧写进 socket 缓冲就算发出去了，
     * **没有 ack、不会回失败**。所以"网络刚断、TCP 还没判死"那几十秒里发的事件会静默消失
     * （10-18 双模拟器实测：客户端 logcat 有 net_down，服务器 0 条，且没进补报队列）。
     * 只有 `isConnected` 为假或发送抛异常时才会入队——StandbyProbe 的重放缓存补的正是这段盲区。
     */
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
