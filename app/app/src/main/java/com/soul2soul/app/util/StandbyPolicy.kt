package com.soul2soul.app.util

/**
 * 待机探针的纯判定层（零 Android 依赖，所以能在 JVM 里逐条测）。
 * 把"该不该报 / 报成什么名字"从回调里抽出来，是因为这类判定留在 `BroadcastReceiver`
 * 内部就永远只能靠真机验证，而真机正是我们接不上的那一台（米15）。
 */
object StandbyPolicy {

    /**
     * 心跳只在"已配对且不在会话中"时发。
     * 未配对期 `diag.*` 会被服务器在未鉴权 socket 上直接丢掉（发了等于没发）；
     * 会话期已有 10s 的 `qos_tick`，再叠一条心跳只是噪声。
     */
    fun shouldHeartbeat(paired: Boolean, sessionBusy: Boolean): Boolean = paired && !sessionBusy

    /**
     * 共享端自己的 receiver 已经发过 `screen_off`（role=sharer），探针不得再发一遍：
     * 同一件事两个真值来源，读日志的人就得先猜哪条是真的。
     */
    fun skipScreenEvent(isSharerLive: Boolean): Boolean = isSharerLive

    /**
     * 两次心跳之间的单调秒差。首次（或 elapsedRealtime 倒退 = 重启）没有可比对象 ⇒ -1（编码成 `x`）。
     * 这里**不许**换成 `System.currentTimeMillis()` 相减：设备时钟实测漂移 ~10.5 小时（Mi5），
     * wall-clock 相减会把"进程一直活着"读成"消失了半天"。
     */
    fun gapSec(prevElapsedSec: Long, elapsedSec: Long): Long =
        if (prevElapsedSec <= 0L || elapsedSec < prevElapsedSec) -1L else elapsedSec - prevElapsedSec

    /** 缺口判据：大于 2 个周期 ⇒ 进程被冻结或被杀（宁可判松，误报一次就没人信这套了） */
    fun gapSuspect(gapSec: Long, expectedSec: Long): Boolean = gapSec > expectedSec * 2

    /**
     * `getAppStandbyBucket()` 的返回值是 10/20/30/40/50。写死字面量而不是引用
     * `UsageStatsManager.STANDBY_BUCKET_*`：单测跑在 mockable android.jar 上，
     * 那里的静态字段全被置 0，用常量会让用例断言出一堆假相等（源码里看不出错）。
     */
    fun bucketName(bucket: Int): String = when (bucket) {
        10 -> "act"
        20 -> "wset"
        30 -> "freq"
        40 -> "rare"
        50 -> "rst"
        else -> "x$bucket"
    }
}
