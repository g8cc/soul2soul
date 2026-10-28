package com.soul2soul.app.session

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.soul2soul.app.MainActivity
import com.soul2soul.app.R

object SessionStatusNotifier {
    fun recovering(ctx: Context, role: String, text: String) {
        NotificationManagerCompat.from(ctx).cancel(Notif.ID_SESSION_STATUS_ENDED)
        notify(ctx, role, text, ongoing = true, silent = true)
    }

    fun ended(ctx: Context, role: String, text: String) {
        val manager = NotificationManagerCompat.from(ctx)
        manager.cancel(Notif.ID_SESSION_STATUS)
        manager.cancel(Notif.ID_SESSION_STATUS_ENDED)
        notify(
            ctx,
            role,
            text,
            ongoing = false,
            silent = false,
            id = Notif.ID_SESSION_STATUS_ENDED,
            openSession = false,
        )
    }

    fun clear(ctx: Context) {
        NotificationManagerCompat.from(ctx).apply {
            cancel(Notif.ID_SESSION_STATUS)
            cancel(Notif.ID_SESSION_STATUS_ENDED)
        }
    }

    private fun notify(
        ctx: Context,
        role: String,
        text: String,
        ongoing: Boolean,
        silent: Boolean,
        id: Int = Notif.ID_SESSION_STATUS,
        openSession: Boolean = true,
    ) {
        val manager = NotificationManagerCompat.from(ctx)
        if (!manager.areNotificationsEnabled()) return
        val target = if (role == "viewer" && openSession) {
            Intent(ctx, SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            ctx,
            if (role == "viewer") 61 else 62,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx, Notif.CH_SESSION_STATUS)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(ctx.getString(R.string.session_status_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setSilent(silent)
        if (!ongoing) builder.setTimeoutAfter(12_000L)
        if (!silent && android.os.Build.VERSION.SDK_INT < 26) {
            Notif.defaultSoundUri()?.let { builder.setSound(it) }
        }
        runCatching { manager.notify(id, builder.build()) }
    }
}
