package com.bikeguard

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 第一信号源：通知监听。无需 root，是整个方案性价比最高的一层。
 *
 * 覆盖两种入口：
 *  - com.sankuai.meituan       美团 App（单车业务在主 App 内，无独立客户端）
 *  - com.eg.android.AlipayGphone  支付宝扫码骑美团单车（很多人走这条路径）
 * 如果常用微信小程序，再加 com.tencent.mm，但小程序通知极少，收益有限。
 */
class BikeNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected, rules=${SignalParser.ruleCount}")
        // 记下连接时间：设置页据此显示"监听是活着还是已经死了"。
        // 国产 ROM 上通知监听被静默掐断是最常见的失效原因，没有这个就完全看不出来。
        getSharedPreferences("health", MODE_PRIVATE)
            .edit().putLong("lastConnect", System.currentTimeMillis()).apply()
        SignalLog.event(this, "CONNECT", "监听服务已连接")
        // 服务被系统重启后，从磁盘恢复未完结的骑行会话
        BikeSessionEngine.restore(this)
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "listener disconnected")
        SignalLog.event(this, "DISCONNECT", "监听服务被断开，提醒可能失效")
        // 请求系统重连；部分 ROM 会拒绝，但这是唯一能做的
        runCatching { requestRebind(android.content.ComponentName(this, BikeNotificationListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isTarget(sbn.packageName)) return
        val n = sbn.notification ?: return
        val e = n.extras

        // 美团/支付宝的骑行通知都是标准样式，TITLE + TEXT + BIG_TEXT 足够覆盖。
        // 部分机型会发 MessagingStyle 通知，正文在 EXTRA_MESSAGES 里，
        // 但解析它的 Message.getMessageFromBundle 是 @hide API，SDK 编译不到，
        // 公开的 EXTRA_TEXT 已经能拿到同一段文字，故不做展开。
        val flat = buildString {
            append(e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "").append('\n')
            append(e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "").append('\n')
            append(e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: "").append('\n')
            append(e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: "").append('\n')
        }

        val sig = SignalParser.parse(flat)
        SignalLog.write(this, sbn.packageName, sbn.id, flat, sig)
        sig?.let { BikeSessionEngine.onSignal(this, it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isTarget(sbn.packageName)) return
        // 只关心"常驻不可划掉"的骑行中通知：它消失基本等价于订单结束。
        // 普通通知被用户划掉也会走到这里，由引擎侧判断会话是否匹配。
        BikeSessionEngine.onSignal(this, Signal.OngoingNoticeGone(sbn.id, sbn.tag))
    }

    companion object {
        private const val TAG = "BikeGuard"

        /** 精确包名 */
        val TARGETS = setOf(
            "com.sankuai.meituan",
            "com.eg.android.AlipayGphone",
            "com.tencent.mm"
        )

        /**
         * 放宽到前缀匹配：美团的单车通知在部分版本/渠道由子包发出
         * （com.sankuai.meituan.*）。原来只认主包，开锁收到了、
         * 关锁漏掉，表现就是"关锁后还在计时"。
         */
        fun isTarget(pkg: String): Boolean {
            if (pkg in TARGETS) return true
            return pkg.startsWith("com.sankuai.") ||
                pkg.startsWith("com.eg.android.Alipay") ||
                pkg.startsWith("com.tencent.mm")
        }

        /** 最近一次监听连接的时间，0 表示从未连上 */
        fun lastConnectedAt(c: android.content.Context): Long =
            c.getSharedPreferences("health", android.content.Context.MODE_PRIVATE)
                .getLong("lastConnect", 0L)
    }
}
