# HaoAI PC 端（Windows）

手机端的 HaoAI 有个天花板：它的"手"在手机里。想让它替你改代码、跑构建、开软件，
就得有一个跑在电脑上的 HaoAI。这一版就是那个东西。

架构决定来自 `HaoAI-Windows端与手机协同方案.md`：**PC 是权威源，手机是节点**；
内核共享、壳不共享（参考的 7 家 agent 里没有一家用 Compose Desktop 做桌面）。

## 怎么装（Windows）

```
cd pc
gradle packageExe
```

`jpackage` 只在完整 JDK 里有（Android Studio 自带的 jbr 没有），任务会自己找：
`JPACKAGE` 环境变量 → `~\.gradle\jdks\*\bin` → `C:\Program Files\{Eclipse Adoptium,Java,Microsoft}\*\bin`
→ PATH。都找不到时会给一句能照做的提示，而不是抛一个"启动进程失败"。
（写死一条绝对路径是踩过坑的：这台机器上昨天还在的 Temurin 25，今天整个目录就没了。）

产出 `pc/build/package/HaoAI-PC/`：**自带 JVM 的应用目录**，双击或命令行跑
`HaoAI-PC.exe doctor` / `HaoAI-PC.exe serve`。用 `--type app-image` 而不是安装包：
不写注册表、不要管理员、删文件夹即卸载。约 126MB（大头是捆绑的运行时）。

只想跑代码的话 `gradle installDist` 就够了，产物在 `build/install/haoai-pc/bin/`。

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

审批四个出口：允许一次 / **以后这类都允许**（写进规则表）/ 本任务都允许 / 拒绝。
无人应答 300 秒**超时即拒**（fail-closed）。

### 停止

跑着的任务随时能停：网页右下角那个发送键在跑的时候就变成红色 ■（同一个位置，
不新增第二个长得很像的圆钮）。`POST /api/stop` 做两件事：

1. 给引擎置位。停止是**收尾式**的——只在回合边界与工具边界生效，不掐断正在进行的
   模型流：一次 `chat()` 是有界的，中途掐要往 Provider 塞取消令牌，换来的只是少等几秒，
   代价是半截 `tool_call` 落进历史。
2. **把挂着的审批与提问一次性判掉**（审批给 deny、提问给空）。漏了这条，按了停止
   引擎还卡在 `fut.get(300s)` 上等一个不会来的点击——"停止"按钮就成了它自己要中止的那件事的受害者。

中断会**成为数据**：历史里留一条"用户按了停止，之前的调用没执行完，先确认现状再继续"，
并且**每个已声明的 `tool_call_id` 仍然补一条 tool 回复**（OpenAI 兼容协议要求成对，
少一次下一次请求就 400，"中断"反而把会话写坏）。这三条各有一个测试盯着。

### 权限规则表（S2）

`ask` 档不可能真的每条命令都点一次，`auto` 又是全裸 —— 中间这层是能不能长期用的关键。

```
haoai allow "git push*"          # 命中就不再问
haoai deny  "write(.env)"        # 命中直接拒，并把理由回给模型
haoai ask   "shell(npm publish*)" # 即使 auto 也要问
haoai rules / haoai rules clear
```

- 语法 `tool(pattern)`，**有序、后写的覆盖先写的**（照 opencode 的 `findLast`）。
- 命令先做**前缀归约**再匹配：`git checkout main` → `git checkout`，所以一条规则能盖住一族命令
  （Windows 命令大小写不敏感，归约前统一小写）。
- 规则**按工作区分开存**（`%LOCALAPPDATA%\HaoAI\rules.json`）：这个仓库里允许的不带到别的仓库。
- 两条红线：**必须人工确认清单**（`rm -rf`、`git push --force`、`reg`、`shutdown`…）任何档位绕不过；
  `plan` 模式压过 allow 规则。
- 优先级实测过：规则压档位、后写压先写、alwaysAsk 压 auto、plan 压 allow。

## 常驻交互进程（S3）

`shell` 是"跑一条命令拿一次输出"，喂不了交互式 CLI。多了五把：

