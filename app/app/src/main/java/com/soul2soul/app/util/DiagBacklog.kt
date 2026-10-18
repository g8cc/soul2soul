package com.soul2soul.app.util

import android.content.Context
import com.soul2soul.app.session.Presence
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * 断连期埋点补报（米15 远程取证的唯一通路）：`SelfCheck.sendDiag` 要求信令已连，
 * 于是恰好瞎在最需要的那一瞬间——米15 不在本机、接不了 adb，锁屏后它做了什么
 * （FGS 还在不在、采集有没有被 ROM 拆掉、终止原因是什么）只能靠
 * "事件先落本机文件，信令复活后重放"才能到服务器。
 *
 * 重放复用线上那版就已认识的 `diag.event` 通道，服务器零改动。
 * 事件真实时刻带在 detail 前缀 `at=<epochMs>`：diag 报文没有独立时间字段，
 * 而服务器行的时间是"补报到达时刻"，不是事件发生时刻——不带这个前缀就等于没报。
 *
 * v0.2.59 起这个队列不再只是"能存"：待机和通话期都会产读数，量比原来大一个数量级，
 * 所以这里做了三件事——分级淘汰（死亡瞬间的行不许被心跳/QoS 挤掉）、一次复活多排几批
 * （抖动期每 17~104 秒重连一次，每次只发 20 条等于永远排不空）、入队离开调用方线程
 * （广播接收器与 ICE 回调都可能落在主线程）。
 */
object DiagBacklog {

    /** 超过这个体积才做整表修剪，平时一条 append */
    private const val TRIM_BYTES = 96 * 1024
    private const val FLUSH_BATCH = 20
    /** 单次复活最多排空几批：宁可多跑几轮，也不要在一次 onState 里占着 io 线程不放 */
    private const val FLUSH_MAX_BATCHES = 5
    private const val FLUSH_MAX = FLUSH_BATCH * FLUSH_MAX_BATCHES
    private const val FILE_NAME = "s2s-diag-backlog.ndjson"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "s2s-diag-backlog").apply { isDaemon = true }
    }
    private val lock = Any()

    /**
     * 入队：调用方已判定"发不出去"，这里只做本地落盘，绝不碰 socket、绝不触发重连。
     * 文件 I/O 挪到 io 线程——广播接收器/ICE 回调调用时可能在主线程。
     */
    fun enqueue(ctx: Context, type: String, body: JSONObject) {
        val line = runCatching {
            JSONObject().put("t", type).put("b", withAt(body)).toString()
        }.getOrNull() ?: return
        val app = ctx.applicationContext
        runCatching {
            io.execute {
                runCatching {
                    synchronized(lock) {
                        val f = file(app)
                        if (f.exists() && f.length() > TRIM_BYTES) writeAll(app, readLines(app) + line)
                        else f.appendText(line + "\n")
                    }
                }
            }
        }
    }

    /**
     * 信令复活后补报（PresenceService.onState(true) 触发）。
     * 先把取走的批次从文件里删掉再发：崩溃时宁可少几条，也不重复刷同一事件。
     * 一次复活连排 [FLUSH_MAX_BATCHES] 批，直到发不出去或队列空——抖动期 200 条积压
     * 若每次只清 20 条，需要十次重连才出得来，而那时现场早凉了。
     */
    fun flush(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { io.execute { runCatching { drain(app) } } }
    }

    private fun drain(ctx: Context) {
        val taken = synchronized(lock) {
            val lines = readLines(ctx)
            if (lines.isEmpty()) emptyList()
            else {
                val head = lines.take(FLUSH_MAX)
                writeAll(ctx, lines.drop(head.size))
                head
            }
        }
        if (taken.isEmpty()) return
        var failedAt = -1
        for (i in taken.indices) {
            if (!replay(ctx, taken[i])) { failedAt = i; break }
        }
        if (failedAt >= 0) synchronized(lock) {
            // 失败那条及其后的未发送部分塞回头部，保持原时序
            writeAll(ctx, taken.drop(failedAt) + readLines(ctx))
        }
    }

    /**
     * 还原成 `diag.event` 发出。会话终态也走这条：服务器的 `[diag.session]` 行不打 detail，
     * 时刻会被丢掉，而 `[diag.event]` 行打，所以统一降级成 event + `at/why/dur` 三要素。
     */
    private fun replay(ctx: Context, line: String): Boolean {
        val parsed = runCatching { JSONObject(line) }.getOrNull() ?: return true
        val type = parsed.optString("t")
        val body = parsed.optJSONObject("b") ?: return true
        if (!Presence.client.isConnected) return false
        val at = body.optLong("at", 0L)
        val ice = body.optString("ice", "-")
        val detail = if (type == "diag.session") {
            "at=$at why=${body.optString("reason", "-")} dur=${body.optLong("liveMs", -1L)}"
        } else {
            "at=$at ${body.optString("detail", "-")}"
        }
        return Presence.client.send("diag.event") {
            put("role", body.optString("role", "-"))
            put("event", if (type == "diag.session") "session_end" else body.optString("event", "-"))
            put("ice", ice)
            put("fg", body.optBoolean("fg", false))
            put("screenOn", body.optBoolean("screenOn", true))
            put("app", body.optString("app", "-"))
            put("detail", detail.take(64))
        }
    }

    private fun withAt(body: JSONObject): JSONObject {
        val copy = JSONObject()
        body.keys().forEach { k -> copy.put(k, body.get(k)) }
        copy.put("at", System.currentTimeMillis())
        return copy
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    private fun readLines(ctx: Context): List<String> = runCatching {
        val f = file(ctx)
        if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }.getOrDefault(emptyList())

    /**
     * 分级淘汰后整表重写（淘汰规则在 `BacklogTiers`，纯函数、有 JVM 单测锁）。
     * 条目上限（[BacklogTiers.MAX_ENTRIES]）比体积闸先到：一行 ~200 字节，200 行才 40KB < 96KB，
     * 所以 TRIM_BYTES 只是"什么时候值得整表重写"的触发器，真正的容量控制是分级配额。
     */
    private fun writeAll(ctx: Context, lines: List<String>) {
        runCatching {
            val kept = BacklogTiers.trim(lines)
            if (kept.isEmpty()) file(ctx).delete() else file(ctx).writeText(kept.joinToString("\n") + "\n")
        }
    }
}
