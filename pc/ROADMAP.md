# HaoAI PC 端：功能缺口地图与施工批次（2026-09-28 整理）

这份文档回答一个问题：**跟 ZCODE、OpenClaw、Hermes、以及 vibe-coding 那一类 agent 比，我们还缺什么，先做哪个。**
每一条都对本仓库 grep 过 —— "没有"是查过的没有，不是印象。

## 0. 证据来源（谁说的）

| 来源 | 怎么拿到的 | 取了什么 |
|---|---|---|
| ZCODE（用户天天用） | 本机 `E:\ZCode\` v3.14.3：`resources\glm\zcode.cjs` 的 `/help` 表、18 个内置插件、`resources\app.asar` 的 i18n 键 | 多标签会话/时间线、终端多标签、Git 面板、命令面板 quickPick、`/rewind` 检查点、`/workflow` 工作流、`/fork`、subagent 可配 model/tools/color、内嵌浏览器 IAB、插件市场 40 款、`/mode plan\|build\|edit\|yolo` |
| OpenClaw（认可其 UI） | 本机 `~\.openclaw\openclaw.json` + npm 包 `openclaw@2026.8.1` | control-ui 的 20+ 页面（会话/审批/技能/cron/任务/设备/用量/仪表盘/凭据）、flows、hooks（session-memory、compaction-notifier、command-logger）、多 agent 各配 workspace+模型、subagents 并发 8、image/video-generation、tts、secrets、memory-wiki、飞书通道、iOS/Android 节点、exec-approvals |
| Hermes | `github.com/NousResearch/hermes-agent` 文档 | 单 gateway 连多平台、会话连续、语音转写、`execute_code`、cron 自然语言、结果投递、FTS5 跨会话检索、7 种终端后端（本地/Docker/SSH/…）、技能自我改进 |
| vibe-coding 头部 | Claude Code / Codex / OpenCode 文档 | 计划模式、沙箱与权限、Hooks、Skills、子 agent 与后台 agent、Routines 定时与 `/loop`、`/share`、`/undo`、IDE 内联 diff、code review、定时任务 |
| star 清单 | `api.github.com/users/a953179255/starred`（私有 list 拿不到，退回全量 57 个 star 筛 agent/coding） | claude-code、codex、opencode、hermes-agent、openclaw、ZCode、deepseek-harness、pi、mem0、ClawPanel 等 30 个 |

## 1. 我们已经有的（别重复造）

多会话并行（每会话独立引擎、事件按 sid 分流、同会话排队）· 断点恢复 · 18 把内置工具 + git + 浏览器 CDP + 桌面控制 · 权限规则表（S2）与三档 plan/ask/auto · 审批内联卡 + 键盘决定 y/a/n/r · 子任务与单独停 · 定时任务 · MCP 客户端 · 记忆（项目/全局）· 上下文压缩与水位 · 用量账本 · 回收站 · 图片进上下文 · HTML 沙箱预览 · 会话级工具开关 · @ 提及、Ctrl+V 截图、引用/删句/置顶 · 搜索与跳转。

右栏现有 6 个页签：`tab-tasks / tab-usage / tab-files / tab-mem / tab-cron / tab-tools`。
执行面现状：`Pty.kt` 是**管道**不是伪终端（`Pty.kt:25` 明说），`shell_open/send/read/close/list` 五把工具 + `shell_list`。

## 2. 缺口清单

格式：**编号 · 干什么** ｜ 对标 ｜ 现状（查证）｜ 对用户的理由 ｜ 验收判据 ｜ 涉及端

### P0 —— 用户点名 + 主用途

1. **备份导出/恢复（PC 端）** ｜ OpenClaw `backups/`、手机端 `DataBackupManager`（导出侧已落 `1c0c208`，恢复侧待做）｜ `pc/**/*.kt` grep `backup` **零命中**，PC 端一条会话误删只能靠 `.trash` ｜ 你明说"这两条都要做"；几十条会话 + 记忆 + 规则是真会丢的资产 ｜ 导出 → `manifest.jsonl` 每行一条 + sha256 → 恢复到空状态根，会话/记忆/设置/规则全在；被改动的条目按行号拒绝恢复 ｜ **PC**（manifest schema 与手机对齐，给后面跨端同步打底）
2. **真 PTY（ConPTY）+ `write_stdin` 语义** ｜ 对标 codex / dsh ｜ **2026-09-28 做过一轮，卡在半路，下面的实测结论别再重新推** ｜
   - **前提被推翻了一半**：管道下 `python -i` 其实能问能答（拿到 `>>> 42`）、`read` 式行提示也能过
     （`hi-wang`）。所以 ConPTY 的价值**不在**"边看边答"，而在这四件管道做不到的事：
     `isatty()` 为真（否则程序自己关颜色/关进度/拒绝运行）、输入**回显**、**Ctrl+C**、全屏 TUI。
     `Pty.kt:20-23` 那段"完全没法用"的说法要照这个改。
   - **伪终端本身建得起来**：`CreatePipe` × 2 + `CreatePseudoConsole(100x30)` 返回 hr=0、句柄有效。
     探可用性要**真开一个再关掉**，别读版本号：JVM 在 Windows 11 上把 `os.version` 报成 `10.0`，
     拿它比 ">= 1809" 会把支持的机器判成不支持（第一版就是这么红的）。
   - **卡点**：`CreateProcessW` 带 `EXTENDED_STARTUPINFO_PRESENT` 一律 `ERROR_INVALID_PARAMETER(87)`，而
     ① 同一个 STARTUPINFOEX（`cb=120`，偏移 104 处读回来确实是属性表指针）**不带这个 flag → 成功**；
     ② 用 jna-platform 自带的 `Kernel32.CreateProcess` + 普通 STARTUPINFO → **成功**（真起来了 cmd.exe，有 pid）；
     ③ `InitializeProcThreadAttributeList` 两趟（先问出 48 字节再初始化）返回 true，
       `UpdateProcThreadAttribute(PSEUDOCONSOLE, hpc, 8)` 也返回 true；
     ④ `lpValue` 传 HPCON 本身 / 传"指向 HPCON 的指针"，两种都 87；
     ⑤ `si`、`pi` 改成裸 `Pointer` 传（绕开 Structure marshalling）也 87。
     → 不是结构体布局、也不是字符串宽度（但**确实**踩过一条：`Native.load` 不传
     `W32APIOptions.UNICODE_OPTIONS` 时 `String` 按 ANSI 走，必须传）。
     剩下最可疑的是 `attribute` 这个 `DWORD_PTR` 参数在 JNA 里怎么过去（值 vs 指针）。
   - **下次从这里接着查**：(a) 用 `PROC_THREAD_ATTRIBUTE_HANDLE_LIST` 做对照，分清"属性表整体不行"
     还是"只有 PSEUDOCONSOLE 这项不行"；(b) 照抄一个能跑的开源调用序列（rust `portable-pty`、
     Java 的 conpty 绑定）逐参数对齐；(c) 小坑备忘：`WinBase.STARTUPINFO.cb` 是 `WinDef.DWORD` 不是 `Int`。
   - **别把量具算进结论**：`GetLastError()` 要在失败那一步**立刻**读（`DeleteProcThreadAttributeList`
     会覆盖它），而 `runCatching { ... }` 会把 JNA 的异常吞成 `false` —— 两个都做过之后，
     我拿到过一条"失败 err=0"的假线索，绕了三四轮。
   - **退路**（如果 ConPTY 一时拿不下）：`shell_open` 加 `tty` 选项，能建就建、建不了就在工具结果里
     写明"这台没有 TTY：Ctrl+C 与回显不可用"，而不是静默用管道假装成功。

3. **跨端：会话镜像 + 远程审批** ｜ OpenClaw gateway 多通道 + DM 配对审批、Hermes 单 gateway 跨平台会话连续 ｜ 两端完全隔离：PC 的审批卡只有本机能点，手机看不到 PC 在跑什么 ｜ 你对 PC 端的首要定位就是"和手机端联动" ｜ 手机配对后能看到 PC 待审卡并允许/拒绝，PC 真执行；会话只读回放；断开重连不重复决策 ｜ **两端**（需局域网端点 + token 配对，安全边界要写死）
4. **记忆条目化 + 两端同步** ｜ Hermes FTS5 跨会话检索、OpenClaw memory-wiki/dreaming、ZCODE `MEMORY.md` 索引 ｜ PC 的 `/api/memory` 是整段文本框：没有条目 CRUD、没有搜索、没有与手机共用 ｜ 助手与 vibe coding 的长期记忆都靠它，现在各写各的 ｜ 条目增删改查 + 搜索；手机端读到同一份 ｜ **两端**（依赖 #3 的通道）

### P1 —— 主用途衍生（视频创作 / Vibe coding / 认可的 UI）

5. **Git 面板** ｜ ZCODE Git 面板（暂存/提交/差异）｜ 只有 `git` 命令工具与审批用的 review 视图，**人**没有看 diff、勾选暂存、按提交的地方 ｜ vibe coding 的核心交互 ｜ 改动出现在面板 → 勾 1 个文件 → 提交 → `git log` 里有；未提交前有明确的"工作区不干净"提示 ｜ PC
6. **终端面板（人用的多标签 shell）** ｜ ZCODE 内置终端多标签、OpenClaw terminal 面板 ｜ shell_* 五把工具只有 agent 会用，人没界面；右栏无「终端」页签 ｜ 与 #2 是一对：工具给 agent，面板给人，**共用同一进程注册表**（人在界面开的 shell，agent 也能喂） ｜ 页签出现 → 开一个 shell → 输入回车有输出 → 空闲回收提示存在 → 像素剧本全绿 ｜ PC（排在 #2 之后）
7. **浏览器预览面板（画面进网页、可点）** ｜ ZCODE 内嵌浏览器 IAB、OpenClaw browser-panel ｜ `Browser.kt` 能控制 Edge，但界面里只能看工具回的截图，人不能"盯着它操作/伸手点一下" ｜ 视频自动化与看页面都要 ｜ 面板出现 CDP 实时画面 → 在面板上点一下 → 浏览器里真的收到点击（用 URL/文本变化断言）；**只操作已开的会话实例，不新开面** ｜ PC
8. **媒体工具（ffmpeg）** ｜ OpenClaw image/video-generation + media 播放转码、Hermes 语音 ｜ `pc/**` grep `ffmpeg|transcode` **零命中** ｜ 你直说要"视频制作自动化、帮直播和创作视频" —— 这是最直接的那块 ｜ 无 ffmpeg 时**明确报错并给出安装指引**（不能静默失败）；有则：转码 mp4→mp3、抽 1 帧 png、切 3 秒、抽音轨；产出进 `.haoao-output/` 并在产出面板能 `<video>/<audio>` 播放 ｜ PC
9. **录屏 + 直播控制** ｜ OpenClaw nodes `screen.record`（它自己 gateway 层 deny 了）｜ 无（grep `obs|record` 零命中）｜ 直播创作的下一步 ｜ 录一段 mp4 到产出面板可播；OBS（obs-websocket）先做"检测到才可用"，没装就说清楚 ｜ PC
10. **音视频附件 + 代码执行** ｜ ZCODE `code_execution`/`node-repl`、Codex `execute_code` ｜ 附件白名单只有文本+图片（`index.html:1187-1188`），mp4/wav 拖进去没有专门路径；无 `run_code` ｜ 视频工作流要递素材；vibe coding 要跑一段脚本看结果 ｜ 拖 mp4 → 附件胶囊显示类型与大小 → 模型收到落盘路径；`run_code` 跑出的 png 出现在产出面板；超时有界 ｜ PC
11. **命令面板（Ctrl+Shift+P）** ｜ ZCODE quickPick、OpenClaw command-palette ｜ `grep -c palette = 0`；只有聊天内 `/` 菜单与 Ctrl+K 搜会话 ｜ 认可的 UI 点之一：所有入口一个浮层找得到 ｜ 浮层模糊搜「会话 / 命令 / 设置项 / 文件」→ 上下键 → 回车执行；输入框聚焦时不该抢键（沿用 v0.42 的规矩）｜ PC
12. **任务运行历史（runs）** ｜ OpenClaw tasks/runs、ZCODE 后台任务列表 ｜ 定时任务有列表，但**每一次跑完不留档**，没有"上次几点跑的、成功没、重跑" ｜ 自动化必须可回看，否则不敢开定时 ｜ 跑 3 次 → 历史 3 条（时间/触发/结果摘要）→ 点重跑出第 4 条 ｜ PC

### P2 —— 增强项（排后面）

13. **工作区检查点 `/rewind`** ｜ ZCODE `/rewind`、OpenClaw applied-history ｜ 只有工具级快照（每会话 200 个/50MB）与 `/api/rollback`，没有"把整个工作区按一下存一个点、再回到它" ｜ 改坏了要整体回退 ｜ 建检查点 → 再改 → 回滚 → 多个文件内容回来 ｜ PC
14. **多标签/分屏会话** ｜ ZCODE 多标签 ｜ 单视图切会话（SSE 已按 sid 分流，地基在）｜ 对照代码与对照聊天时想并排看 ｜ 两条同时跑左右各自流式、不串台 ｜ PC
15. **会话分享快照（只读 HTML）** ｜ OpenCode `/share` ｜ 只有导出 markdown ｜ 给人看的成品形态 ｜ 导出的 HTML 无头浏览器打开，消息全在 ｜ PC
16. **技能 zip/URL 导入 + 技能目录页** ｜ ZCODE 插件市场 40 款、OpenClaw ClawHub ｜ `/api/skills` 只有读/增/删 ｜ 能力扩展要能"装进来" ｜ 上传含 `SKILL.md` 的 zip → 列表出现 → `/命令` 可用；zip 里带 `../` 越界路径 → 拒绝并说明 ｜ PC
17. **hooks（事件挂脚本）** ｜ ZCODE 7 个 hooks、OpenClaw session-memory/compaction-notifier ｜ 无 ｜ 想在回合结束时自动记一笔/推一个通知 ｜ 挂 `on-run-end` 脚本 → 跑完文件被写；脚本失败只记日志不打断会话 ｜ PC
18. **审批风险分级** ｜ OpenClaw exec-approvals（security=full）、Claude Code 权限模式 ｜ 规则表有（Policies.kt），但待审卡上没有"这条有多危险" ｜ 点允许之前要看得见风险 ｜ `rm -rf /` 标红高危、`echo` 标低危；**先做确定性规则打分，不烧模型** ｜ PC
19. **凭据条目管理** ｜ OpenClaw secrets ｜ 只有 `HAOAI_HOME/apikey`、`searchkey` 两个裸文件（有意不进 PcSettings，这条约束保留）｜ key 越来越多，要看得见、改得动、撤销掉 ｜ 设置页列出（值打码）、可改可删；`GET /api/settings` 永远不回传值 ｜ PC
20. **定时任务增强：自然语言建任务 + 结果投递** ｜ Hermes 自然语言 cron 与"结果投递任意平台"、OpenClaw isolated session ｜ cron 表单要人自己填字段；跑完的摘要**没有去处**（只在会话里） ｜ 自动化要"跑到哪儿了我看得见" ｜ 一句话 → 解析成 cron 并预览（确认才落库）→ 跑完摘要写进指定会话/文件 ｜ PC
21. **语音输入 / 回答朗读** ｜ Hermes 语音备忘与转写、Codex 语音 ｜ `grep speech = 0` ｜ 直播时口述、看结果不用读屏 ｜ 按钮在、无权限时给明确文案（headless 验不了权限，判据只能是"降级不炸"）｜ PC
22. **预设 agent（角色分组）** ｜ OpenClaw agents（main/ninya/code/media 各配模型与 workspace）、ZCODE bots ｜ 只有全局默认 + 按会话换模型 ｜ 直播助手 / 安卓开发 / 视频剪辑 三种人设常用 ｜ 建一个预设 → 新会话自动带它的模型、工作区、档位 ｜ PC
23. **工作流（把一串提示存成链）** ｜ ZCODE `/workflow` `/dwf` `/expert`、Claude Code Routines ｜ 无 ｜ vibe coding 里"先测再改再提交"这类固定套路 ｜ 存 2 步链 → 跑 → 两个用户轮次按序出现；第 2 步失败不跑第 3 步 ｜ PC
24. **`pc/` 并入 `:core`（工程债）** ｜ 内部 ｜ `pc/` 仍是独立 Gradle 构建，与手机端各写各的 Engine ｜ 跨端（#3/#4）之后两边引擎代码会长期漂移 ｜ 根构建能 `:core:test`；会话模型与工具接口两端共用 ｜ 两端

### 本阶段明确不做

在线插件市场（无需求且要带审核）、多消息通道（Telegram/飞书等，用户没提）、云端会话与分享到公网、
容器/SSH 沙箱后端（Hermes 那 7 种，等真的需要隔离环境再说）、移动端 UI 大改（除非跨端必须）、
按模型的风险预判（先上确定性规则打分）。

## 3. 施工批次（一批 = 一个可回滚的提交单元，批内自带测试 + 像素 + README 段）

| 批 | 内容 | 依赖 | 端 |
|---|---|---|---|
| **B1** | 备份导出/恢复（manifest + sha256 + 恢复校验 + 设置页入口） | — | PC |
| **B2** | 真 PTY/ConPTY：`shell_*` 升级为带 TTY（或新增 `shell_pty`），补 `write_stdin` 语义 | — | PC |
| **B3** | 终端面板（右栏「终端」页签、多标签、与 agent 共用进程注册表） | B2 | PC |
| **B4** | 跨端通道（局域网端点 + token 配对）+ 会话只读镜像 + 远程审批 | — | 两端 |
| **B5** | Git 面板（status/勾选暂存/看 diff/提交，走 S2 审批） | — | PC |
| **B6** | 命令面板 + 任务运行历史 | — | PC |
| **B7** | 媒体工具 ffmpeg（转码/剪切/抽帧/抽音轨/封面）+ 产出媒体预览 | — | PC |
| **B8** | 浏览器预览面板（CDP 画面进网页 + 点按回传） | — | PC |
| **B9** | 音视频附件 + `run_code` | — | PC |
| **B10** | 记忆条目化（CRUD/搜索）+ 两端同步 | B4 | 两端 |
| **B11** | 技能 zip/URL 导入 + 技能目录页 + hooks + 凭据条目 + 审批风险分级 | — | PC |
| **B12** | 工作区检查点 /rewind + 多标签分屏 + 只读分享快照 | — | PC |
| **B13** | 定时任务自然语言与投递 + 语音 + 预设 agent + 工作流链 | — | PC |
| **B14** | 录屏（OBS 后置） | B7 | PC |
| **B15** | `:core` 合并 | B4 | 两端 |

**执行顺序**：B1 → B2 → B3 → B5 → B6 → B7 → B8 → B9 → B4 → B10 → B11 → B12 → B13 → B14 → B15
（B4 跨端通道放在前面几批做完、手稳了再动它 —— 它要开监听端口，安全边界必须一次做对。）

## 4. 每一批的完成判据（统一口径）

1. Kotlin 测试覆盖新行为（判据看**结果**，不看返回 200）；
2. `node tools/ui-check.js` + `node tools/md-check.js` 全绿；
3. 有 UI 的批次必须有 `tools/steps/ui-*.json` 像素剧本，截图我亲自看过；
4. README 加「这一批」小节，测试总数更新；
5. **一次提交一次推送**（你要能逐批回滚），提交信息写清"为什么"。
