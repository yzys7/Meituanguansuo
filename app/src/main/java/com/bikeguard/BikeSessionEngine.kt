package com.bikeguard

import android.content.Context
import android.util.Log

/**
 * 骑行会话看门狗（通知配对模型）
 *
 * ── 前提（实测，2026-11）────────────────────────────────────
 * 美团单车的开锁与关锁都会在通知栏各推一条：
 *   开锁：开锁成功，正在骑行中 🚲 / 点击返回App查看订单信息，快速关锁还车~
 *   关锁：行程结束，已为您关锁结费 / 您已成功关锁还车，点此查看行程费。
 *
 * 于是整个判定可以归结为一次"配对"：同一笔订单应该先后收到这两条。
 * 只收到前一条、迟迟等不到后一条 —— 就是忘记关锁。
 *
 * ── 时间轴 ──────────────────────────────────────────────────
 *   T0            收到开锁通知 → arm()，起 50 分钟倒计时
 *   T0+Δ          收到关锁通知 → disarm()，正常结束，全程无打扰
 *   T0+50min      仍未收到关锁通知 → 闹钟触发 → 强提醒
 *   T0+55min      二次提醒
 *   T0+60min      三次提醒，之后停止（平台此刻已自动结单）
 *
 * 正常骑行（10–30 分钟）会走 T0+Δ 这条路径，用户完全感知不到这个 App 存在。
 * 只有 50 分钟还没关锁，它才会出声。这是本方案能长期用下去的关键：
 * 不打扰，才不会被卸载。
 *
 * ── 为什么是 50 分钟 ────────────────────────────────────────
 * 平台在车辆连续静止 60 分钟时自动结束订单并停止计费。定在 50 分钟，
 * 留出 10 分钟处置窗口：发现、返回车位、上锁、或者联系客服都来得及。
 * 定在 60 分钟之后就没有意义了，那时只能申诉。
 *
 * ── 唯一需要容忍的误报 ──────────────────────────────────────
 * 单次骑行本身超过 50 分钟（长距离、跨区），闹钟会在路上响。
 * 这种情况点通知上的「仍在骑行」顺延 30 分钟即可。
 */
object BikeSessionEngine {

    private const val TAG = "BikeGuard"
    private const val PREF = "ride_session"

    /** 开锁后多久提醒。50 分钟 = 平台 60 分钟自动结单前留 10 分钟处置窗口 */
    const val DEFAULT_REMIND_MIN = 50

    /** 到点后逐级提醒的间隔（分钟），长度即提醒次数 */
    private val ESCALATE_MIN = intArrayOf(5, 5)

    /** 点「仍在骑行」后顺延的时长 */
    private const val STILL_RIDING_EXTEND_MIN = 30

    /** 同一次骑行的多条开锁通知，在这个窗口内不重置计时 */
    private const val DEDUP_MS = 5 * 60_000L

    /** 超过这个时长视作早已自动结单，直接丢弃会话 */
    private const val STALE_MS = 120 * 60_000L

    enum class Phase { IDLE, ARMED, FIRED }

    fun onSignal(ctx: Context, s: Signal) {
        val c = ctx.applicationContext
        when (s) {
            is Signal.RideStarted -> arm(c, s)
            is Signal.RideEnded -> {
                // 没有进行中的会话时，结束信号只可能是误报（外卖订单之类的
                // 营销通知命中了 END 规则）。忽略并留痕，不碰任何状态。
                if (prefs(c).getString(KEY_PHASE, null) != null) {
                    disarm(c, "收到结束信号 amount=${s.amount}")
                } else {
                    SignalLog.event(c, "END-IGNORED", "无进行中会话，忽略结束信号")
                }
            }
            is Signal.ServerNudge -> escalateNow(c, "平台提示仍在计费")
            // 以下信号 v2 不参与判定，保留分支仅为接口稳定
            is Signal.RideOngoing -> Unit
            is Signal.OngoingNoticeGone -> Unit
            is Signal.Motion -> Unit
        }
    }

