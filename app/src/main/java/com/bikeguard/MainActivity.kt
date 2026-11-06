package com.bikeguard

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置与状态页。整个 App 只有这一屏，装完只需要做三件事：
 *   1) 授予"通知使用权"（必需的，否则收不到开锁通知）
 *   2) 允许通知、允许精确闹钟
 *   3) 加进电池优化白名单（可选，但强烈建议）
 *
 * 界面用代码搭而不是 XML：少一堆资源文件，改起来也直观。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var permBox: LinearLayout
    private lateinit var minutesInput: EditText
    private lateinit var parseInput: EditText
    private lateinit var parseResult: TextView
    private lateinit var logText: TextView
    private lateinit var diagText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏全屏弹出（到点提醒升级时），保证亮屏可见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 从提醒点进来立刻刷新状态，但不自动清除会话 ——
        // 用户可能只是来看一眼，误判关锁反而会丢掉提醒。
        if (intent?.getBooleanExtra(EXTRA_FROM_ALERT, false) == true) {
            BikeSessionEngine.markSeen(this)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(title("关锁卫士"))
        root.addView(sub("开锁后 N 分钟仍未收到关锁通知，就响铃提醒"))
        root.addView(space(18))

        root.addView(section("当前状态"))
        statusText = body("").apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(statusText)
        root.addView(button("模拟一次开锁（调试用）") { simulateStart() })
        root.addView(space(6))
        root.addView(button("我已关锁 / 清除当前会话") { clearSession() })

        root.addView(space(20))
        root.addView(section("权限"))
        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(permBox)

        root.addView(space(20))
        root.addView(section("提醒设置"))

        minutesInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(BikeSessionEngine.remindMinutes(this@MainActivity).toString())
            hint = "分钟"
        }
        root.addView(labelled("开锁后提醒时长（分钟，建议 50）", minutesInput))
        root.addView(button("保存") {
            val v = minutesInput.text.toString().toIntOrNull()
            if (v == null || v !in 5..100) {
                toast("请输入 5–100 之间的整数")
            } else {
                BikeSessionEngine.setRemindMinutes(this, v)
                toast("已保存：$v 分钟")
                refresh()
            }
        })
        root.addView(space(6))
        root.addView(button("立即测试一次提醒") { testAlert() })
        root.addView(hint("测试不受计时影响，会直接弹通知 + 震动 + 语音。"))

        root.addView(space(20))
        root.addView(section("文案自测"))
        root.addView(hint("点下面的按钮填入真实文案，不用手打。解析结果不对就说明规则要改。"))
        parseInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            gravity = Gravity.TOP
            hint = "开锁成功，正在骑行中 🚲 …"
        }
        root.addView(parseInput)

        // 之前只有一个空输入框，用户直接点「解析」得到 UNMATCHED，会误以为规则坏了。
        // 这里给两个一键填入，把最容易搞错的顺序问题变成一次点击可验证。
        val samples = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        samples.addView(Button(this).apply {
            text = "填入开锁示例"; textSize = 13f
            setOnClickListener { parseInput.setText(SAMPLE_START); parseResult.text = "" }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        samples.addView(Button(this).apply {
            text = "填入关锁示例"; textSize = 13f
            setOnClickListener { parseInput.setText(SAMPLE_END); parseResult.text = "" }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(samples)

        root.addView(button("解析") {
            val txt = parseInput.text.toString().trim()
            if (txt.isEmpty()) {
                parseResult.text = "输入为空 —— 空字符串当然匹配不到任何规则。\n" +
                    "点上面「填入开锁示例」，再点一次解析。"
            } else {
                val hit = SignalParser.explain(txt)
                val sig = SignalParser.parse(txt)?.javaClass?.simpleName ?: "null"
                parseResult.text = "命中规则：$hit\n产出信号：$sig\n长度：${txt.length} 字符"
            }
        })
        parseResult = body("").apply { setPadding(0, dp(8), 0, 0) }
        root.addView(parseResult)

        root.addView(space(20))
        root.addView(section("运行诊断"))
        diagText = body("").apply { setPadding(0, dp(6), 0, 6) }
        root.addView(diagText)

        root.addView(space(20))
        root.addView(section("事件与通知日志"))
        root.addView(hint("跨天读取。前 30 条，倒序。ARM=设置闹钟，FIRE=响了，DISARM=取消。"))
        logText = body("").apply { textSize = 11f }
        root.addView(logText)
        root.addView(button("刷新日志") { refreshLog() })
        root.addView(space(6))
        root.addView(button("分享日志文件") { shareLog() })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshLog()
        // 每秒走一次：之前只在 onResume 刷一次，
        // 通知在你没退出页面时到达，界面就一直停在"空闲"，
        // 看起来像"开锁了但没有开始计时"。
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderState()
            ticker.postDelayed(this, 1000)
        }
    }

    // ── 状态与权限 ────────────────────────────────────────────

    /** 只更新状态与诊断文字，重建权限行太重，放到 refresh() 里 */
    private fun renderState() {
        val st = BikeSessionEngine.status(this)
        statusText.text = when {
            st == null -> "空闲：没有进行中的骑行会话。\n收到开锁通知后会自动开始计时。"
            else -> buildString {
                val elapsed = (System.currentTimeMillis() - st.startedAt) / 1000
                append("进行中：开锁于 ${time(st.startedAt)}\n")
                append("已过 ${fmt(elapsed)}")
                st.bikeNo?.let { append(" · 车 $it") }
                append('\n')
                when {
                    st.phase == BikeSessionEngine.Phase.FIRED ->
                        append("已提醒过，等待你确认（点「我已关锁」结束）")
                    else -> {
                        val remain = ((st.due - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                        append("${clockOf(st.due)} 提醒，还剩 ${fmt(remain)}")
                    }
                }
            }
        }

        // 诊断：不看这一行，就没法判断"到底有没有在工作"
        val lastConnect = BikeNotificationListener.lastConnectedAt(this)
        val connectedNow = listenerOn()
        diagText.text = buildString {
            append("监听服务：")
            if (!connectedNow) {
                append("未授权（收不到任何通知）")
            } else if (lastConnect <= 0L) {
                append("已授权，但还没连上")
            } else {
                val ago = (System.currentTimeMillis() - lastConnect) / 60_000
                append("已连接（最近一次 ${time(lastConnect)}，${ago} 分钟前）")
            }
            append('\n')

            append("提醒阈值：${BikeSessionEngine.remindMinutes(this@MainActivity)} 分钟\n")

            append("闹钟状态：")
            if (st == null) {
                append("未设置（没有进行中的骑行）")
            } else {
                append("${clockOf(st.due)} 触发（状态栏有「骑行中」通知）")
            }
        }
    }

    private fun fmt(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "%d:%02d".format(m, s)
    }

    private fun refresh() {
        renderState()

        permBox.removeAllViews()
        permBox.addView(permRow(
            "通知使用权",
            listenerOn(),
            "去开启",
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        ))
        permBox.addView(permRow(
            "通知权限",
            notificationsOn(),
            "去开启",
            null
        ))
        permBox.addView(permRow(
            "精确闹钟",
            exactAlarmOn(),
            "去开启",
            exactAlarmIntent()
        ))
        permBox.addView(permRow(
            "忽略电池优化",
            batteryFree(),
            "去设置",
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
        ))
    }

    private fun permRow(name: String, ok: Boolean, action: String, intent: Intent?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val label = TextView(this).apply {
            text = "$name\n${if (ok) "已开启" else "未开启"}"
            textSize = 14f
            setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)
        if (!ok) {
            row.addView(Button(this).apply {
                text = action
                textSize = 13f
                setOnClickListener {
                    if (intent == null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestPermissions(
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                            )
                        }
                    } else {
                        runCatching { startActivity(intent) }
                            .onFailure { toast("这台机器没有对应的设置页") }
                    }
                }
            })
        }
        return row
    }

    private fun listenerOn(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.split(':').any { it.startsWith(packageName) }
    }

    private fun notificationsOn(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            nm.areNotificationsEnabled()
        } else true
    }

    private fun exactAlarmOn(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun exactAlarmIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
        else null

    private fun batteryFree(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    // ── 调试动作 ──────────────────────────────────────────────

    private fun simulateStart() {
        BikeSessionEngine.onSignal(this, Signal.RideStarted(null))
        toast("已按开锁处理，${BikeSessionEngine.remindMinutes(this)} 分钟后提醒")
        refresh()
    }

    private fun clearSession() {
        BikeSessionEngine.userConfirmed(this)
        AlertDispatcher.cancel(this)
        toast("会话已清除")
        refresh()
    }

    private fun testAlert() {
        AlertDispatcher.fireBg(
            this, 1, System.currentTimeMillis() - 50 * 60_000L, null, 50
        )
    }

    /**
     * 原来只读"今天"那一个文件，跨天就看不到前一天的骑行，诊断等于瞎了。
     * 现在读全部 signal-*.jsonl，并且把 JSON 渲染成人能读的一行：
     *   11-04 17:38 START→RideStarted  开锁成功，正在骑行中…
     */
    private fun refreshLog() {
        val files = filesDir.listFiles { f -> f.name.startsWith("signal-") }
            ?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) {
            logText.text = "（还没有任何记录）"
            return
        }

        val all = ArrayList<String>()
        files.forEach { f ->
            runCatching { f.readLines().forEach { if (it.isNotBlank()) all.add(it) } }
        }
        if (all.isEmpty()) {
            logText.text = "（文件是空的）"
            return
        }

        val shown = all.takeLast(30).map { renderLine(it) }
        logText.text = shown.joinToString("\n")
    }

    /** 把一行 JSON 压成一行可读文本；解析失败就原样显示 */
    private fun renderLine(raw: String): String = runCatching {
        val o = org.json.JSONObject(raw)
        val ts = o.optString("ts", "?")            // 11-04 17:38:30.445
        val short = if (ts.length >= 14) ts.substring(0, 14) else ts
        val sig = o.optString("sig", "?")
        val text = o.optString("text", "").trim()
        val brief = if (text.length > 46) text.substring(0, 46) + "…" else text
        if (o.optString("pkg") == "@self") {
            "· $short  $sig ${brief}"              // App 自己的动作
        } else {
            "$short  $sig  $brief"
        }
    }.getOrElse { raw }

    private fun shareLog() {
        val dir = filesDir
        val files = dir.listFiles { f -> f.name.startsWith("signal-") }?.toList().orEmpty()
        if (files.isEmpty()) {
            toast("还没有日志")
            return
        }
        // 合并成一份再分享，省得用户逐个翻
        val merged = File(cacheDir, "signal-all.txt")
        merged.writeText(files.sortedBy { it.name }.joinToString("\n") { it.readText() })
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse("file://${merged.absolutePath}"))
                        putExtra(Intent.EXTRA_TEXT, merged.readText())
                    }, "分享日志"
                )
            )
        }.onFailure { toast("分享失败：${it.message}") }
    }

    // ── 小工具 ────────────────────────────────────────────────

    private fun title(s: String) = TextView(this).apply {
        text = s; textSize = 24f; setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun section(s: String) = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(0xFF888888.toInt())
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun sub(s: String) = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(0xFF666666.toInt())
        setPadding(0, dp(4), 0, 0)
    }

    private fun body(s: String) = TextView(this).apply {
        text = s; textSize = 14f
    }

    private fun hint(s: String) = TextView(this).apply {
        text = s; textSize = 12f; setTextColor(0xFF888888.toInt())
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun labelled(label: String, field: EditText): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(field, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun space(h: Int) = TextView(this).apply {
        height = dp(h)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun time(ts: Long) =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

    private fun left(startedAt: Long, minutes: Int): Long {
        val due = startedAt + minutes * 60_000L
        return ((due - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
    }

    private fun clockOf(ts: Long) =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    companion object {
        const val EXTRA_FROM_ALERT = "fromAlert"

        // 2026-11 实测的真实推送原文，测试框一键填入用
        const val SAMPLE_START =
            "开锁成功，正在骑行中 🚲\n点击返回App查看订单信息，快速关锁还车~"

        const val SAMPLE_END =
            "行程结束，已为您关锁结费\n您已成功关锁还车，点此查看行程费。"
    }
}
