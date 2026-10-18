package com.soul2soul.app.session

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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

    // 信令连接态（主页醒目胶囊的唯一权威可读源）。SharedFlow 无 replay，
    // Activity 可能错过 onState 事件，故落在此处，onResume 也能直接读到当前值。
    const val CONN_CONNECTING = 0
    const val CONN_CONNECTED = 1
    const val CONN_OFFLINE = 2

    @Volatile
    var connState: Int = CONN_CONNECTING

    // 找回请求的服务端弹窗是"一次性"的：SharedFlow 无 replay，Activity 可能错过事件。
    // 服务收到后先落这里，再把主页顶到前台，由 onResume/onNewIntent 领取（只领一次）。
    @Volatile
    private var pendingRecallFrom: String? = null

    @Volatile
    private var pendingRecallAt = 0L

    fun offerRecall(from: String?) {
        pendingRecallFrom = from
        pendingRecallAt = System.currentTimeMillis()
    }

    /** @return 60s 内的待答找回请求发起方；领取即清空（与服务端 pendingTtl 对齐） */
    fun claimRecall(): String? = synchronized(this) {
        val v = pendingRecallFrom ?: return null
        pendingRecallFrom = null
        if (System.currentTimeMillis() - pendingRecallAt > 60_000) null else v
    }

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
    // 会话期信令断口的起点，复活时把 downMs 带给服务器（signaling_down 本身发不出去）
    @Volatile private var signalingDownSinceMonoMs = 0L

    private val reconnectRunner = object : Runnable {
        override fun run() {
            connect()
        }
    }

    // 待机期（无会话）信令只按"翻转"自报：一次断连一条 down、复活一条 up。
    // 不加这个门，Doze 里每 30s 退避重连一次 = 120 条/小时噪声，真正的 outage 形状反而读不出来。
    @Volatile private var standbySigDown = false

    /**
     * 息屏保活锁：锁屏断连根因是 CPU 进 doze 睡死后回不了服务器 ping 的 pong（30s 即被 terminate）。
     * 仅配对时持有；acquire 必须带超时——MIUI 异常杀进程时 onDestroy 不执行，无超时=永久漏锁耗电。
     * 到期前由 wakeReArm 自我续期，等效持续持有但带防呆语义。
     */
    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soul2soul::presence")
            .apply { setReferenceCounted(false) }
    }

    /**
     * 信令掉线/复活的自报。downStartMonoMs>0 才是"从断口复活"；首连不许报 downMs=0 这种假长度。
     *
     * detail 预算：补报会加 `at=<13位毫秒> ` 前缀，服务器再过 `safeTag` **静默**截到 64
     * ⇒ 可用只有 ~47。所以 reconnectAttempt 封顶两位数、downMs 封顶 7 位，省下的位数给
     * 网络/Doze 快照：一次复活同时看到"断了多久"和"当时是不是在 Doze"，比第 105 次重连这个数字有用得多。
     *
     * `downMs` 必须用单调钟相减：复活那一刻正是系统重新同步时钟的时刻，wall-clock 相减会把
     * "断了 4 分钟"读成"断了 -3.8 小时"或反之（AGENTS 口径：设备时钟不可信，Mi5 实测慢 ~10.5h）。
     */
    private fun reportSignaling(event: String, attempt: Int, downStartMonoMs: Long, role: String) {
        val detail = "reconnectAttempt=${attempt.coerceAtMost(99)}" +
            (if (downStartMonoMs > 0L)
                " downMs=${(SystemClock.elapsedRealtime() - downStartMonoMs).coerceIn(0L, 9_999_999L)}" else "") +
            com.soul2soul.app.util.StandbyProbe.signalingFlags()
        com.soul2soul.app.util.SelfCheck.reportEvent(
            this,
            role,
            event, "-", detail
        )
    }

    /** 会话期/待机期都算不出"角色"的地方不许硬写 viewer：待机掉线被读成观看端掉线就是这个坑 */
    private fun signalRole(): String = when {
        ScreenShareService.isRunning() -> "sharer"
        Presence.sessionBusy -> "viewer"
        else -> "standby"
    }

    private val wakeReArm = Runnable { updateWakeLock(Prefs.paired(this)) }

    private fun updateWakeLock(keepAlive: Boolean) {
        handler.removeCallbacks(wakeReArm)
        if (keepAlive) {
            // acquire 结果必须出数：「她那边锁到底拿到没有」是"CPU 睡死 vs 网络真断"的分叉点，
            // 而这条路径此前只有一行无声的 acquire
            val ok = runCatching {
                if (wakeLock.isHeld) wakeLock.release() // 非计数锁：重获以刷新超时
                wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
            }
            com.soul2soul.app.util.StandbyProbe.onWakeLock(held = ok.isSuccess, failed = ok.isFailure)
            if (ok.isSuccess) handler.postDelayed(wakeReArm, WAKE_REARM_MS)
        } else if (wakeLock.isHeld) {
            wakeLock.release()
            com.soul2soul.app.util.StandbyProbe.onWakeLock(held = false, failed = false)
        }
    }

    /** 留言箱任何变化：同步通知栏常驻条数 + 聊天记录落盘（服务常驻，UI 不在也一样跟） */
    private val inboxListener: () -> Unit = {
        com.soul2soul.app.msg.UnreadNotifier.sync(this)
        val snap = com.soul2soul.app.msg.InboxStore.snapshot()
        val file = java.io.File(filesDir, com.soul2soul.app.msg.ChatPersistence.FILE_NAME)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.soul2soul.app.msg.ChatPersistence.save(file, snap)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notif.ensureChannels(this)
        startForeground(Notif.ID_PRESENCE, buildPresenceNotification())
        // 只在"本次会话期间新来的留言"才挂条数通知：不在服务启动时补挂历史未读
        // （用户要求：通知栏不要常驻，有留言了才显示条数）
        // 聊天记录先于监听恢复：进程重启后聊天窗口还是完整的（微信式）
        com.soul2soul.app.msg.InboxStore.restore(
            com.soul2soul.app.msg.ChatPersistence.load(
                java.io.File(filesDir, com.soul2soul.app.msg.ChatPersistence.FILE_NAME),
            ),
        )
        com.soul2soul.app.msg.InboxStore.addListener(inboxListener)

        if (!started) {
            started = true
            Presence.client.setPairToken(Prefs.pairToken(this))
            Presence.client.stateListener = object : SignalingClient.StateListener {
                override fun onState(connected: Boolean) {
                    Presence.connState = if (connected) Presence.CONN_CONNECTED else Presence.CONN_OFFLINE
                    SignalBus.emit(org.json.JSONObject().put("type", "local.connState").put("connected", connected))
                    if (connected) {
                        reconnectAttempt = 0
                        // 断连期间攒下的埋点在复活后补报（hello 已先发，服务器按到达顺序处理，authed 成立）
                        com.soul2soul.app.util.DiagBacklog.flush(this@PresenceService)
                        // 上一轮进程的死因（ApplicationExitInfo）只有此刻才可能发得出去，且只读一次
                        com.soul2soul.app.util.StandbyProbe.reportPreviousExit(this@PresenceService)
                    } else {
                        // 指数退避: 3s, 6s, 12s ... 上限 30s，避免服务端故障时打爆
                        val delay = (RECONNECT_BASE_MS shl reconnectAttempt.coerceAtMost(4))
                            .coerceAtMost(RECONNECT_MAX_MS)
                        reconnectAttempt += 1
                        handler.postDelayed(reconnectRunner, delay)
                    }
                    // 信令断/续自报。此前只在会话期报 ⇒ 「待机 socket 到底死了多久」在服务器上
                    // 至今是一格空白（10-18 那 9 次 pong 超时全部只能从服务端日志反推）。
                    // 现在两条路都报，但待机期只报翻转：Doze 里每 30s 一次退避重连不能刷成噪声。
                    if (connected) {
                        val down = signalingDownSinceMonoMs
                        signalingDownSinceMonoMs = 0L
                        if (Presence.sessionBusy) {
                            reportSignaling("signaling_up", reconnectAttempt, down, signalRole())
                        } else if (standbySigDown) {
                            standbySigDown = false
                            reportSignaling("signaling_up", reconnectAttempt, down, "standby")
                        }
                        // 待机首连不发：那是每次冷启动都会有的常量事件，heartbeat 已覆盖"活着"
                    } else {
                        if (signalingDownSinceMonoMs == 0L) {
                            signalingDownSinceMonoMs = SystemClock.elapsedRealtime()
                            // 断连被确认的这一刻是重放死线：再晚，死亡窗口里那些"以为发成功了"
                            // 的待机读数（net_down/doze_on/bucket_*）就已经永久丢了
                            com.soul2soul.app.util.StandbyProbe.replaySuspects(this@PresenceService)
                        }
                        if (Presence.sessionBusy) {
                            reportSignaling("signaling_down", reconnectAttempt, 0L, signalRole())
                        } else if (!standbySigDown) {
                            standbySigDown = true
                            reportSignaling("signaling_down", reconnectAttempt, 0L, "standby")
                        }
                    }
                }
            }
            // 先建立总线订阅，再打开 WebSocket，避免服务刚启动时丢掉 registered/paired/accepted。
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                SignalBus.events.collect { handleSignal(it) }
            }
            connect()
            // 待机遥测：未配对期 diag.* 会被服务器在未鉴权 socket 上直接丢弃，所以只在配对期开
            // （不是"埋了坏了"——10-18 复核过这条口径，读日志时未配对期必然为空）
            // 顺序是硬要求：先开探针再拿锁。反过来，首次 acquire 落在 running=false 上，
            // `wake_ok` 就在结构上永远发不出去（10-18 全部日志 0 条就是这样发现的），
            // 而"锁到底拿到没有"正是待机断连要查的第一件事
            if (Prefs.paired(this)) com.soul2soul.app.util.StandbyProbe.start(this)
            // wakelock 必须在 startForeground 之后才允许持有（服务真正前台，防 FGS 起失败漏锁）
            updateWakeLock(Prefs.paired(this))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (wakeLock.isHeld) wakeLock.release() // acquire 的对偶出口（穷举 restore）
        scope.cancel()
        com.soul2soul.app.msg.InboxStore.removeListener(inboxListener)
        // 主动退出必须和"被 ROM 杀"分得开：close() 之前发——socket 还在就当场出去，
        // Doze 下 TCP 已死就进补报队列，复活时带 at_ 出来（诚实说明：这条只能证明"她退出了/服务被停"，
        // 不会改变今天 0/40 条 code=1000 的分布，那批是 TCP 先亡）
        com.soul2soul.app.util.SelfCheck.reportEvent(this, signalRole(), "app_exit", "-", "why=destroy")
        com.soul2soul.app.util.StandbyProbe.stop(this)
        Presence.sessionBusy = false
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
                            .putExtra(SessionActivity.EXTRA_INCOMING, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                showIncomingNotification()
            }
            "accepted" -> {
                // 对方接听：服务侧保证采集起流；不再把主页顶到前台（v0.2.54 无感化）——
                // 被控端停在当前界面，操控方看到的即其真实画面（采集/注入/悬浮均不要求前台）。
                // accepted 与主页共用 SharedFlow，主页可能在后台错过这条事件；
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
            }
            "call.canceled" ->
                // 对方在接听前取消：来电通知必须跟着消失，否则挂着一条"来电"，
                // 点进去是已经被 SignalRouter 判死的空会话页
                androidx.core.app.NotificationManagerCompat.from(this)
                    .cancel(Notif.ID_CALL)
            "paired" -> {
                Prefs.setPaired(this, true)
                json.optString("token").takeIf { it.isNotEmpty() }?.let {
                    Prefs.setPairToken(this, it)
                    Presence.client.setPairToken(it)
                }
                // 配对成功即打开待机遥测（start 幂等）：从此"锁屏静置"这一段不再是无因黑箱。
                // 必须先 start 后拿锁，否则首次 acquire 的 `wake_ok` 永远发不出
                com.soul2soul.app.util.StandbyProbe.start(this)
                updateWakeLock(true)
            }
            "unpaired" -> {
                // 对端解绑（或自己发起后服务端确认）：清除本地配对与令牌
                Prefs.setPaired(this, false)
                Prefs.setPairToken(this, null)
                Presence.client.setPairToken(null)
                com.soul2soul.app.msg.InboxStore.clear()
                com.soul2soul.app.util.SelfCheck.reportEvent(this, "standby", "app_exit", "-", "why=unpair")
                // 释放锁必须在 stop 之前：探针停了之后 `wake_off` 就发不出去，
                // "解绑后锁到底放没放"会重新变成一格空白
                updateWakeLock(false) // 解除配对即无呼叫需求，息屏保活锁同步释放
                // stop 是 start 的对偶出口：解绑后没有呼叫需求，心跳与传感器必须一起关掉，
                // 否则未配对期会攒出一堆发不出去的排队事件（还占补报队列配额）
                com.soul2soul.app.util.StandbyProbe.stop(this)
            }
            "pair.recall_ask" -> {
                // 老搭档找回请求：主页可能压根不在（SharedFlow 无 replay），
                // 先落领取槽再顶前台+挂通知，弹窗由 MainActivity 领取后展示
                Presence.offerRecall(json.optString("from"))
                showRecallNotification()
                runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
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
            "msg.recalled" -> {
                // 撤回事件同时下发给双方：本地打墓碑（折叠成提示行），UI 靠 InboxStore 监听刷新
                val id = json.optString("id")
                if (id.isNotEmpty()) com.soul2soul.app.msg.InboxStore.recall(id)
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

    private fun showRecallNotification() {
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val pi = PendingIntent.getActivity(
            this, 14, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, Notif.CH_CALL)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(getString(R.string.recall_ask_title))
            .setContentText(getString(R.string.recall_ask_msg))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(Notif.ID_RECALL, notification)
            // 找回请求 60s 后服务端就过期了（claimRecall 同判据）：通知必须同步消失，
            // 否则挂一整天的"死链"，点了只会看到"请求已过期"
            handler.postDelayed({ nm.cancel(Notif.ID_RECALL) }, RECALL_NOTIFY_TTL_MS)
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
        // 略大于服务端 pendingTtl(60s)+本地 claimRecall 判据：过期瞬间前一刻的点击仍应可用
        private const val RECALL_NOTIFY_TTL_MS = 65_000L
        // 息屏保活锁：单轮 6h，5h 自我续期——即使 wakeReArm 被意外截断，锁也只会到期释放而不会永久漏持
        private const val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val WAKE_REARM_MS = 5 * 60 * 60 * 1000L
    }
}