```
shell_open(label, shell, command?, cwd?)   起一个常驻 bash/pwsh/cmd
shell_send(id, text, enter?)               往它的 stdin 写一行
shell_read(id, wait_ms?, max_chars?)       取新产生的输出
shell_close(id) / shell_list()
```

用管道不是伪终端（Windows 上真 PTY 要引 ConPTY/JNI，代价大收益小；差别只在没有彩色 TTY
与行编辑）。同一进程的状态在多次 send 之间保留 —— 测试里就是靠 `x=hello` 再
`echo got-$x-$((6*7))` 拿到 `got-hello-42` 来证明"确实是同一个 shell"。
空闲 20 分钟自动回收，最多同时 8 个。

## 浏览器控制（默认关）

```
haoai flags on browser_control     # 关着时这把工具对模型根本不存在（不进 schema）
haoai browser shot --url=https://example.com --path=shot.png
```

子命令：`status open navigate read eval click type screenshot shot tabs close`。
走 CDP 直连本机 Edge/Chrome，**不引 Playwright**。三点实测教训写在代码注释里：
profile 目录不能共用（Chrome 不让两个实例抢）、target 要挑真的页面（连到 omnibox 会
表现为"CDP 坏了"）、CLI 每次调用是新进程所以导航+截图要一步做完（`shot`）。
用**独立临时配置目录**，不碰你自己的 Edge 登录态。

网页版的截图就是用这条链路自己截自己验收的。

## 屏幕与点击控制（默认关）

```
haoai flags on desktop_control
haoai screen windows                       # 谁在屏幕上、在哪、多大
haoai screen ui --title=记事本              # 某个窗口的控件树（限深限量）
haoai screen invoke --title=记事本 --name=粘贴   # 按控件名字点，不用猜坐标
haoai screen click --x=656 --y=90          # 只能点坐标的地方
haoai screen type --text="100% (x) 中文"    # Unicode 直发，输入法不背锅
haoai screen focus                         # 键盘此刻发给的是谁
```

子命令：`capture windows ui focus`（只读）+ `click invoke type key`（会动你的电脑）。
实现是生成 PowerShell 调系统自带的 .NET（`CopyFromScreen` / `user32!EnumWindows` /
`System.Windows.Automation` / `SendInput`），**不引 JNA、不引原生库**。

三层闸门，任何一层没开都不会真点：实验特性开关 → 权限闸（每次问，审批卡上写明
"当前焦点窗口是谁"）→ 环境变量 `HAOAI_ALLOW_CLICK=1`。最后一层的理由：
一次点击落在哪个窗口上，取决于此刻谁在最前面，而这恰恰是模型看不见、人也容易看错的。

这条路上踩到并修掉的实测坑（都写在 `Desktop.kt` 的注释里，别指望读代码能预见）：

- `Add-Type -TypeDefinition` 编译内联 C# 时**默认不引用 System.Windows.Forms**，
  写 `Cursor.Position` 直接 SOURCE_CODE_ERROR → 全部改用 user32 P/Invoke。
- PowerShell 的 `$true` / `$false` / `$null` 是**只读自动变量**，
  `$true = …` 语法合法、编译不报错，跑起来才炸 → 现在有一条测试按模式扫所有生成的脚本。
- UIAutomation 的托管 API 在 PowerShell 里**不好用**：`PatternIdentifiers::InvokePattern`
  静默返回 `$null`，`PropertyCondition` 因为 `NameProperty` 被当成 `DependencyProperty`
  而构造失败。所以 `invoke` 不做 InvokePattern，改成"UIA 找元素拿矩形 → 真鼠标点中心"。
- **点击前必须回读光标位置**：`SetCursorPos` 之后 `GetCursorPos` 对不上就不点，
  并把 DPI 与虚拟桌面一起报出来。报"已点击"而实际没点到，比不点危险得多。
