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
# 清上一轮没退干净的僵尸（无头 Edge 攒标签、haoai 服务占着 87xx 端口）。
# 必须在挑端口之前做 —— 晚一步就会连自己刚起的服务一起杀掉。
powershell -NoProfile -ExecutionPolicy Bypass -File tools/kill-stale-shot.ps1 2>/dev/null || true

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
# Git 面板这条链路要"工作区本身是一个真仓库，而且有改动"：
# 空仓库什么都验不到 —— 暂存、取消暂存、提交、diff 全要有真实的行可点。
# 故意留一个未跟踪的中文名文件：porcelain 的 -z 就是为了它（默认输出会转义中文）。
if [ -n "${PRE_GIT:-}" ]; then
  mkdir -p "$HOME_DIR/ws"
  ( cd "$HOME_DIR/ws" && \
    git init -q . && \
    printf 'one\ntwo\n' > a.txt && printf 'first\n' > b.txt && \
    git add -A >/dev/null && \
    git -c user.email=t@t -c user.name=t commit -qm "seed" >/dev/null && \
    printf 'one\ntwo changed\n' > a.txt && \
    printf '第一行\n第二行\n' > 中文.txt && \
    printf 'brand new\n' > new.txt ) || { echo "x 造测试仓库失败（这台机器没有 git？）"; exit 1; }
  echo "  seeded git repo (a.txt 改动 / 中文.txt 未跟踪 / new.txt 未跟踪)"
fi
# 媒体这条链路要一段**真能解码**的素材：假字节流浏览器画不出画面，
# 于是 faststart、Range、videoWidth 这三样全都验不到 —— 看着绿其实什么都没测。
# PRE_CLIP=素材.mp4 就用 ffmpeg 现造一段 4 秒的（testsrc + 440Hz 正弦）。
if [ -n "${PRE_CLIP:-}" ]; then
  case "$PRE_CLIP" in
    *.*) ;;
    *) echo "x PRE_CLIP 要的是带扩展名的文件名（如 素材.mp4），现在是「$PRE_CLIP」：ffmpeg 认不出没有扩展名的输出格式，报的错还看着像 ffmpeg 坏了"; exit 1 ;;
  esac
  FF="${FFMPEG:-$(command -v ffmpeg || true)}"
  [ -n "$FF" ] || { echo "x PRE_CLIP 需要 ffmpeg：装一个或把可执行文件路径写进 FFMPEG"; exit 1; }
  mkdir -p "$HOME_DIR/ws"
  "$FF" -y -hide_banner -loglevel error -f lavfi -i "testsrc=duration=4:size=320x240:rate=15" \
    -f lavfi -i "sine=frequency=440:duration=4" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest \
    "$HOME_DIR/ws/$PRE_CLIP" || { echo "x 造测试素材失败"; exit 1; }
  echo "  seeded clip $PRE_CLIP"
fi
# 记忆整理这条链路要"盘上已经存着一份旧记忆"。时间戳必须按**运行时的现在**算：
# 判据是"30 天没用"，夹具里写死时间戳的话过几天就永远命中不了 ——
# 那种剧本会一直绿，而它其实什么都没验（这是"量具自己会坏"的又一种坏法）。
if [ -n "${PRE_MEMORY:-}" ]; then
  mkdir -p "$HOME_DIR/ws"
  python - "$HOME_DIR/ws/MEMORY.md" <<'PY'
import sys, time
now = int(time.time() * 1000); day = 86_400_000
def line(i, imp, text, t, typ="fact"):
    return ("- [%s · 重要度%d] %s <!-- id:%s imp:%d type:%s created:%d lastUsed:%d uses:1 uq:1 -->"
            % (i, imp, text, i, imp, typ, t, t))
