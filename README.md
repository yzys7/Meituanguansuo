# 关锁卫士 BikeLockGuard

骑完美团单车忘记关锁，平台会一直计费到车辆静止满 60 分钟才自动结单。这个 App 只做一件事：

**收到「开锁成功」通知 → 开始倒计时；收到「关锁」通知 → 静默撤销；倒计时结束还没关锁 → 强提醒。**

默认提醒阈值 50 分钟（可在 App 内调整），给平台 60 分钟自动结单留出 10 分钟处置窗口。正常骑行全程无打扰。

## 工作原理

美团单车的开锁和关锁都会向通知栏推送系统通知：

| 事件 | 通知文案 | 动作 |
|---|---|---|
| 开锁 | 「开锁成功，正在骑行中 🚲」 | 建立会话，启动倒计时 |
| 关锁 | 「行程结束，已为您关锁结费」 | 撤销倒计时，静默 |

App 通过 `NotificationListenerService`（通知使用权）监听 `com.sankuai.*` 与支付宝系包名的通知，用关键词规则配对两类信号。倒计时交给 `AlarmManager`（`setExactAndAllowWhileIdle` + `ELAPSED_REALTIME_WAKEUP`），会话状态同步落盘（`commit`），闹钟 Intent 里自带会话快照，进程被杀、手机重启都能恢复。

骑行期间状态栏有一条「骑行中」通知，带系统级走秒计时；关锁即消失，平时不骑行时状态栏干净，无常驻。

**不需要 root、不需要 Xposed、不 hook 任何应用**，只读取自己手机的系统通知。`lsposed/BikeHook.kt` 只是早期调研用的只读探针，非必需。

## 功能

- 开锁/关锁通知配对判定，文案规则可在「文案自测」框里验证
- 50 分钟未关锁强提醒：全屏通知 + 走 `STREAM_ALARM` 的语音播报（静音模式也能出声）+ 闹钟级震动
- 升级提醒：第 55、60 分钟各再提醒一次
- 通知上固定「已关锁」「仍在骑行（顺延 30 分钟）」两个操作
- 骑行中状态栏计时通知（系统渲染，进程被杀照走）
- 事件与通知日志（jsonl，App 内可读、可分享），含 ARM / FIRE / DISARM / RECOVER 全链路记录
- 开机自恢复未完成会话（BOOT_COMPLETED）
- 运行诊断区：监听服务是否活着、闹钟是否已设置

## 安装与权限

`dist/BikeLockGuard.apk`，minSdk 26（Android 8.0+）。

装完后必须授予：

1. **通知使用权**（核心，唯一信号源）
2. 通知权限（Android 13+）
3. 精确闹钟权限（Android 12+，不授予则提醒不准时）
4. 电池优化白名单

### 国产 ROM 额外注意（重要）

**「忽略电池优化」不等于「允许后台运行」**，这是两套独立开关。实测 OPPO（ColorOS）上即使加进电池优化白名单，通知监听服务仍会被冻结——表现为骑完车回家后打开 App，它才「补」检测到开锁通知，计时起点延后。

需要在系统设置里再给一次：

- **OPPO / 一加（ColorOS）**：设置 → 应用管理 → 关锁卫士 → 耗电管理 → **允许完全后台行为**
- **小米 / 红米（MIUI）**：省电策略改为「无限制」，并在最近任务卡片加锁
- **vivo（OriginOS）**：后台耗电管理 → 允许高耗电；自启动开关打开
- **华为（HarmonyOS）**：应用启动管理 → 关闭自动管理，手动允许自启动 / 关联启动 / 后台活动

验证方法：骑一次车后看 App 内「运行诊断」——监听服务应显示「已连接（最近一次 N 分钟前）」，N 很小才正常。

## 构建

本仓库同时支持两条构建路径：

**Android Studio / Gradle（推荐）**：直接用 Android Studio 打开本目录，`build.gradle.kts` 已就绪。

**无 Gradle 环境（命令行）**：

```bash
bash tools/fetch-kotlinc.sh   # 一次性：下载 Kotlin 编译器到 buildtools/lib
bash tools/build-apk.sh       # aapt2 -> kotlinc -> d8 -> apksigner
```

需要本机有 JDK 17+ 和 Android SDK（build-tools 36 / android-36）。脚本会在 `buildtools/` 生成自用 debug 签名证书并复用——**删除它会导致新旧 APK 签名不一致、无法覆盖安装**。

## 目录结构

```
app/src/main/java/com/bikeguard/
  Signal.kt                    信号模型 + 文案解析规则（START/END 正则）
  BikeNotificationListener.kt  通知监听服务
  BikeSessionEngine.kt         会话引擎：arm/disarm/触发/恢复
  AlarmScheduler.kt            定时调度 + 闹钟恢复接收器
  AlertDispatcher.kt           强提醒（通知/语音/震动）+ 通知按钮处理
  RideStatusNotifier.kt        骑行中状态栏计时通知
  MainActivity.kt              设置页 / 文案自测 / 日志 / 诊断
tools/                         免 Gradle 打包脚本、logcat 采样脚本
lsposed/                       LSPosed 只读探针（调研用，非必需）
```

## 免责声明

本项目为个人学习用途，与美团无任何关联。只读取本机系统通知，不注入、不 hook、不修改美团 App。文案关键词若随美团版本更新而变化，可能导致判定失效——用 App 内「文案自测」和日志核对即可，欢迎提 Issue 补充规则。

## License

MIT
