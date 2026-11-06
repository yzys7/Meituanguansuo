#!/system/bin/sh
# ============================================================================
# BikeGuard 探针 · dry-run 采集脚本（root / KernelSU）
#
# 用途：在写任何代码之前，先用一周时间把美团单车真实的通知文案与时序采下来。
#       整个方案的可靠性，几乎全部取决于这份样本的完整度。
#
# 用法（手机端，需 su）：
#   su -c sh /data/local/tmp/probe.sh
# 结果：
#   /data/local/tmp/bikeguard-probe.log
#
# 原理：
#   1) logcat 的 events buffer 里，notification_enqueue 事件带包名与 id，
#      这是最轻量的"有通知了"触发器，开销远小于轮询 dumpsys。
#   2) 命中后再 dump 当前通知详情，拿到标题正文。
# ============================================================================

LOG=/data/local/tmp/bikeguard-probe.log
PKGS='com.sankuai.meituan|com.eg.android.AlipayGphone'

echo "==== bikeguard probe start $(date) ====" >> "$LOG"

dumpsys notification --noredact 2>/dev/null | head -5 | grep -q pkg= \
  || echo "WARN: 该 ROM 的 dumpsys 可能不支持 --noredact，正文可能被隐去" >> "$LOG"

logcat -c
logcat -b events -v time 2>/dev/null | while IFS= read -r line; do
  case "$line" in
    *notification_enqueue*)
      case "$line" in
        *com.sankuai.meituan*|*AlipayGphone*)
          ts=$(date '+%Y-%m-%d %H:%M:%S')
          {
            echo ""
            echo "### $ts"
            echo "EVT $line"
            echo "--- dump ---"
            dumpsys notification --noredact 2>/dev/null \
              | sed -n '/NotificationRecord.*com\.sankuai\.meituan/,/^$/p' | head -40
            dumpsys notification --noredact 2>/dev/null \
              | sed -n '/NotificationRecord.*AlipayGphone/,/^$/p' | head -40
          } >> "$LOG"
          ;;
      esac
      ;;
  esac
done
