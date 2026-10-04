package com.soul2soul.app.session

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
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
import kotlinx.coroutines.CoroutineStart
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

    /** 远程操控授权开关：默认关，通知栏「允许TA操控」手动开，会话结束自动收回 */
    @Volatile private var ctlAllowed = false
    @Volatile private var lastCtlWarnAt = 0L
    @Volatile private var live = false
    private val pendingSignals = mutableListOf<JSONObject>()

    /**
     * 锁屏看门狗（PRD FR-8）：锁屏后 VirtualDisplay 停止出帧，15 秒后自动结束防挂死。
     * 不能用"帧数停止增长"判断 —— 静止画面同样不出帧，会误杀（已踩坑）。
     * 锁屏瞬间立即通知观看端，别让对方对着冻结画面干等。
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> if (live) {
                    if (ctlAllowed) {
                        ctlAllowed = false // 清醒时给的授权不跨锁屏存续，解锁后需重新点「允许TA操控」
                        updateNotification(if (micMuted) getString(R.string.sharing_muted) else getString(R.string.sharing_live))
                    }
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
                if (webRtc == null) { stopSelf(); return START_NOT_STICKY } // 会话已结束：迟到的通知动作别挂尸
                micMuted = !micMuted
                webRtc?.muteLocalAudio(micMuted)
                updateNotification(if (micMuted) getString(R.string.sharing_muted) else getString(R.string.sharing_live))
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_CTL -> {
                if (webRtc == null) { stopSelf(); return START_NOT_STICKY }
                if (!ctlAllowed && !RemoteControlService.isReady()) {
                    ctlToast(R.string.ctl_need_acc)
                } else {
                    ctlAllowed = !ctlAllowed
                    ctlToast(if (ctlAllowed) R.string.ctl_enabled_toast else R.string.ctl_disabled_toast)
                    updateNotification(if (micMuted) getString(R.string.sharing_muted) else getString(R.string.sharing_live))
                }
                return START_NOT_STICKY
            }
        }
        if (webRtc != null) return START_NOT_STICKY

        val projection: Intent? = intent?.let { it.getProjectionExtra(EXTRA_PROJECTION) }
        if (projection == null) {
            MainActivity.cancelOutgoing()
            SignalBus.emit(JSONObject().put("type", "local.sessionEnded"))
            stopSelf()
            return START_NOT_STICKY
        }

        Notif.ensureChannels(this)
        try {
            startSessionForeground()
        } catch (e: Exception) {
            Log.e("ScreenShareService", "start foreground failed", e)
            Presence.client.send("bye")
            MainActivity.cancelOutgoing()
            SignalBus.emit(JSONObject().put("type", "local.sessionEnded"))
            stopSelf()
            return START_NOT_STICKY
        }
        Presence.sessionBusy = true // 会话期间自动拒接新呼叫
        // 每次新会话必须从零开始收授权：服务实例若跨会话存活(onDestroy 未及时跑)，
        // 上一通话的"允许TA操控"绝不能带进这一通
        ctlAllowed = false
        updateNotification(if (micMuted) getString(R.string.sharing_muted) else getString(R.string.sharing_live))
        com.soul2soul.app.util.WifiKeeper.acquire(this) // WiFi 高性能锁：防省电断流

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screenReceiver,
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF).apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // 必须先订阅再创建 offer，否则极快返回的 answer/ICE 可能在 collector 建立前被 SharedFlow 丢弃。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            SignalBus.events.collect { json ->
                when (json.optString("type")) {
                    "sdp", "ice" -> {
                        val client = webRtc
                        if (client == null) {
                            pendingSignals += json
                        } else {
                            dispatchSignal(client, json)
                        }
                    }
                    "bye", "peer.gone" -> stopSession(sendBye = false)
                }
            }
        }

        // FGS 已激活，此时才允许 getMediaProjection（ScreenCapturerAndroid 构造时内部调用）。
        // 构造或启动失败时要主动结束双方会话，不能让观看端永久停在“连接中”。
        try {
            val client = WebRtcClient(
                context = this,
                isSharer = true,
                iceServers = IceServerParser.parse(Presence.iceServersJson),
                listener = this,
            )
            webRtc = client
            client.startSharer(projection)
            pendingSignals.forEach { dispatchSignal(client, it) }
            pendingSignals.clear()
            startService(Intent(this, AnnotationOverlayService::class.java))
        } catch (e: Exception) {
            Log.e("ScreenShareService", "start WebRTC failed", e)
            stopSession(sendBye = true)
        }
        // 系统若杀掉会话进程，不做无意义的复活（屏幕授权意图已失效）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watchdog.removeCallbacks(screenOffStop)
        watchdog.removeCallbacksAndMessages(null) // 排队中的手势注入一并作废（服务已亡不再代表会话授权）
        ctlAllowed = false
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        live = false
        liveState = false
        Presence.sessionBusy = false
        setSpeakerphone(false)
        com.soul2soul.app.util.WifiKeeper.release()
        pendingSignals.clear()
        webRtc?.close()
        webRtc = null
        super.onDestroy()
    }

    // ---------- WebRtcClient.Listener ----------

    private fun dispatchSignal(client: WebRtcClient, json: JSONObject) {
        runCatching {
            when (json.optString("type")) {
                "sdp" -> client.onRemoteSdp(json.getJSONObject("sdp"))
                "ice" -> client.onRemoteIce(json.getJSONObject("candidate"))
            }
        }.onFailure { Log.w("ScreenShareService", "bad media signal", it) }
    }

    override fun onSignalOut(json: JSONObject) {
        Presence.client.sendRaw(json)
    }

    override fun onRemoteVideo(track: org.webrtc.VideoTrack) {}

    override fun onRemoteVideoSize(width: Int, height: Int) {}

    override fun onDataMessage(json: JSONObject) {
        Log.d("S2S-DC", "recv k=${json.opt("k")} id=${json.opt("id")}")
        // 观看端清晰度切换 → 共享端；笔迹/表情/特效 → 悬浮窗服务（懒加载画布）
        when (json.optString("k")) {
            "res" -> webRtc?.setCaptureLongEdgeOnMain(json.optInt("edge", 1280))
            "g" -> handleGesture(json)
            else -> AnnotationOverlayService.hook?.invoke(json)
        }
    }

    /** 远程操控手势（可靠 ctl 通道，整笔一条）：仅在用户允许时注入 */
    private fun handleGesture(json: JSONObject) {
        if (!ctlAllowed) return
        // dispatchGesture 要求带 Looper 的线程；WebRTC 回调在 signaling 线程
        watchdog.post {
            // 收回授权可能发生在入队之后：注入前必须以主线程上的最新状态再判一次
            if (!ctlAllowed) return@post
            val pts = json.optJSONArray("pts") ?: return@post
            if (!RemoteControlService.isReady()) {
                ctlAllowed = false
                ctlToast(R.string.ctl_need_acc)
                return@post
            }
            val list = ArrayList<PointF>(pts.length())
            for (i in 0 until pts.length()) {
                val p = pts.optJSONArray(i) ?: continue
                list.add(PointF(p.optDouble(0).toFloat(), p.optDouble(1).toFloat()))
            }
            RemoteControlService.dispatchNormalized(list, json.optLong("dur", 120L))
        }
    }

    private fun ctlToast(res: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCtlWarnAt < 3000L) return // 手势连发时别把屏幕糊满 toast
        lastCtlWarnAt = now
        watchdog.post {
            android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLive() {
        live = true
        liveState = true
        SignalBus.emit(JSONObject().put("type", "local.sessionLive"))
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
        liveState = false
        Presence.sessionBusy = false
        watchdog.removeCallbacks(screenOffStop)
        setSpeakerphone(false)
        micMuted = false
        ctlAllowed = false // 会话结束立即收回操控授权
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

    /** Android 14 会校验 FGS 类型对应的运行时权限；无麦克风权限时只声明屏幕采集。 */
    private fun startSessionForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            Notif.ID_SESSION,
            buildNotification(getString(R.string.sharing_starting)),
            type,
        )
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
        val ctlIntent = PendingIntent.getService(
            this, 23,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_TOGGLE_CTL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notif.CH_SESSION)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(getString(R.string.sharing_title))
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, if (micMuted) getString(R.string.action_unmute) else getString(R.string.action_mute), muteIntent)
            .addAction(0, if (ctlAllowed) getString(R.string.ctl_disallow) else getString(R.string.ctl_allow), ctlIntent)
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
        const val ACTION_TOGGLE_CTL = "com.soul2soul.app.action.TOGGLE_CTL"
        const val EXTRA_PROJECTION = "projection"
        private const val SCREEN_OFF_TIMEOUT_MS = 15_000L

        // 会话级单例：生命周期与 ScreenShareService 完全绑定（onDestroy 清空），不构成泄漏
        @Suppress("StaticFieldLeak")
        @Volatile
        var webRtc: WebRtcClient? = null
            private set

        @Volatile
        private var liveState = false

        fun isRunning(): Boolean = webRtc != null

        fun isLive(): Boolean = liveState

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
