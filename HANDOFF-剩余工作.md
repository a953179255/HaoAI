# 交接：T2 批量问卷收尾 + 安卓两件 + B4 实验（记录：2026-10-02 本会话）

给接手 Agent：从 §1 开始按顺序做。背景结论（§5）是已核实的事实，不用重查。

## 0. 大盘状态

- HEAD = `ee9901e`（B22/T1：PC `ask_user` 对齐手机端，已 commit，**未 push**——本会话内 github:443 一直连不上，push 前先重试）。
- 工作区**未提交**：T2（PC 批量问卷全套）+ 安卓 4 个文件 + mock + 1 个新像素剧本（清单见 §6）。
- **当前卡点**：手机端编译红 1 处（§1），修完才能继续。
- PC 侧最后一次全绿：`gradle test` **554 通过**（545 B22 基线 + 新增 9）；`ui-askbatch.json` 像素 **20 步/14 条全过**。

## 1. 当前卡点（先修这个）

```text
e: file:///G:/HaoAI/app/src/main/java/com/haoai/agent/platform/PcLink.kt:108:49
   Cannot infer type for type parameter 'T'. Specify it explicitly.
```

复现：`gradle.bat -p G:\HaoAI :app:compileDebugKotlin`（gradle 路径见 §7）。

背景：kotlinx-serialization **1.9.0** 把 `Decoder`/`Encoder` 从顶层包搬到了
`kotlinx.serialization.encoding`（已修好 import，见 PcLink.kt 头部）；
顶层 `serialDescriptor()` 函数已不在（已改用 `ListSerializer(String.serializer()).descriptor`）。
报错行 108 在新增的 `PcOptListSerializer` 对象体内（给 `PcPayload.options` 做的
"字符串 | {label,description} 对象"兼容解码）。

排查建议（按顺序）：
1. 重跑一次编译看**完整错误上下文**（之前抓的日志行被截断，只有一行）。
2. 重点看 `String.serializer()` / `decodeSerializableValue` / `encodeSerializableValue`
   处的泛型推断——`import kotlinx.serialization.serializer` 是否还在、是否被别名遮挡。
3. 若 import 全对仍红，考虑改写 `descriptor` 为显式类型：
   `ListSerializer(String.serializer()).descriptor` 已是显式，退路是把整个自定义
   serializer 改为 `JsonTransformingSerializer`（只做 decode，不写 serialize）。

## 2. 已做完（不用重做，判据都在）

- **T2 PC 全套**（与手机端 `AskUserBatchTool` 同契约）：`Tools.kt`（`Gate.askBatch` 默认桥
  逐题落单题 ask＋工具本体＋`askBatchParams`＋注册）、`Approval.kt`（`awaitAskBatch`＋
  公共收尾 `awaitFut`＋`parseBatchAnswers`）、`Server.kt`（Gate 覆写＋`lanPendingRow`
  批量透传＋`decideNote` 批量分支）、`Prompt.kt`（batch 分流句）、`index.html`
  （批量本地循环卡＋CSS）、`phone.html`（批量卡，状态挂 `window.__batchSt` 跨 4 秒刷新存活）。
  判据：`AskUserBatchToolTest` 6 条（对标手机端 5 条＋默认桥 1 条）、`ApprovalBrokerTest`
  批量载荷、`EngineFlowTest` 端到端（走默认桥）、`LanTest` 批量行、`HunkTest`
  decideNote 批量措辞、计数锁 23→24（DesktopTest/EngineFlowTest）、mock `ask_batch`
  场景、像素 `ui-askbatch.json` 20 步/14 条全绿、截图已亲验
 （`haoai-ui-shot/bq01-question-one.png`、`bq02-question-three.png`：进度/题面/选项全对位）。
