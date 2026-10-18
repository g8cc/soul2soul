package com.soul2soul.app.util

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.soul2soul.app.session.Presence
import com.soul2soul.app.session.ScreenShareService

/**
 * 待机遥测探针（v0.2.59）：把传感器从会话生命周期里搬出来。
 *
 * 为什么必须单独有这一个：此前全项目唯一的屏幕监听在 `ScreenShareService`，而它只在
 * 共享会话期间存在 ⇒ 「锁屏静置时手机自己干了什么」在生产日志里是**空的**（10-18 复核：
 * 两次已确认的米15 掉线，补报队列 0 条）。待机期恰是唯一要看的时段，却一句话不说。
 *
 * 全部为**边沿触发**（状态翻转才发一条），同一状态不重复刷；每条都带 `k=<elapsedRealtime 秒>`
 * 单调锚点——设备时钟不可信（Mi5 实测慢 ~10.5h），只有单调钟能区分"被冻了 20 分钟"与"时钟跳了"。
 *
 * 隐私线：只报 transport 类型名、Doze 布尔、bucket 整数、锁状态、进程死因枚举号。
 * 不碰 SSID/BSSID/IP/网关/ICE 地址，也不读 `ApplicationExitInfo.description`（自由文本，可能含路径）。
 */
object StandbyProbe {

    /** 心跳周期：意义在"缺口"（连续两条差 >2× ⇒ 进程被冻或被杀），不承担投递保证 */
    private const val HEARTBEAT_SEC = 300L
    private const val HEARTBEAT_MS = HEARTBEAT_SEC * 1000L

    /** bucket 降级是待机断网最直接的自证，60s 轮一次够定位到分钟级 */
    private const val BUCKET_POLL_MS = 60_000L

    @Volatile private var running = false

    private var app: Context? = null
    private var cm: ConnectivityManager? = null
    private val handler = Handler(Looper.getMainLooper())

    // 边沿去重基准："?"/null/-1 = 还没读过（第一次读到就发，宁多发一条也别漏掉起始状态）
    @Volatile private var lastNet = "?"
    @Volatile private var lastDoze: Boolean? = null
    @Volatile private var lastBucket = -1
    @Volatile private var lastWakeHeld: Boolean? = null
    @Volatile private var lastBeatSec = 0L
    @Volatile private var exitRead = false

    private var screenReceiverRegistered = false
    private var dozeReceiverRegistered = false
    private var netCallbackRegistered = false

    /** 由 `PresenceService` 在配对期间调用；重复调用安全（running 门） */
    fun start(ctx: Context) {
        if (running) return
        val application = ctx.applicationContext
        running = true
        app = application
        cm = runCatching {
            application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }.getOrNull()
        emit("probe_start", "k=${ksec()}")

        // 屏幕开关：这两个 action 是系统广播，Android 14+ 要求注册时显式声明导出性，
        // 非导出注册照样收得到（ScreenShareService 已这么用）
        runCatching {
            ContextCompat.registerReceiver(
                application, screenReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenReceiverRegistered = true
        }
        runCatching {
            ContextCompat.registerReceiver(
                application, dozeReceiver,
                IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            dozeReceiverRegistered = true
        }
        runCatching {
            cm?.registerDefaultNetworkCallback(netCallback)
            netCallbackRegistered = true
        }
        // 开机第一轮：bucket 立刻读一次（否则首条降级事件要等到 60s 后）
        pollBucket()
        handler.postDelayed(heartbeatRunner, HEARTBEAT_MS)
        handler.postDelayed(bucketRunner, BUCKET_POLL_MS)
    }

    /** 与 start 严格对偶：解绑与 onDestroy 都必须走到（hideX 必穷举 restoreX） */
    fun stop(ctx: Context) {
        if (!running) return
        running = false
        val application = ctx.applicationContext
        if (screenReceiverRegistered) runCatching { application.unregisterReceiver(screenReceiver) }
        if (dozeReceiverRegistered) runCatching { application.unregisterReceiver(dozeReceiver) }
        if (netCallbackRegistered) runCatching { cm?.unregisterNetworkCallback(netCallback) }
        screenReceiverRegistered = false
        dozeReceiverRegistered = false
        netCallbackRegistered = false
        handler.removeCallbacksAndMessages(null)
        lastNet = "?"
        lastDoze = null
        lastBucket = -1
        lastBeatSec = 0L
        app = null
        cm = null
    }

    fun isRunning(): Boolean = running

    /**
     * 给信令自报拼接的现场快照：探针没起来就返回空串（不许编一条 `n=unk` 冒充读过）。
     * 长度控制在 12 字符内——信令那条 detail 的总预算只有 ~47。
     */
    fun signalingFlags(): String = when {
        !running -> ""
        else -> " n=${if (lastNet == "?") "unk" else lastNet}" +
            " d=${when (lastDoze) { null -> "x"; false -> "0"; true -> "1" }}"
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 共享端会话里已经报过同名 screen_off（role=sharer），这里再报就是两个真值来源
            if (StandbyPolicy.skipScreenEvent(ScreenShareService.isRunning())) return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> emit("screen_off", "live=${liveFlag()} k=${ksec()}")
                Intent.ACTION_SCREEN_ON -> emit("screen_on", "live=${liveFlag()} k=${ksec()}")
            }
        }
    }