- **中文输入法会改写 SendKeys**：实测 `HaoAI键入测试` 只剩"键入测试"、
  `(x)` 被当拼音候选变成"（行）"、`^a` 触发的是输入法的中英切换而不是全选。
  所以 `type` 改成 `SendInput` + `KEYEVENTF_UNICODE` 直发 UTF-16 码元（16 字符 →
  系统接收 32 个按键事件，这个数字会印在返回值里，`cbSize` 算错就变 0）；
  `key` 仍走 SendKeys，输入法开着时组合键可能被截走这件事写进了工具说明。
- **`SendInput` 返回"全部接收"不等于送达**：第一版把 `KEYEVENTF_UNICODE` 写成 0x20
  （正确值 0x4），18 个字符 → 系统回报"36 个事件全部接收"，记事本里一个字都没有。
  翻转结论的是同一时刻、同一窗口用旧的 SendKeys 发 `HaoAI` 立刻进了标题。
  修完之后实测：`100%(x)键入测试^a+b` **一字不差**进了记事本标题 ——
  `%` 没被吞、`(x)` 没被输入法当拼音候选、`^a` 就是字面的 `^a`。
  所以返回值里写的是"入队 N 个按键事件；入队不等于送达"。
- **没有前台窗口时输入是丢进虚空的**：这台机器出现过 `GetForegroundWindow` 返回 NULL 的
  整段时间（点了桌面之后尤其容易进这个状态），那时 `SendInput` 照样回报"全部接收"，
  可没有任何应用收到字。现在 `type`/`key` 先读接收方，没有接收方就明说"已被系统丢弃"，
  而不是报成功。
- `.cmd` 启动器**不能有中文注释**：cmd.exe 按控制台代码页（cp936）读批处理文件，
  UTF-8 的中文注释会被重新分词当成命令执行。解释文字挪到了这份 README。
- 别的 agent / 插件留下的**全屏置顶覆盖窗**（这台机器上实测有
  `Cua.AgentCursorOverlay`，3840x1080 铺满虚拟桌面）会吃掉点击。
  坐标点对了、光标也动了，但事件落在那个覆盖层上 —— 遇到"点了没反应"先 `screen windows`
  看看有没有这种覆盖窗，别急着怀疑点击实现。

### 多条会话可以同时跑（每条一个引擎）

服务端是 `sessions: Map<sid, Managed(engine, running)>`，**每条会话有自己的引擎和 running**，
不再是一个全局 `engine` 加一把"跑着就别切"的旗。上一版的 409 保护虽然堵住了
"切走造出停不下来的孤儿任务"（老引擎还在自己线程里一轮轮调模型，而 `/api/stop`
只对着新引擎置位 —— 实测 running 一直挂着 true 到 60 轮跑完），
代价是一次只能干一件事，而这恰恰是 codex / opencode / ZCode 都不接受的限制。

现在的规则：

- **同一条会话**同时只跑一个任务（第二个 `/api/task` 返回 409 并说清原因）——
  排队会把两条任务的工具卡串进同一段历史，事后看不出哪条输出属于哪条；
- **不同会话**并行，上限 `maxRunning = 4`，超了才拒；
- 事件按会话分流：sid 走 SSE 的 `id:` 字段，浏览器以 `MessageEvent.lastEventId` 暴露，
  所以不用改任何一个已有事件的负载格式。前端每条会话一个 `.view` 容器，
  后台那条的输出写进它自己的容器，切回去不用重拉、也不丢中间内容；
- 侧栏：正在跑的会话亮一道脉动绿光，后台攒了新回答挂数字角标，
  在等确认的挂橙色 `!`；
- 停止、档位、"本任务都允许"都是**按会话**的：停 A 不碰 B，
  B 的待确认也不会被顺手拒掉（那会让人以为是自己点错了）；
- 常驻内存的会话超过 `maxResident = 12` 条时，把最久没碰且没在跑的请出去 ——
  历史本来就在 `sessions/pc-<id>.json`，下次打开按原 id 重建，用户看不出差别。

跑着的时候按回车**不是**停止。上一版把"发送按钮在跑时变成 ■"顺手套到了回车键上，
于是用户在新会话里打完一句话按回车，被杀掉的是后台那条跑了十分钟的任务。
现在停止只有 ■ 一个入口，那句话留在框里，界面上给一条说明为什么没发出去。