- **安卓**：`PcWatchdog` 看门狗 #10（循环体 try-catch＋`lastPollAt`＋`ensureRunning`＋
  finally 置 polling=false；`State.lastPollAt`；批量通知措辞）、`PcLinkScreen`
  （进屏 `LaunchedEffect` 自检＋"上次轮询/停了 N 分钟" chip）、`PcLink`
  （`PcOptListSerializer`＋`batch`/`questionCount` 字段，**待编译验证**）、`PcLinkTest` 3 用例。
- **mock**：`pc/tools/mock-openai.py` 新增 `ask_batch` 场景（3 题一次提交）。
- **用户拍板**（ROADMAP 待同步，见 §4）：#35 旧中文路径**全部保留不动**；B13 语音输入**不做**
  （用户用电脑输入法语音转文字）。

## 3. 待做清单（按顺序）

1. 修 §1 → `:app:compileDebugKotlin` 绿。
2. `:app:testDebugUnitTest` 全绿（含新增 `PcLinkTest` 3 条）。
3. `node tools/ui-check.js` + `node tools/md-check.js` 重跑（`ui-askbatch.json`
   在上次双检后又改过 3 次：join 分隔符 bug、done 态竞速、`\n` 过度转义）。
4. `assembleRelease` ＋装回真机（`install -r` + 通知授权，命令见 §7）。
5. **B4 回收实验**：先确认手机"严格省电"开关当前状态 → 息屏长等 30min+ →
   看进程死没死（pid）、通知到没到。诱发不出回收 → 按既有证据销账
   （Flyme 息屏不杀进程，见 ROADMAP 5.2；"提问通知没在真机看过"已由 5.2 销账）。
6. **文档**（都在 `pc/` 下）：
   - ROADMAP 5.2 item2：`#35`→"已核，全部保留"销账；
   - ROADMAP B10 行（约 120 行）：删"还欠"——第二步**已由别的会话当晚完工**
     （`DreamYield`＋`DreamYieldTest` 6 条，ledger 152 行有记录，行文没同步）；
   - ROADMAP B13 行：digest 推通知已在 5.2 落地（`PcDigestWatch`＋`haoai_pc_done`
     通道，真机验过）＋语音输入"用户决定不做"；
   - ROADMAP 新增 T2 行 ＋ README 新增 T2 节（参照既有 "B22" 节写法）；
   - B4 实验结论写进对应行。
7. `git add -A && commit` ＋ `push`（网络若仍断就留着；注意 HEAD 已有 `ee9901e` 未推，
   别 `rebase`，直接在上面叠）。
8. 可选（时间够再做）：手机网页端批量卡像素（只做了桌面 `ui-askbatch`；单题手机像素
   `ui-phoneask` 仍绿，可参照它的配对流程拼一条批量版）。

## 4. 已知事实（核查过，直接用）

- B10 第二步已完工（别重做，只同步文档）。
- B13 结果推通知已完工（`PcDigestWatch`＋通道，5.2 真机验过）；语音输入用户明确不做。
- T1 的连带风险已修一半：PC `lanPendingRow` 现发对象 options，旧 APK（B15-2 版，
  手机上现在装的就是）按 `List<String>` 解析会整份失败——安卓侧兼容解码就是 §1
  在修的这处，**修完必须装机**，否则通知通道（审批＋提问）会哑。
- `askTimeoutSec` 保持 900s 是有意决定（无人值守不挂死；手机端无限等是已知差异）。
- 通知按钮直答是设计（确认步属于卡面，手机端原生亦如此）。
- 批量回执不许外泄数组原文（`decideNote` 批量分支只说题数）。
- `lanPendingRow` 批量：`options` 置空、`questions` 整份透传、`questionCount`、
  `title` 取题组名（`/api/state` pending 投影与旧像素只认 `question` 键，broker
  载荷里 `question` 字段放的就是标题）。
- 像素注意：`must` 语义是 truthy；第 17 步是"抢 done 卡或以落库为准"双保险
  （`via=dom/state`），因历史重画会抹掉待决卡；`key` 步骤依赖空框焦点规则
  （`ui-askkeys` 既有模式）。

## 5. 工作区改动清单（`git status`，未提交）

