package com.soul2soul.app.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notif {
    const val CH_PRESENCE = "presence"
    const val CH_SESSION = "session"
    const val CH_CALL = "call"
    const val ID_PRESENCE = 1
    const val ID_SESSION = 2
    const val ID_CALL = 3

    fun ensureChannels(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 26) return // 26 以下无通知渠道概念，NotificationCompat 优先级兜底
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_PRESENCE, "在线待命", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SESSION, "屏幕共享", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALL, "呼叫", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
