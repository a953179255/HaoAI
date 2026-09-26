# HaoAI PC 端（Windows）

手机端的 HaoAI 有个天花板：它的"手"在手机里。想让它替你改代码、跑构建、开软件，
就得有一个跑在电脑上的 HaoAI。这一版就是那个东西。

架构决定来自 `HaoAI-Windows端与手机协同方案.md`：**PC 是权威源，手机是节点**；
内核共享、壳不共享（参考的 7 家 agent 里没有一家用 Compose Desktop 做桌面）。

## 现在能做什么

同一个内核，两种壳：

```
cd pc
gradle installDist                       # 或 gradle run --args="…"

build/install/haoai-pc/bin/haoai-pc.bat doctor      # 自检：状态根/密钥/模型连通/工作区
build/install/haoai-pc/bin/haoai-pc.bat chat        # 终端对话（vibe coding 时最快）
build/install/haoai-pc/bin/haoai-pc.bat serve       # 本地网页版 → http://127.0.0.1:8712/
build/install/haoai-pc/bin/haoai-pc.bat task "…" --auto   # 跑一条就走，可被脚本调用
```

`pc/tools/haoai.cmd` 是包了一层的启动器，会先 `chcp 65001`——不做这步，
中文系统（cp936）的控制台会把所有中文输出显示成乱码。

首次使用：

```
haoai key sk-xxxx          # 密钥存 %LOCALAPPDATA%\HaoAI\apikey，不进仓库
haoai init D:\我的项目      # 设工作区
haoai set model=glm-5.3-flash base=https://api.b.ai/v1
haoai doctor               # 连通性
```

## 三档权限（PC 能执行任意命令，所以这一层不能省）

| 档位 | 能干什么 | 用途 |
|---|---|---|
| `plan` | 只有读类工具进 schema，写/命令一律拒绝 | 先出方案，不动手 |
| `ask` | 写文件与执行命令前逐条问（默认） | 日常 |
| `auto` | 不再打断 | 已知安全的批量活 |

审批四个出口：允许一次 / 本任务都允许 / 拒绝。无人应答 300 秒**超时即拒**（fail-closed）。

## 与手机端同源的行为

- **工具结果溢出**：超过 `STORED_CAP=16000` 的输出去向工作区 `.haoai-output/`，
  会话里只留头尾摘要 + 路径 + "用 read 分段回读"的指路。开关在 `HaoFlag`，默认开。
- **两级截断**：落库 16000、发请求 4000（`REQ_CAP`），所以"发给模型的窗口"不会
  决定"用户能看到多少"。
- **改文件前留快照**：`.haoai-snap/`，覆盖/编辑前自动存。
- 实验特性统一注册在 `HaoFlag`，`haoai flags` 看与拨。

## 已验证到哪一步

- `gradle test` → **19 条全绿**：16 条引擎流程（计划模式拒写且 write 不进 schema、
  审批放行/拒绝两条路、溢出落文件与指针、快照、会话落库与恢复、todo、ask_user、
  grep/glob、未知工具不崩循环），3 条真 HTTP 流式（中文按 4 字节切碎不损坏、
  `tool_calls.arguments` 分片拼回合法 JSON、429 标可重试 / 400 不可重试）。
- **端到端跑过真实任务**（假模型 + 真文件系统）：`todo → write → edit → read → 结论`，
  磁盘上的文件内容正确，快照与 `.haoai-output/` 都按预期出现。
- **网页版跑通**：`/api/task` 触发一轮完整回合，标题、待办、工具卡、用量、历史回放都对。

## 还没做（按重要性）

1. **没接上真模型**：`apikey.txt` 里那把商汤 key 报 `model is not found`，
   b.ai 那把 `balance=0`，opencode zen 免费额度锁客户端。引擎与网关代码是好的，
   缺一把能用的 key。补上就跑 `haoai doctor` 验一次。
2. **`pc/` 是仓库内的独立 Gradle 构建**，没并进根 `settings.gradle.kts`——
   根构建是正在出货的手机 App，AGP 9 的内置 Kotlin 与 `kotlin.jvm` 插件在同一条
   classpath 上会打架。方案里的 Phase 1（抽 `:core` 让两端共用）仍然欠着。
3. **网页版没做过像素级验收**：这台机器上无头 Edge 起不来、内置浏览器没有可见表面，
   只验到 DOM 结构与交互结果。第一次人眼看可能要调排版。
4. 桌面控制（浏览器 / 屏幕理解 / 点击级自动化）、`write_stdin`+PTY、跨端审批与
   会话镜像、记忆与备份同步 —— 都在方案的 Phase 2/3 里，这一版没碰。

## 代码地图

```
pc/src/main/kotlin/com/haoai/pc/
  Env.kt        状态根、环境事实、日志
  Settings.kt   设置落库 + HaoFlag 注册表
  Provider.kt   ChatClient 接口 + OpenAI 兼容流式网关
  Prompt.kt     系统提示（身份 / 环境事实 / 工作纪律 / 工具使用 / Windows 须知）
  Tools.kt      九把工具 + 溢出落文件 + 快照 + diff 摘要
  Engine.kt     回合循环、档位闸、两级截断、会话持久化
  Server.kt     127.0.0.1 HTTP + SSE + 审批/提问回环
  Main.kt       CLI：doctor / key / init / set / flags / task / chat / serve
pc/src/main/resources/ui/index.html   网页壳（单文件，无外部依赖）
pc/tools/mock-openai.py               开发用假网关，没密钥时也能端到端验流程
```
