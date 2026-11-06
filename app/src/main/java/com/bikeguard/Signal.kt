package com.bikeguard

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一信号模型。采集器只生产 Signal，判定引擎只消费 Signal。
 *
 * 真正参与判定的只有三个：
 *   RideStarted  —— 开锁通知到达，建立会话并起 50 分钟倒计时
 *   RideEnded    —— 关锁通知到达，撤销倒计时
 *   ServerNudge  —— 平台催还推送，提前触发
 */
sealed class Signal {
    abstract val at: Long

    /** 开锁成功，骑行开始 */
    data class RideStarted(val bikeNo: String?, override val at: Long = now()) : Signal()

    /** 关锁成功 / 行程结束：计费已停止 */
    data class RideEnded(val amount: String?, override val at: Long = now()) : Signal()

    /** 订单仍在计费中的心跳 */
    data class RideOngoing(override val at: Long = now()) : Signal()

    /** 平台侧催还提醒："长时间未移动，还在计费" */
    data class ServerNudge(override val at: Long = now()) : Signal()

    /** 常驻通知被移除（当前未使用） */
    data class OngoingNoticeGone(val id: Int, val tag: String?, override val at: Long = now()) : Signal()

    /** 运动/位置状态（当前未使用，保留扩展位） */
    data class Motion(
        val moving: Boolean,
        val stillMs: Long,
        val walkAwayMeters: Double,
        override val at: Long = now()
    ) : Signal()

    companion object {
        fun now() = System.currentTimeMillis()
    }
}

/**
 * 文案 → 信号 的规则表。基于 2026-11 实测的两条真实推送校准：
 *
 * ── 开锁 ──────────────────────────────────────────────
 *   标题：开锁成功，正在骑行中 🚲
 *   正文：点击返回App查看订单信息，快速关锁还车~
 *
 * ── 关锁 ──────────────────────────────────────────────
 *   标题：行程结束，已为您关锁结费
 *   正文：您已成功关锁还车，点此查看行程费。
 *
 * ── 三个必须注意的坑 ──────────────────────────────────
 * 1) 开锁标题里含"骑行中"。若让 ONGOING 规则先匹配，开锁信号会被吞掉，
 *    会话根本建立不起来 —— 所以 START 必须排在 ONGOING 之前。
 *
 * 2) 开锁正文里含"关锁还车"这四个字（"快速关锁还车~"）。
 *    所以 END 绝对不能用"关锁还车"这种宽规则，否则开锁瞬间就被判定为结束，
 *    倒计时根本不会启动。这里只能用"成功关锁还车"这类带限定词的写法。
 *
 * 3) END 里不能放"本次骑行 X 元"，它在开锁通知的营销文案里也会命中。
 *
 * 改动这两组规则后，务必用真实样本回放验证：把日志里的原文逐条喂进 parse()，
 * 确认开锁只出 RideStarted、关锁只出 RideEnded。
 */
object SignalParser {

    /** 平台催还：出现即实锤未关锁，不必等 50 分钟 */
    private val NUDGE = listOf(
        Regex("长时间未移动"),
        Regex("还在计费"),
        Regex("点我去还车"),
        Regex("尚未关锁"),
        Regex("未成功关锁")
    )

    /** 关锁通知。注意所有规则都不能命中开锁正文里的"快速关锁还车" */
    // 2026-11-05 删掉"待支付"：美团外卖/团购的订单提醒常含这个词，
    // 骑行期间来一条就会把会话误杀（开锁了但会话立刻被撤销）。
    // 真实的单车关锁文案是"行程结束，已为您关锁结费"，用不着这么宽的规则。
    private val END = listOf(
        Regex("行程结束"),
        Regex("成功关锁还车"),
        Regex("关锁结费"),
        Regex("已为您关锁"),
        Regex("还车成功"),
        Regex("已结束计费")
    )

    /** 开锁通知 */
    private val START = listOf(
        Regex("开锁成功"),
        Regex("解锁成功"),
        Regex("已开锁"),
        Regex("车锁已打开"),
        Regex("开始骑行")
    )

    /** 兜底用，正常情况下不会走到 */
    private val ONGOING = listOf(
        Regex("正在骑行"),
        Regex("骑行中")
    )

    private val AMOUNT = Regex("""(\d+(?:\.\d+)?)\s*元""")
    private val BIKE_NO = Regex("""(?:车辆|单车|车号|编号)[^\d]{0,4}(\d{6,12})""")

    val ruleCount: Int get() = NUDGE.size + END.size + START.size + ONGOING.size

    fun parse(text: String): Signal? {
        if (text.isBlank()) return null

        if (NUDGE.any { it.containsMatchIn(text) }) return Signal.ServerNudge()

        if (END.any { it.containsMatchIn(text) }) {
            val amt = AMOUNT.find(text)?.groupValues?.getOrNull(1)
            return Signal.RideEnded(amt)
        }
        if (START.any { it.containsMatchIn(text) }) {
            val bike = BIKE_NO.find(text)?.groupValues?.getOrNull(1)
            return Signal.RideStarted(bike)
        }
        if (ONGOING.any { it.containsMatchIn(text) }) return Signal.RideOngoing()

        return null
    }

    /** 调试用：列出命中的规则，方便对齐文案 */
    fun explain(text: String): String = when {
        NUDGE.any { it.containsMatchIn(text) } -> "NUDGE"
        END.any { it.containsMatchIn(text) } -> "END"
        START.any { it.containsMatchIn(text) } -> "START"
        ONGOING.any { it.containsMatchIn(text) } -> "ONGOING"
        else -> "UNMATCHED"
    }
}

/**
 * 全量落盘。规则对不对，全靠这份日志回放。
 * 位置：/data/data/com.bikeguard/files/signal-YYYYMMDD.jsonl
 */
object SignalLog {

    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val tsFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun write(ctx: Context, pkg: String, id: Int, text: String, sig: Signal?) {
        runCatching {
            val f = File(ctx.filesDir, "signal-${dayFmt.format(Date())}.jsonl")
            val o = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("ts", tsFmt.format(Date()))
                .put("pkg", pkg)
                .put("id", id)
                .put("sig", sig?.javaClass?.simpleName ?: "UNMATCHED")
                .put("hit", SignalParser.explain(text))
                .put("text", text.replace("\n", " ⏎ "))
            f.appendText(o.toString() + "\n")
        }.onFailure { Log.w("BikeGuard", "log fail", it) }
    }

    /**
     * App 自己的动作也写进同一份日志：ARM / DISARM / FIRE / CONNECT / DISCONNECT。
     *
     * 为什么必须有：原来日志只记"收到什么通知"，用户看不到"闹钟有没有设上、
     * 有没有响过"，于是完全无法判断是没触发还是没收到关锁信号。
     * 现在一条时间轴就能回答这个问题。
     */
    fun event(ctx: Context, kind: String, detail: String = "") {
        runCatching {
            val f = File(ctx.filesDir, "signal-${dayFmt.format(Date())}.jsonl")
            val o = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("ts", tsFmt.format(Date()))
                .put("pkg", "@self")
                .put("id", 0)
                .put("sig", kind)
                .put("hit", kind)
                .put("text", detail)
            f.appendText(o.toString() + "\n")
        }.onFailure { Log.w("BikeGuard", "event log fail", it) }
    }
}
