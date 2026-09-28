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

3. **跨端：会话镜像 + 远程审批** —— 🟡 v0.51.0 落了 **PC 侧**（`Lan.kt`：配对码 6 位 / 120 秒 / 一次一用 / 错 8 次作废；设备 token 只存 SHA-256 且常量时间比较；端点默认不监听；`/lan/pending` 只列审批不列问答；镜像只给工作区目录名不给绝对路径）｜ OpenClaw gateway 多通道 + DM 配对审批、Hermes 单 gateway 跨平台会话连续 ｜ 原缺陷：两端完全隔离，PC 的审批卡只有本机能点 ｜ 你对 PC 端的首要定位就是"和手机端联动" ｜ **还欠手机端那一半**：配对界面、镜像列表、审批卡片（要动 Android 端与模拟器） ｜ 安全边界已写死并测过（12 条 `LanTest`）｜ **两端**
4. **记忆条目化 + 两端同步** ｜ Hermes FTS5 跨会话检索、OpenClaw memory-wiki/dreaming、ZCODE `MEMORY.md` 索引 ｜ 🟡 **PC 侧 v0.67.0 已落地**：`Memories.kt` 把工作区 `MEMORY.md` 读成一行一条，**格式与手机端 `MemoryBank.kt` 逐字对齐**（~~那边没有单测钉住格式~~ → **v0.71.0 两端各有一份锁**：手机端 `MemoryBankFormatTest` 用真实写盘量 PC 的解析正则，PC 这边有"照手机端形状读 + 幂等 + 不许擦掉对方文件头说明"）；条目 CRUD / 搜索 / 软取代；注入按 k=8、单条 180 字、总 1200 字挑，打分公式与手机端同一组常数；`/api/memories` + 记忆页签顶部那块条目区 ｜ **还欠**：两端同步（走 B4 那条通道，要先定谁是主 —— 两边都开自动整理会互相整理对方的文件）｜ **v0.69.0 又补了 PC 侧两块**：使用反馈按手机端同一组口径回写（一条每小时最多 +1、`uq` 只数没见过的查询指纹、打 `rc:1` 防召回环；只有真发请求记，看上下文占用不记），以及"整理一次"= 手动两步确认（降级 30 天没用且重要度≤2 / 合并归一化或 Jaccard>0.85 的重复 / 清掉失效满 30 天；**合并不是删掉而是指到保留那条**，误合能改回来）｜ 助手与 vibe coding 的长期记忆都靠它 ｜ **两端**

### P1 —— 主用途衍生（视频创作 / Vibe coding / 认可的 UI）

5. **Git 面板** —— ✅ v0.46.0 已落地（`GitPanel.kt` + 四个 `/api/git*` + 右栏「Git」页签；
   与清单里写的一处偏差：**人点按钮即审批**，所以这四个端点不再走 S2 闸，边界改由路径白名单与"面板上没有 push 类动词"守住）。｜ ZCODE Git 面板（暂存/提交/差异）｜ 只有 `git` 命令工具与审批用的 review 视图，**人**没有看 diff、勾选暂存、按提交的地方 ｜ vibe coding 的核心交互 ｜ 改动出现在面板 → 勾 1 个文件 → 提交 → `git log` 里有；未提交前有明确的"工作区不干净"提示 ｜ PC