实测截图（无头 Edge 真渲染，不是示意图）：
[两条会话同时在跑、其中一条弹着审批](G:/hbt/pc-demo/multi-two-running.png) ·
[答完第一个，第二个审批带着"切到那条会话去看"的链接](G:/hbt/pc-demo/multi-approval-jump.png) ·
[侧栏的脉动绿光与档位按会话分](G:/hbt/pc-demo/multi-sidebar-badges.png) ·
[流式回答完整在一个气泡里](G:/hbt/pc-demo/multi-stream.png)

### 审批与提问会排队

弹窗是单实例，多会话并行时后到的会把前一个盖掉，而前一个的 id 再也不会出现 ——
那条会话就永久卡在 `fut.get(300s)` 上，看起来像"agent 无故停了五分钟"。
现在前端是一条 `asks` 队列：答完一个露出下一个，属于后台会话的那条会带一个
"切到「会话标题」去看"的链接，答完自动留在该会话的上下文里。

### 刷新页面不会丢掉待决的审批

审批与提问原本只通过 SSE 推一次：你一刷新（或开第二个标签页），对话框就没了。
现在 `/api/state?sid=` 会带上**这条会话**还挂着的 `pending`，页面加载时重新弹进队列；
别的会话的待决用侧栏的橙色 `!` 指路（点开那条会话就会补上弹窗）。
一次只回一条：`stateJson` 里 `pending` 按 sid 过滤，不会给一条无关的会话
弹一个不属于它的确认框。

### 历史会话可以改名和删除了

侧栏每条会话悬停露出 ✎ / ✕。改名只动 `title` 一个字段（摘要与水位必须原样活着）；
删除是**移进 `sessions/.trash`**，不是就地抹掉 —— 会话文件是用户与 agent 的全部过程记录，
误删一次的成本远高于多留一份垃圾。正在跑的会话不许删（返回 409 并说明原因），
否则那个线程会把删掉的文件又写回来，表现成"删了又出现"。

## 答案的 markdown 渲染

`md()` 住在 `index.html` 里，Gradle 测试跑不了它，所以配了一个能单跑的脚本：

```
node pc/tools/md-check.js      # 12 项：排版行为 + 一次注入
```

它是行级渲染（先按行切，再拼标签），不是那串全局 `replace` 硬怼出来的。改回去会踩两个实测过的坑：
① 围栏代码块先渲染、行级 `"\n → <br>"` 后跑 ⇒ `<pre>` 里每行代码之间多一个空行（`<pre>` 本来就保留换行）；
② 代码块是"在整段转义之前搬出去"的，回来时不补转义 ⇒ 模型引用的文件内容能在页面里塞标签。
支持：一~四级标题、有序/无序列表、引用块、围栏代码块（右上角标语言）、行内码、粗斜体、
`[文字](链接)` 与裸链接。截图：[渲染后的回答](G:/hbt/pc-demo/md-render.png)。

流式回答要**累加**着画：`delta` 事件是增量，之前每次都用"当前这一片"重画整个气泡，
于是长回答在界面上只剩最后那一片（真截图里模型说了"丙先读一个文件；这句话应该整段出现…"，
页面上只有"丙。"），回合结束时补的完整回答又变成第二个气泡。
现在 `answer()` 累加进 `v.acc`，`answer`(TextDone) 只把同一个气泡换成完整文本，
工具一开始执行就收口当前气泡（下一轮的话开新气泡）。

### 界面这三层怎么验（CSS / HTML / JS 之间没有编译期联系）

```
node pc/tools/ui-check.js    # 秒级静态检查：JS 语法能过 V8 解析、JS 挂的 class 在 CSS 里都有规则、
                             # $#+id 取的元素都存在、前后端事件名与接口名两两对得上
node pc/tools/shot.js --out <目录> --steps <steps.json>   # 真像素：无头 Edge + CDP，按步骤点/打字，每步存图
```

