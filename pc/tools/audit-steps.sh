#!/usr/bin/env bash
# 把 pc/tools/steps 下的像素剧本**按退出码**全量重跑一遍。
#
# 为什么要这么一份脚本：v0.67.0 有一份剧本我记成了"31 步全绿"，实际它第 14 步的判据
# 写成在 getBoundingClientRect() 的结果上取 scrollWidth —— 恒为 false，从来没绿过。
# 退出码一直是对的，错在我把命令接了 `| tail`（管道把退出码换成了 tail 的）之后只看打印数值。
# "打印不是判据"这条对剧本成立，对**跑剧本这件事本身**同样成立。
#
# 跑法（仓库根）：bash pc/tools/audit-steps.sh [名字过滤子串...]
# 参数一律从 README 原样抄：剧本的 SHOT_MODE / PRE_* 夹具配错就会红成"不存在的回归"
# （这是踩过第二次的坑，见 README「量具自己会坏」那节）。
set -uo pipefail

cd "$(dirname "$0")/.."
PC="$PWD"          # pc/ —— README 在这里
ROOT="$PWD/.."     # 仓库根 —— README 里的命令是从这里跑的
FILTERS=("$@")
LOG="${AUDIT_LOG:-$TEMP/pc-steps-audit.log}"
: > "$LOG"

# 从 README 里把每条跑法原样抓出来（含行首的 ENV=... 前缀），同一个剧本取**最长**那条
# （带注释与全部环境变量的那条才是完整跑法；SHOT_DPR=2 那条是"重拍一张"，不是验收跑法）。
# 两个坑：① `$` 不加 re.M 是整个文本的结尾，一条都匹配不上；
# ② Windows 上 python 往管道里默认按 GBK 编码，README 里的 `PRE_CLIP=素材.mp4` 会被写成
#    另一串字节，bash 读回去就是错的文件名 —— 红起来看着像产品坏了，其实是夹具没造出来。
export PYTHONIOENCODING=utf-8
python - "$PC/README.md" "$PC/tools/steps" 2> "$TEMP/pc-steps-missing.txt" > "$TEMP/pc-steps-commands.txt" <<'PY'
import io, os, re, sys
t = io.open(sys.argv[1], encoding='utf-8').read()
best = {}
# `(?:pc/)?` 不是宽容，是**补漏**：README 里两种写法都有（从仓库根跑的 `pc/tools/…`
# 和从 pc/ 里跑的 `tools/…`），只认前一种的话，后一种写下的剧本**从来没进过全量审计**——
# 实测 65 份里漏了 18 份，包括 ui-skill / ui-product / ui-risk 这几份刚验收过的。
for full, name in re.findall(
        r'((?:[A-Z_]+=(?:"[^"]*"|\'[^\']*\'|\S+)\s+)*bash (?:pc/)?tools/ui-shot\.sh (?:pc/)?tools/steps/([a-z0-9\-]+)\.json[^\n`#]*?)\s*(?:#|`|\r|$)', t, re.M):
    full = re.split(r'\s{2,}#', full.strip())[0].strip()
    if 'SHOT_DPR' in full:      # 高倍率重拍不是验收跑法
        continue
    # 统一成"从仓库根跑"的形状：本脚本下面是在 ROOT 下执行的
    full = full.replace('bash tools/ui-shot.sh tools/steps/', 'bash pc/tools/ui-shot.sh pc/tools/steps/')
    if name not in best or len(full) > len(best[name]):
        best[name] = full
have = {f[:-5] for f in os.listdir(sys.argv[2]) if f.endswith('.json')}
# 有文件、没跑法 = 这份剧本压根没被审计过。静默跳过就是"跑了 47 份、红 0 份"那种假绿，
# 所以这里既打印到 stderr（给人看），也**照样不为它编一条命令**（宁可少跑，也不要跑错夹具）。
for m in sorted(have - set(best)):
    sys.stderr.write("MISS %s  steps/%s.json 在 README 里没有原样跑法，这份没进审计\n" % (m, m))
