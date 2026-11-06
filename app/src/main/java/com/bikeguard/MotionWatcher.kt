package com.bikeguard

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log

/**
 * 第三信号源：运动状态。只在"骑行会话存活期间"注册定位，会话结束立即注销，
 * 避免常驻 GPS 带来的耗电与后台限制问题。
 *
 * 判据设计（关键）：
 *  "静止"本身区分不了"等红灯"和"下车走人"。真正有区分力的是
 *  "停过 → 又重新移动"，且移动后离停车点越来越远 —— 这就是人车分离。
 *
 * 所以维护两个量：
 *  stillMs       ：连续低速时长
 *  walkAway      ：首次静止点（parkFix）到当前位置的距离
 */
object MotionWatcher {

    private const val TAG = "BikeGuard"
    private const val MIN_INTERVAL_MS = 20_000L
    private const val MIN_DISTANCE_M = 15f
    private const val MOVING_STEP_M = 25f      // 单次位移超过它 → 仍在移动
    private const val MIN_PARK_MS = 45_000L    // 停稳 45s 后再移动才算"下车"

    private var lm: LocationManager? = null
    private var listener: LocationListener? = null

    private var lastFix: Location? = null
    private var parkFix: Location? = null
    private var stillSince = 0L
    private var walkAway = 0.0

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (listener != null) return
        val mgr = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (loc.accuracy > 60f) return   // 丢掉漂移点
                handle(ctx, loc)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("legacy") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }

        runCatching {
            mgr.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, MIN_INTERVAL_MS, MIN_DISTANCE_M, l, Looper.getMainLooper()
            )
            mgr.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, MIN_INTERVAL_MS, MIN_DISTANCE_M, l, Looper.getMainLooper()
            )
        }.onFailure { Log.w(TAG, "location start fail", it) }

        lm = mgr
        listener = l
        Log.i(TAG, "motion watcher on")
    }

    fun stop(ctx: Context) {
        listener?.let { runCatching { lm?.removeUpdates(it) } }
        listener = null
        lm = null
        lastFix = null
        parkFix = null
        stillSince = 0L
        walkAway = 0.0
        Log.i(TAG, "motion watcher off")
    }

    private fun handle(ctx: Context, loc: Location) {
        val prev = lastFix
        val step = prev?.distanceTo(loc) ?: 0f
        val now = System.currentTimeMillis()

        if (step > MOVING_STEP_M) {
            // 停稳过一段时间后又动了 → 判定为下车步行离开
            if (stillSince != 0L && now - stillSince >= MIN_PARK_MS) {
                walkAway = parkFix?.distanceTo(loc)?.toDouble() ?: 0.0
            }
            stillSince = 0L
            parkFix = null
        } else if (stillSince == 0L) {
            stillSince = now
            parkFix = loc
        }

        val stillMs = if (stillSince == 0L) 0L else now - stillSince
        val sep = if (walkAway > 0) walkAway else (parkFix?.distanceTo(loc)?.toDouble() ?: 0.0)

        lastFix = loc
        BikeSessionEngine.onSignal(
            ctx,
            Signal.Motion(moving = step > MOVING_STEP_M, stillMs = stillMs, walkAwayMeters = sep)
        )
    }
}