- `app/.../platform/PcLink.kt`：`PcPayload.options` 自定义解码＋`batch`/`questionCount`＋import 迁移。
- `app/.../platform/PcWatchdog.kt`：看门狗 #10＋`State.lastPollAt`＋批量通知措辞。
- `app/.../ui/settings/PcLinkScreen.kt`：进屏自检＋轮询状态 chip。
- `app/.../platform/PcLinkTest.kt`：＋3 用例（对象选项／旧字符串／批量行）。
- `pc/.../Approval.kt`：`awaitAskBatch`＋`awaitFut`＋`parseBatchAnswers`。
- `pc/.../Prompt.kt`：＋batch 分流句（手机端 SystemPrompt 同款）。
- `pc/.../Server.kt`：`askBatch` 覆写＋LAN 批量透传＋`decideNote` 批量分支。
- `pc/.../Tools.kt`：`Gate.askBatch` 默认桥＋`AskUserBatchTool`＋schema＋注册。
- `pc/.../ui/index.html`：批量本地循环分支＋CSS。
- `pc/.../ui/phone.html`：批量卡（`window.__batchSt`）——单题 B22 改动已在 `ee9901e` 内。
- `pc` 测试：`ApprovalBrokerTest`（批量载荷）、`DesktopTest`（24）、`EngineFlowTest`
  （24＋batch e2e＋`askedQs`）、`HunkTest`（批量回执）、`LanTest`（批量行）。
- `pc/tools/mock-openai.py`：＋`ask_batch` 场景。
- 新文件：`pc/src/test/.../AskUserBatchToolTest.kt`、`pc/tools/steps/ui-askbatch.json`。

## 6. 环境速查

- Gradle：`C:\Users\95317\.gradle\wrapper\dists\gradle-9.6.0-bin\42k10rwplmzkhuboz9kdazi7s\gradle-9.6.0\bin\gradle.bat`
  PC 在 `G:\HaoAI\pc` 直接跑；安卓用 `-p G:\HaoAI :app:...`。
- 手机：`adb -s adb-391QYFCP2266T-VtTJb1._adb-tls-connect._tcp`；
  APK：`G:\HaoAI\app\build\outputs\apk\release\app-release.apk`，`install -r`＋
  `pm grant com.haoai.agent android.permission.POST_NOTIFICATIONS`。
- 像素（工作目录 `G:\HaoAI`）：
  `SHOT_MODE=ask_batch bash pc/tools/ui-shot.sh pc/tools/steps/ui-askbatch.json`；
  单题手机：`SHOT_MODE=ask SHOT_PERM=ask PRE_LAN=1 SHOT_MOBILE=1 SHOT_W=390 SHOT_H=844 bash pc/tools/ui-shot.sh pc/tools/steps/ui-phoneask.json`。