    // ── 武装 ─────────────────────────────────────────────────

    private fun arm(c: Context, s: Signal.RideStarted) {
        val p = prefs(c)
        val now = System.currentTimeMillis()

        if (p.getString(KEY_PHASE, null) == Phase.ARMED.name) {
            val st = p.getLong(KEY_STARTED, 0L)
            if (now - st in 0..DEDUP_MS) {
                Log.i(TAG, "dup open notice, timer kept")
                return
            }
        }

        val minutes = p.getInt(KEY_MINUTES, DEFAULT_REMIND_MIN)
        val fireAt = s.at + minutes * 60_000L

        // 这里必须用 commit() 而不是 apply()：
        // 通知监听是后台上下文，落地瞬间进程就可能被 ROM 回收。
        // apply() 只是排一次异步落盘，进程被 SIGKILL 时这次写会丢，
        // 结果就是"到点了什么都读不到、静默不响"。
        p.edit()
            .putString(KEY_PHASE, Phase.ARMED.name)
            .putLong(KEY_STARTED, s.at)
            .putLong(KEY_DUE, fireAt)          // 到期时刻独立存：顺延后也会跟着变
            .putString(KEY_BIKE, s.bikeNo)
            .putInt(KEY_LEVEL, 1)
            .commit()

        // 状态栏常驻提示：让"在计时"这件事可见，且能跨进程存活
        runCatching { RideStatusNotifier.show(c, s.at, minutes, s.bikeNo) }

        AlarmScheduler.scheduleAt(c, fireAt, s.at, minutes)
        SignalLog.event(c, "ARM", "阈值 ${minutes}min，计划 ${clock(fireAt)} 触发")
        Log.i(TAG, "armed: fire in ${(fireAt - now) / 60_000} min, bike=${s.bikeNo}")
    }

    // ── 触发 ─────────────────────────────────────────────────

    /**
     * 由 AlarmReceiver 调用。
     *
     * @param alarmStartedAt / alarmMinutes 闹钟 Intent 里自带的会话信息。
     *        这是最后一道保险：prefs 万一没了，靠它也能响。
     */
    fun onAlarmFired(c: Context, alarmStartedAt: Long = 0L, alarmMinutes: Int = 0) {
        val p = prefs(c)
        var ph = p.getString(KEY_PHASE, null)
        var st = p.getLong(KEY_STARTED, 0L)
        var minutes = p.getInt(KEY_MINUTES, DEFAULT_REMIND_MIN)

        // 状态丢了但闹钟记得 —— 据 extras 恢复，绝不静默。
        if ((ph == null || st <= 0L) && alarmStartedAt > 0L) {
            st = alarmStartedAt
            if (alarmMinutes > 0) minutes = alarmMinutes
            ph = Phase.ARMED.name
            p.edit()
                .putString(KEY_PHASE, ph)
                .putLong(KEY_STARTED, st)
                .putInt(KEY_MINUTES, minutes)
                .putInt(KEY_LEVEL, 1)
                .commit()
            SignalLog.event(c, "RECOVER", "状态丢失，按闹钟携带信息恢复")
        }

        if (ph != Phase.ARMED.name && ph != Phase.FIRED.name) {
            SignalLog.event(c, "FIRE-SKIPPED", "无会话且闹钟无状态，忽略")
            Log.i(TAG, "alarm fired but no active session, ignore")
            return
        }

        if (st <= 0L) return

        // 时钟被调整导致提前触发时，补一次闹钟而不是误报。
        // 用"到期时刻"判断而不是阈值，顺延过的会话才不会提前响。
        val due = dueOf(p)
        if (System.currentTimeMillis() < due - 60_000L) {
            AlarmScheduler.scheduleAt(c, due, st, minutes)
            return
        }

        val level = p.getInt(KEY_LEVEL, 1)
        val elapsedMin = ((System.currentTimeMillis() - st) / 60_000L).toInt()

        RideStatusNotifier.clear(c)   // 交给告警通知接管，别并排两条
        AlertDispatcher.fireBg(c, level, st, p.getString(KEY_BIKE, null), elapsedMin)
        SignalLog.event(c, "FIRE", "第 ${level} 级，已 ${elapsedMin}min")
        Log.i(TAG, "ALERT level=$level elapsed=${elapsedMin}min")

        val next = level - 1
        if (next < ESCALATE_MIN.size) {
            p.edit()
                .putString(KEY_PHASE, Phase.FIRED.name)
                .putInt(KEY_LEVEL, level + 1)
                .commit()
            AlarmScheduler.scheduleAfter(c, ESCALATE_MIN[next] * 60_000L, st, minutes)
        } else {
            p.edit().putString(KEY_PHASE, Phase.FIRED.name).commit()
        }
    }