`shot.js` 不依赖任何第三方包（Node 24 自带 WebSocket，直连 CDP），steps.json 里写
`goto/sleep/eval/click/type/shot`，`eval` 的结果会打印出来当断言用。
为什么要有这份：应用内的浏览器面板没打开时截图工具会拒绝工作，
"界面到底长什么样"就退化成"我推理它应该长什么样"——而界面缺陷恰恰是结构检查全绿也漏掉的那类。
这一批它抓到了三处：左栏工作区/模型停在占位符（`applyState` 里拿 `cur()` 比对象，首屏 curId 还是空的）、
流式回答只剩最后一小片、以及新会话顶栏亮着上一条会话的档位（`/api/new` 没回 mode）。
两个坑记一下：`/json/version` 的键是大写开头的 `Browser`（写成 `j.browser` 会一直判"没起来"，
而 `DevTools listening` 就在 stderr 上）；无头 Edge 刚建 target 时发 `Page.enable`/`Runtime.enable`
会撞上渲染器没就绪的窗口期，命令永久不回 —— 这两个域本来也不是必需的。

## 与手机端同源的行为

- **上下文压缩**：历史正文超过 `compactTriggerChars`（默认 6 万字符）就把早期消息折成一条摘要，
  发给模型的窗口变成 `system + 前情摘要 + 最近 compactKeepTail 条`。三条硬规矩：
  ① **切点只能落在非 tool 消息上** —— 切断 `assistant(tool_calls)` 与它的回复，下一次请求就是 400；
  ② 模型压不动（挂了/超时/答得太短）就退回**确定性机器摘要**（列调用统计、涉及文件、执行过的命令、
  失败次数），省 token 的机制自己不能成为新故障点；
  ③ 摘要与水位跟着会话落库，重开不能丢、也不能把同一段压第二遍（另加迟滞：
  尾巴本身就超线时不要每一轮都再压一次）。
  之前只有"超预算就从头部静默丢"—— 模型不会告诉你它忘了你最初的要求，它会接着编。
- **工具结果溢出**：超过 `STORED_CAP=16000` 的输出去向工作区 `.haoai-output/`，
  会话里只留头尾摘要 + 路径 + "用 read 分段回读"的指路。开关在 `HaoFlag`，默认开。
- **两级截断**：落库 16000、发请求 4000（`REQ_CAP`），所以"发给模型的窗口"不会
  决定"用户能看到多少"。
- **改文件前留快照**：`.haoai-snap/`，覆盖/编辑前自动存。
- 实验特性统一注册在 `HaoFlag`，`haoai flags` 看与拨。

## 已验证到哪一步

- `gradle test` → **85 条全绿**（整套 14 秒）：20 条引擎流程（计划模式拒写且 write 不进 schema、
  审批放行/拒绝两条路、溢出落文件与指针、快照、会话落库与恢复、todo、ask_user、
  grep/glob、未知工具不崩循环、**关着的开关工具即使被模型硬调也不执行**、
  **停止：不执行剩余工具 + 每个 tool_call_id 都有回复 + 历史里留下中断这件事**），
  **6 条全栈（真 HTTP 服务 + 真 SSE + 真协议假网关）：点"允许一次"文件真的写出来、
  点拒绝文件不出现、设置抽屉一次改多个字段且**活着的会话**立刻换上新模型、
  两条会话同时跑且每条事件都落在自己的 sid 上、停 A 不碰 B（B 的待确认还在）、
  同一条会话上的第二个任务 409、切到正在跑的会话不再被拒**，
  7 条上下文压缩（短会话不压、超预算压、压完不出现孤儿 tool 回复、
  模型挂了退回机器摘要、摘要与水位活过重开、迟滞不重复压、兜底摘要只列事实），
  13 条权限规则（语法解析、前缀归约、后写覆盖先写、alwaysAsk 压 auto、plan 压 allow、
  按工作区隔离、落盘重载、走真引擎），
  15 条桌面控制（生成的脚本不残留占位符、PowerShell 布尔写法、引号注入、
  点击前回读光标、无前台窗口不报成功、缺参数不弹审批、plan/deny 两条拒绝路径、
  开关关着时不可见），
  5 条会话改名/删除、4 条 HTTP 接口层（多字段请求体必须每个字段都读得到），
  7 条 git（引号参数切分、只读不问人、真 add/commit/log、deny 规则拦得住 commit、
  不在仓库里给下一步、不支持的子命令列可用），
  5 条常驻进程（同一 shell 保留变量状态、关掉不泄漏、被拒不启进程、空闲回收、list 可见），
  3 条真 HTTP 流式（中文按 4 字节切碎不损坏、`tool_calls.arguments` 分片拼回合法 JSON、
  429 标可重试 / 400 不可重试）。
