package com.bikeguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 骑行中的常驻状态通知（状态栏可见、不可划掉）。
 *
 * 为什么需要：
 *  1) 用户要能看到"现在到底有没有在计时" —— 之前只能进 App 才看得见，
 *     而 App 里的状态还可能因为进程重启读到旧值。
 *  2) 它是唯一能跨进程存活的"状态证明"。进程被杀、prefs 丢了，
 *     这条通知还在，用户至少知道自己处于骑行会话中。
 *
 * 计时用 setUsesChronometer(true)：**由系统 UI 自己渲染**，
 * 我们不需要每秒刷新，进程死了它也照走。这是关键——
 * 用 Handler 每秒改通知文本的方案，进程一死就停了。
 */
object RideStatusNotifier {

    private const val TAG = "BikeGuard"
    private const val CH_ID = "ride_status"
    private const val NOTI_ID = 0x8B1CF

    fun ensureChannel(c: Context) {
        val mgr = c.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CH_ID) != null) return
        // IMPORTANCE_LOW：常驻但不出声、不弹横幅，只是安静地待在那
        val ch = NotificationChannel(CH_ID, "骑行状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "骑行进行中时的常驻提示，关锁后自动消失"
            setShowBadge(false)
            enableVibration(false)
        }
        mgr.createNotificationChannel(ch)
    }

    fun show(c: Context, startedAt: Long, minutes: Int, bikeNo: String?) {
        ensureChannel(c)

        val open = PendingIntent.getActivity(
            c, 11,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ack = PendingIntent.getBroadcast(
            c, 12,
            Intent(c, AckReceiver::class.java).setAction(AckReceiver.ACTION_ACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val due = startedAt + minutes * 60_000L
        val text = buildString {
            append("开锁 ${clock(startedAt)}")
            bikeNo?.let { append(" · 车 $it") }
            append(" · ${clock(due)} 仍未关锁就提醒你")
        }

        val b = Notification.Builder(c, CH_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("骑行中")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\n关锁后这条会自动消失。"))
            .setWhen(startedAt)
            .setUsesChronometer(true)   // 系统渲染的走秒计时
            .setShowWhen(true)
            .setOngoing(true)           // 不可划掉
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "已关锁", ack).build())

        c.getSystemService(NotificationManager::class.java)?.notify(NOTI_ID, b.build())
        Log.i(TAG, "status notification shown")
    }

    /** 到点提醒时撤掉，交给告警通知接管，避免两条并排 */
    fun clear(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTI_ID)
    }

    private fun clock(ts: Long) =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
}
