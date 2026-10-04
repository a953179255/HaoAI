#!/usr/bin/env bash
# 冒烟：一条命令回答"前端还接得上吗"。
#   bash pc/tools/smoke.sh [输出目录]
#
# 覆盖四类"结构全绿仍会漏"的事故（每一类都有真实事故垫底）：
#   1) 页面态两栏让位   —— a755250：切到专家/知识库，右边永远挂着一列会话列表；
#   2) 跳转落位         —— 从页面态用面板跳会话/开右栏页签，点在 display:none 的
#                          元素上=选中态翻了、面板画了、人什么都看不见；
#   3) 网格钉轨         —— Ctrl B 收栏整窗空白（自动布位把主区前移进窄轨道）；
#   4) 消息全链路       —— 输入框 → /api/task → SSE → 工具回合 → 最终回答渲染。
#
# 与 ui-shot.sh 的分工：那份管"起服务/假网关/新鲜状态根/僵尸清理/二进制闸门"，
# 这份只做两件事——先把二进制装新（冒烟定位是随手跑，闸门只报错不构建），再挑剧本。
# 剧本本身在 tools/steps/ui-pages.json；像素级专项验收仍走
#   bash pc/tools/ui-shot.sh tools/steps/<剧本>.json
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE="${GRADLE:-$USERPROFILE/.gradle/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0/bin/gradle}"
[ -f "$GRADLE" ] || GRADLE=gradle
echo "== installDist（up-to-date 时秒回）=="
"$GRADLE" installDist -q --console=plain

echo "== ui-check 静态自检 =="
if ! UI_OUT=$(node tools/ui-check.js 2>&1); then
  echo "$UI_OUT"; exit 1
fi
echo "  通过"

echo "== ESLint 门（真作用域分析：no-undef / 重复键 / 不可达…）=="
if ! LINT_OUT=$(node tools/lint.js 2>&1); then
  echo "$LINT_OUT"; exit 1
fi
echo "  $LINT_OUT"

exec bash tools/ui-shot.sh tools/steps/ui-pages.json "${1:-${TEMP:-/tmp}/haoai-smoke}"
