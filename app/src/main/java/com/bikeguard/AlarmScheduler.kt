package com.bikeguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 闹钟调度。50 分钟的等待必须交给 AlarmManager，不能用 Handler.postDelayed：
 * 进程随时会被系统回收，postDelayed 一定会丢。
 *
 * 用 ELAPSED_REALTIME_WAKEUP 而不是 RTC_WAKEUP：
 * 前者基于开机时长，不受用户改时间/跨时区影响，对"50 分钟后"这种
 * 相对时长才是正确的语义。代价是重启后闹钟失效，由 BootReceiver 重算。
 *
 * ── 2026-11-05 关键修复 ─────────────────────────────────────
 * 原来闹钟只带一个空 Intent，到点后完全依赖 SharedPreferences 判断
 * "有没有会话"。一旦状态没保住（进程被 ROM 杀掉、apply() 还没落盘），
 * 到点就会静默返回 —— 用户看到的是"时间到了但什么都没发生"。
 *
 * 现在把 startedAt / minutes 直接塞进 PendingIntent 的 extras：
 * 闹钟自己就带着"这次该提醒谁"，即使 prefs 空了也能响。
 */
object AlarmScheduler {

    private const val TAG = "BikeGuard"
    private const val REQ = 0x8B1CE

    const val EXTRA_STARTED = "startedAt"
    const val EXTRA_MINUTES = "minutes"

    fun scheduleAt(c: Context, wallClockAt: Long, startedAt: Long, minutes: Int) {
        val delay = (wallClockAt - System.currentTimeMillis()).coerceAtLeast(0L)
        scheduleAfter(c, delay, startedAt, minutes)
    }

    fun scheduleAfter(c: Context, delayMs: Long, startedAt: Long, minutes: Int) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        val pi = pending(c, startedAt, minutes)

        // Android 12+ 精确闹钟需要 SCHEDULE_EXACT_ALARM，用户可撤销。
        // 被撤销时降级为 setAndAllowWhileIdle —— Doze 下可能延迟十几分钟，
        // 但总比完全不响好。
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Log.w(TAG, "exact alarm denied, fallback to inexact")
            }
        }.onFailure { Log.e(TAG, "schedule failed", it) }

        Log.i(TAG, "alarm set: +${delayMs / 60_000} min (exact=$canExact)")
    }

    fun cancel(c: Context) {
        runCatching { c.getSystemService(AlarmManager::class.java)?.cancel(pending(c, 0L, 0)) }
    }

    private fun pending(c: Context, startedAt: Long, minutes: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, REQ,
            Intent(c, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_ALARM)
                .putExtra(EXTRA_STARTED, startedAt)
                .putExtra(EXTRA_MINUTES, minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** 闹钟到点，交给看门狗决定是提醒还是重新武装 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_ALARM) {
            BikeSessionEngine.onAlarmFired(
                context.applicationContext,
                intent.getLongExtra(AlarmScheduler.EXTRA_STARTED, 0L),
                intent.getIntExtra(AlarmScheduler.EXTRA_MINUTES, 0)
            )
        }
    }

    companion object {
        const val ACTION_ALARM = "com.bikeguard.ALARM"
    }
}

/**
 * AlarmManager 的闹钟在重启后全部丢失，所以开机必须重算剩余时间。
 * MY_PACKAGE_REPLACED 覆盖 App 升级后闹钟失效的情况。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i("BikeGuard", "boot/replace -> restore watchdog")
                BikeSessionEngine.restore(context.applicationContext)
            }
        }
    }
}
