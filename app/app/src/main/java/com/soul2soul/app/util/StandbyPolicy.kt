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
     * 补报盲区：`SelfCheck.sendDiag` 只有在 socket **已经被判死**时才会入队。
     * 而"网络刚丢、TCP 还没判死"的几十秒里，`client.send()` 把帧写进内核缓冲就返回 true，
     * 对端根本没收到——10-18 双模拟器实测：客户端 logcat 有 `net_down`，服务器 0 条，
     * 队列里也没有（`signaling_down` 反而到了，因为它发的时候 socket 已判死、抛了异常）。
     * 偏偏"断网在前、信令死在后"这个顺序就是待机断连最要命的一段。
     *
     * 所以待机事件在发出时先留一份副本（[StandbyProbe.replaySuspects]），断连被确认那一刻
     * 补进队列。这里定的是"哪些副本还算数"：
     * - `windowMs` 之外的一律丢掉——环里几十分钟前的正常心跳与这次断连没有因果关系，
     *   补出去只会把死亡窗口稀释掉；
     * - 单调钟倒退（`elapsedRealtime` 归零 = 真发生过重启）也不补，那是新进程的缓存。
     */
    fun shouldReplay(nowMonoMs: Long, eventMonoMs: Long, windowMs: Long): Boolean =
        nowMonoMs >= eventMonoMs && nowMonoMs - eventMonoMs <= windowMs

    /**
     * `getAppStandbyBucket()` 的取值是 10/20/30/40/45（`javap -constants` 对 android-34/35/36
     * 的 `android.app.usage.UsageStatsManager` 实测三档一致：ACTIVE=10、WORKING_SET=20、
     * FREQUENT=30、RARE=40、**RESTRICTED=45**；平台里根本没有 50 这一档）。
     * 写死字面量而不是引用 `UsageStatsManager.STANDBY_BUCKET_*`：单测跑在 mockable android.jar 上，
     * 那里的静态字段全被置 0，用常量会让用例断言出一堆假相等（源码里看不出错）。
     *
     * 落在这五档之外的一律 `x<值>` 而不是猜个名字：ROM 自己塞进来的未知档位如果被读成
     * "restricted"，我们就永远发现不了 HyperOS 在这里干了什么手脚。
     */
    fun bucketName(bucket: Int): String = when (bucket) {
        10 -> "act"
        20 -> "wset"
        30 -> "freq"
        40 -> "rare"
        45 -> "rst"
        else -> "x$bucket"
    }
}