6. **终端面板（人用的多标签 shell）** ｜ ZCODE 内置终端多标签、OpenClaw terminal 面板 ｜ shell_* 五把工具只有 agent 会用，人没界面；右栏无「终端」页签 ｜ 与 #2 是一对：工具给 agent，面板给人，**共用同一进程注册表**（人在界面开的 shell，agent 也能喂） ｜ 页签出现 → 开一个 shell → 输入回车有输出 → 空闲回收提示存在 → 像素剧本全绿 ｜ PC（排在 #2 之后）
7. **浏览器预览面板（画面进网页、可点）** —— ✅ v0.50.0 已落地（右栏「预览」页签 + `PreviewPanel` + 六个 `/api/preview*`）。｜ ZCODE 内嵌浏览器 IAB、OpenClaw browser-panel ｜ `Browser.kt` 能控制 Edge，但界面里只能看工具回的截图，人不能"盯着它操作/伸手点一下" ｜ 视频自动化与看页面都要 ｜ **实测**：真 Edge 起一台（独立配置目录），靶页标题从 `预览靶页 0` → 送入中文后带上 `你好呀` → 点画面正中让靶页计数器 +1（换算按视口比例，不是 element.click()）；帧 `naturalWidth=921`；切走页签轮询真停；「关掉这台浏览器」后端口不再应答。｜ **与原案的一处偏差**：原写"只操作已开的实例"，实现是**读路径（看状态/取帧）绝不 spawn，只有人按打开/送入/点/切标签才允许起一台** —— 光开页签就有副作用不行，但完全不给人开口的面板没用 ｜ PC
8. **媒体工具（ffmpeg）** —— ✅ v0.45.0 落了前六下（`Media.kt` + `media` 工具 + `/api/media` Range +「产出」直接播）；v0.66.0 补上**成片那四下**：`srt`（写字幕，不碰 ffmpeg）、`subtitle`（把字幕烧进画面）、`caption`（封面叠中文大标题）、`join`（两段拼一段，concat 滤镜 + 先探音轨）。｜ OpenClaw image/video-generation + media 播放转码、Hermes 语音 ｜ `pc/**` grep `ffmpeg|transcode` **零命中** ｜ 你直说要"视频制作自动化、帮直播和创作视频" —— 这是最直接的那块 ｜ 无 ffmpeg 时**明确报错并给出安装指引**（不能静默失败）；有则：转码 mp4→mp3、抽 1 帧 png、切 3 秒、抽音轨；产出进 `.haoao-output/` 并在产出面板能 `<video>/<audio>` 播放 ｜ PC
9. **录屏 + 直播控制** —— ✅ v0.53.0 落了录屏（`Record.kt` + 第 22 把工具 `record`；录 → 停 → 界面里立刻能播 → 交给 `media` 剪；停止走 stdin 的 `q` 而不是 kill，否则 mp4 少 moov box 变成"存在但放不出来"）。OBS 那条（obs-websocket，检测到才可用）待做 ｜ OpenClaw nodes `screen.record`（它自己 gateway 层 deny 了）｜ 原缺陷：grep `obs|record` 零命中 ｜ 直播创作的下一步 ｜ **实测**：真录到这台机器 3840×1080 的桌面，浏览器解码出 videoWidth=3840、约 1s / 410248 字节 ｜ PC
10. **音视频附件 + 代码执行** —— ✅ v0.49.0 已落地（附件按扩展名分 `images`/`media` 两条路，`run_code` 跑 python/node 并把运行目录里新出的图交回模型）。｜ ZCODE `code_execution`/`node-repl`、Codex `execute_code` ｜ 原缺陷：附件白名单只有文本+图片，mp4/wav 拖进去没有专门路径；无 `run_code` ｜ 视频工作流要递素材；vibe coding 要跑一段脚本看结果 ｜ 实测：chip 带 `▶` 与字节数、气泡里播放器真解码 320×240/4s、刷新重放还在、`run_code` 的 dot.png 到模型手里（naturalWidth=1）、计划模式拒跑且不留目录 ｜ PC
11. **命令面板（Ctrl+Shift+P）** —— ✅ v0.47.0 已落地。｜ ZCODE quickPick、OpenClaw command-palette ｜ `grep -c palette = 0`；只有聊天内 `/` 菜单与 Ctrl+K 搜会话 ｜ 认可的 UI 点之一：所有入口一个浮层找得到 ｜ 浮层模糊搜「会话 / 命令 / 设置项 / 文件」→ 上下键 → 回车执行；输入框聚焦时不该抢键（沿用 v0.42 的规矩）｜ PC
12. **任务运行历史（runs）** —— ✅ v0.47.0 已落地（`RunLedger` + 用量页签底部 + ↻ 填回输入框）。｜ OpenClaw tasks/runs、ZCODE 后台任务列表 ｜ 定时任务有列表，但**每一次跑完不留档**，没有"上次几点跑的、成功没、重跑" ｜ 自动化必须可回看，否则不敢开定时 ｜ 跑 3 次 → 历史 3 条（时间/触发/结果摘要）→ 点重跑出第 4 条 ｜ PC

### P2 —— 增强项（排后面）

