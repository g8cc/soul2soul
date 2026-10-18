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
 */
object DiagBacklog {

    private const val MAX_ENTRIES = 200
    /** 超过这个体积才做整表修剪，平时一条 append（调用方可能在主线程，不能每次读全文） */
    private const val TRIM_BYTES = 96 * 1024
    private const val FLUSH_BATCH = 20
    private const val FILE_NAME = "s2s-diag-backlog.ndjson"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "s2s-diag-backlog").apply { isDaemon = true }
    }
    private val lock = Any()

    /** 入队：调用方已判定"发不出去"，这里只做本地落盘，绝不碰 socket、绝不触发重连 */
    fun enqueue(ctx: Context, type: String, body: JSONObject) {
        runCatching {
            val line = JSONObject().put("t", type).put("b", withAt(body)).toString()
            synchronized(lock) {
                val f = file(ctx)
                if (f.exists() && f.length() > TRIM_BYTES) writeAll(ctx, readLines(ctx) + line)
                else f.appendText(line + "\n")
            }
        }
    }

    /**
     * 信令复活后补报（PresenceService.onState(true) 触发）。
     * 先把取走的批次从文件里删掉再发：崩溃时宁可少几条，也不重复刷同一事件。
     */
    fun flush(ctx: Context) {
        runCatching {
            io.execute {
                runCatching {
                    val lines = synchronized(lock) { readLines(ctx) }
                    if (lines.isEmpty()) return@runCatching
                    val batch = lines.take(FLUSH_BATCH)
                    synchronized(lock) { writeAll(ctx, lines.drop(FLUSH_BATCH)) }
                    val failedAt = batch.indexOfFirst { !replay(ctx, it) }
                    if (failedAt >= 0) synchronized(lock) {
                        writeAll(ctx, batch.drop(failedAt) + readLines(ctx))
                    }
                }
            }
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

    private fun writeAll(ctx: Context, lines: List<String>) {
        runCatching {
            val keep = if (lines.size > MAX_ENTRIES) lines.takeLast(MAX_ENTRIES) else lines
            if (keep.isEmpty()) file(ctx).delete() else file(ctx).writeText(keep.joinToString("\n") + "\n")
        }
    }
}
