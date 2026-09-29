package com.soul2soul.app.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * 会话期间的 WiFi 高性能锁：阻止 WiFi 芯片进入省电休眠。
 * 老机型（如小米5）在省电模式下 UDP 实时流会周期性断流/掉线——这正是"总是断开"的高发原因之一。
 * 仅在会话期间持有，结束即释放，不影响日常续航。
 */
object WifiKeeper {

    private var lock: WifiManager.WifiLock? = null

    fun acquire(ctx: Context) {
        if (lock != null) return
        runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            lock = wm.createWifiLock(mode, "soul2soul:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { android.util.Log.w("WifiKeeper", "acquire failed", it) }
    }

    fun release() {
        runCatching { lock?.release() }
        lock = null
    }
}
