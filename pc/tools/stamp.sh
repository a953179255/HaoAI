#!/usr/bin/env bash
# 给"像素闸门"记一次源码内容指纹：bash pc/tools/stamp.sh
#
# 为什么需要它：ui-shot.sh 开头那道"装好的二进制比源码旧就停"的闸门，
# 光看时间戳会**误报** —— `git checkout` / `merge --squash` 按同样内容重写文件，
# mtime 全部前移，于是收拢一次分支之后 77 份剧本一份都跑不动（2026-10-03 撞上的），
# 症状是"环境坏了"，而二进制其实是新的。
#
# 闸门因此多了一把锁：`src/main` 的内容指纹记在 build/install/haoai-pc/.srcsha，
# mtime 前移但指纹没变 ⇒ 只是被重写过，放行。
# 指纹是"二进制确实是新的"那一刻记下的 —— 正常路径由 ui-shot.sh 自己写。
# 这一份是**手工补记**的入口：你确认过 dist 是新的（刚跑完 installDist，
# 或源码自上次构建以来一个字没改），又不想让闸门一直误报，就跑它。
#
# 刻意不做成"闸门发现没指纹就自动补"：那等于第一次误报就把锁拆了，
# 而"改完源码忘了 installDist"正是这道闸门存在的唯一理由。
set -euo pipefail
cd "$(dirname "$0")/.."
BIN=build/install/haoai-pc/bin/haoai-pc.bat
[ -f "$BIN" ] || { echo "x 没有 $BIN —— 先跑 gradle installDist"; exit 1; }
SHA=$(find src/main -type f | LC_ALL=C sort | xargs cat 2>/dev/null | sha1sum | cut -d' ' -f1)
[ -n "$SHA" ] || { echo "x 算不出 src/main 的指纹（目录空的？）"; exit 1; }
printf '%s' "$SHA" > build/install/haoai-pc/.srcsha
echo "  已记指纹 $SHA（$(find src/main -type f | wc -l) 个文件）"
echo "  前提是你确认过 dist 是新的 —— 不是就先跑 installDist"