for k in sorted(best):
    print(best[k])
PY

# 一份都没抓到就报错退出。"跑了 0 份、红 0 份"看着像全绿 —— 这类"什么都没做却成功"
# 的收尾正是这次审计要抓的东西（README 里那条命令的写法一变，脚本就会静默空转）。
if [ ! -s "$TEMP/pc-steps-commands.txt" ]; then
  echo "x 一条跑法都没从 $PC/README.md 里抓到（README 的写法变了？别把 0 份当成全绿）"
  exit 1
fi

total=0; bad=0
while IFS= read -r cmd; do
  cmd="${cmd%$'\r'}"          # Windows 上的 python 写的是 CRLF
  [ -n "$cmd" ] || continue
  name="$(printf '%s' "$cmd" | sed -n 's|.*steps/\([a-z0-9\-]*\)\.json.*|\1|p')"
  if [ ${#FILTERS[@]} -gt 0 ]; then
    hit=0
    for f in "${FILTERS[@]}"; do case "$name" in *"$f"*) hit=1 ;; esac; done
    [ "$hit" = "1" ] || continue
  fi
  total=$((total+1))
  # 一条命令一份日志：失败时 mock.log / serve.log 留在状态根里（KEEP_HOME=1 才不清）。
  out="$TEMP/pc-audit-$name.log"
  ( cd "$ROOT" && eval "$cmd" ) > "$out" 2>&1
  rc=$?
  # 三个数分开看：跑了几步、**声明了几条判据**、几条没过。
  # 只看退出码会把"零判据的绿"当成通过（v0.67.0 那条恒 false 的判据就是这么混过去的），
  # 所以判据数为 0 单独标红，别让它躲在 ok 里。
  miss=$(grep -ac '^  !!' "$out" || true)
  asserts=$(grep -ao '步 / [0-9]* 条判据' "$out" | tail -1 | grep -o '[0-9]*' | head -1)
  [ -n "$asserts" ] || asserts='?'
  if [ "$rc" != "0" ]; then
    bad=$((bad+1))
    printf 'RED  %-16s rc=%s 判据=%s 没过=%s  %s\n' "$name" "$rc" "$asserts" "$miss" "$out" | tee -a "$LOG"
  elif [ "$asserts" = "0" ]; then
    bad=$((bad+1))
    printf 'RED  %-16s 一条判据都没声明 —— 绿了等于没测（去 steps/%s.json 补 must）\n' "$name" "$name" | tee -a "$LOG"
  else
    printf 'ok   %-16s 判据=%s\n' "$name" "$asserts" | tee -a "$LOG"
  fi
done < "$TEMP/pc-steps-commands.txt"

# 有文件、没跑法：这份剧本**根本没被审计过**，比"红"更糟（红至少说明有人跑过它）。
# 计入 bad，让退出码替它说话 —— 否则"跑了 47 份、红 0 份"会一直骗下去（实测漏了 18 份）。
#
# 这一小段自己也曾是坏的：`while read -r _ name _` 是按"MISS 名字 说明"三列写的，
# 可上面那行 sed 只吐出**一个**名字，于是 name 永远为空、每条都 continue，
# 整个棘轮静默空转（实测：4 份没跑法的剧本，收尾仍打印"红 0 份"、退出码 0）。
# 教训：加判据必须连"它到底会不会红"一起验 —— 做一次能翻转结论的最小实验（这里就是
# 故意留一份没跑法的剧本，看退出码变不变）。
if [ -s "$TEMP/pc-steps-missing.txt" ]; then
  while read -r name; do
    [ -n "$name" ] || continue
    total=$((total+1)); bad=$((bad+1))
    printf 'RED  %-16s README 里没有原样跑法 —— 这份从来没进过审计\n' "$name" | tee -a "$LOG"
  done < <(sed -n 's/^MISS \([^ ]*\).*/\1/p' "$TEMP/pc-steps-missing.txt")
fi

echo "----"
echo "跑了 $total 份，红 $bad 份；汇总在 $LOG"
[ "$bad" = "0" ]