    /**
     * 平台已经明确推送"长时间未移动，还在计费"——这是实锤，跳过时间窗直接最高级。
     * 通常发生在开锁 25 分钟左右，能比 50 分钟的兜底更早救你。
     */
    private fun escalateNow(c: Context, reason: String) {
        val p = prefs(c)
        if (p.getString(KEY_PHASE, null) == null) return
        val st = p.getLong(KEY_STARTED, 0L)
        if (st <= 0L) return

        p.edit().putString(KEY_PHASE, Phase.FIRED.name).putInt(KEY_LEVEL, 3).commit()
        AlarmScheduler.cancel(c)
        RideStatusNotifier.clear(c)

        val elapsedMin = ((System.currentTimeMillis() - st) / 60_000L).toInt()
        AlertDispatcher.fireBg(c, 3, st, p.getString(KEY_BIKE, null), elapsedMin)
        SignalLog.event(c, "FIRE", "平台催还，直接第 3 级（$reason）")
        Log.i(TAG, "ESCALATE now: $reason")
    }

    // ── 解除 ─────────────────────────────────────────────────

    fun userConfirmed(c: Context) = disarm(c, "用户确认已关锁")

    /** 用户点「仍在骑行」，顺延一个窗口 */
    fun extendRiding(c: Context) {
        val p = prefs(c)
        val st = p.getLong(KEY_STARTED, 0L)
        if (st <= 0L) return
        val next = System.currentTimeMillis() + STILL_RIDING_EXTEND_MIN * 60_000L
        p.edit()
            .putString(KEY_PHASE, Phase.ARMED.name)
            .putInt(KEY_LEVEL, 1)
            .putLong(KEY_DUE, next)
            .commit()
        AlarmScheduler.scheduleAt(c, next, st, ((next - st) / 60_000L).toInt())
        AlertDispatcher.cancel(c)
        runCatching { RideStatusNotifier.show(c, st, ((next - st) / 60_000L).toInt(), p.getString(KEY_BIKE, null)) }
        SignalLog.event(c, "EXTEND", "顺延 ${STILL_RIDING_EXTEND_MIN}min 到 ${clock(next)}")
        Log.i(TAG, "extended by ${STILL_RIDING_EXTEND_MIN}min")
    }

    private fun disarm(c: Context, reason: String) {
        // 注意：不能 clear()。KEY_MINUTES 是用户配置，clear 会把它一起清掉，
        // 导致"为了调试改成 2 分钟，一次正常关锁后又变回 50"。
        prefs(c).edit()
            .remove(KEY_PHASE)
            .remove(KEY_STARTED)
            .remove(KEY_DUE)
            .remove(KEY_BIKE)
            .remove(KEY_LEVEL)
            .commit()
        AlarmScheduler.cancel(c)
        AlertDispatcher.cancel(c)
        RideStatusNotifier.clear(c)
        SignalLog.event(c, "DISARM", reason)
        Log.i(TAG, "disarm: $reason")
    }

