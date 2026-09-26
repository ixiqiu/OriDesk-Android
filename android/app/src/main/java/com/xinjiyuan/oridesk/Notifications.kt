package com.xinjiyuan.oridesk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService

/**
 * 通知渠道与通知构建（方案 B：通知由**我们自己**发，所以角标可控）。
 *
 * 两条通知：
 * - 工单通知：每个工单一个通知 id（同一工单的新消息**更新**原通知，不再堆一条），
 *   并用 `setNumber(未读数)` 把数字交给启动器角标。
 * - 前台服务常驻通知：Android 要求前台服务必须可见，同时也是"推送还活着"的指示灯。
 */
object Notifications {

    const val CHANNEL_TICKETS = "oridesk_tickets"
    const val SERVICE_NOTIFICATION_ID = 1

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_TICKETS,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 发一条工单通知。
     *
     * [badgeNumber] 是 E1 返回的 `awaiting`（"需要我处理的"数量）。用它做
     * `setNumber`，启动器角标就会显示真实待办数，而不是"有几条未读通知"——
     * 后者会在通知被划掉后归零，与真实待办脱节。
     */
    fun showTicket(
        context: Context,
        ticketId: Long,
        title: String,
        body: String,
        badgeNumber: Int,
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_TICKETS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setAutoCancel(true)
            .setNumber(badgeNumber)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(ticketIntent(context, ticketId))
            .build()

        post(context, notificationId(ticketId), notification)
    }

    /** 前台服务的常驻通知（点击回到主界面）。 */
    fun buildServiceNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_TICKETS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_service_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent(context))
            .build()

    private fun post(context: Context, id: Int, notification: Notification) {
        // POST_NOTIFICATIONS 在 Android 13+ 是运行时权限。没授权时 notify 会被静默丢弃
        // （不抛异常），所以这里不做 try/catch 式的"补救"——权限引导放在界面上。
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (t: SecurityException) {
            // 未授予通知权限。忽略：用户下次进设置页会看到提示。
        }
    }

    private fun ticketIntent(context: Context, ticketId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(UrlRules.ticketDeepLink(ticketId))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId(ticketId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 工单号直接用通知 id：同一工单的新消息会更新原通知，不会堆成一片。 */
    fun notificationId(ticketId: Long): Int = (ticketId % Int.MAX_VALUE).toInt()
}