- **端到端跑过真实任务**（假模型 + 真文件系统）：`todo → write → edit → read → 结论`，
  磁盘上的文件内容正确，快照与 `.haoai-output/` 都按预期出现。
- **修掉一个一直在骗人的审批链路**（这一条最值得记）：`/api/decide` 之类的接口原本
  每个字段各读一次请求体，而 `HttpExchange.requestBody` 是**一次性的流** ——
  于是只有第一个字段拿得到值，`decision` 永远是空串，
  **"允许一次"在服务端等价于"拒绝"**，`ask_user` 的回答也永远传不回去。
  75 条单测全绿也照样漏，因为它们测的是引擎与存储，**没有一条从 HTTP 口进去**。
  发现过程是顺手加 `/api/rename`（要读 id + title 两个字段）时它稳定返回 404。
  现在：请求体在每个 handler 里只读一次（`Body(ex)`），并补了 `WebApiTest` 这一层
  —— 专测"一个请求体里读多个字段的接口，字段必须都拿得到"。
  再往上还补了 `ApprovalFlowTest`：真 HTTP 服务 + 真 SSE + 假网关（真协议），
  判据是"点允许 ⇒ 文件出现且内容对 / 点拒绝 ⇒ 文件不出现"，不看返回值 200 就算过。
  写这份测试时又栽了两个自己该想到的坑，记下来：① 三条测试共用一个 server，
  设置那条把档位改成了 `auto`，于是先跑它就收不到审批事件 —— 单跑通过、合跑失败，
  典型共享状态没还原；② 等的事件名写成了 `done`，服务端发的是 `answer`，
  白等 15 秒超时（测试时间从 1.2s 掉回 16s 就是最好的信号）。
  端到端复验：`ask` 档跑一轮 → SSE 出 `ask` 与 `approval` → `decide allow_once`
  → `theme.txt` 真写出来了（6 字符 2 行），界面卡片可见。
- **网页版跑通**：`/api/task` 触发一轮完整回合，标题、待办、工具卡、用量、历史回放都对。
- **停止按钮是拿真任务验的**：给 mock 网关加了一个 `--mode loop`（永远不收尾，每轮 sleep 0.6s，
  存在的唯一理由就是"其它模式跑太快，来不及按停止"）。跑起来之后 `/api/state` 的
  `running` 从 true 变 false、历史末尾出现中断记录；截图里跑着是红 ■、停下是绿 ➤。
- **这次截图还抓出一个结构测试永远看不见的布局 bug**：会话一长，输入框连同发送/停止按钮
  被推到视口外面。根因是 `.main` 作为 grid 项没有 `min-height:0` ——
  于是 `#stream` 自己永远不溢出（`scrollTop=scrollHeight` 白设），整页被顶高。
  一行 CSS 修掉。**教训：DOM 结构全绿不等于界面是对的，长内容状态必须看像素。**
- **桌面控制在真机上跑过**：截屏 3840x1080（2.8MB PNG）、窗口列表带 pid/进程名/矩形/标题、
  UIA 控件树、真点击（光标回读确认到位）、`invoke` 的三条路径（找不到窗口 /
  找不到控件 / 找到但没开第二道闸）、无前台窗口时的诚实报错，
  以及**输入真的落进了应用**：`100%(x)键入测试^a+b` 一字不差出现在记事本标题里。
  最后这条是抓出 `KEYEVENTF_UNICODE` 写错之后才通的 —— 之前它"入队成功、送达为零"。