13. **工作区检查点 `/rewind`** ｜ ZCODE `/rewind`、OpenClaw applied-history ｜ 只有工具级快照（每会话 200 个/50MB）与 `/api/rollback`，没有"把整个工作区按一下存一个点、再回到它" ｜ 改坏了要整体回退 ｜ 建检查点 → 再改 → 回滚 → 多个文件内容回来 ｜ PC —— 🟡 v0.52.0 落了检查点与整轮回滚（`Checkpoints.kt` + 「产出」页签那颗钮，两步确认）；分屏（v0.65.0）与分享快照（v0.64.0）都已落；**v0.70.0 补上最后一寸**：消息动作里那颗「回到这里（含文件）」= 截历史 + 退这一轮及其之后所有轮的文件改动（`Checkpoints.begin` 多记一个 `at`=这一轮开头那句的下标，才对得上轮次；老账本没有 `at` 就直接拒绝，不许按时间猜）
14. **多标签/分屏会话** ｜ ZCODE 多标签 ｜ 单视图切会话（SSE 已按 sid 分流，地基在）｜ 对照代码与对照聊天时想并排看 ｜ 两条同时跑左右各自流式、不串台 —— ✅ v0.65.0 已落地（顶栏 ▥ 把"刚才看的那条"并到右边；**每一格自己是一条滚动容器**，贴底才跟着走；点任意一格换"这句话发到哪一条"；每格顶上 sticky 标题条标出输入对象；`ui-check.js` 从此盯"写死 `.view.on` 的选择器必须也管 `.duo`"）
15. **会话分享快照（只读 HTML）** ｜ OpenCode `/share` ｜ 只有导出 markdown ｜ 给人看的成品形态 ｜ 导出的 HTML 无头浏览器打开，消息全在 ｜ PC —— ✅ v0.64.0 已落地（`Share.kt` + 顶栏 ↗；零脚本、零外链、markdown 在服务端渲一小撮；读回来的 name 过 `safeName` + canonical 两道）
16. **技能 zip/URL 导入 + 技能目录页** ｜ ZCODE 插件市场 40 款、OpenClaw ClawHub ｜ `/api/skills` 只有读/增/删 ｜ 能力扩展要能"装进来" ｜ 上传含 `SKILL.md` 的 zip → 列表出现 → `/命令` 可用；zip 里带 `../` 越界路径 → 拒绝并说明 ｜ PC —— ✅ v0.63.0 已落地（`SkillDocs.kt`：`skills/<slug>/SKILL.md` + YAML 头，与手机端同一形状；三个入口=粘贴/网址/本地 zip；越界条目被拒且**报出来**；这版是"用户用 `/` 唤起"，模型自选要连着 `promptIndex` + `skill` 工具一起做）
17. **hooks（事件挂脚本）** ｜ ZCODE 7 个 hooks、OpenClaw session-memory/compaction-notifier ｜ 无 ｜ 想在回合结束时自动记一笔/推一个通知 ｜ 挂 `on-run-end` 脚本 → 跑完文件被写；脚本失败只记日志不打断会话 ｜ PC —— ✅ v0.62.0 已落地（`Hooks.kt` + `/api/hooks`，事件 `run-end`，上下文走 `HAOAI_*` 环境变量、结论全文走 `$HAOAI_OUTFILE` 文件；只有用户能写命令，所以不过审批闸口）
18. **审批风险分级** ｜ OpenClaw exec-approvals（security=full）、Claude Code 权限模式 ｜ 规则表有（Policies.kt），但待审卡上没有"这条有多危险" ｜ 点允许之前要看得见风险 ｜ `rm -rf /` 标红高危、`echo` 标低危；**先做确定性规则打分，不烧模型** ｜ PC —— ✅ v0.59.0 已落地（`Risk.kt` 三档 + 卡上徽标与"为什么"；**auto 档从此不为高危自动放行**）
19. **凭据条目管理** ｜ OpenClaw secrets ｜ 只有 `HAOAI_HOME/apikey`、`searchkey` 两个裸文件（有意不进 PcSettings，这条约束保留）｜ key 越来越多，要看得见、改得动、撤销掉 ｜ 设置页列出（值打码）、可改可删；`GET /api/settings` 永远不回传值 ｜ PC —— ✅ v0.61.0 已落地（`Secrets.kt` + `/api/secrets` 读/改/撤，掩码只给前 2 后 2；`/api/settings` 连以前那 6 位 `keyHint` 也收掉了）
20. **定时任务增强：自然语言建任务 + 结果投递** ｜ Hermes 自然语言 cron 与"结果投递任意平台"、OpenClaw isolated session ｜ cron 表单要人自己填字段；跑完的摘要**没有去处**（只在会话里） ｜ 自动化要"跑到哪儿了我看得见" ｜ 一句话 → 解析成 cron 并预览（确认才落库）→ 跑完摘要写进指定会话/文件 ｜ PC
21. **语音输入 / 回答朗读** ｜ Hermes 语音备忘与转写、Codex 语音 ｜ `grep speech = 0` ｜ 直播时口述、看结果不用读屏 ｜ 按钮在、无权限时给明确文案（headless 验不了权限，判据只能是"降级不炸"）｜ PC
22. **预设 agent（角色分组）** ｜ OpenClaw agents（main/ninya/code/media 各配模型与 workspace）、ZCODE bots ｜ 只有全局默认 + 按会话换模型 ｜ 直播助手 / 安卓开发 / 视频剪辑 三种人设常用 ｜ 建一个预设 → 新会话自动带它的模型、工作区、档位 ｜ PC —— ✅ v0.60.0 已落地（`Presets.kt` + 右栏「角色」页签 + 顶栏角色徽标 + 人设进系统提示的「本次角色」）
23. **工作流（把一串提示存成链）** ｜ ZCODE `/workflow` `/dwf` `/expert`、Claude Code Routines ｜ 无 ｜ vibe coding 里"先测再改再提交"这类固定套路 ｜ 存 2 步链 → 跑 → 两个用户轮次按序出现；第 2 步失败不跑第 3 步 ｜ PC
24. **`pc/` 并入 `:core`（工程债）** ｜ 内部 ｜ `pc/` 仍是独立 Gradle 构建，与手机端各写各的 Engine ｜ 跨端（#3/#4）之后两边引擎代码会长期漂移 ｜ 根构建能 `:core:test`；会话模型与工具接口两端共用 ｜ 两端

