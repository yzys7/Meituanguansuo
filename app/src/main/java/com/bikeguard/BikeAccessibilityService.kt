package com.bikeguard

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 第二信号源：无障碍。用于补通知监听的盲区 ——
 * 美团把不少单车状态只放在页面内（骑行计时页、行程结束页），不推通知。
 *
 * 代价是脆弱：页面结构一变就失效。所以这里的策略是"只取文本、交给同一个
 * 解析器"，命中就产出 Signal，不命中就当没发生，不影响主链路。
 *
 * 注意：部分 App 会检测无障碍服务。开启后如遇美团异常，先关掉这一层验证。
 */
class BikeAccessibilityService : AccessibilityService() {

    private var lastHash = 0
    private var lastAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in BikeNotificationListener.TARGETS) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        val text = StringBuilder()
        collect(event.source, text, 0)
        val flat = text.toString()
        if (flat.isBlank()) return

        // 页面内容变化极频繁，做一次去重 + 节流
        val h = flat.hashCode()
        val now = System.currentTimeMillis()
        if (h == lastHash && now - lastAt < 5_000) return
        lastHash = h
        lastAt = now

        val sig = SignalParser.parse(flat) ?: return
        Log.i("BikeGuard", "a11y -> ${sig.javaClass.simpleName}")
        BikeSessionEngine.onSignal(this, sig)
    }

    private fun collect(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 14 || sb.length > 600) return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), sb, depth + 1)
        }
    }

    override fun onInterrupt() {}
}
