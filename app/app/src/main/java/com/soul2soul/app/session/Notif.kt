package com.soul2soul.app.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

object Notif {
    const val CH_PRESENCE = "presence"
    const val CH_SESSION = "session"
    const val CH_CALL = "call"

    /**
     * 留言渠道的 id。**不许改回 `"msg"`**：Android 8+ 已建渠道的声音与档位由系统侧持有，
     * `createNotificationChannel` 只补建、不覆盖已有渠道——沿用旧 id 时，装过 0.2.64 的手机
     * 永远停在"静音"，只有卸载重装才响（10-23 用户改口径「留言做成带声音的」，靠新 id + 删旧渠道生效）。
     */
    const val CH_MSG = "msg2"

    /** 0.2.64 的静音渠道（IMPORTANCE_LOW，用户 10-20 定口径「静静躺在通知里」），已被上面那条取代 */
    const val CH_MSG_SILENT = "msg"

    const val ID_PRESENCE = 1
    const val ID_SESSION = 2
    const val ID_CALL = 3
    const val ID_MSG = 4
    const val ID_RECALL = 5

    /** 26 以下没有渠道概念，声音只能挂在通知本身上（Mi5/API 24 走这条） */
    fun defaultSoundUri(): android.net.Uri? =
        runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) }.getOrNull()

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
        // 未读留言：出声、不横幅弹出（IMPORTANCE_DEFAULT 响但不 heads-up；HIGH 会盖出横幅）
        val msg = NotificationChannel(CH_MSG, "留言提醒", NotificationManager.IMPORTANCE_DEFAULT)
        defaultSoundUri()?.let { uri ->
            msg.setSound(
                uri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        nm.createNotificationChannel(msg)
        // 旧静音渠道必须删掉，否则系统设置里留下两条「留言提醒」，她分不清关哪条
        nm.deleteNotificationChannel(CH_MSG_SILENT)
    }
}
