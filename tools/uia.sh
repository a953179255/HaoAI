#!/usr/bin/env bash
# Android UI 验证加速工具：基于 uiautomator dump 的语义定位（替代截图猜坐标）。
# 用法: tools/uia.sh <命令> [...]
#   dump                输出当前界面 XML（单行）
#   find <k=v>          打印元素中心 "x y"；k = text / desc / cls（默认 text，包含匹配）
#   has <k=v>           断言元素存在（退出码 0/1）
#   tap <k=v>           点按元素中心
#   tapxy <x> <y>       裸坐标点击（兜底）
#   type <文本>         向焦点输入框输入（空格自动转 %s；先 tap 输入框）
#   clear               清空焦点输入框（ctrl+a → del；不支持时 END+连按退格）
#   key <code>          keyevent（如 4=BACK）
#   wait <k=v> [秒]     轮询等元素出现（默认 30s），输出中心坐标
#   waitgone <k=v> [秒] 轮询等元素消失
#   send                点「发送」按钮（desc/text=发送；失败回退坐标表）
#   shot [文件]         截图（只用于最终视觉确认）
# SER=<adb序列号> 可指定设备（默认 emulator-5554）
set -uo pipefail
SER="${SER:-emulator-5554}"
A() { adb -s "$SER" "$@"; }
# 发送按钮兜底坐标（语义定位失败时按设备取）
send_fallback() { case "$SER" in emulator-*) echo "960 2235";; *) echo "1005 2222";; esac; }

dump() { A shell 'uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml'; }

# 在 dump XML 中找元素 → 输出 "cx cy"；$1=匹配串（如 text=允许 / desc=发送 / cls=EditText）
find_center() {
  local key="${1%%=*}" val="${1#*=}" node
  [ "$key" = "$1" ] && { key="text"; val="$1"; }
  local xml; xml=$(dump) || return 1
  node=$(printf '%s' "$xml" | sed 's/<node/\n<node/g' | grep -F "$key=\"$val\"" | head -1)
  [ -z "$node" ] && { node=$(printf '%s' "$xml" | sed 's/<node/\n<node/g' | grep -F "$val" | head -1); }
  [ -z "$node" ] && return 1
  local b; b=$(printf '%s' "$node" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
  [ -z "$b" ] && return 1
  printf '%s' "$b" | grep -o '[0-9]\+' | awk 'NR<=4{a[NR]=$1} END{printf "%d %d\n", (a[1]+a[3])/2, (a[2]+a[4])/2}'
}

has() { find_center "$1" >/dev/null; }

cmd="${1:-help}"; [ $# -gt 0 ] && shift
case "$cmd" in
  dump) dump ;;
  find) find_center "$1" ;;
  has)  if has "$1"; then echo "OK: $1"; else echo "MISS: $1"; exit 1; fi ;;
  tap)  c=$(find_center "$1") || { echo "未找到: $1"; exit 1; }
        A shell input tap $c; echo "tapped($c): $1" ;;
  tapxy) A shell input tap "$1" "$2" ;;
  type) t=${1// /%s}; A shell input text "$t" ;;
  clear)
        if A shell input keycombination 113 47 2>/dev/null; then A shell input keyevent 67
        else A shell input keyevent 123; for i in $(seq 1 60); do A shell input keyevent 67; done; fi ;;
  key)  A shell input keyevent "$1" ;;
  wait) t="${2:-30}"; for i in $(seq 1 $((t*2))); do
          c=$(find_center "$1") && { echo "OK($c): $1"; exit 0; }; sleep 0.5; done
        echo "TIMEOUT: $1"; exit 1 ;;
  waitgone) t="${2:-30}"; for i in $(seq 1 $((t*2))); do
          has "$1" || { echo "GONE: $1"; exit 0; }; sleep 0.5; done
        echo "STILL THERE: $1"; exit 1 ;;
  send) c=$(find_center "desc=发送" || find_center "text=发送") || c=$(send_fallback)
        A shell input tap $c; echo "sent($c)" ;;
  shot) f="${1:-/tmp/uia_shot.png}"; A exec-out screencap -p > "$f"; echo "$f" ;;
  *) sed -n '2,22p' "$0" ;;
esac
