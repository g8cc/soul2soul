package com.soul2soul.app.msg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.PendingIntent
import android.content.Intent
import com.soul2soul.app.R
import com.soul2soul.app.session.Notif

/**
 * 通知栏常驻未读留言条数（用户选的形态：静音、不横幅、读完消失）。
 * plan 是纯决策（JVM 单测锁定）；sync 负责落到系统通知。
 * 调用方：PresenceService 挂 InboxStore 监听 + MessageActivity 前后台切换。
 */
object UnreadNotifier {

    enum class Action { Show, Hide, Keep }

    /** 数字没变不重复 notify（防刷屏闪烁）；0 且本来就没显示也不动作 */
    fun plan(shownCount: Int, unread: Int): Action = when {
        unread <= 0 -> if (shownCount > 0) Action.Hide else Action.Keep
        unread == shownCount -> Action.Keep
        else -> Action.Show
    }

    /** 留言页在前台时视同已读：屏上正看着，通知栏不该再挂一条催 */
    @Volatile
    var suppressed = false

    private var shownCount = 0

    fun sync(ctx: Context) {
        val unread = if (suppressed) 0 else InboxStore.received().size
        when (plan(shownCount, unread)) {
            Action.Keep -> return
            Action.Hide -> {
                shownCount = 0
                NotificationManagerCompat.from(ctx).cancel(Notif.ID_MSG)
            }
            Action.Show -> {
                shownCount = unread
                val nm = NotificationManagerCompat.from(ctx)
                if (!nm.areNotificationsEnabled()) return // 权限没给就只留主页角标
                runCatching { nm.notify(Notif.ID_MSG, build(ctx, unread)) }
            }
        }
    }

    private fun build(ctx: Context, count: Int): android.app.Notification {
        val tap = PendingIntent.getActivity(
            ctx, 25,
            Intent(ctx, MessageActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (count == 1) ctx.getString(R.string.msg_unread_one)
        else ctx.getString(R.string.msg_unread_many, count)
        return NotificationCompat.Builder(ctx, Notif.CH_MSG)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText(ctx.getString(R.string.msg_unread_tap))
            .setNumber(count)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
    }
}
