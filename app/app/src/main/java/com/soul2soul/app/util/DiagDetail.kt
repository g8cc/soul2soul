package com.soul2soul.app.util

/**
 * `diag.*` 的 detail 编码器（纯函数，无 Android 依赖，所以能在 JVM 里逐边界测）。
 *
 * 为什么要单独一层：服务器把 `detail` 过 `safeTag`——非 `[\w.-]` 一律换成 `_`，然后
 * **静默截断到 64**（没有溢出标记）。而补报路径还会在前面再加 `at=<epochMs> `（约 17 字符），
 * 所以真正能安全使用的预算只有 ~47。用 `key=value` 的自然写法两条就撑爆，
 * 于是要么被裁掉一半、要么把 `=` 磨成 `_` 让 grep 口径变脏。这里定的语法只依赖
 * 逗号与数字（`safeTag` 会连逗号一起换成 `_`，但每一位仍然对得上），并在编码阶段就夹紧，
 * 保证"被截断"不会成为读不出数的原因。
 */
object DiagDetail {

    /** 补报后仍然安全的长度：64 - len("at=" + 13位毫秒 + " ") */
    const val QUEUED_BUDGET = 47

    /** 一次 QoS 采样的原始读数（未夹紧）。-1/NaN 表示"这一格没有" */
    data class Qos(
        val rttMs: Long,
        val path: String,
        val availableKbps: Long,
        val edge: Long,
        val videoLossPct: Double,
        val videoJitterMs: Double,
        val videoFps: Double,
        val audioLossPct: Double,
        val audioJitterMs: Double,
        val audioConcealedMs: Double,
    )

    /**
     * `q=<rtt>,<选路>,<可用kbps>,<长边>,<视频丢包×10>,<视频抖动>,<视频帧率>,<音频丢包×10>,<音频抖动>,<音频消隐>`
     * 例：`q=29,h-s,1200,1598,5,21,29,0,3,0`（丢包保留一位小数×10，抖动/消隐按整数毫秒截断）
     *
     * 上限（超出即夹，读日志时按"≥上限"理解，不是精确值）：rtt/抖动/消隐 999ms、可用带宽 99999kbps、
     * 边长 9999px、帧率 999fps、丢包 100.0%。夹这么狠不是省事——`detail` 总共只有 ~47 可用字符，
     * 少一个上限就会被 `safeTag` 从尾部**静默**裁掉，裁掉的是哪一格没人知道。
     */
    fun qos(r: Qos): String =
        "q=${ms(r.rttMs)},${pathCode(r.path)},${kbps(r.availableKbps)},${r.edge.coerceIn(0, 9999)}," +
            "${pctX10(r.videoLossPct)},${ms(r.videoJitterMs)},${fps(r.videoFps)}," +
            "${pctX10(r.audioLossPct)},${ms(r.audioJitterMs)},${ms(r.audioConcealedMs)}"

    /** 选路 `local->remote` 压成两个字母：h=host s=srflx r=relay o=其它/未知 */
    fun pathCode(path: String): String {
        val parts = path.split("->")
        val local = parts.firstOrNull().orEmpty()
        val remote = parts.getOrNull(1).orEmpty()
        return "${typeCode(local)}-${typeCode(remote)}"
    }

    /**
     * `candidate.sdp` 里的 `typ <kind>` → 计数下标（0=host 1=srflx 2=relay 3=prflx）。
     * 只认这四种子串，认不出返回 -1 让调用方丢弃：宁缺一条计数，也不把未知类型塞进 relay 里，
     * 那会把"TURN 分配失败"这个结论伪造出来。
     */
    fun candidateIndex(sdp: String): Int = when {
        sdp.contains("typ host") -> 0
        sdp.contains("typ srflx") -> 1
        sdp.contains("typ relay") -> 2
        sdp.contains("typ prflx") -> 3
        else -> -1
    }

    /** ICE 收集完成时的本地 candidate 类型分布：`h2s1r0p0`，`r0` = TURN 一个中继都没拿到 */
    fun gather(host: Int, srflx: Int, relay: Int, prflx: Int): String =
        "h=${host.coerceAtMost(99)} s=${srflx.coerceAtMost(99)} " +
            "r=${relay.coerceAtMost(99)} p=${prflx.coerceAtMost(99)}"

    private fun typeCode(t: String) = when (t.lowercase()) {
        "host" -> "h"
        "srflx" -> "s"
        "relay" -> "r"
        else -> "o"
    }

    private fun kbps(v: Long): String = if (v < 0) "x" else v.coerceAtMost(99999).toString()

    /** 丢包率放大 10 倍保一位小数；-1/NaN 表示这一格没读数（不许拿 0 冒充"没有丢包"） */
    fun pctTenths(v: Double): String =
        if (!v.isFinite() || v < 0) "x" else (v * 10).toInt().coerceAtMost(1000).toString()

    private fun pctX10(v: Double): String = pctTenths(v)

    private fun ms(v: Double): String =
        if (!v.isFinite() || v < 0) "x" else v.toInt().coerceAtMost(999).toString()

    /** 整数毫秒读数（rtt 这类本来就是整数）：负数=没读数，不许渲染成 "-1"（`-` 会被磨成 `_`） */
    fun msCap(v: Long): String = if (v < 0) "x" else v.coerceAtMost(999).toString()

    private fun ms(v: Long): String = msCap(v)

    private fun fps(v: Double): String =
        if (!v.isFinite() || v < 0) "x" else v.toInt().coerceAtMost(999).toString()

    /**
     * 上一次进程死因（`ApplicationExitInfo`，API 30+）：只报枚举号与相对年龄。
     * `description`/`status` 是自由文本（可能含路径），按隐私线一律不读；
     * `subReason` 在 compileSdk 34 的 android.jar 里没有，不假装能报。
     */
    fun exit(reason: Int, importance: Int, ageMin: Long): String =
        "r=$reason i=$importance a=${ageMin.coerceIn(0, 99999)}"

    /**
     * 待机心跳：`k` 单调秒锚点（elapsedRealtime/1000）、`g` 与上一条心跳的间隔秒（`x`=本进程首条）、
     * 之后是网络/Doze/bucket/锁/信令快照。
     *
     * `g` 必须是单调差而不是 wall-clock 差：设备时钟实测漂移 ~10.5 小时，用 `currentTimeMillis`
     * 相减会把"一直活着"读成"消失了半天"，整条判据就废了。
     */
    fun heartbeat(
        elapsedSec: Long,
        gapSec: Long,
        net: String,
        doze: Boolean,
        bucket: Int,
        wakeHeld: Boolean,
        signalingUp: Boolean,
    ): String = "k=${elapsedSec.coerceAtMost(9999999)},g=${if (gapSec < 0L) "x" else gapSec.coerceAtMost(99999)}" +
        ",n=${net.ifEmpty { "unk" }.take(4)},d=${if (doze) 1 else 0}" +
        ",b=${if (bucket < 0) "x" else bucket.coerceAtMost(99)}" +
        ",w=${if (wakeHeld) 1 else 0},s=${if (signalingUp) 1 else 0}"
}
