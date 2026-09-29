#!/usr/bin/env bash
# 端到端验一遍"外部程序真的能用这套接口"：起假网关 + 真服务，用客户端发一个任务、
# 在终端里批一张卡、确认文件真的写出来了。
#   bash pc/tools/api-smoke.sh
#
# 为什么单独要这一份，而不只靠 ApiDocTest：文档与测试能保证"路径存在、形状对"，
# 但**读流的同时还要能回答审批**这件事只有真跑一遍才知道 ——
# 那是这个客户端存在的全部理由（curl 做不到，因为要一边读一边答）。
set -euo pipefail

cd "$(dirname "$0")/.."
PC_DIR="$PWD"
BIN=build/install/haoai-pc/bin/haoai-pc.bat
if [ ! -f "$BIN" ]; then echo "x 没有 $BIN —— 先跑 gradle installDist"; exit 1; fi
# 与 ui-shot.sh 同一条护栏：验的是**上一版程序**的 smoke，比没有更坏
NEWEST_SRC=$(find src/main -type f \( -name '*.kt' -o -name '*.html' \) -newer "$BIN" -print -quit 2>/dev/null)
if [ -n "$NEWEST_SRC" ]; then
  echo "x 装好的二进制比源码旧（$NEWEST_SRC 更新过）—— 先跑 gradle installDist 再来"; exit 1
fi

# 端口必须挑"当前真没人听"的：Windows 上 SO_REUSEADDR 让第二个进程也能绑上同一端口，
# 于是请求被先起来的那个接走 —— 多 agent 共用这台机器时这是最常见的假结果。
free_port() {
  for p in $(seq "$1" "$2"); do
    [ "$(netstat -ano | grep -cE "127\.0\.0\.1:$p\s.*LISTENING" || true)" = "0" ] && { echo "$p"; return; }
  done
  echo ""; return 1
}
MOCK_PORT="$(free_port 8891 8990)"; PORT="$(free_port 8940 8999)"
[ -n "$MOCK_PORT" ] && [ -n "$PORT" ] || { echo "x 找不到空端口"; exit 1; }
echo "端口 mock=$MOCK_PORT web=$PORT"

HOME_DIR="${TMPDIR:-$TEMP}/haoai-apismoke-$(date +%H%M%S)"
mkdir -p "$HOME_DIR/ws"
export HAOAI_HOME="$HOME_DIR"
WS="$HOME_DIR\\ws"

python -m py_compile tools/mock-openai.py tools/haoai-client.py || { echo "x python 侧语法不过"; exit 1; }
python tools/mock-openai.py --port "$MOCK_PORT" --mode approve > "$HOME_DIR/mock.log" 2>&1 &
MOCK=$!
cleanup() {
  kill "$MOCK" 2>/dev/null || true
  [ -n "${SRV:-}" ] && kill "$SRV" 2>/dev/null || true
  [ "${KEEP_HOME:-0}" != "1" ] && rm -rf "$HOME_DIR" 2>/dev/null || true
}
trap cleanup EXIT
sleep 1

"$BIN" set base="http://127.0.0.1:$MOCK_PORT/v1" model=mock "workspace=$WS" mode=ask > "$HOME_DIR/set.log" 2>&1
"$BIN" serve --port "$PORT" > "$HOME_DIR/serve.log" 2>&1 &
SRV=$!
for i in $(seq 1 40); do curl -sf "http://127.0.0.1:$PORT/api/state" >/dev/null && break; sleep 0.5; done

BASE="http://127.0.0.1:$PORT"
fail() { echo "x $1"; echo "--- client.log ---"; tail -20 "$HOME_DIR/client.log" 2>/dev/null || true; exit 1; }

# 1) 只读的那几个端点先过一遍：它们不需要回答，最容易先坏
curl -sf "$BASE/api/sessions" >/dev/null || fail "GET /api/sessions 不通"
curl -sf "$BASE/api/runs" >/dev/null || fail "GET /api/runs 不通"
curl -sf "$BASE/api/usage" >/dev/null || fail "GET /api/usage 不通"
python tools/haoai-client.py --base "$BASE" state | grep -q "档 ask" || fail "state 子命令没把权限档打出来"

# 2) 发一个会弹审批卡的任务：假网关的 approve 剧本要写 approved.txt，
#    客户端必须一边读流一边在终端问一句 —— 这就是它存在的理由
printf 'y\n' > "$HOME_DIR/answer.txt"
( cd "$HOME_DIR" && python "$PC_DIR/tools/haoai-client.py" --base "$BASE" task "写一个文件，我要批准它" ) \
  < "$HOME_DIR/answer.txt" > "$HOME_DIR/client.log" 2>&1 || fail "客户端退出码非零（见 client.log）"

grep -q "需要批准" "$HOME_DIR/client.log" || fail "客户端没收到审批卡（SSE 读流没读到 approval？）"
[ -f "$HOME_DIR/ws/approved.txt" ] || fail "批了却没写出来：approved.txt 不在工作区里"
grep -q "从手机上批准的" "$HOME_DIR/ws/approved.txt" || fail "写出来的内容不对"

# 3) 收口要真的收口：running=false，且运行账本里留下了这一条
curl -s "$BASE/api/state" | grep -q '"running":false' || fail "任务没收口（running 还是 true）"
curl -s "$BASE/api/runs" | grep -q "写一个文件" || fail "运行账本里查不到这一条"

echo "ok  客户端发任务 → 读到审批卡 → 终端回答 → 文件真写出来 → 收口并记账"
echo "ok  文档里承诺稳定的那几个端点，外部程序真的能用"