open(sys.argv[1], "w", encoding="utf-8").write("\n".join([
    "# 长期记忆", "",
    "## 偏好（1）",
    line("1111aaaa", 4, "回答先给结论再给依据", now - 2 * day, "preference"),
    "", "## 事实（5）",
    line("2222bbbb", 5, "这个仓库用 gradle 9.6 构建，JDK 是 Temurin 25", now - day),
    line("3333cccc", 2, "很久以前顺手记下的一条：老机器的盘符是 D", now - 40 * day),
    line("4444dddd", 1, "上一次试过的导出目录叫 out", now - 90 * day),
    line("5555eeee", 3, "run the gradle build task with jdk", now - 3 * day),
    line("6666ffff", 1, "run the gradle build task with jdk now", now - 4 * day),
    "", "## 已归档（1）",
    "- [7777aaaa · 重要度2] 三个月前忘掉的一条 <!-- id:7777aaaa imp:2 type:fact created:%d "
    "lastUsed:0 uses:0 sup:archived upd:%d -->" % (now - 60 * day, now - 40 * day),
    "",
]) + "\n")
print("  seeded MEMORY.md（该降级 2 / 该合并 1 / 该清掉 1 / 正常 3）")
PY
fi
# 断点恢复这条链路要"盘上已经有一条没跑完的现场"：服务端一起来就得认得它，
# 所以会话文件得在服务端启动之前写好。
# 状态根目录名带时间戳、调用方事先不知道，所以工作区路径用 @WS@ 占位，
# 在这里替换（JSON 里的反斜杠要翻倍，不然解析出来是个残缺路径）。
# PRE_RUNSTATE 可以是一条会话，也可以是若干条：多条才验得出"横幅是每条会话自己的"
# —— 多会话并行时它跟着切会话跑到别条上面，是最容易漏的那类错。
if [ -n "${PRE_RUNSTATE:-}" ]; then
  mkdir -p "$HOME_DIR/sessions" "$HOME_DIR/ws"
  SHOT_WS="$WS_WIN" PRE_RUNSTATE="$PRE_RUNSTATE" python - "$HOME_DIR/sessions" <<'PY'
import json, os, sys
# @WS@ 换成这次运行的工作区绝对路径。JSON 里反斜杠要翻倍，所以用 json.dumps 转义
# 之后再剥掉外层引号，而不是手搓 replace —— 手搓的转义是最容易错的一位。
ws = json.dumps(os.environ["SHOT_WS"])[1:-1]
objs = json.loads(os.environ["PRE_RUNSTATE"].replace("@WS@", ws))
objs = objs if isinstance(objs, list) else [objs]
out = sys.argv[1]
for o in objs:
    # 会话文件的命名规矩是 pc-<id>.json（SessionIndex.fileFor），前缀错了服务端就找不到它
    with open(os.path.join(out, "pc-" + o["id"] + ".json"), "w", encoding="utf-8") as f:
        json.dump(o, f, ensure_ascii=False)
    print("  seeded session", o["id"])
PY
fi
export HAOAI_HOME="$HOME_DIR"

# 假网关自己语法错的时候，症状是"没独占端口"（进程起来又立刻死了，端口上零个监听），
# 看着像端口被别的 agent 抢了 —— 先花 0.2 秒把语法问题在这里说清楚。
python -m py_compile tools/mock-openai.py || { echo "x tools/mock-openai.py 语法不过（上面就是报错行）"; exit 1; }

python tools/mock-openai.py --port "$MOCK_PORT" --mode "$MODE" > "$HOME_DIR/mock.log" 2>&1 &
MOCK=$!
cleanup() {
  kill "$MOCK" 2>/dev/null || true
  [ -n "${SRV:-}" ] && kill "$SRV" 2>/dev/null || true
  # 每次跑都换一个状态根（上面那条：不换就会混进上一轮的会话），跑完它就是纯垃圾，
  # 一天二十来次就是二十个目录。失败时留着看 mock.log / serve.log；KEEP_HOME=1 强制留。
  if [ "${KEEP_HOME:-0}" != "1" ] && [ "${RC:-1}" = "0" ]; then
    # 服务端进程刚 kill，Windows 上它的当前目录还会被占一会儿：删不动就算了，
    # 不能让一次"验收其实全绿"的运行因为收尾删目录失败而报非零退出码。
    sleep 0.4
    rm -rf "$HOME_DIR" 2>/dev/null || true
  fi
}
trap 'RC=$?; cleanup' EXIT
sleep 1
if [ "$(netstat -ano | grep -cE "127\.0\.0\.1:$MOCK_PORT\s.*LISTENING" || true)" != "1" ]; then
  echo "x 假网关没独占端口 $MOCK_PORT（看 $HOME_DIR/mock.log）"; exit 1
fi

"$BIN" set base="http://127.0.0.1:$MOCK_PORT/v1" model=mock "workspace=$WS_WIN" mode="${SHOT_PERM:-auto}" maxTokens=2048 \
  > "$HOME_DIR/set.log" 2>&1
tail -2 "$HOME_DIR/set.log"
# 有的链路要在设置里多写几项才验得到（降级链就是：主模型与备胎都得先落进设置）。
# 一律走同一个 CLI，不手改 settings.json —— 落盘口径（哪些字段存默认值）由代码自己保证。
# 空格分隔的 k=v；值里不许有空格（有就会在这里被拆成两项，报出来像"设置项不认识"）。
for kv in ${PRE_SET:-}; do
  "$BIN" set "$kv" >> "$HOME_DIR/set.log" 2>&1 || { echo "x set $kv 失败（看 $HOME_DIR/set.log）"; exit 1; }
  echo "  set $kv"
