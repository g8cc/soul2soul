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
import kotlinx.coroutines.Job
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

    @Volatile private var live = false
    @Volatile private var stopping = false
    /** 系统撤销录屏授权而会话存续：画面停、语音与数据通道照旧（0.2.64） */
    @Volatile private var videoPaused = false
    private val pendingSignals = mutableListOf<JSONObject>()
    private var signalJob: Job? = null
    private var screenReceiverRegistered = false
    private var audioRoute: AudioRouteController? = null
    private var lastCtlErrorAt = 0L
    private val ctlTelemetry = CtlLatencyTelemetry { android.os.SystemClock.elapsedRealtime() }

    /**
     * 锁屏=画面暂停、会话存续（v0.2.56 推翻 PRD FR-8 的"15 秒自动结束"）：
     * 两人边语音边各玩各的，手机不动就会锁屏，会话必须继续。
     * 结束会话只剩三条路：本端挂断 / 对端 bye·peer.gone / ICE 真断超时。
     * 仍不能用"帧数停止增长"判断锁屏 —— 静止画面同样不出帧，会误杀（已踩坑）。
     * 锁屏瞬间立即通知观看端，让对方知道画面是暂停而不是卡死。
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> if (live) {
                    // 清醒时给的授权不跨锁屏存续，解锁后需重新点「允许TA操控」。
                    // 收回发生在观看端操控中途：立刻说清"为什么突然点不动了"，
                    // 否则对方只会以为操控坏了（这正是"退出了还能操控/退出后不能操控"困惑的来源）
                    dispatchConsent(consent.revokeOnScreenOff())
                    sendScreenStatus("screenoff")
                    // 锁屏只在 DataChannel 上通知对方，服务器看不见：自报一份，
                    // 否则"被控端锁屏后对方看到的停格"在服务器日志里就是无因现象
                    com.soul2soul.app.util.SelfCheck.reportEvent(
                        this@ScreenShareService, "sharer", "screen_off",
                        webRtc?.iceStateName ?: "-", "keepalive",
                    )
                }
                android.content.Intent.ACTION_SCREEN_ON -> {
                    sendScreenStatus("screenon")
                    com.soul2soul.app.util.SelfCheck.reportEvent(
                        this@ScreenShareService, "sharer", "screen_on",
                        webRtc?.iceStateName ?: "-", "resumed",
                    )
                }
            }
        }
    }

    /** 锁屏状态通知双通道补发：anno 容忍丢包（弱网下 screenon 丢了，对方的"锁屏了"横幅就再也回不来），
     *  所以再走一遍可靠有序的 ctl；横幅显隐本身幂等，重复送达无害 */
    private fun sendScreenStatus(k: String) {
        val json = org.json.JSONObject().put("k", k)
        runCatching { webRtc?.sendAnnotation(json) }
        runCatching { webRtc?.sendControl(json) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 结束原因是 local_stop 只能说明"点了停止"，说不清点的是哪一个：App 内结束按钮与
                // 通知栏动作走的是同一条 ACTION_STOP。这条事件记的是"哪个控件被点"这个输入事实，
                // 不是第二个终止原因真值源（reason 仍只有 local_stop 一处）。
                com.soul2soul.app.util.SelfCheck.reportEvent(
                    this, "sharer", "stop_tap", webRtc?.iceStateName ?: "-",
                    "src_" + (intent.getStringExtra(EXTRA_STOP_SRC) ?: "unknown"),
                )
                stopSession(sendBye = true)
                return START_NOT_STICKY
            }
            ACTION_RESTART_CAPTURE -> {
                // 通知栏"重新开画面"→ CaptureReauthActivity 拿到新授权后回到这里。
                // 会话没拆过，所以只需要换采集器；失败也不能拆（语音还活着），只把通知留原样=还能再点
                if (webRtc == null) { stopSelf(); return START_NOT_STICKY }
                val data = intent.getProjectionExtra(EXTRA_PROJECTION)
                if (data == null) {
                    com.soul2soul.app.util.SelfCheck.reportEvent(
                        this, "sharer", "projection_reopen_fail",
                        webRtc?.iceStateName ?: "-", "no_data",
                    )
                    return START_NOT_STICKY
                }
                val ok = runCatching { webRtc?.restartCapture(data) == true }.getOrDefault(false)
                if (ok && videoPaused) {
                    videoPaused = false
                    sendScreenStatus("videoon")
                } else if (!ok) {
                    com.soul2soul.app.util.SelfCheck.reportEvent(
                        this, "sharer", "projection_reopen_fail",
                        webRtc?.iceStateName ?: "-", "startCapture",
                    )
                }
                updateNotification(statusText())
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                if (webRtc == null) { stopSelf(); return START_NOT_STICKY } // 会话已结束：迟到的通知动作别挂尸
                micMuted = !micMuted
                webRtc?.muteLocalAudio(micMuted)
                updateNotification(statusText())
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_CTL -> {
                if (webRtc == null) { stopSelf(); return START_NOT_STICKY }
                dispatchConsent(consent.toggle(RemoteControlService.isReady()))
                return START_NOT_STICKY
            }
            ACTION_SET_CTL -> {
                // 授权对话框的确定性开关（比 toggle 更适合"当前状态→目标状态"）
                if (webRtc == null) { stopSelf(); return START_NOT_STICKY }
                dispatchConsent(
                    consent.setTarget(
                        intent.getBooleanExtra(EXTRA_CTL_ON, false),
                        RemoteControlService.isReady(),
                    )
                )
                return START_NOT_STICKY
            }
        }
        if (webRtc != null) return START_NOT_STICKY

        // stopSelf() normally destroys the service, but a rapid redial can arrive before
        // onDestroy(). Reset all per-call state before accepting the new projection.
        stopping = false
        pendingSignals.clear()
        videoPaused = false // 新会话从零开始：上一通的"画面已停"绝不能带进这一通

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
            // 后台起 FGS 被拒（Android 12+ ForegroundServiceStartNotAllowedException /
            // Android 14+ HyperOS FGS 类型校验）是"被控端几秒即断"的头号嫌疑。
            // 这里只发 bye 的话，服务器看到的就是一个无因断连，必须先自报。
            com.soul2soul.app.util.SelfCheck.reportEvent(
                this, "sharer", "fgs_start_failed", "-", e.javaClass.simpleName,
            )
            Presence.client.send("bye")
            MainActivity.cancelOutgoing()
            SignalBus.emit(JSONObject().put("type", "local.sessionEnded"))
            stopSelf()
            return START_NOT_STICKY
        }
        Presence.sessionBusy = true // 会话期间自动拒接新呼叫
        // 每次新会话必须从零开始收授权：服务实例若跨会话存活(onDestroy 未及时跑)，
        // 上一通话的"允许TA操控"绝不能带进这一通
        consent.resetForNewSession()
        lastCtlErrorAt = 0L
        ctlTelemetry.reset()
        updateNotification(statusText())
        com.soul2soul.app.util.WifiKeeper.acquire(this) // WiFi 高性能锁：防省电断流
        // AudioDeviceModule 构造时会读取系统音频参数，必须先固定通信模式和扬声器路由。
        audioRoute = AudioRouteController(this).also { it.start() }

        if (!screenReceiverRegistered) {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                screenReceiver,
                android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF).apply {
                    addAction(android.content.Intent.ACTION_SCREEN_ON)
                },
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenReceiverRegistered = true
        }

        // 必须先订阅再创建 offer，否则极快返回的 answer/ICE 可能在 collector 建立前被 SharedFlow 丢弃。
        signalJob?.cancel()
        signalJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
                    "bye", "peer.gone" -> {
                        // 秒断判定：peer.gone 只代表**信令**断了（服务器 30s pong 超时就会广播一次），
                        // P2P 媒体可能完全正常。必须在拆会话**之前**记下到达瞬间的 ICE 态——
                        // stopSession 之后 ice 只会是 CLOSED，「谁把会话关掉的」就永远读不出来了。
                        com.soul2soul.app.util.SelfCheck.reportEvent(
                            this@ScreenShareService, "sharer", "peer_signal",
                            webRtc?.iceStateName ?: "-",
                            "sig=" + if (json.optString("type") == "peer.gone") "gone" else "bye",
                        )
                        // 占位必须在 stopSession 之前：收尾的 close() 会当场读 endReason 并上报，
                        // 之后才写就来不及了（sendBye=false 那条 local_stop 守卫也进不去）。
                        // 否则共享端被对方挂断时 diag.session.reason 只剩 local_close，"谁关的"要跨两行拼。
                        webRtc?.noteEnd(if (json.optString("type") == "peer.gone") "peer_gone" else "remote_bye")
                        stopSession(sendBye = false)
                    }
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
            com.soul2soul.app.util.SelfCheck.reportEvent(
                this, "sharer", "webrtc_start_failed", webRtc?.iceStateName ?: "-",
                e.javaClass.simpleName,
            )
            // 抢在 stopSession 的 local_stop 之前把真因压进 first-wins 槽：
            // 否则服务器收到的是"用户主动停止"，一句谎报
            webRtc?.noteEnd("webrtc_start_failed")
            stopSession(sendBye = true)
        }
        // 系统若杀掉会话进程，不做无意义的复活（屏幕授权意图已失效）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        watchdog.removeCallbacksAndMessages(null) // 排队中的手势注入一并作废（服务已亡不再代表会话授权）
        consent.resetForNewSession()
        signalJob?.cancel()
        signalJob = null
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        scope.cancel()
        live = false
        liveState = false
        Presence.sessionBusy = false
        com.soul2soul.app.util.WifiKeeper.release()
        pendingSignals.clear()
        webRtc?.close()
        webRtc = null
        audioRoute?.stop()
        stopService(Intent(this, AnnotationOverlayService::class.java))
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
        // signaling 线程第一时间打时间戳：后面每一跳（主线程排队、系统串行回放）都能算账
        val recvAt = android.os.SystemClock.elapsedRealtime()
        // WebRTC 在 signaling 线程回调；悬浮窗、授权状态机和采集切档统一回主线程串行。
        watchdog.post {
            if (stopping) return@post
            when (json.optString("k")) {
                "res" -> webRtc?.setCaptureLongEdgeOnMain(
                    json.optInt("edge", com.soul2soul.app.webrtc.VideoQualityPolicy.DEFAULT_EDGE)
                )
                "g" -> handleGesture(json, recvAt)
                else -> AnnotationOverlayService.hook?.invoke(json)
            }
        }
    }

    /** 远程操控手势（ctlg 快道，整笔一条）：仅在用户允许时注入 */
    private fun handleGesture(json: JSONObject, recvAt: Long) {
        when (val held = consent.heldCheck()) {
            is CtlConsentMachine.Decision.Allow -> Unit
            is CtlConsentMachine.Decision.Deny -> {
                dispatchConsent(held.effects)
                return
            }
        }
        // dispatchGesture 要求带 Looper 的线程；WebRTC 回调在 signaling 线程
        watchdog.post {
            // 收回授权可能发生在入队之后：注入前必须以主线程上的最新状态再判一次
            val readiness = RemoteControlService.readiness()
            when (val pre = consent.preInjectCheck(readiness == RemoteControlService.Readiness.READY)) {
                is CtlConsentMachine.Decision.Allow -> Unit
                is CtlConsentMachine.Decision.Deny -> {
                    dispatchConsent(pre.effects)
                    if (readiness != RemoteControlService.Readiness.READY) {
                        sendControlError(readiness.toControlErrorReason())
                    }
                    return@post
                }
            }
            val pts = json.optJSONArray("pts") ?: return@post
            val list = ArrayList<PointF>(pts.length())
            for (i in 0 until pts.length()) {
                val p = pts.optJSONArray(i) ?: continue
                // optDouble 缺省返 0.0：坏点会被当成左上角真实坐标注入（点击左上角），
                // 必须用 NaN 哨兵跳过（v0.2.37 审计）
                val x = p.optDouble(0, Double.NaN)
                val y = p.optDouble(1, Double.NaN)
                if (x.isNaN() || y.isNaN()) continue
                list.add(PointF(x.toFloat(), y.toFloat()))
            }
            ctlTelemetry.onStrokeAccepted()
            val result = RemoteControlService.dispatchNormalized(
                list,
                json.optLong("dur", 120L),
                { reason -> sendControlError(reason) },
            ) { dispatchedAt, doneAt, playMs ->
                val sample = ctlTelemetry.onStrokeSettled(recvAt, dispatchedAt, doneAt, playMs)
                if (ctlTelemetry.shouldLog(doneAt)) {
                    Log.d(
                        "S2S-Lag",
                        "stroke wait=${sample.queueWaitMs}ms total=${sample.totalMs}ms " +
                            "play=${sample.playMs}ms overflow=${sample.overflowMs}ms " +
                            "inflight=${sample.inflight}",
                    )
                }
            }
            if (result != RemoteControlService.DispatchResult.ACCEPTED) {
                // 无回放回调的失败路径也要结算，否则 inflight 只增不减
                val now = android.os.SystemClock.elapsedRealtime()
                ctlTelemetry.onStrokeSettled(recvAt, now, now, 0L)
                sendControlError(result.toControlErrorReason())
            }
        }
    }

    private fun sendControlError(reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCtlErrorAt < CTL_ERROR_THROTTLE_MS) return
        lastCtlErrorAt = now
        Log.w("S2S-Ctl", "remote control injection failed: reason=$reason")
        runCatching {
            webRtc?.sendControl(JSONObject().put("k", "ctl_error").put("reason", reason))
        }
    }

    private fun RemoteControlService.DispatchResult.toControlErrorReason(): String = when (this) {
        RemoteControlService.DispatchResult.ACCEPTED -> "accepted"
        RemoteControlService.DispatchResult.SERVICE_UNAVAILABLE -> "service_unavailable"
        RemoteControlService.DispatchResult.GESTURE_CAPABILITY_MISSING -> "gesture_capability_missing"
        RemoteControlService.DispatchResult.INVALID_GESTURE -> "invalid_gesture"
        RemoteControlService.DispatchResult.SYSTEM_ACTION_REJECTED -> "system_action_rejected"
        RemoteControlService.DispatchResult.GESTURE_REJECTED -> "gesture_rejected"
    }

    private fun RemoteControlService.Readiness.toControlErrorReason(): String = when (this) {
        RemoteControlService.Readiness.SERVICE_DISCONNECTED,
        RemoteControlService.Readiness.UNSUPPORTED -> "service_unavailable"
        RemoteControlService.Readiness.GESTURE_CAPABILITY_MISSING -> "gesture_capability_missing"
        RemoteControlService.Readiness.READY -> "accepted"
    }

    /** 执行状态机产出的副作用：Toast/被拒回灌/通知栏刷新（节流判定已在状态机内完成） */
    private fun dispatchConsent(effects: List<CtlConsentMachine.Effect>) {
        for (e in effects) {
            when (e) {
                is CtlConsentMachine.Effect.Toast -> watchdog.post {
                    android.widget.Toast.makeText(this, e.res, android.widget.Toast.LENGTH_SHORT).show()
                }
                is CtlConsentMachine.Effect.DeniedNotice -> runCatching {
                    webRtc?.sendControl(org.json.JSONObject().put("k", "ctl_denied"))
                }
                is CtlConsentMachine.Effect.DeniedScreenOff -> runCatching {
                    webRtc?.sendControl(org.json.JSONObject().put("k", "ctl_denied").put("reason", "screenoff"))
                }
                is CtlConsentMachine.Effect.RefreshNotification -> updateNotification(statusText())
            }
        }
        // 授权翻转同步视频策略：操控期间锁低档保帧率，收回后恢复用户档位（幂等）
        webRtc?.setControlMode(consent.allowed)
    }

    override fun onLive() {
        watchdog.post {
            if (stopping || webRtc == null) return@post
            live = true
            liveState = true
            SignalBus.emit(JSONObject().put("type", "local.sessionLive"))
            updateNotification(statusText())
            // 共享端也报一份：断线/操控问题时两份矩阵对起来看是谁的环境缺了什么
            com.soul2soul.app.util.SelfCheck.report(
                this, "sharer",
                JSONObject().put("ctlAllowed", ctlAllowed),
            )
            val (sw, sh) = com.soul2soul.app.util.ScreenSize.real(this)
            Log.d(
                "S2S-Geom",
                "sharer display=${sw}x${sh} app=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} " +
                    "capture=${webRtc?.captureWidth}x${webRtc?.captureHeight}",
            )
        }
    }

    override fun onEnded(reason: String) {
        watchdog.post { stopSession(sendBye = reason != "bye") }
    }

    override fun onRtt(ms: Long) {}

    /**
     * 系统撤销录屏授权（她锁屏的那条路）：会话存续、只停画面。
     * 0.2.63 及以前这里等于挂断，于是"她锁屏省电"被对方读成"她把我挂了"——
     * 而音频/涂鸦/操控跟 MediaProjection 没有任何依赖关系，10-19 日志里 ice 一直 CONNECTED。
     */
    override fun onProjectionRevoked() {
        watchdog.post {
            // 迟到的回调不许改状态：收尾路径会把 webRtc 置空，那一刻再发 videooff 就是给对方
            // 一条再也不会被 videoon 撤掉的横幅（成对操作必须穷举退出路径）
            if (stopping || webRtc == null || !live) return@post
            if (videoPaused) return@post // 边沿触发，重复 onStop 不刷通知
            videoPaused = true
            sendScreenStatus("videooff")
            updateNotification(statusText())
        }
    }

    // ---------- 会话控制 ----------

    private fun stopSession(sendBye: Boolean) {
        if (stopping) return
        stopping = true
        signalJob?.cancel()
        signalJob = null
        // Drop queued data-channel/UI work from this call before a rapid redial can reuse the service.
        watchdog.removeCallbacksAndMessages(null)
        live = false
        liveState = false
        Presence.sessionBusy = false
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        micMuted = false
        consent.resetForNewSession() // 会话结束立即收回操控授权
        // 静音是发送轨属性，随 AudioSource 销毁而复位，无需再全局恢复系统麦克风
        com.soul2soul.app.util.WifiKeeper.release()
        if (sendBye) Presence.client.send("bye")
        // 没有任何上游终止原因时才落到 local_stop：用户点停止/连接失败会先 noteEnd
        if (sendBye) webRtc?.noteEnd("local_stop")
        val client = webRtc
        webRtc = null
        client?.close()
        // 先停音轨再还原系统路由，防止销毁窗口里出现短促残音。
        audioRoute?.stop()
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

    private fun updateNotification(text: String) {
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(Notif.ID_SESSION, buildNotification(text))
        } catch (_: SecurityException) {
            // 用户运行中撤销了通知权限：忽略，不影响会话
        }
    }

    /** 通知正文把"语音到底开没开"说清楚：无麦权限时根本没有上行音频，别让人以为按静音就行 */
    private fun statusText(): String = getString(
        ShareNoticePolicy.statusTextRes(
            noMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED,
            muted = micMuted,
            videoPaused = videoPaused,
        )
    )

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
            Intent(this, ScreenShareService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_STOP_SRC, "notify"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val muteIntent = PendingIntent.getService(
            this, 22,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 操控按钮走对话框（Activity PendingIntent），不再用服务 PendingIntent+Toast：
        // Android 7/MIUI 通知面板不是 secure 窗口且不会因服务动作自动收起，
        // 面板盖住 toast → 授权失败时用户只感觉"点了没反应"（真机实锤）。
        // 对话框一出现面板自动收起；无障碍没就绪时对话框驻留显示引导
        val ctlIntent = PendingIntent.getActivity(
            this, 23,
            Intent(this, CtlConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(CtlConsentActivity.EXTRA_AUTO_APPROVE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 自画入口是摇一摇（见 AnnotationOverlayService）：通知按钮 Android 7 只放得下 3 个，
        // 这里保持 静音/操控/结束 不变
        // HyperOS/Android13+ 默认折叠 FGS 通知，动作按钮经常根本看不见——
        // 把"点通知整体"变成授权对话框入口，授权路径不再依赖按钮可见性
        val consentIntent = PendingIntent.getActivity(
            this, 24,
            Intent(this, CtlConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 画面已停时整条通知的点按改道到"重新开画面"：Android 7 只放得下 3 个动作按钮，
        // 而 HyperOS 默认把 FGS 通知的动作折叠到看不见——正文点按是唯一稳定的入口
        // （与上面 consentIntent 同一个理由，别再指望多塞一个按钮）
        val reauthIntent = PendingIntent.getActivity(
            this, 25,
            Intent(this, CaptureReauthActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notif.CH_SESSION)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(getString(R.string.sharing_title))
            .setContentText(text)
            .setContentIntent(if (videoPaused) reauthIntent else consentIntent)
            .setOngoing(true)
            // 动作必须带图标：Android 8+ 通知折叠态只渲染图标按钮，icon=0 的纯文字按钮
            // 要展开才显示，而 MIUI/HyperOS 常驻通知默认折叠——v0.2.46 真机实锤"按钮时有时无"
            .addAction(R.drawable.ic_notif_mic, if (micMuted) getString(R.string.action_unmute) else getString(R.string.action_mute), muteIntent)
            .addAction(R.drawable.ic_notif_ctl, if (ctlAllowed) getString(R.string.ctl_disallow) else getString(R.string.ctl_allow), ctlIntent)
            .addAction(R.drawable.ic_notif_end, getString(R.string.action_end), stopIntent)
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
        const val ACTION_RESTART_CAPTURE = "com.soul2soul.app.action.RESTART_CAPTURE"
        const val ACTION_TOGGLE_MUTE = "com.soul2soul.app.action.TOGGLE_MUTE"
        const val ACTION_TOGGLE_CTL = "com.soul2soul.app.action.TOGGLE_CTL"
        const val ACTION_SET_CTL = "com.soul2soul.app.action.SET_CTL"
        const val EXTRA_CTL_ON = "ctl_on"
        const val EXTRA_PROJECTION = "projection"
        const val EXTRA_STOP_SRC = "stop_src"
        private const val CTL_ERROR_THROTTLE_MS = 2_000L

        /** 操控授权状态机（会话级）：通知按钮与授权对话框都经它读写，跨 Activity/Service 共享 */
        val consent = CtlConsentMachine { android.os.SystemClock.elapsedRealtime() }

        /** ctlAllowed 是状态机的投影：读取点（对话框/通知栏）不变，对话框乐观写转交状态机 */
        var ctlAllowed: Boolean
            get() = consent.allowed
            set(value) = consent.optimisticSet(value)

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

        /**
         * 用户从 App 内点"结束"（MainActivity 共享中卡片）。src 进 stop_tap 的 detail，
         * 因为通知栏动作与它是同一条 ACTION_STOP，日志要能分清她点的是哪一个。
         */
        fun stopSharing(ctx: Context, src: String) {
            ctx.startService(
                Intent(ctx, ScreenShareService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_STOP_SRC, src),
            )
        }
        /**
         * 重新授权后把新 token 交回服务：只换采集器，会话与音频不动。
         * 这里不回传成败——采集结果由服务自己刷新通知栏（画面起没起来，她在通知上就能看到），
         * Activity 拿一个布尔也没有第二条真值来源可用。
         */
        fun reopenCapture(ctx: Context, projectionData: Intent) {
            ctx.startService(
                Intent(ctx, ScreenShareService::class.java)
                    .setAction(ACTION_RESTART_CAPTURE)
                    .putExtra(EXTRA_PROJECTION, projectionData),
            )
        }
    }
}
