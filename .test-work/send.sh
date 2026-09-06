#!/bin/bash
# 用法: send.sh "<text with + as spaces> <MARKER>"  —— 发送并验证用户气泡出现（防假发送）
export MSYS_NO_PATHCONV=1
S=adb-391QYFCP2266T-VtTJb1._adb-tls-connect._tcp
W="G:/工作台/HaoAI/.test-work"
MARKER="$2"

for try in 1 2 3; do
  adb -s "$S" shell "input keyevent 111" 2>/dev/null; sleep 0.8
  adb -s "$S" shell "input tap 350 2236"; sleep 1.2
  adb -s "$S" shell "input text $1"; sleep 1
  adb -s "$S" shell "input keyevent 111"; sleep 1.8
  adb -s "$S" shell "uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
  adb -s "$S" shell "cat /sdcard/ui.xml" > "$W/ui_send.xml" 2>/dev/null
  python - "$W/ui_send.xml" <<'EOF' > "$W/send_xy.txt"
import re, sys
x = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'content-desc="发送"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
if m:
    y = (int(m.group(2))+int(m.group(4)))//2
    if y > 1700:  # 必须是底部输入栏的发送键，顶栏同名按钮不算
        print((int(m.group(1))+int(m.group(3)))//2, y)
EOF
  read X Y < "$W/send_xy.txt"
  if [ -z "$X" ]; then echo "try$try: SEND_BTN_NOT_AT_BOTTOM"; continue; fi
  adb -s "$S" shell "input tap $X $Y"
  sleep 2.5
  adb -s "$S" shell "uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
  adb -s "$S" shell "cat /sdcard/ui.xml" > "$W/ui_sent.xml" 2>/dev/null
  if grep -q "$MARKER" "$W/ui_sent.xml"; then echo "SENT_OK try$try $(date +%H:%M:%S)"; exit 0; fi
  echo "try$try: bubble not verified"
done
echo "SEND_FAILED_ALL_TRIES"; exit 1
