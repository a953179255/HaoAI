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
python - "$PC/README.md" > "$TEMP/pc-steps-commands.txt" <<'PY'
import io, re, sys
t = io.open(sys.argv[1], encoding='utf-8').read()
best = {}
for full, name in re.findall(
        r'((?:[A-Z_]+=\S+\s+)*bash pc/tools/ui-shot\.sh pc/tools/steps/([a-z0-9\-]+)\.json[^\n`#]*?)\s*(?:#|`|\r|$)', t, re.M):
    full = re.split(r'\s{2,}#', full.strip())[0].strip()
    if 'SHOT_DPR' in full:      # 高倍率重拍不是验收跑法
        continue
    if name not in best or len(full) > len(best[name]):
        best[name] = full
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
  gates=$(grep -ac '!!' "$out" || true)
  if [ "$rc" != "0" ]; then
    bad=$((bad+1))
    printf 'RED  %-16s rc=%s gates=%s  %s\n' "$name" "$rc" "$gates" "$out" | tee -a "$LOG"
  else
    printf 'ok   %-16s gates=%s\n' "$name" "$gates" | tee -a "$LOG"
  fi
done < "$TEMP/pc-steps-commands.txt"

echo "----"
echo "跑了 $total 份，红 $bad 份；汇总在 $LOG"
[ "$bad" = "0" ]