    private val dozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) return
            val idle = isIdle(context)
            val prev = lastDoze
            lastDoze = idle
            if (prev == idle) return
            emit(if (idle) "doze_on" else "doze_off", "k=${ksec()}")
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val t = netOf(network)
            emit("net_up", "n=$t k=${ksec()}")
            edgeNet(t)
        }

        override fun onLost(network: Network) {
            // 只报"掉了"不猜原因：默认网切换时 onLost 可能晚于 onAvailable，
            // 靠顺序推断会把一次切换读成两次断网
            emit("net_down", "n=$lastNet k=${ksec()}")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            edgeNet(netToken(caps))
        }
    }

    private fun edgeNet(token: String) {
        val prev = lastNet
        lastNet = token
        if (prev != "?" && prev != token) emit("net_type", "from=$prev to=$token k=${ksec()}")
    }

    /** transport → 4 字符代号：只用来分"Wi-Fi 断了 / 切蜂窝 / 挂了 VPN"，不含任何地址信息 */
    private fun netToken(caps: NetworkCapabilities?): String = when {
        caps == null -> "unk"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bt"
        else -> "unk"
    }

    private fun netOf(network: Network?): String {
        val manager = cm ?: return if (lastNet == "?") "unk" else lastNet
        return runCatching { netToken(manager.getNetworkCapabilities(network ?: manager.activeNetwork)) }
            .getOrDefault("unk")
    }

    private val heartbeatRunner = object : Runnable {
        override fun run() {
            if (!running) return
            val application = app
            if (application != null &&
                StandbyPolicy.shouldHeartbeat(Prefs.paired(application), Presence.sessionBusy)
            ) tick(application)
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val bucketRunner = object : Runnable {
        override fun run() {
            if (!running) return
            pollBucket()
            handler.postDelayed(this, BUCKET_POLL_MS)
        }
    }

    /**
     * 一条心跳。**它的价值不是"我还活着"，而是缺口**：连续两条 `k=` 差 >2×300s ⇒ 中间进程被冻
     * （Doze 深睡）或被杀。发不出去时它照旧进 DiagBacklog，复活后 `at_`（真实时刻）与 `k_`（单调秒）
     * 成对出现，就能算出被冻了多久——这正是"待机掉线"目前唯一缺的那格。
     */
    private fun tick(ctx: Context) {
        val now = ksec()
        val gap = StandbyPolicy.gapSec(lastBeatSec, now)
        lastBeatSec = now
        emit(
            "heartbeat",
            DiagDetail.heartbeat(
                elapsedSec = now,
                gapSec = gap,
                net = netOf(null),
                doze = isIdle(ctx),
                bucket = lastBucket,
                wakeHeld = lastWakeHeld == true,
                signalingUp = runCatching { Presence.client.isConnected }.getOrDefault(false),
            ),
        )
    }

    private fun pollBucket() {
        if (Build.VERSION.SDK_INT < 28) return
        val application = app ?: return
        val bucket = runCatching {
            (application.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
                .appStandbyBucket
        }.getOrDefault(-1)
        if (bucket < 0) return
        val prev = lastBucket
        lastBucket = bucket
        if (prev == bucket) return
        // HyperOS/MIUI 的待机断网几乎都发生在 bucket 掉到 restricted 那一刻，值得单独一行
        emit("bucket_" + StandbyPolicy.bucketName(bucket), "prev=$prev k=${ksec()}")
    }

    /**
     * 上一轮进程为什么死（`ApplicationExitInfo`，API 30+）：只在**首次信令连通**时读一次——
     * socket 没通时发了也发不出去，而这条路径上禁止 `.connect(`（D9）。
     * 这是米15「被 ROM 杀了还是自己睡死」目前唯一的远程答案。
     */
    fun reportPreviousExit(ctx: Context) {
        if (exitRead || Build.VERSION.SDK_INT < 30) return
        exitRead = true
        val application = ctx.applicationContext
        runCatching {
            val am = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // 查自己包名不需要 PACKAGE_USAGE_STATS；空表 = 系统没记下死因，不许编一条
            val latest = am.getHistoricalProcessExitReasons(application.packageName, 0, 1).firstOrNull()
                ?: return
            val ageMin = (System.currentTimeMillis() - latest.timestamp) / 60_000L
            emit("aexit", DiagDetail.exit(latest.reason, latest.importance, ageMin))
        }
    }

    /**
     * 保活锁状态翻转（由 `PresenceService.updateWakeLock` 回报）。
     * acquire 抛异常每次都报——它是"锁没拿到所以 CPU 睡死"的直接证据，不值得被去重吃掉。
     */
    fun onWakeLock(held: Boolean, failed: Boolean) {
        if (!running) return
        if (failed) {
            emit("wake_fail", "k=${ksec()}")
            return
        }
        val prev = lastWakeHeld
        lastWakeHeld = held
        if (prev == held) return
        emit(if (held) "wake_ok" else "wake_off", "k=${ksec()}")
    }

    private fun isIdle(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isDeviceIdleMode
    }.getOrDefault(false)

    private fun liveFlag(): String = if (Presence.sessionBusy) "1" else "0"

    /** 单调秒（自开机起、含深睡）：跨重启会归零，正好用来暴露"发生过一次重启" */
    private fun ksec(): Long = SystemClock.elapsedRealtime() / 1000L

    private fun emit(event: String, detail: String) {
        val application = app ?: return
        // 待机期没有 WebRTC，ice 恒 "-"：这一格不是没埋，是本来就没有
        runCatching { SelfCheck.reportEvent(application, "standby", event, "-", detail) }
    }
}