done

# 有的链路要"实验特性是开的"才验得到（预览面板：browser_control 关着时端点压根不动浏览器）。
# 走 CLI 而不是手改 settings.json —— 开关的落盘口径（只存与默认值不同的那些）由代码自己保证。
for k in ${PRE_FLAGS:-}; do
  "$BIN" flags on "$k" >> "$HOME_DIR/set.log" 2>&1 || { echo "x 开不了开关 $k（看 $HOME_DIR/set.log）"; exit 1; }
  echo "  flag on: $k"
done
# 手机联动这条链路要"端点已经开着、而且有一台已配对设备"：
# 端口每轮现挑（写死就会和别的进程抢），配对走 CLI —— 它写的就是服务读的那份 lan.json。
LAN_PORT=""
if [ -n "${PRE_LAN:-}" ]; then
  LAN_PORT="$(free_port 8720 8759)"
  [ -n "$LAN_PORT" ] || { echo "x 找不到空的局域网端口（8720-8759 全被占）"; exit 1; }
  "$BIN" lan on --port "$LAN_PORT" >> "$HOME_DIR/set.log" 2>&1 || { echo "x haoai lan on 失败（看 set.log）"; exit 1; }
  "$BIN" lan pair 测试手机 >> "$HOME_DIR/set.log" 2>&1 || { echo "x haoai lan pair 失败"; exit 1; }
  echo "  lan on :$LAN_PORT + 一台已配对设备"
fi
# 剧本里写 @MOCK@ 的地方换成这次的假网关端口（预览靶页就挂在它上面，端口每轮现挑，写死必串台）。
if grep -qE '@MOCK@|@LAN@|@WEB@' "$STEPS" 2>/dev/null; then
  # @WEB@ = 桌面那头的端口：手机剧本要跳回桌面页去勾「允许从手机派活」，
  # 写死 8712 的话换成别的端口就测的是别人的服务。
  sed -e "s|@MOCK@|$MOCK_PORT|g" -e "s|@LAN@|${LAN_PORT:-0}|g" -e "s|@WEB@|$PORT|g" "$STEPS" > "$HOME_DIR/steps.json"
  STEPS="$HOME_DIR/steps.json"
  echo "  steps @MOCK@ -> $MOCK_PORT"
fi

"$BIN" serve --port "$PORT" > "$HOME_DIR/serve.log" 2>&1 &
SRV=$!
for i in $(seq 1 40); do
  curl -sf "http://127.0.0.1:$PORT/api/state" > /dev/null && break
  sleep 0.5
done
echo "服务端 -> $(curl -s "http://127.0.0.1:$PORT/api/state" | head -c 200)"

# 调试端口也每次挑"真没人听"的：写死 9339 的话，上一轮的僵尸一占就串台
# 手机网页端要**真的配对一次**：配对码只能由跑着的服务发，所以在这里现取现用，
# 再塞进剧本里的 @CODE@。写死码等于测了个假流程。
if [ -n "${PRE_LAN:-}" ]; then
  LAN_RAW="$(curl -s -X POST "http://127.0.0.1:$PORT/api/lan/code")"
  # 接口回的 JSON 没有结尾换行，直接管道进 sed 一行都匹配不上（表现是"码没拿到"）。
  LAN_CODE="$(printf "%s\n" "$LAN_RAW" | grep -o "\"code\":\"[0-9]\{6\}\"" | head -1 | cut -d\" -f4)"
  [ -n "$LAN_CODE" ] || { echo "x 拿不到配对码（局域网端点没开起来？）"; exit 1; }
  echo "  pairing code $LAN_CODE"
  sed "s|@CODE@|$LAN_CODE|g" "$STEPS" > "$HOME_DIR/steps2.json"
  STEPS="$HOME_DIR/steps2.json"
fi

SHOT_PORT="$(free_port 9340 9399)"
[ -n "$SHOT_PORT" ] || { echo "x 找不到空的调试端口"; exit 1; }
node tools/shot.js --out "$OUT" --port "$SHOT_PORT" --width "${SHOT_W:-1500}" --height "${SHOT_H:-930}" \
  ${SHOT_MOBILE:+--mobile} \
  --url "http://127.0.0.1:$PORT/" --steps "$STEPS"
echo "图在 $OUT"
ls "$OUT"
