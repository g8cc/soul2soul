package com.soul2soul.app.session

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.soul2soul.app.MainActivity
import com.soul2soul.app.R
import com.soul2soul.app.signaling.SignalBus
import com.soul2soul.app.webrtc.IceServerParser
import com.soul2soul.app.webrtc.WebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 共享端会话服务（FGS: mediaProjection|microphone）。
 * Android 14+ 强制顺序：startForeground() 之后才能创建 MediaProjection。
 */
class ScreenShareService : Service(), WebRtcClient.Listener {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var micMuted = false
    @Volatile private var live = false

    /**
     * 锁屏看门狗（PRD FR-8）：锁屏后 VirtualDisplay 停止出帧，15 秒后自动结束防挂死。
     * 不能用"帧数停止增长"判断 —— 静止画面同样不出帧，会误杀（已踩坑）。
     * 锁屏瞬间立即通知观看端，别让对方对着冻结画面干等。
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> if (live) {
                    runCatching {
                        webRtc?.sendAnnotation(org.json.JSONObject().put("k", "screenoff"))
                    }
                    watchdog.postDelayed(screenOffStop, SCREEN_OFF_TIMEOUT_MS)
                }
                android.content.Intent.ACTION_SCREEN_ON -> {
                    watchdog.removeCallbacks(screenOffStop)
                    runCatching {
                        webRtc?.sendAnnotation(org.json.JSONObject().put("k", "screenon"))
                    }
                }
            }
        }
    }
    private val screenOffStop = Runnable {
        if (live) stopSession(sendBye = true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession(sendBye = true)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                micMuted = !micMuted
                webRtc?.muteLocalAudio(micMuted)
                updateNotification(if (micMuted) getString(R.string.sharing_muted) else getString(R.string.sharing_live))
                return START_NOT_STICKY
            }
        }
        if (webRtc != null) return START_NOT_STICKY

        val projection: Intent? = intent?.let { it.getProjectionExtra(EXTRA_PROJECTION) }
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        Notif.ensureChannels(this)
        startForeground(Notif.ID_SESSION, buildNotification(getString(R.string.sharing_starting)))
        Presence.sessionBusy = true // 会话期间自动拒接新呼叫
        com.soul2soul.app.util.WifiKeeper.acquire(this) // WiFi 高性能锁：防省电断流

        // FGS 已激活，此时才允许 getMediaProjection（ScreenCapturerAndroid 构造时内部调用）
        val client = WebRtcClient(
            context = this,
            isSharer = true,
            iceServers = IceServerParser.parse(Presence.iceServersJson),
            listener = this,
        )
        webRtc = client
        client.startSharer(projection)

        startService(Intent(this, AnnotationOverlayService::class.java))

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screenReceiver,
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF).apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        scope.launch {
            SignalBus.events.collect { json ->
                when (json.optString("type")) {
                    "sdp" -> webRtc?.onRemoteSdp(json.getJSONObject("sdp"))
                    "ice" -> webRtc?.onRemoteIce(json.getJSONObject("candidate"))
                    "bye", "peer.gone" -> stopSession(sendBye = false)
                }
            }
        }
        // 系统若杀掉会话进程，不做无意义的复活（屏幕授权意图已失效）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watchdog.removeCallbacks(screenOffStop)
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        webRtc?.close()
        webRtc = null
        super.onDestroy()
    }

    // ---------- WebRtcClient.Listener ----------

    override fun onSignalOut(json: JSONObject) {
        Presence.client.sendRaw(json)
    }

    override fun onRemoteVideo(track: org.webrtc.VideoTrack) {}

    override fun onRemoteVideoSize(width: Int, height: Int) {}

    override fun onDataMessage(json: JSONObject) {
        Log.d("S2S-DC", "recv k=${json.opt("k")} id=${json.opt("id")}")
        // 观看端清晰度切换 → 共享端；笔迹/清屏 → 悬浮窗服务（懒加载画布）
        when (json.optString("k")) {
            "res" -> webRtc?.setCaptureLongEdgeOnMain(json.optInt("edge", 1280))
            else -> AnnotationOverlayService.hook?.invoke(json)
        }
    }

    override fun onLive() {
        live = true
        updateNotification(getString(R.string.sharing_live))
        setSpeakerphone(true)
    }

    override fun onEnded(reason: String) {
        stopSession(sendBye = reason != "bye")
    }

    override fun onRtt(ms: Long) {}

    // ---------- 会话控制 ----------

    private fun stopSession(sendBye: Boolean) {
        live = false
        Presence.sessionBusy = false
        watchdog.removeCallbacks(screenOffStop)
        setSpeakerphone(false)
        micMuted = false
        // 静音是发送轨属性，随 AudioSource 销毁而复位，无需再全局恢复系统麦克风
        com.soul2soul.app.util.WifiKeeper.release()
        if (sendBye) Presence.client.send("bye")
        val client = webRtc
        webRtc = null
        client?.close()
        stopService(Intent(this, AnnotationOverlayService::class.java))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // 关键：主叫端主页的"呼叫中"状态必须同步复位，否则对端挂断后主叫永远卡在呼叫中
        MainActivity.cancelOutgoing()
        runCatching {
            com.soul2soul.app.signaling.SignalBus.emit(
                org.json.JSONObject().put("type", "local.sessionEnded")
            )
        }
        stopSelf()
    }

    private fun setSpeakerphone(on: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (on) {
            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            am.mode = android.media.AudioManager.MODE_NORMAL
        }
    }

    private fun updateNotification(text: String) {
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(Notif.ID_SESSION, buildNotification(text))
        } catch (_: SecurityException) {
            // 用户运行中撤销了通知权限：忽略，不影响会话
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 21,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val muteIntent = PendingIntent.getService(
            this, 22,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notif.CH_SESSION)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(getString(R.string.sharing_title))
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, if (micMuted) getString(R.string.action_unmute) else getString(R.string.action_mute), muteIntent)
            .addAction(0, getString(R.string.action_end), stopIntent)
            .build()
    }

    private fun Intent.getProjectionExtra(name: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(name, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }

    companion object {
        const val ACTION_STOP = "com.soul2soul.app.action.STOP_SHARE"
        const val ACTION_TOGGLE_MUTE = "com.soul2soul.app.action.TOGGLE_MUTE"
        const val EXTRA_PROJECTION = "projection"
        private const val SCREEN_OFF_TIMEOUT_MS = 15_000L

        // 会话级单例：生命周期与 ScreenShareService 完全绑定（onDestroy 清空），不构成泄漏
        @Suppress("StaticFieldLeak")
        @Volatile
        var webRtc: WebRtcClient? = null
            private set

        fun isRunning(): Boolean = webRtc != null

        fun start(ctx: Context, projectionData: Intent) {
            val intent = Intent(ctx, ScreenShareService::class.java)
                .putExtra(EXTRA_PROJECTION, projectionData)
            // 调用点在授权回调（前台），24/25 用 startService 合法
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /** 悬浮气泡的结束按钮回调 */
        fun stopFromOverlay(ctx: Context) {
            ctx.startService(Intent(ctx, ScreenShareService::class.java).setAction(ACTION_STOP))
        }
    }
}
