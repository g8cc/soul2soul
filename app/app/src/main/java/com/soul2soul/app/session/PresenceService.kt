package com.soul2soul.app.session

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.soul2soul.app.MainActivity
import com.soul2soul.app.R
import com.soul2soul.app.signaling.SignalBus
import com.soul2soul.app.signaling.SignalingClient
import com.soul2soul.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 全局入口：常驻信令连接 + ICE 服务器缓存 + 会话占用状态 */
object Presence {
    val client = SignalingClient()
    var iceServersJson: org.json.JSONArray? = null

    @Volatile
    var sessionBusy: Boolean = false

    fun ensureStarted(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(Intent(ctx, PresenceService::class.java))
        } else {
            // 24/25: startService 即可，且服务 onCreate 必须立即 startForeground
            ctx.startService(Intent(ctx, PresenceService::class.java))
        }
    }
}

/**
 * 常驻待命前台服务：唯一持有信令长连接。
 * 依赖用户开启 自启动/电池无限制/后台弹出界面（App 内有引导），否则进程被杀时收不到呼叫。
 */
class PresenceService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var started = false
    private var reconnectAttempt = 0

    private val reconnectRunner = object : Runnable {
        override fun run() {
            connect()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notif.ensureChannels(this)
        startForeground(Notif.ID_PRESENCE, buildPresenceNotification())

        if (!started) {
            started = true
            Presence.client.setPairToken(Prefs.pairToken(this))
            Presence.client.stateListener = object : SignalingClient.StateListener {
                override fun onState(connected: Boolean) {
                    if (connected) {
                        reconnectAttempt = 0
                    } else {
                        // 指数退避: 3s, 6s, 12s ... 上限 30s，避免服务端故障时打爆
                        val delay = (RECONNECT_BASE_MS shl reconnectAttempt.coerceAtMost(4))
                            .coerceAtMost(RECONNECT_MAX_MS)
                        reconnectAttempt += 1
                        handler.postDelayed(reconnectRunner, delay)
                    }
                }
            }
            // 先建立总线订阅，再打开 WebSocket，避免服务刚启动时丢掉 registered/paired/accepted。
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                SignalBus.events.collect { handleSignal(it) }
            }
            connect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        Presence.sessionBusy = false
        com.soul2soul.app.util.WifiKeeper.release()
        Presence.client.close()
        started = false
        super.onDestroy()
    }

    private fun connect() {
        Presence.client.connect(Prefs.deviceId(this))
    }

    private fun handleSignal(json: JSONObject) {
        when (json.optString("type")) {
            "incoming" -> {
                Presence.iceServersJson = json.optJSONArray("iceServers")
                if (Presence.sessionBusy) {
                    // 正在会话中: 自动拒接，主叫端会看到"对方正忙"
                    Presence.client.send("decline")
                    return
                }
                if (MainActivity.outgoingPending) {
                    // 撞车：双方同时点了「一起玩」。按设备号确定性裁决——
                    // 设备号小的邀请优先：拒接对方来电，继续等对方接受我的邀请
                    // 设备号大的让路：撤销自己的呼叫，转入接听对方的来电
                    val mine = Prefs.deviceId(this)
                    val from = json.optString("from")
                    if (mine < from) {
                        Presence.client.send("decline")
                        return
                    }
                    Presence.client.send("cancel")
                    MainActivity.cancelOutgoing()
                    Log.d("PresenceService", "glare resolved: yield to $from")
                }
                // App 在前台时直接拉起会话页（不依赖全屏意图权限）；后台路径走通知
                runCatching {
                    startActivity(
                        Intent(this, SessionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                showIncomingNotification()
            }
            "accepted" -> {
                // 主叫切到了后台时对方接听：把 TA 拉回来（offer 由已启动的共享服务处理）
                // accepted 与主页共用 SharedFlow，主页可能在后台重建而错过这条事件；
                // 常驻服务必须自己启动共享端，不能把媒体建立依赖在 Activity 是否及时订阅。
                Presence.iceServersJson = json.optJSONArray("iceServers")
                if (MainActivity.outgoingPending) {
                    MainActivity.acceptedReceived = true
                    MainActivity.projectionData?.let { projection ->
                        runCatching {
                            ScreenShareService.start(this, projection)
                        }.onFailure {
                            Log.e("PresenceService", "unable to start share service", it)
                            Presence.client.send("bye")
                            MainActivity.cancelOutgoing()
                        }
                    }
                }
                runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            "paired" -> {
                Prefs.setPaired(this, true)
                json.optString("token").takeIf { it.isNotEmpty() }?.let {
                    Prefs.setPairToken(this, it)
                    Presence.client.setPairToken(it)
                }
            }
            "unpaired" -> {
                // 对端解绑（或自己发起后服务端确认）：清除本地配对与令牌
                Prefs.setPaired(this, false)
                Prefs.setPairToken(this, null)
                Presence.client.setPairToken(null)
                com.soul2soul.app.msg.InboxStore.clear()
            }
            "msg.inbox" -> {
                // 上线送达的留言箱：由常驻服务入库（UI 可能根本不存在），已读删除在留言页完成
                val arr = json.optJSONArray("items") ?: return
                val list = (0 until arr.length()).mapNotNull {
                    com.soul2soul.app.msg.Msg.fromJson(arr.optJSONObject(it), mine = false)
                }
                com.soul2soul.app.msg.InboxStore.addAll(list)
            }
            "msg.new" -> {
                com.soul2soul.app.msg.Msg.fromJson(json, mine = false)
                    ?.let { com.soul2soul.app.msg.InboxStore.add(it) }
            }
        }
    }

    private fun showIncomingNotification() {
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val tapIntent = Intent(this, SessionActivity::class.java)
            .putExtra(SessionActivity.EXTRA_INCOMING, true)
        val pi = PendingIntent.getActivity(
            this, 11, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, Notif.CH_CALL)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(getString(R.string.incoming_call))
            .setContentText(getString(R.string.incoming_call_body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(Notif.ID_CALL, notification)
        } catch (e: SecurityException) {
            Log.w("PresenceService", "no notification permission", e)
        }
    }

    private fun buildPresenceNotification(): Notification =
        NotificationCompat.Builder(this, Notif.CH_PRESENCE)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(getString(R.string.presence_title))
            .setContentText(getString(R.string.presence_body))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 12, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    companion object {
        private const val RECONNECT_BASE_MS = 3000L
        private const val RECONNECT_MAX_MS = 30_000L
    }
}