    // ── 恢复 ─────────────────────────────────────────────────

    /**
     * AlarmManager 的闹钟在重启后全部丢失，所以 BOOT_COMPLETED 必须调用这里重算。
     * 同时覆盖 App 被系统杀掉后重新拉起的情况。
     */
    fun restore(c: Context) {
        val p = prefs(c)
        val ph = p.getString(KEY_PHASE, null) ?: return
        if (ph == Phase.IDLE.name) return

        val st = p.getLong(KEY_STARTED, 0L)
        if (st <= 0L) return

        val elapsed = System.currentTimeMillis() - st
        if (elapsed > STALE_MS) {
            disarm(c, "会话过期 ${elapsed / 60_000}min")
            return
        }

        val due = dueOf(p)
        if (System.currentTimeMillis() >= due) {
            SignalLog.event(c, "RESTORE", "已过到期时间，立即触发")
            Log.w(TAG, "restore: already past deadline, fire now")
            onAlarmFired(c, st, p.getInt(KEY_MINUTES, DEFAULT_REMIND_MIN))
        } else {
            AlarmScheduler.scheduleAt(c, due, st, p.getInt(KEY_MINUTES, DEFAULT_REMIND_MIN))
            // 进程重启后状态通知可能已经不在了，补一条
            runCatching { RideStatusNotifier.show(c, st, p.getInt(KEY_MINUTES, DEFAULT_REMIND_MIN), p.getString(KEY_BIKE, null)) }
            SignalLog.event(c, "RESTORE", "重算闹钟，剩余 ${(due - System.currentTimeMillis()) / 60_000}min")
            Log.i(TAG, "restore: rearmed")
        }
    }

    /** 到期时刻：优先用存的 KEY_DUE，老数据缺这个字段时按阈值推算 */
    private fun dueOf(p: android.content.SharedPreferences): Long {
        val due = p.getLong(KEY_DUE, 0L)
        if (due > 0L) return due
        val st = p.getLong(KEY_STARTED, 0L)
        return st + p.getInt(KEY_MINUTES, DEFAULT_REMIND_MIN) * 60_000L
    }

    // ── 状态查询（给设置页用）─────────────────────────────────

    /** 当前会话快照；无会话时为 null */
    data class Snapshot(
        val startedAt: Long,
        val due: Long,
        val minutes: Int,
        val phase: Phase,
        val bikeNo: String?
    )

    fun status(c: Context): Snapshot? {
        val p = prefs(c)
        val phName = p.getString(KEY_PHASE, null) ?: return null
        val ph = runCatching { Phase.valueOf(phName) }.getOrNull() ?: return null
        if (ph == Phase.IDLE) return null
        val st = p.getLong(KEY_STARTED, 0L)
        if (st <= 0L) return null
        return Snapshot(
            st, dueOf(p), p.getInt(KEY_MINUTES, DEFAULT_REMIND_MIN), ph, p.getString(KEY_BIKE, null)
        )
    }

    /** 用户从提醒点进 App 看了一眼；只记录，不解除 */
    fun markSeen(c: Context) {
        Log.i(TAG, "alert opened by user")
    }

    // ── 配置 ─────────────────────────────────────────────────

    fun setRemindMinutes(c: Context, minutes: Int) {
        prefs(c).edit().putInt(KEY_MINUTES, minutes.coerceIn(5, 100)).commit()
        Log.i(TAG, "remind window = $minutes min")
    }

    fun remindMinutes(c: Context) = prefs(c).getInt(KEY_MINUTES, DEFAULT_REMIND_MIN)

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun clock(ts: Long) =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

    private const val KEY_PHASE = "phase"
    private const val KEY_STARTED = "startedAt"
    private const val KEY_DUE = "due"
    private const val KEY_BIKE = "bike"
    private const val KEY_LEVEL = "level"
    private const val KEY_MINUTES = "minutes"
}