## 还没做（按重要性）

1. **没接上真模型**：`apikey.txt` 里那把商汤 key 报 `model is not found`，
   b.ai 那把 `balance=0`，opencode zen 免费额度锁客户端。引擎与网关代码是好的，
   缺一把能用的 key。补上就跑 `haoai doctor` 验一次。
2. **`pc/` 是仓库内的独立 Gradle 构建**，没并进根 `settings.gradle.kts`——
   根构建是正在出货的手机 App，AGP 9 的内置 Kotlin 与 `kotlin.jvm` 插件在同一条
   classpath 上会打架。方案里的 Phase 1（抽 `:core` 让两端共用）仍然欠着。
3. **网页版只验过它自己的截图**：用 `browser shot` 让受控 Edge 打开本地页面再截回来人眼看，
   验的过程中发现并修掉了三处（JS 语法错整页白屏、审批第 4 个按钮没接、初始加载不刷新会话列表）。
   这不等于完整验收：窄屏/长会话/滚动条这类没覆盖，第一次真人长时间用还会再挑出东西。
4. ~~桌面控制（浏览器 / 屏幕理解 / 点击级自动化）~~ —— 浏览器控制与屏幕/点击控制
   已经落地（都默认关）。Phase 2 还欠：`write_stdin` 的真 PTY（ConPTY）、
   MCP 客户端、多会话并发、跨端审批与会话镜像、记忆与备份同步。

## 代码地图

```
pc/src/main/kotlin/com/haoai/pc/
  Env.kt        状态根、环境事实、日志
  Settings.kt   设置落库 + HaoFlag 注册表
  Provider.kt   ChatClient 接口 + OpenAI 兼容流式网关
  Prompt.kt     系统提示（身份 / 环境事实 / 工作纪律 / 工具使用 / Windows 须知）
  Tools.kt      9 把基础工具 + 溢出落文件 + 快照 + diff 摘要
  Policies.kt   S2 权限规则表：tool(pattern) 有序匹配 + 命令前缀归约 + alwaysAsk
  GitTool.kt    一把 git（子命令切分保留引号；只读不问人，改仓库走同一张规则表）
  Pty.kt        S3 常驻交互进程（open/send/read/close/list）+ 共用的 shell 启动器
  Browser.kt    CDP 浏览器控制 + 手写极简 WebSocket 客户端（CdpSocket）
  Desktop.kt    屏幕理解与点击级自动化：生成的 PowerShell 模板 + PsRunner
  Engine.kt     回合循环、档位闸、两级截断、上下文压缩、中断、会话持久化
  Compress.kt   压缩的两份产出：给模型的提示 + 确定性机器摘要（兜底）
  SessionIndex.kt 磁盘会话索引：列表 / 恢复 / 改名 / 删除（移进 .trash）
  Server.kt     127.0.0.1 HTTP + SSE + 审批/提问回环（请求体每个 handler 只读一次）；
                每条会话一个引擎，事件按 sid 分流（sid 走 SSE 的 id: 字段），
                同条会话一次一个任务、不同会话最多并行 4 条、常驻超过 12 条请出最久没碰的
  Main.kt       CLI：doctor / key / init / set / flags / allow / rules / task / chat / serve
pc/src/main/resources/ui/index.html   网页壳（单文件，无外部依赖）
pc/tools/haoai.cmd                    启动器（chcp 65001；文件本身必须纯 ASCII）
pc/tools/mock-openai.py               开发用假网关，没密钥时也能端到端验流程
                                      （loop 模式专测停止；multi 模式按标记给每条会话一份自己的剧本）
pc/tools/md-check.js                  网页壳 markdown 渲染器的离线检查（node 直接跑）
pc/tools/ui-check.js                  壳子三层的对账：JS 语法 / class 有没有规则 / id 存不存在 /
                                      前后端事件名与接口名对不对得上
pc/tools/shot.js                      真像素验收：无头 Edge + CDP，按 steps.json 点与打字，每步存图
```