25. **子任务可配模型与工具** ｜ ZCODE subagents（各配 model/tools/color）、OpenClaw 多 agent（各配 workspace+模型）｜ 原先 `task` 整份继承父会话的模型与工具，等于拿最贵的模型干最便宜的活 ｜ vibe coding 里"读三份日志归纳"与"据此定方案"该用不同档的模型 ｜ ✅ v0.68.0 已落地：`task` 多 `model`/`tools`/`mode` 三个参数；**档位只能更严**（plan 派不出写的子任务）、工具只能是父会话已有的子集（要没有的明确拒绝并列出手里有的）、配置写在子任务卡**标题**上（收起也看得见，且活得过刷新）

### 本阶段明确不做

在线插件市场（无需求且要带审核）、多消息通道（Telegram/飞书等，用户没提）、云端会话与分享到公网、
容器/SSH 沙箱后端（Hermes 那 7 种，等真的需要隔离环境再说）、移动端 UI 大改（除非跨端必须）、
按模型的风险预判（先上确定性规则打分）。

## 3. 施工批次（一批 = 一个可回滚的提交单元，批内自带测试 + 像素 + README 段）

| 批 | 内容 | 依赖 | 端 |
|---|---|---|---|
| **B1** | 备份导出/恢复（manifest + sha256 + 恢复校验 + 设置页入口） | — | PC |
| **B2** | 真 PTY/ConPTY：`shell_*` 升级为带 TTY（或新增 `shell_pty`），补 `write_stdin` 语义 | — | PC |
| **B3** | ✅ v0.48.0 已落地：终端面板（右栏「终端」页签、多进程、与 agent 共用 `ProcRegistry`，各一个游标） | B2（管道版够用；TTY 仍是 B2 的事） | PC |
| **B4** | 🟡 v0.51.0 落了 **PC 侧**（局域网端点 + 配对码 + 只读会话镜像 + 远程审批 + 界面那段开关），v0.54.0 落了**手机网页端**（配对 / 会话与待批 / 只读镜像），v0.55.0 又加上**从手机派活**（默认关的 `allowSend` 闸 + 桌面勾选框 + 手机上单独一行回执），PC 侧到这就齐了；还欠的是 **Android 原生那一半**（装 APK 才有：通知、后台常驻、免开浏览器） | — | 安卓端 |
| **B5** | ✅ v0.46.0 已落地：Git 面板（status/勾选暂存/看 diff/提交） | — | PC |
| **B6** | ✅ v0.47.0 已落地：命令面板（Ctrl+Shift+P）+ 任务运行历史（runs.jsonl + ↻） | — | PC |
| **B7** | ✅ v0.45.0 已落地：媒体工具 ffmpeg（info/转码/剪切/抽帧/抽音轨/封面）+ 产出直接播 | — | PC |
| **B8** | ✅ v0.50.0 已落地：「预览」页签（CDP 帧进网页 + 点/滚/送字回传 + 用完关掉） | — | PC |
| **B9** | ✅ v0.49.0 已落地：音视频附件（`media` 一条独立字段）+ `run_code`（python/node） | — | PC |
| **B10** | 🟡 v0.67.0 落了 **PC 侧条目化**（`Memories.kt` 与手机端同一行格式 + `/api/memories` + 记忆页签条目区 + 按预算注入）；还欠的是**两端同步**（要动手机端，并先定谁是主） | B4 | 安卓端 |
| **B11** | ✅ 全部落地：审批风险分级（v0.59.0）、凭据条目（v0.61.0）、hooks（v0.62.0）、技能 SKILL.md 导入（v0.63.0） | — | PC |
| **B12** | ✅ 三件齐了：v0.52.0 **检查点 / 回到某次之前**（整轮撤销）、v0.64.0 **只读分享快照**（`Share.kt` + 顶栏 ↗，零脚本零外链）、v0.65.0 **两条会话并排（分屏）**（每格独立滚动 + 点格子换输入对象 + `.view.on/.duo` 的结构性判据） | — | PC |
| **B13** | 🟡 v0.56.0 落了**一句话排期**（规则解析 + weekly/once/一天多时刻 + 边打边预览"下次什么时候跑"，看不懂就拒绝），v0.57.0 落了**跑完的结果去哪**（`Digest`：桌面定时页一块实时汇总 + 导出 md + 手机「结果」页签，数据仍取自运行账本），v0.58.0 又落了**任务链**（`Workflows`：一行一步、跑在同一条会话里，定时任务可以直接跑一条链）；还欠：语音输入、把结果推到手机通知（要 B4 的安卓那一半）。v0.60.0 落了**预设 agent（角色卡）**（`Presets.kt` + 「角色」页签，人设进系统提示，「用它开一条」一次带上模型/工作区/档位） | B4 | PC |
| **B14** | ✅ v0.53.0 已落地：`record` 工具（gdigrab 录桌面，停止走 `q` 收尾）；OBS（obs-websocket）仍按"检测到才可用"留着 | B7 | PC |
| **B16** | ✅ v0.68.0 已落地：子任务可配模型 / 工具 / 档位（权限只降不升，配置写在卡标题上，刷新后还在） | — | PC |
| **B17** | ✅ v0.69.0 已落地：记忆的**使用反馈**回写（一条每小时最多 +1、`uq` 只数没见过的查询指纹、打 `rc:1`；只有真发请求记账，看占用不记）+ **整理一次**（手动两步确认：降级 / 合并 / 清掉，合并不是删而是指到保留那条）；顺带修掉"PC 一保存就把手机端写的未知元数据键擦掉"这个跨端缺陷 | — | PC |
| **B18** | ✅ v0.70.0 已落地：**回到这一句之前 = 话和文件一起退**（消息动作那颗「回到这里（含文件）」；`at` 把消息对上轮次，退的是这一轮及其之后所有轮，老账本没 `at` 就拒绝回退；顺带清掉被退掉那轮留下的待办清单） | B12 | PC |
| **B19** | ✅ v0.71.0 已落地：**两端共用 `MEMORY.md` 的格式钉上了**（手机端新增 `MemoryBankFormatTest` 6 条，用真实写盘量 PC 解析器用的同一批正则；PC 修掉"存一次盘就擦掉手机端文件头那两行 `>` 说明"——`Doc.prelude` 原样带回且幂等） | B10 | 两端 |
| **B20** | ✅ v0.72.0 已落地：**剪辑链第三段** —— `speed`（变速，setpts+atempo 串级）/ `fade`（按真实时长算淡出，两种退化都拒绝）/ `mix`（BGM 压在主音下面、`normalize=0`、在主音结束处截断、画面 `-c:v copy`） | B7 | PC |
| **B21** | ✅ v0.73.0 已落地：**命令面板补齐后面几批的入口**（分屏 / 整理条目记忆 / 手动压缩 / 自动化页签 / 新会话，`PALSET` 的目标可以是函数＝「动作」组，`palTabBtn` 先开页签再等按钮出来并滚到看得见）；看像素顺手修了三处：面板透明底（假玻璃）、分屏提示说反了方向、`palRender` 局部 `box` 撞了输入框的全局名导致三句空操作；`ui-check.js` 新增"面板每条入口都真的存在" | — | PC |
| **B15** | `:core` 合并 | B4 | 两端 |

**执行顺序**：B1 → B2 → B3 → B5 → B6 → B7 → B8 → B9 → B4 → B10 → B11 → B12 → B13 → B14 → B15
（B4 跨端通道放在前面几批做完、手稳了再动它 —— 它要开监听端口，安全边界必须一次做对。）

## 4. 每一批的完成判据（统一口径）

1. Kotlin 测试覆盖新行为（判据看**结果**，不看返回 200）；
2. `node tools/ui-check.js` + `node tools/md-check.js` 全绿；
3. 有 UI 的批次必须有 `tools/steps/ui-*.json` 像素剧本，截图我亲自看过；
4. README 加「这一批」小节，测试总数更新；
5. **一次提交一次推送**（你要能逐批回滚），提交信息写清"为什么"。