- 截图目录：`C:\Users\95317\AppData\Local\Temp\haoai-ui-shot\`。

---

## 7. 收尾状态（接手 Agent 2026-10-02 补，逐条对 §3）

| §3 | 状态 | 证据 |
|---|---|---|
| 1 修编译红 | ✅ | 根因是 `String.serializer()` 缺 `kotlinx.serialization.builtins.serializer` 这个 import（1.9.0 搬包之后才暴露），四处红一次修完；`:app:compileDebugKotlin` 绿 |
| 2 安卓全量 | ✅ **238 条 0 红** | 但**新增的三条夹具本身是坏的**：`"payload":` 后面漏了 `{` → JSON 不合法 → 两条用例红，红话还是"电脑回的不是 JSON"（看着像产品坏了）。补 `{` 后转绿；另做了变异检查：摘掉 `PcOptListSerializer` 自定义解码，对象选项那条立刻红 —— 判据真咬在产品代码上 |
| 3 双检 + 像素 | ✅ | `ui-check`/`md-check` 过；`ui-askbatch` 20 步/14 条判据全绿，四张图亲验（第 1 题、第 3 题、提交后收成已答、落库原文）；PC `gradle test` **554 条 fresh 重跑 0 红**（不是复用上次的 XML：`cleanTest test`）；**全量像素审计也重跑了：66 份 / 红 0 份 / MISS 0 份**（含新加的 `ui-askbatch`，零判据剧本仍是 0） |
| 4 装机 | ✅ **14:35 装回**（无线调试恢复后） | `app-release.apk` = versionCode 39 / 0.18.6，`install -r` 覆盖装、**数据没清**（会话与配对都在）、Flyme 那道安装闸门要过、装完把无障碍权限补回。§4 那条警告**作废**：带新解码的包已经在机上。装机顺带发现并修掉的更大的问题：**电脑上局域网端点原本是关的**，而手机此前配的是当晚测试用的假端点 → `lan on --port 8953`（避开审计用的 8720-8890）+ 起真 `serve` + 手机重新配对，两端都读到成对结果（手机「上次轮询 0 秒前」＋「上一次问：问过了：电脑上没有要批的」；电脑 `lan devices` 见到 `MEIZU 20 Pro`）。端口与开关落在 `lan.json`，**下次双击 exe 会自动带着局域网起** |
| 5 B4 回收实验 | 🟡 **重跑了，只净剩 5 分钟灭屏** | 15:02:55 起确认 `mWakefulness=Dozing`，灭屏段内电脑侧读到的心跳**间隔恒 20 秒、零漏拍、手机 pid 全程未变**；15:09 另一路 scrcpy 点亮屏幕并进了电脑联动页，窗口被打断（后 16 分钟亮屏，不算灭屏证据）。**教训**：这条实验的前置条件是"有一段没人碰手机的 35 分钟"，不是"我起了一个 35 分钟的定时器"——同一台设备多路共用时，先把"谁在用"量出来再决定跑不跑。"轮询冻住能否自己爬起来"仍待设备空闲时补（做法写在 ROADMAP 5.1 #10 行末） |
| 6 文档 | ✅ | ROADMAP：B10 行删"还欠"、B13 行改口（digest 已落 + 语音用户裁定不做）、5.1 #10 看门狗销账并写清落了什么、5.2 #35 销账（全部保留不动）、新增 T2 行、顺手把 B22 行那个全角 `｜` 改回半角让"依赖"真的成一格；README：新增「T2」节 + **给 `ui-askbatch` 补了可抄的跑法**（不补，`audit-steps.sh` 会把它算成 MISS 红掉——这条规矩是 09-30 那晚立的） |
| 7 提交 + 推送 | ✅ | `ee9901e` 与之后每一笔都已推到 `origin/master`（github:443 确实时通时断：第一次 push 超时，重试即通——**push 失败要重试而不是当作没做**） |
| 8 手机网页端批量卡像素 | ✅ 已补 | 新剧本 `ui-phoneaskbatch`（390×844，23 步 / 35 条判据，含"手动触发一次 refresh() 之后仍停在第二题"这条状态判据）。**它第一次跑就把手机页跑崩了**：待批整块只剩 `[object HTMLDivElement]`（批量分支 return 了 DOM 元素，外层是 innerHTML 拼字符串）；修好之后又暴露第二条——手机上答完、电脑上落的是"1. 未作答 2. 未作答 3. 未作答"（`/lan/decide` 用 `field()` 取 answer，正则遇引号就停，`JSON.stringify([...])` 只截到 `[`）。两条都修了 + 各钉一道判据（像素 `noStringifiedNode`/`answerLanded` + `LanTest` 新用例，变异验过）。PC 侧随后 `cleanTest test` 555 全绿。教训写进 README：**标"可选"的那份像素恰恰是唯一盲区** |

**下一次接手的顺序**：① 手机回到 Wi-Fi 并开无线调试 → `assembleRelease` + `install -r` + 通知授权 + 补无障碍；
② 装机后跑一遍 B4（看 chip 会不会从"停了 N 分钟"自己回到"几秒前"）；③ 有空补手机网页端批量卡的像素。
