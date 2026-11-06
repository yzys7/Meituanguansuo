package com.bikeguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 执行层：提醒。
 *
 * L1：高优通知 + 强震动 + 语音播报
 * L2：加响铃 + 全屏界面
 * L3：持续震动，直到用户处理
 *
 * 一条实战经验：语音播报比通知有效得多。骑完车手机大概率在口袋里，
 * 屏幕上的东西根本不会被看到，但播报会让人立刻停下来确认。
 */
object AlertDispatcher {

    private const val TAG = "BikeGuard"
    private const val CH_ID = "bike_lock_guard"
    private const val NOTI_ID = 0x8B1CE
    private const val MAX_RETRY = 6

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var retry = 0

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = c.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CH_ID) != null) return
        // Android 8 才引入 Channel；此处已由 ensureChannel 的版本判断兜住

        val ch = NotificationChannel(CH_ID, "关锁提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "开锁后到达设定时长仍未确认关锁时的强提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 900)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // setBypassDnd(true) 需要 ACCESS_NOTIFICATION_POLICY（用户在"勿扰权限"里授予），
            // 未授予时在部分 ROM 上会直接抛 SecurityException，故按实际情况决定。
            setBypassDnd(canBypassDnd(c))
        }
        mgr.createNotificationChannel(ch)
    }

    private fun canBypassDnd(c: Context): Boolean = runCatching {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return false
        nm.isNotificationPolicyAccessGranted
    }.getOrDefault(false)

    /**
     * @param level     1/2/3，越高越强
     * @param startedAt 开锁时间戳
     * @param elapsedMin 已过分钟数
     */
    fun fireBg(c: Context, level: Int, startedAt: Long, bikeNo: String?, elapsedMin: Int) {
        ensureChannel(c)

        val title = when (level) {
            1 -> "骑行 $elapsedMin 分钟了，关锁了吗？"
            2 -> "⚠ 还没关锁？已计费 $elapsedMin 分钟"
            else -> "⚠⚠ 请立即确认关锁，已 $elapsedMin 分钟"
        }
        val body = buildString {
            append("开锁于 ${clock(startedAt)}")
            bikeNo?.let { append(" · 车辆 $it") }
            append("，至今没收到关锁通知。")
            append("\n已关锁请点「已关锁」；仍在骑行请点「仍在骑行」顺延。")
        }

        val open = PendingIntent.getActivity(
            c, 1,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_FROM_ALERT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ack = PendingIntent.getBroadcast(
            c, 2,
            Intent(c, AckReceiver::class.java).setAction(AckReceiver.ACTION_ACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val riding = PendingIntent.getBroadcast(
            c, 3,
            Intent(c, AckReceiver::class.java).setAction(AckReceiver.ACTION_STILL_RIDING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = Notification.Builder(c, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "已关锁", ack).build())
            .addAction(Notification.Action.Builder(null, "仍在骑行", riding).build())

        if (level >= 2) {
            b.setFullScreenIntent(open, true)   // Android 14+ 需用户在特殊权限里放行
        }

        c.getSystemService(NotificationManager::class.java)?.notify(NOTI_ID, b.build())

        vibrate(c, level)
        speak(c, level, elapsedMin)
    }

    private fun clock(ts: Long): String {
        val f = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        return f.format(java.util.Date(ts))
    }

    private fun vibrate(c: Context, level: Int) {
        val v = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        val pattern = when (level) {
            1 -> longArrayOf(0, 400, 150, 400)
            2 -> longArrayOf(0, 600, 200, 600, 200, 600)
            else -> longArrayOf(0, 800, 200, 800, 200, 800, 200, 800)
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }

    private fun speak(c: Context, level: Int, mins: Int) {
        val text = when (level) {
            1 -> "骑行已经 $mins 分钟，请确认是否已关锁"
            2 -> "请确认单车是否已关锁，已经计费 $mins 分钟"
            else -> "警告，单车可能仍在计费，请立即确认关锁"
        }
        val app = c.applicationContext

        if (tts == null) {
            // 首次初始化是异步的：这一次发不出声，但会在初始化完成后补上。
            // 否则第一次骑行（最需要提醒的那次）恰好是哑的。
            tts = TextToSpeech(app) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = Locale.CHINA
                Log.i(TAG, "tts init status=$status ready=$ttsReady")
            }
        }
        if (!ttsReady) {
            // 还在初始化：稍等片刻再试，次数有限，避免卡住主线程
            if (retry < MAX_RETRY) {
                retry++
                Handler(Looper.getMainLooper()).postDelayed({ speak(c, level, mins) }, 600)
                Log.i(TAG, "tts pending, retry #$retry")
            } else {
                Log.w(TAG, "tts not ready, give up speaking")
                retry = 0
            }
            return
        }
        retry = 0

        // 走 STREAM_ALARM：静音/振动模式下仍能出声，这是关键
        val params = android.os.Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_ALARM)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "bikeguard-$level")
    }

    fun cancel(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTI_ID)
        tts?.stop()
        retry = 0
    }
}

class AckReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ACK -> BikeSessionEngine.userConfirmed(context)
            ACTION_STILL_RIDING -> BikeSessionEngine.extendRiding(context)
        }
    }

    companion object {
        const val ACTION_ACK = "com.bikeguard.ACK"
        const val ACTION_STILL_RIDING = "com.bikeguard.STILL_RIDING"
    }
}
