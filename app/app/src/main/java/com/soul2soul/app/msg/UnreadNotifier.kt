package com.soul2soul.app.msg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.PendingIntent
import android.content.Intent
import com.soul2soul.app.R
import com.soul2soul.app.session.Notif

/**
 * 通知栏常驻未读留言（v0.2.31 选的形态：静音、不横幅、读完消失；#82 起正文就是留言本身）。
 * plan 是纯决策（JVM 单测锁定）；sync 负责落到系统通知。
 * 调用方：PresenceService 挂 InboxStore 监听 + MessageActivity 前后台切换。
 */
object UnreadNotifier {

    enum class Action { Show, Hide, Keep }

    /** 展开态放得下的行数：再多只留最近的（InboxStyle 在多数 ROM 上本来就只画 5 行） */
    const val MAX_LINES = 5

    /** 用"是哪几条"而不是"有几条"当签名：读完一条又来一条时条数相同、内容不同，
     *  只比条数会让通知栏停在上一条的预览上（这一条才是 #82 的判据） */
    fun signature(unread: List<Msg>): String? =
        if (unread.isEmpty()) null else unread.joinToString(",") { it.id }

    fun plan(shownSig: String?, unread: List<Msg>): Action {
        val next = signature(unread)
        return when {
            next == null -> if (shownSig != null) Action.Hide else Action.Keep
            next == shownSig -> Action.Keep
            else -> Action.Show
        }
    }

    /** 语音不转文字，只报时长（向上取整；0/负数不给假读数） */
    fun voiceSec(durMs: Int): Int = if (durMs <= 0) 0 else (durMs + 999) / 1000

    /** 通知正文只有一行：换行与连续空白压成单个空格（留言里塞满换行会把通知撑爆） */
    fun flatten(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    fun bodyLines(unread: List<Msg>): List<Msg> =
        if (unread.size <= MAX_LINES) unread else unread.subList(unread.size - MAX_LINES, unread.size)

    /** 留言页在前台时视同已读：屏上正看着，通知栏不该再挂一条催 */
    @Volatile
    var suppressed = false

    private var shownSig: String? = null

    fun sync(ctx: Context) {
        val unread = if (suppressed) emptyList() else InboxStore.received()
        when (plan(shownSig, unread)) {
            Action.Keep -> return
            Action.Hide -> {
                shownSig = null
                NotificationManagerCompat.from(ctx).cancel(Notif.ID_MSG)
            }
            Action.Show -> {
                shownSig = signature(unread)
                val nm = NotificationManagerCompat.from(ctx)
                if (!nm.areNotificationsEnabled()) return // 权限没给就只留主页角标
                runCatching { nm.notify(Notif.ID_MSG, build(ctx, unread)) }
            }
        }
    }

    private fun lineOf(ctx: Context, m: Msg): String =
        if (m.kind == "voice") ctx.getString(R.string.msg_voice_line, voiceSec(m.durMs))
        else flatten(m.text)

    private fun build(ctx: Context, unread: List<Msg>): android.app.Notification {
        val count = unread.size
        val title = if (count == 1) ctx.getString(R.string.msg_unread_one)
        else ctx.getString(R.string.msg_unread_many, count)
        // 折叠态正文=最新一条；空白正文（服务端不该发，但畸形条目会）退回"点我查看"
        val newest = lineOf(ctx, unread.last())
        val tap = PendingIntent.getActivity(
            ctx, 25,
            Intent(ctx, MessageActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(ctx, Notif.CH_MSG)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText(newest.ifEmpty { ctx.getString(R.string.msg_unread_tap) })
            .setNumber(count)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(tap)
        if (count > 1) {
            val style = NotificationCompat.InboxStyle()
            for (m in bodyLines(unread)) style.addLine(lineOf(ctx, m))
            b.setStyle(style)
        }
        return b.build()
    }
}
