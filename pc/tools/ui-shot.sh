#!/usr/bin/env bash
# 一键起"假网关 + 真服务 + 无头浏览器"的像素验收。
#   bash pc/tools/ui-shot.sh [steps.json] [输出目录]
# 为什么要脚本化：这三样东西的启动顺序与端口互相依赖，手工起过一次就忘了，
# 而"界面到底长什么样"这件事只有真截图能定案 —— 记在文档里的结论第二天就不能信了。
set -euo pipefail

CALLER="$PWD"
cd "$(dirname "$0")/.."
STEPS="${1:-tools/steps/ui.json}"
# 剧本路径按 README 的写法是从仓库根给的（pc/tools/steps/x.json），而这里已经 cd 到 pc/，
# 于是照抄命令会 ENOENT —— 两处都认：先按 pc/ 相对，找不到再按调用方当时所在目录。
[ -f "$STEPS" ] || STEPS="$CALLER/$STEPS"
OUT="${2:-$TEMP/haoai-ui-shot}"
MODE="${SHOT_MODE:-tools}"

BIN=build/install/haoai-pc/bin/haoai-pc.bat
if [ ! -f "$BIN" ]; then echo "x 没有 $BIN —— 先跑 gradle installDist"; exit 1; fi

# 端口必须挑"当前真没人听"的：BaseHTTPRequestHandler 开了 SO_REUSEADDR，
# 在 Windows 上第二个进程**能绑上同一个端口**（不报错），于是请求被先起来的那个
# 接走 —— 实测就这么被坑过一次：指定 --mode tools，答出来的却是别人那份 spill 剧本，
# 界面上一堆"缺元素"的假故障。绑成功不等于端口是你的，所以要数一遍监听数。
free_port() {
  for p in $(seq "$1" "$2"); do
    [ "$(netstat -ano | grep -cE "127\.0\.0\.1:$p\s.*LISTENING" || true)" = "0" ] && { echo "$p"; return; }
  done
  echo ""; return 1
}
MOCK_PORT="$(free_port 8791 8890)"; PORT="$(free_port 8737 8790)"
[ -n "$MOCK_PORT" ] && [ -n "$PORT" ] || { echo "x 找不到空端口"; exit 1; }
echo "端口 mock=$MOCK_PORT web=$PORT"
# 每次跑都换一个状态根：上一轮的会话会被服务端"接上上次那条"，
# 于是这次的截图里混着上次的消息，看起来像渲染错了其实只是没清空。
HOME_DIR="${TMPDIR:-$TEMP}/haoai-uishot-$(date +%H%M%S)"

WS_WIN="$HOME_DIR\\ws"
mkdir -p "$HOME_DIR" "$HOME_DIR/ws" "$OUT"
# 有些验收要工作区里先有东西（@ 提及没文件可提就什么都验不到）。
# PRE_TOUCH="a/b.txt c.txt" —— 空格分隔的相对路径，各塞一行 seed。
for f in ${PRE_TOUCH:-}; do
  mkdir -p "$HOME_DIR/ws/$(dirname "$f")"
  printf 'seed\n' > "$HOME_DIR/ws/$f"
done
# 工作区切换器要"另一个真的存在的目录"（不能只测报错那条路）
for d in ${PRE_DIRS:-}; do mkdir -p "$HOME_DIR/$d"; done
# 图片这条链路要一张真能渲染的 PNG（假魔数的 72 字节文件浏览器画不出来，只剩文件名）
for f in ${PRE_PNG:-}; do
  mkdir -p "$HOME_DIR/ws/$(dirname "$f")"
  printf %s "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==" | base64 -d > "$HOME_DIR/ws/$f"
done
export HAOAI_HOME="$HOME_DIR"

python tools/mock-openai.py --port "$MOCK_PORT" --mode "$MODE" > "$HOME_DIR/mock.log" 2>&1 &
MOCK=$!
cleanup() {
  kill "$MOCK" 2>/dev/null || true
  [ -n "${SRV:-}" ] && kill "$SRV" 2>/dev/null || true
  # 每次跑都换一个状态根（上面那条：不换就会混进上一轮的会话），跑完它就是纯垃圾，
  # 一天二十来次就是二十个目录。失败时留着看 mock.log / serve.log；KEEP_HOME=1 强制留。
  if [ "${KEEP_HOME:-0}" != "1" ] && [ "${RC:-1}" = "0" ]; then rm -rf "$HOME_DIR"; fi
}
trap 'RC=$?; cleanup' EXIT
sleep 1
if [ "$(netstat -ano | grep -cE "127\.0\.0\.1:$MOCK_PORT\s.*LISTENING" || true)" != "1" ]; then
  echo "x 假网关没独占端口 $MOCK_PORT（看 $HOME_DIR/mock.log）"; exit 1
fi

"$BIN" set base="http://127.0.0.1:$MOCK_PORT/v1" model=mock "workspace=$WS_WIN" mode=auto maxTokens=2048 \
  > "$HOME_DIR/set.log" 2>&1
tail -2 "$HOME_DIR/set.log"

"$BIN" serve --port "$PORT" > "$HOME_DIR/serve.log" 2>&1 &
SRV=$!
for i in $(seq 1 40); do
  curl -sf "http://127.0.0.1:$PORT/api/state" > /dev/null && break
  sleep 0.5
done
echo "服务端 -> $(curl -s "http://127.0.0.1:$PORT/api/state" | head -c 200)"

node tools/shot.js --out "$OUT" --port 9339 --width "${SHOT_W:-1500}" --height "${SHOT_H:-930}" \
  --url "http://127.0.0.1:$PORT/" --steps "$STEPS"
echo "图在 $OUT"
ls "$OUT"
