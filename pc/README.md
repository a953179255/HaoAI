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

## 这一批：排版重做 + 消息级操作 + 思考链

对着手机端的 ChatScreen 和 codex / opencode / ZCode 那一类桌面壳子比，之前那版网页壳
"能用但简陋"：一条回答出来只有气泡，改错了只能整段重说，模型怎么想的看不见，
跑到第 12 轮不知道花了多少 token。这一批补的是这些。

**排版**
- 三栏可折叠（`Ctrl+B` 收左栏），顶栏一条：标题 / 模型 / 上下文占用环 / 档位 / 主题 / 导出。
- 明暗两套主题走 CSS 变量（`:root[data-theme]`），不是"另一份样式表"，所以新组件天然两套都对。
- 右栏三个页签：任务（待办 + 进度条）/ 用量（每轮与总计）/ 产出（这条会话动过哪些文件）。
- 空会话不再是黑洞：一张起手卡（四条常见活法，点一下填进输入框，不直接发）。

**思考链**：`delta.reasoning_content` / `reasoning` 一路从 Provider 接到界面，
流式期间是一张带秒表的"思考中…"卡，正文一到就自动收起成"思考了 1.4s 点开看"，
并且**跟着历史落库**——刷新后还在（`Msg.reasoning`）。

**每轮那行小字**：`↑ 入 token ↓ 出 token 耗时 第 N 轮`，实时事件与回放走同一种算法，
所以刷新前后这行不会变样。

**消息级操作**（悬停在气泡上显形）：复制 / 编辑重发 / 重新生成 / 删到这里。
后三个都是历史手术，坐标是引擎历史的下标（不是 DOM 序号——中间夹着工具卡）。

**写改类工具卡底下的「↩ 退回上一版」**：用 `.haoai-snap/` 里最近一份覆盖回去。

**其它**：代码块复制按钮与高亮、`/` 斜杠命令面板、会话正文搜索（`?q=`）、
模型切换器（列表由服务端代问网关，密钥不下前端）、导出 markdown、回到顶部气泡、
toast、快捷键（`Ctrl+N/K/B`、`Alt+1/2/3`、`Esc`）。markdown 渲染器新支持表格
（`|a|b|` + 分隔行才成表，没有分隔行的竖线仍是普通文字）。

### 这一批真像素验收抓到的四处（结构检查全绿也抓不到）

1. **`.acts2` 那排按钮永久隐形**：CSS 里只有 `.acts2{opacity:0}`，没有 `.msg:hover+.acts2{opacity:1}`。
   DOM 在、能点、看不见。ui-check 比的是"class 有没有规则"，而规则本来就在——只有像素能看出来。
2. **"删到这里"多删一条**：`cutTo` 写成 `while (size > index)`，把用户选中的那一句也删了，
   整个会话看起来被清空。现在 `cutTo(index, keepAt)`：删到这里留这一句，编辑重发/重新生成不留。
3. **重开会话丢掉工具消息的 `name`**：落盘写了、`restore()` 没读。表现是刷新后工具卡全变成
   匿名的 "tool"，而"↩ 退回上一版"要靠 name 配对路径，于是整排按钮消失。已补字段级回归测试。
4. **量具自己会坏**：`shot.js` 的 `click()` 原来直接拿 `getBoundingClientRect()`，目标在视口外时
   坐标是负数，于是"点到了"其实点在空中——hover 才显形的东西永远验不到，还被误判成"hover 不生效"。
   现在先 `scrollIntoView` 再量。另一处：Windows 上 `SO_REUSEADDR` 让第二个进程**能绑上同一个端口**，
   于是指定 `--mode tools` 却被上一轮没死透的 spill 假网关接走，界面报一堆假故障——
   `ui-shot.sh` 现在自己挑空端口并数监听数。

一键跑这套验收（假网关 + 真服务 + 无头浏览器，全程不联网）：

```
bash pc/tools/ui-shot.sh                 # 默认 tools 剧本，图落在 %TEMP%\haoai-ui-shot
SHOT_MODE=ask bash pc/tools/ui-shot.sh   # 换剧本（chat/tools/spill/ask/git/loop/multi）
```

## 这一批：审批内联卡 + diff 审阅 + 附件 + 窄屏

**审批与提问从模态弹窗改成消息流里的内联卡。** 弹窗是单实例，所以需要一套队列，
而队列本身就是 bug 的来源：多会话并行时后到的盖掉前一个，前一个的 id 再也不会出现，
那条会话永久卡在 `fut.get(300s)` 上（表现是"agent 无故停了五分钟"）；更要紧的是弹窗
把上下文全遮住，看不见"这一步要动哪个文件"就得凭一句描述决定放不放行。
现在每条会话在自己的流里摆一张卡（琥珀色边框 + 转圈），四个按钮语义不变
（拒绝 / 以后这类都允许 / 本任务都允许 / 允许一次），答完就地收起并留一行"已允许一次"。
"队列"这个概念整个删掉，`/api/state` 的 `pending` 也从"只回第一条"改成**全回**
（只回一条时，另一条引擎在等、界面上却什么都不欠）。

**diff 审阅视图**：`write` / `edit` 的结果卡展开后直接是行级 diff（`+` 绿、`-` 红、上下文灰），
卡头右侧一个 `−1 行 / +1 行` 的小标。三条约束：
① diff 由服务端算（`Diff.unified`，公共前后缀之外的整段算一个替换块，最多 80 行），
正确性不该由界面层决定；② diff **不进模型上下文** —— 工具结果原样发给模型，
整篇 diff 塞进去等于每改一次文件多付几百行 token；③ diff **跟着历史落库**，
所以刷新之后还能回头核对改动（只随 SSE 流一次的话，看完回答再想核对就没了）。

**附件**：输入框左边的 📎，也可以直接把文件拖进窗口。文本类（md/txt/json/源码…，<200KB）
贴在光标处并用围栏包住，模型一次就读到；其它类型走 `POST /api/attach` 落到工作区的
`.haoai-attach/<时间戳>-<名>`，插一句路径给模型按路径读 —— 二进制塞进文本历史只会变乱码。
文件名一律清洗掉路径分隔符再自己拼名字，**不把用户给的路径交给文件系统**（`..\..\x` 会写到工作区外面）。

**窄屏**：≤1080px 与 ≤780px 两档。上一版只是把栏 `display:none` 掉，结果
任务/用量/产出在窄屏**根本到不了** —— 页签按钮 fixed 出来了，可面板在 `.rail.r` 里，
父级一隐藏，fixed 的子元素跟着不显示（fixed 不会把 `display:none` 的后代救回来）。
现在两栏在窄屏改成**浮层**：顶栏多一个 ▤ 开右栏，☰ 开左栏，选中一条会话或按 Esc 就收起。

**工作区文件浏览**：右栏「产出」页签下面是工作区浏览器（`GET /api/files`、`GET /api/file`），
点目录进去、点文件弹出预览 —— agent 改完一堆文件，用户的下一个动作就是"那我看看那个文件"，
没有它就得开资源管理器去记路径，而路径本来就写在界面上。切会话时路径归零（每条会话自己的
工作区）。**路径不许逃出工作区**：算完 canonical 再判前缀，不在区内的一律退回根 ——
这个服务只绑 127.0.0.1，但浏览器里任何页面都能对本机端口发请求，所以这条得在服务端守住。
三条实测：`path=../../../../Windows` 回的是工作区根（2 条，不是 Windows 目录）、
`path=../../settings.json`（状态根里那份，含 keyHint）回 404、
`path=.haoai-attach/../hello.txt` 规范化后仍在区内正常打开。

### 这批的验收

```
SHOT_MODE=ask bash pc/tools/ui-shot.sh pc/tools/steps/ui-ask.json      # 内联审批/提问：mask 不再出现
bash pc/tools/ui-shot.sh pc/tools/steps/ui-review.json                 # diff 着色 + 两种附件
SHOT_W=900 bash pc/tools/ui-shot.sh pc/tools/steps/ui-narrow.json      # 窄屏浮层（700 同理）
bash pc/tools/ui-shot.sh pc/tools/steps/ui-files.json                  # 文件浏览 + 三条越界尝试
PRE_DIRS="other" SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-group.json  # 侧栏按目录分组 + 累计用量
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-cron.json     # 定时任务：加/跑一次/停用/删 + 思考强度
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-compact.json   # 手动压缩：折一半/诚实读数/刷新还在
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-img.json      # 图片附件胶囊 + 真的走 image_url
PRE_TOUCH="notes.txt" PRE_PNG="seed/one.png" SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-search.json  # 搜索提供方设置 + 文件浏览器里的图片预览
PRE_PNG="seed/one.png" SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-model.json  # 只改当前会话的模型 + 回答里的 markdown 图片与 url 转义
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-paste.json  # 拖拽落点层 + Ctrl+V 贴截图进附件
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-trash.json  # 回收站放回 + 侧栏每组点开更多
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-jump.json  # 搜索命中跳到那一条并闪一下
SHOT_MODE=loop bash pc/tools/ui-shot.sh pc/tools/steps/ui-queue.json  # 跑着的时候再发一句：排队胶囊 + 撤回
SHOT_MODE=tools bash pc/tools/ui-shot.sh pc/tools/steps/ui-msgops.json  # 引用 / 删这一句 / 会话置顶
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-usage.json  # 用量账本：按模型分行 + 近 14 天柱子
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-preview.json  # HTML 沙箱预览 + 长代码块折叠（含隔离断言）
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-tools.json  # 会话级工具开关 + 切会话要重画面板
PRE_RUNSTATE="$(cat pc/tools/fixtures/resume-sessions.json)" SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-resume.json  # 断点恢复：横幅 / 续跑 / 丢掉，两条会话各管各的现场
SHOT_MODE=ask bash pc/tools/ui-shot.sh pc/tools/steps/ui-askkeys.json  # 审批/提问按键盘决定 + 输入框里打字不误伤
SHOT_MODE=subloop bash pc/tools/ui-shot.sh pc/tools/steps/ui-substop.json  # 子任务单独停 + 总闸连带停（几何三条 + 中断回话）
SHOT_W=760 SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-cron-narrow.json  # 窄屏浮层里的同一套
```
876px 与 676px 各跑一遍：右栏默认 `none`、点 ▤ 之后 `flex` 且 `right<=innerWidth`（真的在屏幕内）、
676px 左栏浮层宽 300px 且带会话列表、点一条会话后收起。

## 这一批：审批留痕 + 设置抽屉能管规则与密钥

**审批结论进历史**：`Msg.note` 记"允许一次 / 本任务都允许 / 写了规则 / 已拒绝 / 超时未答"，
跟着会话落库、随 `/api/state` 回来，画在工具卡右上的小标里（`允许一次 · −0 行 / +1 行`）。
之前它只随 SSE 流一次：内联卡答完就地收起，刷新之后"这个文件到底是谁点头写的"就查不出来了 ——
权限层最该留痕的恰恰是这一件事。闸口拿引擎引用改成创建方直接给（不再按 sid 查 `sessions`，
那张表会踢掉超出驻留上限的会话）。

**设置抽屉**：
- 权限规则从"只能看"变成可增可删（`POST /api/rule`，走 `Rule.parse` 同一套语法：`shell(git push*) deny`）。
  审批卡上有"以后这类都允许"，就得有收回来的地方。规则按**这条会话的工作区**存。
- 密钥给了界面入口（`type=password`，留空＝不改；placeholder 回显的是前 6 位，不能顺手当新值写回）。
  它不进 `PcSettings`（那份会被 `GET /api/settings` 整体发回前端），只落 `HAOAI_HOME/apikey`。

验收：`bash pc/tools/ui-shot.sh pc/tools/steps/ui-settings.json`（加规则→1 条、删→0 条、字段类型）
与 `SHOT_MODE=ask ... ui-ask.json`（答完之后 `.ntag` 仍是"允许一次 · −0 行 / +1 行"）。
全栈测试里加了一条：点"允许一次"之后 `/api/state` 的那条 `role=tool` 消息必须带 `note=允许一次`。
（写这条断言时踩到一个坑：`assistant` 那条消息的 `name` 也是 `write`（从它的 calls 补出来的），
只按名字找会先撞上它 —— 判据要连 `role` 一起挑。）

## 这一批：项目说明（AGENTS.md 那一类）读得到也改得动

之前 PC 端**完全没有**这一环：`PromptCtx.extra` 这个槽位一直空着，模型永远看不到用户在
仓库里写的规矩 —— 而 codex / opencode / claude-code / ZCode 全都把"这个仓库的规矩"
收敛到一个随仓库走的文本里（AGENTS.md / CLAUDE.md），手机端也早有记忆分区。
用户把一个仓库交给 agent，第一件要交代的事就是"构建用什么命令、哪些目录别动"。

- `Memory`：按 `AGENTS.md` → `CLAUDE.md` → `.haoai/memory.md` 找（工作区找不到再看 git 根），
  找到几份拼几份，每份标出处，总量截到 1.2 万字并说明截断。
- **每回合现读，不缓存**：用户改完文件，下一句话就生效 —— 这是那几家的共同做法，
  缓存进"记忆"反而会让人以为规则没生效。
- 系统提示里给一个小节：`## 项目说明（用户写在仓库里的规则）`，并写明"比默认纪律更具体，
  冲突时以它们为准"。
- 右栏多一个「记忆」页签：显示会写到哪个文件、能直接编辑保存（`GET/POST /api/memory`）。
  保存目标优先顺着已有的 `AGENTS.md` 写（别的 agent 也认这份），没有才写 `.haoai/memory.md`
  （不污染仓库根）。路径由服务端算，前端不能指定写哪儿。

两条新判据：`AGENTS md reaches the model every turn`（**发给模型的那条 system 消息里必须含规则原文**
—— 这个功能最容易坏在中间：读到了没塞进 PromptCtx，或塞了但被截断吃掉），
以及 `memory files are labelled capped and prefer AGENTS md`（出处标注、上限、保存目标）。
界面侧：`bash pc/tools/ui-shot.sh pc/tools/steps/ui-mem.json`（保存后路径从"将创建"变"已存在"、
重开页签读回原文、盘上文件确实是那句话）。

## 这一批：全局记忆 + 自定义命令（技能）

**全局记忆**：`HAOAI_HOME/MEMORY.md` 这个槽位早就声明了却没人读（和 `PromptCtx.extra` 一样是空挂的）。
现在它排在项目说明最前面，跨项目生效（"回答用中文"这类人的习惯），工作区里的 `AGENTS.md`
则是仓库的规矩 —— 两者分开存，别把个人偏好写进仓库去。

**自定义命令（技能）**：`HAOAI_HOME/skills.json`，一条 = 名字 + 一句说明 + 一段提示词。
`/` 面板里排在内置命令后面并带「技能」标；选中它**是把提示词填进输入框**而不是直接发出去 ——
用户多半还要补一句"这次是哪个文件"。重名拒绝（内置赢），名字里不许有空格。
存的是 JSON 而提示词必然带换行与引号，所以转义单独测了一条（坏文件按"没有技能"处理，
手改错一个逗号不该让整页起不来）。

**顺带修一处回归**：`/clear` 走的是 `/api/cut?index=0`，而 v0.18 把 `cutTo` 的默认语义改成
"保留这一句"，于是清空历史会留下第一条。现在 `/api/cut` 收 `keep` 参数，`/clear` 传 `keep=0`。

**占用明细多一行**：顶栏那圈"上下文占用"里，项目说明从系统提示中**单列**出来
（实测 `系统提示 1,422 字 | 项目说明 83 字 | 工具说明 3,264 字`）—— "快满了"的两种成因
（纪律太长 vs 用户自己的规矩太长）该删的不是一样东西。保存记忆后发 `settings` 事件让占用重算，
而不是往对话流里插一条"已写入"（页签里那行"已存 N 字"和 toast 已经说完了）。

验收：`bash pc/tools/steps 见下` →
`bash pc/tools/ui-shot.sh pc/tools/steps/ui-mem.json`（项目/全局两块记忆保存后读回、
建技能 → `/rev` 面板出现 `/review 技能 …` → 点一下提示词进输入框 → 删除归零）。

## 这一批：MCP 客户端 —— 外部工具接进引擎

参照列表里那几家（codex / claude-code / opencode / dsh）都靠 MCP 把"我实现不了的能力"
变成生态：数据库、issue 系统、公司内部工具，不用每个 agent 各写一遍。之前 PC 端没有这一层，
用户就只能用内置那十八把工具。

- `Mcp.kt`：stdio 上跑**换行分隔的 JSON-RPC 2.0**（不是 LSP 那套 `Content-Length` 头）。
  `initialize` → `notifications/initialized` → `tools/list` → `tools/call`。
  读回包**按 id 匹配并跳过通知**：server 会主动推 `notifications/xxx`，不按 id 挑就会把
  日志当结果解析，症状是"工具结果是一团看不懂的东西"。
- 外部工具包成引擎的 `Tool`，名字 `mcp_<server>_<tool>`（网关要求 `[A-Za-z0-9_-]`，
  加前缀也让界面上"这是外部工具"一眼看得出来，还不会和内置撞名）。
- **三条安全边界，都不许放宽**：① 整条链路挂在 `mcp_client` 开关上，**默认关**，
  关着时这些工具对模型不存在（和浏览器控制同一套理由：别人写的进程能干什么我们不知道）；
  ② 跑哪个命令由**用户自己写进 `HAOAI_HOME/mcp.json`**，界面只能改这份配置，
  模型没有这条路 —— 它能调用已注册的工具，但拉不起新进程；③ 工具 `kind="write"`：
  计划模式一律挡住，其余档位每次调用都过权限闸。
- 一个坏 server 只让"外部工具"变空，不许把引擎带崩（`allTools()` 整体包 runCatching）。
  握手/列工具 6 秒超时（设置抽屉打开时要同步问一遍每个 server 的状态，装死的外部进程
  不该把界面吊住），真调用才给 60 秒。
- 管理入口在设置抽屉：列出每个 server 的连接状态与工具数、可加可删；加完发 `settings`
  事件让顶栏占用重算（工具说明那一行会变长）。

### 验收：对着真协议跑，不是对着自己的解析函数跑

`tools/mock-mcp.js` 是一个最小的**真** MCP 服务器（stdio + 换行 JSON-RPC），
`tools/call` 会把入参写进磁盘标记文件 —— 于是能证明"这次调用真的到了外部进程"，
而不是客户端自己编了个返回值。三条判据各对一个失败面（`McpTest`）：
① 握手 + 列工具 + 真调用（并且磁盘标记里有那句话）；② 命令不存在的 server 只让外部工具变空，
引擎照常拿到内置那套；③ 开关关着时 `visibleToolNames()` 里没有任何 `mcp_` 开头的工具。
界面侧：`bash pc/tools/ui-shot.sh pc/tools/steps/ui-mcp.json`
（加 `node …/mock-mcp.js` → 列表出现"已连上，1 把工具" → 删除回到"（还没配）"）。

## 这一批：子任务（subagent）

模型现在能派一个子任务去做一件独立的事：`task {prompt,label}` → 引擎**新建一个子 Engine**
（独立历史、共用同一个权限闸与设置），跑完只把**结论**当成工具结果交回父会话。

为什么值得做：并行调研时子任务的中间过程（几十次 read/grep）如果都灌进父会话，
上下文立刻被挤满，而父会话需要的只有那句结论 —— 这正是 subagent 存在的理由。

三条设计决定：
- **过程与结论分家**：结论进 `content`（模型看得见），过程一行一条进 `Msg.sub`
  （只给界面，不发模型，和 `diff` 同一套理由）。所以刷新之后那张"子任务卡"还在，
  而不是只剩一句结论。
- **深度限一层**（`MAX_DEPTH=1`）：子任务再派子任务会被挡，挡的结果作为工具结果回给模型，
  界面上能看到"它试过了"。没这道闸，一句"把所有模块都看一遍"能给自己开出一条流水线。
- **子会话不落盘**：一次性的调研出现在左侧会话列表里只会碍事，重启后也没人需要接上它。

界面上是一张独立的 `.card.sub`：跑的时候展开、逐条记"用了哪把工具"，
跑完收起并把结论露在第一行（不占正文，正文只放父会话自己的话）。

验收：`SHOT_MODE=sub bash pc/tools/ui-shot.sh pc/tools/steps/ui-sub.json`
—— 子任务卡、标签、过程行、结论、以及**磁盘上只有 1 条会话**（子任务没漏进列表）。
Kotlin 侧 `a subagent runs its own loop and only its conclusion comes back` 断言
父历史里**不含**子任务读到的第二行原文（真跑过 = 过程被压掉了），并检查 `Msg.sub` 落了库。

## 这一批：定时任务 + 思考强度

**定时任务**（`Schedules.kt`，存在 `HAOAI_HOME/schedules.json`）：到点自己起一条**新会话**去跑那句话。
手机端早就有定时任务，而桌面这台机器才是真正常开着的 —— "每天早上把构建日志看一遍写份摘要"
这种活只有常驻机器能替人干。三条刻意的取舍：

- **不补跑**。笔记本睡了八小时，醒来一口气触发八次既刷爆网关也不是用户要的。
  interval 从上一次（或创建时刻）往后推，daily 今天过了点就补一次、跑过就等明天。
  这条由 `Schedule.nextDue` 一处实现，并被 `a slept machine fires once, not eight times` 钉住。
- **每 5 秒轮询而不是精确定时器**：`Timer` 在睡眠期间不触发、醒来也不补，轮询天然把
  "睡醒发现过期"变成一次普通判断。调度与执行分开（`Scheduler` 只管时机，`fire` 由服务端注入），
  两边才能各自单测。
- **起新会话而不是塞进当前会话**：定时任务是另一件事，混进用户正在聊的那条只会搅乱上下文。
  标题在 `startRun` 的锁里就定成任务名并补写一次盘，否则侧栏刷出来还是"新会话"。

界面是第 5 个页签「定时」：列表卡显示 名字 / 每多久或每天几点 / 下次什么时候 / 上次结果，
每条三个动作（跑一次、停用、启用、删）。选「每天一次」就把"几分钟"那格藏起来，反过来也是。

**思考强度**（`PcSettings.reasoningEffort` → `Provider`）：默认**不发** `reasoning_effort` 这个字段。
本机 llama-server 与不少兼容网关收到不认识的字段直接 400，"界面有下拉框"不等于可以永远发。
设置抽屉里一格下拉（不发 / low / medium / high），CLI `set effort=high` 同效；
CLI 写进去的下拉框没有的值会补成一项，不会在保存时被静默改成"不发"。

### 这一批被像素与测试抓出来的四个缺陷

1. **`GET /api/settings` 里没有 `contextChars`**，而保存时 `num()` 把空读成 0 ——
   于是"打开设置点保存"就把上下文窗口清零，那圈占用永远显示 0%。现在读写两头都补上了，
   并且 `every drawer field round-trips through the endpoint` 把抽屉里每一格都按字段名过一遍。
2. **API 密钥那格是 `type=password`，而 CSS 只写了 `input[type=text]`** —— 深色主题下它是一块白底。
3. **定时任务列表沿用了设置抽屉那个 `max-height:150px` 内滚**：这一栏本身就能滚，
   套两层的结果是第二张卡被齐腰切断，而用户不知道下面还有东西。
4. **假网关把 SSE 的帧分隔写在了三引号原始串里**（`\n` 是两个字符），Provider 一帧都解不出来 ——
   于是"跑一次"其实每条都在报错，而 Kotlin 测试全绿。判据从"会话出现了"改成
   "会话里真的说过话、有回复、且不再 running"。同一个坑也暴露了 `startRun` 的旧顺序：
   先建会话再判并行上限，满了时的一次失败发送会在列表里留下一条空白"新会话"
   （定时任务撞上就更糟，每五分钟一条），现在改成先判定后新建，
   并用 `WebServer(maxRunning = 0)` 让这条能被测。

验收：`SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-cron.json`（宽屏：加两条任务、
跑一次冒出一条以任务名命名的会话、停用显示"不会跑"、删、抽屉里的思考强度往返），
`SHOT_W=760 SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-cron-narrow.json`（窄屏浮层）。
Kotlin 侧新增 `ScheduleTest`（17 条：时间算法 / 存储 / HTTP CRUD / 跑一次真起会话 / 满了不留壳）与
`ReasoningEffortTest`（5 条：发与不发 / 走到线上 / 设置端点往返 / 抽屉字段整体往返），共 120 条全绿。

## 这一批：输入框 @ 提及文件 + 测试临时目录关进笼子

**@ 提及**：输入框里打 `@`（行首或空格/括号之后）就弹出工作区里的文件，接着打就是按片段过滤，
`Tab`/`↑↓` 选、`Enter` 或点一下把 `@相对路径` 插回光标处，`Esc` 关掉。面板与斜杠命令共用一个
（`.slash`），所以样式与键盘手感是一套。

- **只插路径，不塞内容**：一个大文件能一口把上下文顶满，而模型本来就有 `read`/`glob` ——
  看到 `@路径` 自己决定读哪段更准。系统提示里补了这条语义，免得模型以为"被指出来=我读过了"。
- **只看光标前那段**：光标在句子中间时不该被尾巴上的 `@` 触发；补全晚到一步也要丢掉
  （`frag!==atFrag` 就不画），否则会出现"打的是 Server 面板里却是刚才那次搜索结果"。
- 服务端 `GET /api/files?q=` 是新的有界递归搜索：**只在 canonical 之后的工作区内走**、
  扫满 4000 个条目就收、深度到界就停、跳过 `.git`/`node_modules`/`build` 这类目录；
  排序按"文件名开头命中 → 名字短 → 层级浅"，打 `serv` 第一个是 `Server.kt`。
  这个服务只绑 127.0.0.1，但同机任意页面都能对这个端口发请求，所以边界必须在服务端。

**测试临时目录**：`%TEMP%` 里堆了 5000 多个 `haoai-*` —— 每条测试都 `Files.createTempDirectory`
造工作区/状态根，用完没人收。改调用点要动 11 个文件，而 `java.io.tmpdir` 只有一处，
于是指到系统 TEMP 下的专用目录、跑完整个删掉。**第一次尝试是错的**：直接指进
`build/test-tmp` 之后 `GitToolTest` 当场假失败（"不在仓库里却成功了"）——
那条测试的前提就是"临时目录不在任何 git 仓库里"，而 `build/` 在 HaoAI 仓库内。

界面侧还顺手修了一处：`.slash .it code` 原本写死 76px 宽（命令名短，对齐好看），
文件名长得多，`@servicelog.txt` 直接压到后面的「文件」标签上 —— 改成"至少 76、能长"。
`shot.js` 也补了两件：程序化打字后显式把光标放到末尾（否则 @ 这类"看光标前那段"的判断测不到），
以及真的按键步骤 `{"key":"Tab"}`（合成事件测不到 preventDefault 这类默认行为）。

验收：`PRE_TOUCH="src/main/kotlin/Server.kt notes/servicelog.txt hello.txt" SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-at.json`
—— 弹出与排序、Tab/↑ 移动选择、Enter 插入且**不**误发、发出去的气泡里带着 `@路径`、
裸 `@` 列根目录、Esc 关闭、句子中间的 `@` 不触发、斜杠面板没被这次改动弄坏。
Kotlin 侧新增 `FileMentionTest`（6 条：相对路径与正斜杠、排序、脏目录跳过、深度有界、
构造成 `..` 的查询逃不出工作区、不带 q 时仍是单层列表），共 126 条全绿。

## 这一批：工作区切换器（换个项目开一条）+ 一处转义加固

每条会话本来就属于自己那个目录（服务端一直存着 `workspace`，右栏文件浏览、审批规则、快照都按它走），
缺的只是**入口**：以前只能改全局默认，然后新开的会话才跟着走，而"手边这条想换个仓库"没法一步做到。

- 左栏「＋新任务」下面多一颗工作区标签：显示当前这条会话的目录名，悬停看全路径，点开是抽屉。
- 抽屉里是**最近用过的工作区**（按目录给会话归组，带条数与上次时间），每行一个「在这开一条」；
  底下留一格填任意路径。当前那条标「当前」而不是给一个"再开一条"的假按钮。
- **不另存"最近列表"**：会话索引里就有每条的工作区，再存一份就是两个真源，迟早对不上（手机端踩过）。
- 指定了 `ws` 就**一定新建一条**，不复用手边那条空的（那等于把它悄悄挪到别的目录）；
  目录打不开要明确报错并留在原样，**不**"退回默认工作区" —— 那会把人送进一个他没选的仓库里写文件。

顺带修的两处：

1. **`esc()` 只转 `&<>` 不转引号**，而这些字符串大量被插进 `value="…"` / `data-ws="…"` 这类属性里 ——
   一个带引号的模型名或目录名就能把自己关到属性外面去。现在补上 `"`。
2. **新建会话后顶栏与左栏停在上一条的状态**：`newTask` 里那句
   `if(!v.running&&!v.el.children.length)hydrate(d.id)` 永远不成立，因为 `paintHello` 自己
   就是第一个子元素。于是新会话的模型/工作区两行一直是"—"，切换器上线后左栏还亮着旧目录。
   判据改成"有没有真的气泡"（`.msg`），既保住"别把刚发出去的消息抹掉"的原意，也让空会话刷得到 state。

验收：`PRE_DIRS="other" SHOT_MODE=chat bash pc/tools/steps/ui-ws.json`（真像素里看到：
抽屉列出两个目录并标出当前、填一个不存在的目录 → toast 说清是哪个目录打不开且**不**多出一条会话、
换到 `other` 之后左栏标签与顶栏一起跟着变、点回旧会话又跟着变回来、三条会话各在自己的目录里）。
Kotlin 侧新增 `WorkspaceTest`（6 条），共 132 条全绿。

## 这一批：侧栏按工作区分组 + 累计用量

上一条批次让人能"换个项目开一条"之后，侧栏立刻暴露了新问题：两个仓库的会话混在一列里，
标题又都叫"新会话"，看不出谁是谁的。

- **按目录分组**：组头是目录名 + 条数 + 收起/展开，点组头收起（记在 `localStorage`，刷新还在）。
  **只有一个目录时不分组** —— 那样每人每天多看一眼没用的组头，比现在更糟。
  当前会话所在的那组不重排（不然正看着的那条会跳走），其余按最后活动时间。
- 24 条的上限按组分配，某组太长会写"还有 N 条没显示"而不是静默截掉。
- 搜索命中的高亮（`.hit`）在分组下照常工作。

**累计用量**：用量页签原来只有"这一条会话"的 token。现在加一段**今天 / 近 7 天 / 全部**
的条数与 ↑↓ token。数据全部从 `/api/sessions` 已有的字段客户端算出来 ——
服务端本来就报 `prompt/completion/updated`，再加一个 `/api/stats` 只会多一处能写错的地方。
口径写清楚：只算盘上还留着的会话（列表最长 200 条），删掉的不算；"近 7 天"不叫"本周"，
因为没人要周一零点那个边界。

验收：`PRE_DIRS="other" SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-group.json`
（真像素里：两组各 1 条、组头跟着走、收起变 `+` 且 `localStorage` 里落了值、
刷新后分组还在、搜索时两组都出命中、累计那行是 `今天 2 条 ↑2,468 ↓174 …`）。
`ui.json` 全量巡游 83 步无回归（单目录时走的是原来那条平铺路径，所以老判据一条没破）。

## 这一批：手动压缩一次（`/compact` 与用量页那颗按钮）

自动压缩早就有，但它只在**下一轮请求前**、且过了触发线才动手。长会话里人常有另一个判断：
"这段调研没用了，先收一收再往下走" —— 不该逼他等到 6 万字，也不该为此重开一条会话。
所以补一条手动入口：用量页签「上下文」那行旁边的按钮，或输入 `/compact`。

- 绕开的只有**触发线与迟滞**两道闸；切点规则、模型摘要 + 机器兜底、摘要追加与裁剪，
  全部走自动那条同一个实现（`compactOnce(force)`），两条路不会各自腐化。
- **跑着的那条不许压**：历史正在被回合改，这时候算切点等于两条任务往一段历史上写；
  接口把理由说清（"这条正在跑，等它收尾再压"）。
- 手动压缩不在回合里，没人替它落盘 —— `compactNow()` 自己 `persistNow()` 一次，
  否则刷新就回到压缩前的样子。
- 修掉一个真实障碍：`compactKeepTail` 默认 14，于是 12 句的会话按下去"没得压"
  （那恰恰是人最想压的时刻）。force 模式把保留窗口压到"至多折一半、至少留 4 条"。
- 读数诚实：短句会话上压缩可能**一个字都不省**（摘要和原文差不多长），
  提示就照实说"这次没省到字，会话再长些压才有净收益"，不假装成功。
- 顶栏那圈占用旁边加了「已折进摘要 N 条 / 自动触发线 X 字」，
  数据来自 `state.context.{trigger,compactedThrough}`（水位本来就在盘上，只是以前没报给界面）。

一处已知取舍：压完那条流内提示是**重画之后补发的**（引擎也发了一条，但紧接着的 state 重放会把它抹掉），
所以它只在当前这一次可见；长期证据是用量页那行「已折进摘要 N 条」，它来自盘上的水位，刷新也在。

验收：`SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-compact.json`
（真像素：按之前 8 条消息 / 已折 0 条 → 按下去 4 条 + 流里出现那条提示 + 顶栏数字不变就照实说 →
再按一次诚实地拒绝 → 刷新之后「已折进摘要 4 条」还在）。
Kotlin 侧新增 `ManualCompactTest`（5 条：绕开触发线真的折、太短不许吃历史、落盘与水位、
未知会话、正在跑的那条被挡下来），共 137 条全绿。

## 这一批：图片真的进上下文（多模态）

一件一直存在、这次才说破的事：**`screen capture` 与浏览器截图只回一个 PNG 路径**。
模型拿到的是文件名 —— "屏幕理解"其实是模型在猜那个文件里有什么。
附件那栏的注释也写着"视觉输入走同一条路"，而那条路以前根本不存在。

- `Msg.images` 存的是**路径**，发请求时才编码成 `data:image/...;base64,`。
  一张 2.8 MB 的截图编码后 3.7 MB，存进会话文件就是每次截图多付 3.7 MB；
  而图片本来就在工作区里，路径只有几十字节。
- 只有 `user` 角色用数组 content（`[{type:"text"},{type:"image_url"}]`）：
  OpenAI 兼容网关大多不接受 `tool` 角色的数组 content。所以工具产出的图，
  是本轮工具跑完后**补一条 user 消息**递给模型，而不是塞进工具结果里。
- **认类型只认魔数**（png/jpeg/gif/webp），扩展名会骗人；认不出、读不到、超 6 MB 的一律跳过，
  并在正文里说明 —— 一张坏图不该把整轮对话弄发不出去。
- 安全：浏览器提交的图片路径**必须落在会话自己的工作区里**（`insideWorkspace`）。
  这个服务只绑 127.0.0.1，但同机任意页面都能打这个端口，而图片会被编码后**发到远端网关** ——
  一条 `C:/Users/.../id_rsa` 只要被当"图片"递出去，就等于把读文件的口子开给了浏览器。
  一条消息最多 4 张。
- 上下文那圈按**编码后的真实成本**算图片，不按那 40 个字符的路径 ——
  否则"贴十张截图"会显示成只用了 400 字。
- 界面上：图片附件挂在输入框上方的胶囊里（名字 + 大小 + ✕ 撤掉一张），
  发出去就清空；文本附件仍旧直接贴进这句话（那条路本来就好用）。

测试里最值钱的一条是端到端那条：从 `/api/task` 进去，看**假网关真收到的请求体**里
有没有 `image_url` 与 data URL，以及工作区外面那条路径有没有被递出去 ——
判据不在返回值上，在线上的字节上。

验收：`SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-img.json`
（真像素：贴一张图出胶囊、两张、✕ 撤掉一张、发出去胶囊清空）。
Kotlin 侧新增 `ImageContextTest`（6 条），共 143 条全绿。

## 这一批：流里看得见图（缩略图 + `/api/img`）

上一批把像素递给了模型，但界面上只剩一句路径 —— 用户贴了张截图，回头只看到
`.haoai-attach/1790…-shot.png` 一行字。这次补上"人也看得见"那一半：

- 历史与 `state` 里每条消息带上 `images`（路径），`/api/user` 事件负载从裸字符串变成
  `{t,imgs}`（前端两种形状都认，旧页面刷新前不会白屏）。
- 新接口 `GET /api/img?sid=&path=`：把这条会话工作区里的图原样发给浏览器。
  边界与 `/api/files` 同一套理由 —— 服务只绑 127.0.0.1，但同机任意页面都能打这个端口，
  所以路径 canonical 之后必须仍在这条会话自己的工作区内，且只放认得出的四种魔数；
  否则它就是一个"读任意本地文件并回显"的接口。绝对路径与相对路径都要能取回
  （工具产出的截图存绝对路径，用户附件是工作区相对路径）。
- 画出来是"缩略图 + 文件名"两行：点开是新标签页看原图。
  **图坏了（假魔数、被截断）就只留文件名那一行** —— 浏览器默认的裂图图标会盖住气泡，
  所以 `onerror` 直接把 `<img>` 藏掉。
- 缩略图用 `loading="lazy"`：长会话里几十张截图不会一次性把内存吃掉。
  副作用是"刚刷新时视口外那几张还没解码"，`naturalWidth` 读到 0 —— 这是量具的时机问题，
  不是产品坏了；像素判据因此用"取字节 + 状态码 + 类型"来定，视口内那张的 `naturalWidth=1`
  才是"真渲染出来了"的证据。

验收（`PRE_PNG="seed/one.png" SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-img.json`）：
真像素里第二张（合法 1×1 PNG）在气泡内渲染成色块、`naturalWidth=1`；
第一张是故意做的假 PNG（只有魔数），返回 200 但解不出来，于是只剩文件名那一行 ✓。
Kotlin 侧 `ImageContextTest` 加到 8 条（新增：`/api/img` 给工作区内的图回字节、
工作区外/绝对路径/`../`/文本冒充一律 404，以及 `state` 把图片路径报回前端），共 145 条全绿。

## 这一批：`web_search` —— PC 端补齐两端最大的一块功能差

PC 端以前只有 `web_fetch`：等于"知道网址才能读"。而 agent 干活时最常见的一步是
"这个库现在的 API 到底叫什么"、"这个错误码是谁定义的" —— 没有搜索，模型只能凭训练时的记忆答，
答错了界面上一个字都看不出来。手机端为这件事做了 12 家搜索服务，PC 端一条都没有。

- 默认走 **DuckDuckGo 的 `html` 端点**：纯服务端渲染，不要 key、不要浏览器，装完就能用。
  有 key 的人可以切 **博查**（与手机端同源的一家）。`settings.searchProvider` = `auto` 时
  有 key 走博查、没 key 走 DDG；显式选了博查却没 key 就直接说清去哪儿填，不去发一个注定空手的请求。
- key 只落 `HAOAI_HOME/searchkey`（或 `HAOAI_SEARCH_KEY`），**不进设置对象** ——
  那份会被 `GET /api/settings` 整体发回前端，与 API 密钥同一条理由。
- DDG 改版就会一条都解析不出来，而那时最坏的不是报错是"空结果 + 模型开始编"。
  所以：解析抽成纯函数 `parseDuckDuckGo(html)` 用离线 fixture 测（改版时这条先红）；
  一条都没有时明确说"可能关键词太偏，也可能是页面改版解析不出来"，并给出下一步
  （换个说法 / 直接 web_fetch 已知地址）。
- 跳转壳要解：DDG 的链接是 `//duckduckgo.com/l/?uddg=<编码后的真地址>`，
  不解出来模型拿到的就是一条打不开的跳转地址。
- 提示词里补了路由判据（手机端那条"不调研就写作"的教训）：**答案里只要出现"这个仓库之外的事实"
  （版本号、API 名与参数、价格、日期、别人仓库的做法），就必须先搜再答**。
  按产出物的事实性分道，而不是按"这是不是检索类任务"的枚举 —— 前者才是那条缺陷的根。
- 设置抽屉里加了「搜索」一段（提供方下拉 + key 输入，留空=不改；placeholder 说清当前走哪家）。

顺带修一个**一直在骗人的判断**：`/api/file` 用"`readText()` 抛不抛异常"来定是不是二进制，
而 Kotlin 的 `readText()` 遇到坏字节是替换成 U+FFFD 而不是抛 —— 于是一张 PNG 会以一屏乱码
被当成文本发回界面（"二进制不预览"那条从来没生效过）。改成自己判：图片魔数直接算二进制
并且让界面走 `/api/img` 画出来，其余看前 8 KB 有没有 NUL 字节。文件浏览器里现在
点图片是真缩略图（像素里 `naturalWidth=1`），点文本还是 `<pre>`。

验收：`PRE_TOUCH="notes.txt" PRE_PNG="seed/one.png" SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-search.json`
—— 抽屉里那段渲染与提供方往返（选博查→服务端记下→重开还是博查→改回 auto）、
图片预览渲染出 1×1、真鼠标点文本文件仍走 `<pre>`（上一轮"真点击没开"经查是量具时机，
不是产品：`elementFromPoint` 显示那一行上面没有任何遮挡）。
Kotlin 侧新增 `SearchToolTest`（8 条：fixture 解析、改版返回空、提供方选择、
本地假搜索服务走完整链路、空结果要说人话、空 query 不浪费请求、博查 JSON 与缺 key），共 152 条全绿。

## 这一批：按会话换模型 + 回答里的图片真的画出来

顶栏那颗模型 chip 以前是**全局开关**：切一下，所有正在跑的会话一起换。
而"这条长任务换便宜的、那条问答换强的"恰恰是 PC 端多会话并行之后最常见的诉求
（手机端也是按会话选的）。现在弹窗里多了一格「只改当前这条会话」（默认勾上）：

- 勾着 → `POST /api/model {sid, model, scope:session}`，只 `useSettings(settings.copy(model=…))`
  换掉那一个引擎；顶栏 chip 显示的是**当前会话自己的**模型（`stateJson.model` 从引擎读，不再读全局设置），
  所以切会话时 chip 会跟着变，新开的会话回到全局默认。
- 不勾 → 仍走 `POST /api/settings`，全局改、所有引擎（含以后新建的）一起跟上。
- 两条路都不能只改界面不改后端，所以 `ModelScopeTest` 的判据是"问服务端要 state，
  看那一条的 model 变了、别条没变、全局那条也没变"，而不是"chip 文字对不对"。

**测试自己也会假失败**：第一次跑 `ModelScopeTest` 时"只改一条不影响别条"红了，
根因不在产品 —— `/api/new` 在手边那条还空着的时候会**复用**它（那是前面修的
"一路点新任务刷出一堆空会话"），于是两次 newSession 拿到同一条 id。
改成建会话时各带一个临时工作区，才有两条真的会话。量具的假设也要写进注释。

另一半是渲染：模型发的 markdown 里 `![...](url)` 以前原样显示成一行字，
`/api/img` 都建好了却用不上（工具截图能画，模型自己贴的图不能画）。补了图片规则，
同时把三类 url 收进一个白名单函数 `safe()`：

- 只放行 `http(s):`、站内 `/…`、`data:image/`；`javascript:`、`file:` 这种留在文本里不变成标签。
- url 里的双引号编成 `%22`。这条是真问题：`[点](https://a.cn/x"onmouseover="y)`
  以前会拼出 `<a href="https://a.cn/x"onmouseover="y">`，属性引号被闭合，
  后面那截就成了浏览器眼里的属性 —— 模型的回答不是可信输入，**它一句 markdown 就能往 DOM 里塞东西**。
  裸链接那条正则同样以前没过滤，一并过 `safe()`。
- 图片按 `loading="lazy"` + `.ans img` 限宽，别把气泡撑破。

验收：`PRE_PNG="seed/one.png" SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-model.json` ——
弹窗里有那格且默认勾上、切完 chip 变成会话模型且 toast 说"这条会话"、
`GET /api/settings` 里全局模型没动；**先真发一轮**让这条会话非空，再点新任务，
chip 才回到全局默认（`chipAfterNew:"mock"`、`sessions:2`）—— 不先发一轮的话
`/api/new` 会把那条空会话复用回去，"新会话回到默认"就永远验不到（同一件事在
`ModelScopeTest` 里也栽过一次，两边都补了注释）。
渲染侧：`inThisBubble=1` 且 `naturalWidth=1`（`/api/img` 真拉到字节）、`javascript:` 那条原样留在文本里、
`<a>` 的 href 里只剩 `%22`，没有凭空多出来的 `onmouseover` 属性；
再贴一张 2000×120 的宽图，量到的是 `shown:880x55`、`overflow:false`、`#stream` 无横向滚动 —— 
**图片被气泡限住了而不是把版面撑破**。
md-check 新增 3 例（图片渲染、危险协议不转标签、引号编掉），Kotlin 侧 `ModelScopeTest` 5 条，共 158 条全绿（这批之后是 163）。

这两个数一开始都是假的，值得记：断言写在 `document.querySelector('.ans')` 上，
真发一轮之后那是**第一条**回答气泡（本地图在最后一条里），于是"坏 url 原样留着"读成了 false；
宽图第一次量到 `naturalWidth=0`，因为 `loading="lazy"` 的元素不进视口就不解码 ——
要先 `scrollIntoView()` 再 `await img.decode()`。**量具读错了对象，看起来像产品坏了。**

顺带两处自纠：① `GET /api/models` 的"使用中"标记原本比的是**全局**模型，
按会话换过之后会出现"chip 显示本地 7B、弹窗里打勾的是另一行"，改成按这条会话的引擎标
（`ModelScopeTest` 里给假网关加了 `/v1/models`，判据就是"打勾的那一行 id"）；
② `ui-shot.sh` 里已经 `cd` 到 `pc/`，而 README 的命令是从仓库根抄给的（`pc/tools/steps/x.json`），
照抄必 ENOENT —— 现在两处都认。

顺带一处文档自纠：下面「已验证到哪一步」的条数一直停在 91，早就不对了 ——
它恰好是"README 只当索引、数字要回代码里数"这条教训的现场版。

## 这一批：截图 Ctrl+V 直接进会话 + 拖文件有落点

上一批把"图片进上下文"打通了，但要送一张图进去还是得：截图 → 先存成文件 →
点 📎 → 在文件对话框里找到它。微信/QQ/Snipaste 抓完图之后手上只有剪贴板，
这条路上每一步都是断的；拖文件进来更是**拖到一半没有任何反馈**，只能凭感觉松手。

- `paste`：剪贴板里有文件就接过来走同一条附件路；**只截带文件的粘贴**，
  纯文本一律不拦（拦了就是"输入框里贴不进字"，这条有专门的判据）。
- 剪贴板图片经常**没有扩展名**（有的浏览器给的名字就是 `image`），只按后缀认会把真图
  判成"递不出的文件" —— 按 MIME 补一个名字（`粘贴图片.png`）再走。
- `dragenter/dragleave` 计数控制一层 `#dropMask`（"松开：作为附件加进这条会话"），
  `pointer-events:none` 是必须的：不然这层会自己吃掉 dragleave，松手时反而什么都收不到。
  顺带修好一条旧的：原来 `dragover` 无条件 `preventDefault()`，
  于是**选中的文字也拖不进输入框** —— 现在只拦真带文件的那次。
- 附件胶囊里加了 38×26 的小图：连着贴三张截图时，只读 `image.png / image.png / image.png` 分不清。
- 空框时 `insertAtCursor` 不再留开头那两个换行（附件就是这句话的开头，前面空两行会把输入框撑出一大条空白）。
- 提示要跟着改：placeholder 与左栏「怎么用」现在写着"拖进来或 Ctrl+V 贴截图"——
  看不见入口的功能等于没做。

验收（`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-paste.json`，真像素）：
拖拽时那层提示真的铺满屏且 `pointer-events:none`、松手后消失；
贴 `image.png` → 胶囊出现且**胶囊里那张 40×40 的图是解出来的**（`prev:["40x40"]`）；
贴无名图 → 胶囊名字变成 `粘贴图片.png`；贴纯文本 → `defaultPrevented=false`（没被劫持）；
发出去之后自己那条气泡里两张缩略图都 `naturalWidth>0`、各 172×112。

**这一批两次踩到"量具读错对象"**：第一次断言写的是 `.ans img`（回答气泡），
而发出去的图缩略图挂在 `.msg.user .thumbs img` 上，于是读出 `thumbs:0` 像是坏了；
第二次贴的是 72 字节的假 PNG，`onerror` 按设计把坏图藏掉，只剩文件名 ——
换成页面里用 canvas 现画的**真 PNG** 才量得到 `loaded:2`。
还有一条边界要写清：合成 `ClipboardEvent` 不会让浏览器真去插入文本，
所以"文本粘贴没被劫持"的判据只能是 `defaultPrevented`，不是"字出现在框里"。

顺带把**像素量具自己的垃圾**收了：`ui-shot.sh` 每次跑都新建一个状态根（不换就会混进上一轮的会话），
但跑完从来没人删 —— 一天二十来次就是二十个目录躺在 `%TEMP%`。现在成功退出就删掉自己建的那个，
**失败时留着**（`mock.log` / `serve.log` 就在里面），`KEEP_HOME=1` 强制留。
两条都实测过：成功那次目录数 21→21，故意喂一个等不到的选择器那次 21→22。
同一批还清掉了 1400 多个历史遗留的 `haoai-rules-*.json`（测试临时目录改道之前漏的，
`-mmin +20` 只挑没人动的，移进回收站不硬删）。

## 这一批：回收站有入口 + 侧栏不再静默截断

两件"功能其实早就在、但界面上用不到"的事凑一批：

**删掉的会话放不回来。** `delete` 从第一天就是移进 `sessions/.trash` 而不是抹掉，
可"可恢复"只写在 tooltip 那半句话里 —— 没有入口的恢复等于没有，删错一条只能去开文件管理器。
现在左栏「历史会话」标题旁多了一颗**回收站**按钮：

- `GET /api/trash` 列条目（标题 / 几条消息 / 多大 / 什么时候删的），
  `POST /api/untrash {name}` 放回，`POST /api/purge {name}` 才是真删（走 `confirm` 二次确认）。
- **`name` 是客户端给的，所以只认回收站里真实存在的那个文件名**：先算 canonical 再判是否还在
  `.trash` 目录内。`../../settings.json` 这种名字如果不拦，就等于把状态根里任意文件搬进 `sessions/`。
- 放回前先看外面有没有同 id 的会话，有就**拒绝**而不是覆盖 ——
  "删了又建了一条同名新的"之后放回旧的，不该把新的那份历史吃掉。

**侧栏每组只给 8 条，但剩下的点得开。** 以前是全局静默截到 24 条：会话一多，
旧的就等于消失了（只剩搜索一条路），而"还有 N 条没显示"那行字是死的。
现在每组 8 条起，末尾一行「还有 N 条没显示 · 点开」，点一次多给 12 条。

验收（Kotlin）：`SessionTrashTest` 5 条 —— 删除后文件真在 `.trash` 且列表读得到；
放回后**字节级一致**且重新出现在 `/api/sessions`；越界的 `name` 被拒且 `settings.json` 没被动过；
同 id 已在外面时拒绝放回、外面那份一个字没变、回收站里那份还在；`purge` 之后真没了。
**第一条跑出来就是红的**：`/api/trash` 的 JSON 是我手拼的，多了一个 `}`，
`{"...,"title":"x"}` + `,"messages":0}` 拼成非法数组 —— 又是"接口返回 200 但内容没人读过"这一类。

验收（真像素，`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-trash.json`）：
12 条一组时 `rows:9`（8 条 + 当前那条另一组）且那行「还有 4 条没显示 · 点开」在，
点一下 `rowsAfter:13`、那行消失；删一条之后弹窗里 `回收站 / 1 个放回 / 1 个删干净`，
条目带着标题与"0 条 · 233 B · 时间"；点放回 → toast「已放回会话」、弹窗里少一条、
`/api/sessions` 里能查到那个 id。
截屏列表这一段是**直接把页面里的 `sessList` 换成 12 条假数据**测的：
`/api/new` 会复用"还空着的那条"，靠真建会话凑不出一个目录里 12 条非空会话
（要凑得给每条真跑一轮，慢且测的是网关不是渲染）。

## 这一批：搜到一句话，就跳到那一句话

左栏的搜索早就在翻消息正文了（`SessionIndex.match`），但点结果只是"打开那条会话"——
于是要找的那句话埋在六十句里，用户还得自己滚。**"搜到了"和"带你到那一条"是两件事。**

- `match()` 从"回一段字符串"改成回 `Hit(text, index)`，`/api/sessions` 多带一个 `hitAt`；
  标题命中的时候 `hitAt=-1`（没有具体某一条可跳）。
- 界面上点结果 → `openSession(id, hitAt)`：切过去、等历史画完（`applyState` 末尾消费
  挂着的 `v.jump`）、滚到那一条并闪一下（`.flash` 1.9s 后自己摘掉，重复跳同一条也能再闪）。
  点的是**眼前这条**就直接跳，不重新拉 state（那会把正在流式输出的半截回答抹掉）。
- 跳不到就明说：命中的那条可能已经被"就到这里"裁掉，toast 一句"已经不在这条会话里了"，
  而不是静悄悄停在原地。这里用 toast 不往流里插一行——软提醒不该跟着会话留在页面上。

顺带修掉一处**只有真点一下才会暴露**的自伤：`markSessions` 里我写的是
`e.currentTarget.dataset`，而 `e` 是 forEach 的**元素**、不是事件对象，
`currentTarget` 在非派发期是 `null` → 点搜索结果当场抛异常，会话根本切不过去。
同一轮还修了：搜索时列表里会混进一条**不匹配**的"新会话"（内存里那条没参与过滤）。

验收（真像素，`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-jump.json`）：
建一条三问的会话、换新会话、搜"换个话题" → `rows:1`（不再混进不匹配的那条）、
`hit:"2"`、摘要写着"user 第 3 条：…第二问…"；点结果 → `back:true`、
活动视图里 6 个带下标的节点、目标节点在、`.flash` 在、`inView:true`；
2.2 秒后 `flashGone:true`（自己收掉）；再跳一个不存在的下标 →
toast 说清楚、且**没有**把人从当前会话上带走（`stillCur:true`）。

## 这一批：跑着的时候再发一句 —— 排队，不是 409

手机端有"排队 + 插话 + 撤回"（`ChatViewModel`），PC 端以前是直接拒：
"这条会话正在跑，先按停止再发新任务"。可用户手打的下一句十有八九就是要紧接着说的，
而按停止会杀掉跑了十分钟的活 —— 两个选项都不对，正确答案是**让它等着**。

- `Managed` 加一条 `queue`（每会话一条，上限 8 句）。`startRun` 在最前面分诊：
  这条会话正在跑、且不是"编辑重发/重新生成"（那两类要截断历史，排队过去位置就错了）
  ⇒ 入队 + 发 `queue` 事件，**不碰并发判定**，也不新建会话。
- 接下一句的地方只有一个：那条 run 线程的 `finally`（本轮真的结束了）。
  一次只取一句 —— 取完发出去，`running` 又成 true，剩下的由下一条 run 线程接力。
  两条任务往同一段历史上写是最坏的情况，所以宁可慢，不可交错。
- **按停止 = 别再自己往下说了**：这时不但不接下一句，还要把队列清空。
  前端在 `stopNow()` 里把排队的文字原样退回输入框并 toast 一句"撤掉 N 句排队的"，
  所以"清空"不是把用户打的话弄丢。
- 界面上是一条"排队中 · 本轮说完接着说"的胶囊（可撤回），队列同时进 `/api/state`，
  刷新页面之后那几句还在。

验收（Kotlin，`QueueTest` 3 条 + 改掉一条过期的 `ApprovalFlowTest`）：
假网关对带"慢"的那句拖 1.8 秒，第二句才真撞得上"正在跑"。判据不看接口说了什么，
看**网关按什么顺序收到什么**：排队没接上则第二句永远不到、撤回没生效则第二句会到，
两种失败模式都能被同一条测试区分出来。原来那条"第二个任务该被 409"的用例
按新语义改成"该被收下并排队，且第一句仍在待决审批上、第二句不许抢同一段历史"。

验收（真像素，`SHOT_MODE=loop bash pc/tools/ui-shot.sh tools/steps/ui-queue.json`）：
跑着的时候按回车 → 胶囊出现、`state.queue=["这句要排在后面说"]`、那句话**没有**混进对话流；
点 ✕ → 胶囊消失、`state.queue=0` 而会话还在跑。

顺带修掉两处自己造的坑：① Python 补丁里写 `'
'` 会被 Python 先吃掉一层，
落到 JS 里就成了真的换行 —— 字符串字面量断掉、整个脚本不解析，
表现是"点什么都没反应 + `cur is not defined`"，看着像产品坏了其实是补丁坏了（同一批踩两次）；
② `ui-shot.sh` 收尾删状态根时进程还占着那个目录，`rm` 失败会让**全绿的验收**返回非零 ——
量具自己把成功报成失败。现在等 0.4 秒再删，删不掉也不改退出码。

## 这一批：引用、删这一句、会话置顶

手机端消息级操作有七件（复制全文/逐块复制/网页预览/重新生成/编辑重发/删除该消息/引用到输入框），
PC 端以前只有四件。这批补三样，都是"每天会用到、缺了就只能绕路"的：

**引用到输入框** — 把那句话以 `>` 引起来插到输入框最前面，光标停在引用之后。
不是"复制一遍"：复制走的是剪贴板，回来的是原文，而引用要的是"接着这句话问"。

**删这一句** — 关键在"一句"在 agent 历史里不是孤零零一条：用户那句话带出了整轮回答与工具调用，
带 `calls` 的 assistant 后面跟着若干条 tool 回复。**只抽走中间一条会留下孤儿 tool 回复，
下一次请求直接 400** —— 和压缩那条"切点只能落在非 tool 消息上"是同一个道理。所以：

- 删用户那句 = 删它和它带出来的一切（直到下一句用户话）；
- 删带调用的回答 = 删它和属于它的那几条工具回复（按 `callId` 配对，不多删别人的）；
- 直接点工具回复 = 拒绝，并说清"删它上面那条调用"；
- 跑着的时候 = 拒绝（引擎线程正在往同一段历史上写）。

**会话置顶** — 常用的那条不该被时间序冲掉。字段落在会话文件里（`pinned`，和改名一样只动一个字段），
`list()` 先按 pinned 再按 updated 排；行内那颗 `↑` 切换，置顶的那条在标题后带一个「置顶」小标。

验收（Kotlin，`MsgOpsTest` 3 条）：删一句用户话之后 `removed=2`、剩下的用户话只剩一条；
下标越界时一条都不动；置顶排在新会话之前、`pc-<id>.json` 里真写着 `"pinned":true`、
取消之后回到时间序。**判据不看接口返回 200，看盘上和历史里真的少掉了什么。**

验收（真像素，`SHOT_MODE=tools bash pc/tools/ui-shot.sh tools/steps/ui-msgops.json`）：
动作条上五个按钮都在；点引用 → 输入框以 `> 写一个 note.txt 里面放` 开头、光标在第 23 位；
点 `↑` → 行内出现「置顶」标签、`data-pinned="1"`、服务端 `pinned=true`，再点一次 → `pinned=false` 且标签消失；
按 `callId` 找到那条工具回复直接删 → 返回"工具回复不能单独删"；点删这一句 → toast「删掉了 10 条」、
界面上消息与工具卡一起清空。

一处量具边界要写清：无头浏览器会把 `confirm()` 自动答成"取消"，所以"删这一句"那一步是
先把 `window.confirm` 打桩成 `true` 再真点按钮 —— 测的是 `delMsg` 这条代码路径，
不是浏览器的对话框（对话框本身没什么可测的）。

## 这一批：用量账本 —— 把"今天花了多少"从猜变成量

用量页上那行「今天 / 近 7 天 / 全部」以前是**现算**的：把盘上会话的累计 token
按 `updated` 落在哪天来加。会话只有一个总数和一个"最后更新时间"，于是
昨天跑掉三万 token、今天只动了一下标题，那三万就全算成"今天"了 ——
数字看着挺具体，其实是在猜。

现在按**回合**落账：`HAOAI_HOME/usage.jsonl`，一次模型往返一行
（时间 / 模型 / 会话 / 输入输出 token / 耗时 / 成没成）。

- 为什么是追加式 jsonl 而不是一个 JSON 数组：读不用整份解析、写不用整份重写，
  中途断电最坏只坏最后一行 —— 而 `rows()` 会丢掉坏行，**不会让整块看板变成 500**。
- **失败也要入账**：不然"成功率"这个数根本没地方算，而它恰恰是"这个模型今天靠不靠谱"的判据。
- 日期分桶用**本地日期**：人说的"今天"是钟表那个数，不是 UTC。
- 柱子按**补全 token** 算高度：输入 token 会被一次长上下文拉爆，看不出哪天真正在干活。
- 界面上多了三块：按模型分行（条长按总 token）、近 14 天的柱子（缺的那几天也占位，
  不然"最近没干活"会被压缩成一根都没有）、成功率与平均 tok/s。
  图表不引库 —— 整个壳子必须断网能用，柱子就是 div。
- 旧的 `paintTotals()` 一并删掉：**同一个数不能有两个来源**，
  留着它就有"哪块面板说的是真话"这个问题。

验收（Kotlin，`UsageLedgerTest` 5 条）：坏行丢掉而整本仍读得出；
把一行的时间改成 10 天前之后，`today.n=1`、`week.n=1`、`all.n=2`、柱子凑齐 14 根
（这条就是原来那套现算法的照妖镜）；**模型名是一整条 Windows 路径时 JSON 仍然合法**
（反斜杠不转义就直接崩，而本地 gguf 的模型名恰恰全是反斜杠）；
按总 token 排名、成功率 2/3、空账本报零而不是 NaN。

验收（真像素，`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-usage.json`）：
跑两句之后 `今天 2 次 ↑2,468 ↓174`、按模型那行是 `mock · ↑2,468 ↓174 · 2 次`、
条宽 100%；14 根柱子里只有今天那根有高度（49px，其余是最小占位 2px）、
最后一根标着 `09-27`；`成功率 100% · 平均 93.8 tok/s · 账本 2 行`；
用量页 `scrollWidth-clientWidth=0`（没撑出横向滚动）。

## 这一批：HTML 产出可以就地预览 + 长代码块先夹住

模型贴一段 HTML 出来，以前只能读源码 —— 而"页面长对不对"恰恰是读源码读不出来的。
代码块上多了一颗**预览**（语言标 `html`，或者内容开头就是 `<!doctype`/`<html`/`<div` 之类）。

沙箱怎么关是这批唯一要紧的决定：

- `iframe srcdoc=… sandbox=""` —— 默认**什么都不给**，静态 HTML/CSS 直接能看，脚本不跑。
- 勾上「允许脚本运行」只加 `allow-scripts`，**故意不给 `allow-same-origin`**。
  给了同源身份，这段 HTML 就带上本页面的 origin，可以反过来对我们的 127.0.0.1 端口发请求 ——
  而那个服务没有第二道闸（谁都能调 `/api/task`）。模型的回答不是可信输入，
  **预览不该顺便变成它的执行环境**。
- 换 `sandbox` 不会重载文档，所以勾选之后重设一次 `srcdoc`，才是"从干净状态再跑一遍"。
- 不做"在新窗口打开"：`blob:` URL 继承我们的 origin，等于把沙箱白做。
- 弹窗宽度用 `.dialog:has(.pvframe)` 而不是加一个类 —— 加类要在每个开弹窗的地方记得清掉，
  漏一处就把下一个弹窗（改名/切模型）也撑成 980px。

另一半是排版：超过 20 行的代码块先夹到 320px，右下角「展开全部 / 收起」。
agent 一次贴 60 行是常态，不夹住的话回答里真正要看的那句话被顶出视口。

验收（真像素，`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-preview.json`）：
渲染一段带脚本的 HTML + 一段 26 行的代码 → `pv:1 more:1 folded:1`，
夹住的那块 `scrollHeight=636` 而实高 320（`max-height:320px` 生效）；
点预览 → 弹窗宽 980、`sandbox=""`、里面**不含** `same-origin`、`srcdoc` 带着原文；
勾"允许脚本" → `sandbox="allow-scripts"`，页面里的脚本确实跑了（截图里"脚本跑过了"），
而它想改宿主标题的那一行没成功：`document.title` 仍是 `HaoAI · PC`（`hacked:false`）；
点展开全部 → 320 → 638，按钮文字变「收起」。
这批没有 Kotlin 侧新增：纯界面层，判据都在几何与隔离断言上。

**两个只有看像素才会发现的自伤**（都记进"量具会坏"那一类）：

1. 折叠类我起名叫 `clip` —— 而这个界面里 `.clip` **已经是回形针附件按钮**（`width:30px;height:30px`）。
   于是代码块被压成 38px 高，`ui-check` 的"class↔CSS"检查照样全绿（类确实存在）。
   改成 `.fold` 之后 320/638 才对。
2. 一处补丁把 `.dialog{width:…` 后面多补了一个 `}`，规则被提前关掉，
   弹窗的底色/内边距/最大高全丢了 —— 现象是"弹窗变成一块没有边框的裸容器"。
   补丁吃掉"匹配到但没进捕获组"的文本，是同一批里第三次踩转义/括号这类坑。

## 这一批：会话级工具开关（右栏多一个「工具」页签）

手机端 `ToolRegistry` 的开关是**按会话**的，PC 端以前只有一张全局表。
差在哪：让 agent 只做只读调研的那条会话，手里不该还握着 `shell`；
而"全局关掉 shell"又太狠 —— 另开一条正经干活的任务就没法用了。
所以开关放在会话这一层，和 v0.32 的"按会话换模型"同一层。

- `PcSettings.toolsOff: List<String>` —— 引擎按它过滤 `schemas()`，界面上看得见"这条会话开着几把"。
- **可见性挡不住"模型凭记忆硬调"**：`byName` 是全量注册的，模型可以报一个没给它的工具名。
  所以执行侧再挡一次，并且回一句"在这条会话里被关掉了：右栏「工具」页签可以重新打开" ——
  只回"未知工具"会把人引偏。（同一族既有守卫是实验特性那一道，写法照它。）
- 开关**落进会话文件**（`"toolsOff":["shell"]`），`engineFor` 重建引擎时接回来。
  顺带修掉一个 v0.32 就埋下的洞：**会话自己的 `model` 当时只写不读** ——
  盘上存了，重启后引擎还是拿全局设置，"这条会话用本地 7B"其实活不过一次重启。
- 页签里灰掉的是"实验特性没开"，和"这条会话关掉"是两件事，汇总行分开写：
  `19 把工具 · 这条会话开着 17 · 特性未开 2 把`。混成一个数就说不清去哪儿开。

验收（Kotlin，`ToolToggleTest` 4 条）：清单非空且默认全开；
关掉 A 的 `shell` 之后 **A 的请求体 tools 里没有它、B 的请求体里还在**（不看界面看线上）；
模型硬调已关掉的工具 → 第二次请求的历史里出现拒绝语，且那次请求仍不带 shell；
开关写进了会话文件，删了再放回、重新 open 之后还是关着。

验收（真像素，`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-tools.json`）：
19 行、取消勾选之后汇总行跟着变、toast 说"这条会话关掉了"；
**点新任务之后面板要重新变成"全开"** —— 这一步最初是假的（见下），修好之后
`shellChecked:true`、汇总 `开着 17 · 特性未开 2 把`。

这批自己埋了四个坑，都值得记：

1. **字段名撞车**：我给会话视图加清单字段叫 `v.tools`，而 `v.tools` 早就是
   "正在跑的那几张工具卡"的映射表（`applyState`/`newTask` 都把它重置成 `{}`）。
   于是 `paintTools` 拿到一个空对象、`show()` 当场抛异常，表现是"切了会话面板不动"。
   改名 `v.toolList` 才对。
2. **共享 DOM 的面板必须跟着会话重画**：右栏那块面板是所有会话共用的一份，
   切会话不重画就还是上一条的开关状态 —— 和当初"工作区标签停在旧目录"同一类错。
3. 判"另一条会话不受影响"的像素步骤前两次是**假通过**：`/api/new` 会复用还空着的那条，
   所以"新任务"拿到的还是同一条会话。先真跑一轮让这条非空，测出来的才是两条。
4. **测试桩自己错了**：假网关回 `tool_calls` 时我把 `name/arguments` 平铺在 delta 里，
   而 OpenAI 流式格式要求嵌一层 `"type":"function","function":{...}` ——
   解析不出来就永远等不到第二轮请求，看着像"产品没挡住"，其实是桩不对。

## 这一批：断点恢复 —— 跑一半被杀，重启之后接着做（v0.41.0）

PC 端一条任务动辄几十轮工具调用。以前进程一没（关掉窗口、断电、改代码重启），
界面上只剩"这条会话的历史"，**看不出它当时正在跑**，人只能把那句话重问一遍 ——
前面几轮的 token 白花，而且工具可能已经改过盘上的东西，重问等于从头再来。

- `Engine.persist()` 里多一个 `runState`：`{goal,turn,at,started}`。
  开跑写一次、每轮 `TurnStats` 再写一次、**正常收尾（含用户按停止）清掉**。
  所以重启之后还留着的那条，就是被打断的那条 —— 判据是"谁自己收的尾"，不是猜时间。
  每轮多写一次会话文件（几十 KB）换"崩了也留得下痕迹"，这个代价值得。
- `/api/state` 带 `runState`，横幅的判据是 `runState!=null && !running`。
- 界面上是会话顶部一条横幅「上次这条跑到第 N 轮被打断」+ 原目标 + 两个出口：
  「从中断续跑」与「丢掉这段」。**不自动续跑** —— 用户可能已经不想要那个结果了。
- 续跑不重问：发出去的是"接着上次没跑完的那件事继续…已经做完的部分别重做一遍"，
  历史原样带着（`ResumeTest` 判的就是网关收到的请求体里必须有中断前那几轮）。
  横幅上显示的目标沿用**你当初那句原话**，不是这句续跑话术 ——
  不然再被打断一次，横幅会自指成一段绕口令。
- 「丢掉这段」只清现场，不发任何请求（这条断言第一次跑就红了，见下面量具那节）。

顺手修掉两个洞，都是这一批的像素跑出来的：

1. **冷会话点第一下不换画面**：`openSession()` 对"没画过历史的会话"只 `hydrate`、不 `show`
   （`show` 藏在 `applyState` 那句"curId 空或匹配才切"里，而冷会话两边都不满足）。
   表现是点第二条会话只把数据取回来、画面还停在上一条，**得点第二下才换**。
   这是侧栏所有点击的公共路径，跑了三十个版本没人发现 —— 因为以前的剧本要么只有一条会话，
   要么切会话用的是 `/api/new` 的返回值（那条路径自己会 `show`）。
2. `.btn` 全局是 `display:block;width:100%`（侧栏那些整行按钮靠它），横幅里照抄就变成
   **两条通栏大按钮**、把现场挤掉半屏（量出来 156px 高）。加 `.resume .btn{width:auto;flex:none}`
   之后同一处 62px，目标那行也终于看得见。

验收（Kotlin，`ResumeTest` 4 条）：跑不完的那条在盘上留 `runState`、正常收尾的不留；
**换一个新的 `WebServer` 实例（等价于重启进程）** 之后 `/api/state` 认得这条，
`/api/resume` 打到网关的请求体里带着原目标 + 中断前那几轮；`/api/abandon` 清掉且一条请求都不发。

验收（真像素，`PRE_RUNSTATE="$(cat pc/tools/fixtures/resume-sessions.json)" SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-resume.json`）：
26 步全绿。剧本里刻意种了**两条**没跑完的会话 —— 在第二条上点「丢掉这段」之后
`bannersAll:1`（另一条的横幅还在）、`dropRunState:null` 而 `demoRunState.turn:3`，
这才叫"现场是每条会话自己的"；回到第一条点「从中断续跑」，气泡 4→6、
最后一条 user 是"接着上次没跑完的那件事继续（上次到第 3 轮被打断了）。原来的目标：把 tools/step…"、
跑完 `runStateAfter:null`。

这一批被自己的量具绊倒两次，记下来：

- 假网关默认执行器是**一条线程顺序处理**，那句"90 秒不答"的卡住请求把续跑请求堵在后面，
  于是"网关没收到续跑"看着像产品坏了。换 `newCachedThreadPool` 才对。
- 测试里 `bodies.clear()` 放在"看见盘上有 runState 之后"—— 但落盘发生在调用模型**之前**，
  早清一次就会把那条 stuck 请求留在 clear 之后落进列表，
  "丢掉现场不该顺手发请求"就被自己的记录方式判红了。改成先等请求真到网关再清。

## 这一批：审批与提问可以按键盘决定（y / a / n / r，选项按 1..9）（v0.42.0）

写文件、跑命令要人批的时候，鼠标在另一块屏幕上、或者一只手端着咖啡 —— 这是 PC 端
最常见的一个卡手点。手机端早就有"键盘决定"，PC 端以前只有四颗按钮可点。

- 内联审批卡上四把：`y 允许一次`、`a 本任务都允许`、`n 拒绝`、`r 以后这类都允许`；
  `ask_user` 的选项按 `1..9` 编号。键帽就印在按钮上 —— **看不见的快捷键等于没有**。
- 匹配按语义不按文案：按钮带 `data-dec=allow_once|allow_session|deny|allow_rule`，
  键盘路径查这个属性。按 label 文本找的话，改一次文案就悄悄失效。
- 三条"不抢"的规矩比快捷键本身重要：带任何修饰键不抢；`#mask` 弹窗开着不抢；
  **焦点在输入类元素里不抢** —— 只放行一种例外：焦点在 `#box` 且**框是空的**。
  不留这个例外就废了（卡片恰好出现在人刚发完一句话之后，那时焦点就在 `#box` 里），
  而空框里没有要打的字，抢了不丢东西。卡片自带那个"或自己写一句…"输入框不在例外里。
- 只作用于**当前看得见的那条会话**的第一张待决卡：后台那条在等什么，
  切过去看见卡片之后再决定才对，隔着会话替用户点头是不能做的。

判据（真像素，`SHOT_MODE=ask bash pc/tools/ui-shot.sh pc/tools/steps/ui-askkeys.json`，30 步全绿）：

- 卡上看得见：`decs:["allow_rule","deny","allow_session","allow_once"]`、`kbds:["r","n","a","y"]`、
  提问卡 `keys:["1:绿色 1","2:蓝色 2"]`。
- **防误伤那条是主角**：框里先打"我要打"再按 `y` → `boxVal:"我要打y"`、
  待决卡 `stillWaiting:4`（一次都没决定）。这条不是装饰：在输入框里打字把一次写文件放行掉，
  比没有快捷键糟得多。
- 空框按 `2` → `已回答：蓝色，这条会话继续跑`；空框按 `y` → 卡消失、
  历史里那条 write 的 `note:"允许一次"`、`/api/file?path=theme.txt` 读出 `green`。
  判据落在"盘上真有这个文件"，不看按钮变没变。

两个坑：

1. **判据别读收起的 `<details>` 里的 `innerText`**：卡片决定之后会 `c.open=false` 折起来，
   而 `innerText` 是**按布局算的** —— 折起来的子树返回空串。于是 `done:[""]` 看着像
   "那行字没写出来"。换成 `textContent` 才是真值。（同一族坑还有 `naturalWidth=0` 那类"量具自己看不见"。）
2. `/api/file` 的字段叫 `text` 不叫 `content`，读错字段得到空串，差点把"文件写出来了"
   判成"审批没生效"。

顺带修了量具自己：`tools/shot.js` 的 `key()` 以前对字母键不带 `text`，
Chrome 就只发 `keydown` 不产生"打字"，于是"在输入框里按 y"这一族判据根本测不出来
（会一律通过）。现在单字符键带 `text`/`unmodifiedText` 与 VK 码，是真按键。

## 这一批：子任务可以单独停（总闸也会带上它们）（v0.43.0）

`task` 派出去的调研是**阻塞**的：父循环卡在 `spawn` 里，要等子任务返回才看得见停止旗子。
所以以前只有两个选择 —— 等它跑完，或者按总闸把这一轮整个中止（连正在写的正文一起丢）。
而"子任务跑偏了"恰恰是最常见的那种尴尬：模型让它"把所有模块看一遍"，一看就是四十轮。

- `Engine.liveSubs`：`label -> 子引擎`。`POST /api/substop {sid,label}` 只给那一条置停止旗，
  父任务继续。停止仍然只在**回合/工具边界**生效 —— 和主循环同一套规矩，不给子任务开特例。
- 同名子任务各自可停：模型很爱给两条调研起同一个标签，撞名时"停掉它"会停错那条，
  而且看不出来 —— 所以标签自动排号（`数数`、`数数 2`）。
- 被停掉的那条**要带上下文回话**：`子任务被中断（用户停掉了它）。中断前它说到：…`。
  只回"没有结论"，模型通常原地再派一条一模一样的。
- 总闸 `requestStop()` 现在会连带 `liveSubs.values.forEach { it.requestStop() }`：
  按下停止之后那条调研不再自己转圈。这是这条改动里最值钱的一半 ——
  没有它，"停止"在有子任务的回合上等于"等子任务自己跑完"。
- `/api/state` 报 `subs:["数数"]`，界面上每张在跑的子任务卡右上角一颗"停掉它"。
  刷新之后历史里还没有它（它没进历史），所以卡片要按 `subs` 补一张，
  否则"刷新一次就再也停不掉那条调研"。

判据（Kotlin，`SubStopTest` 4 条）：**数"停下来之后还问了模型几次"**，不数秒 ——
第一次用 `awaitIdle(4.5s)` 就自己红了（shell 每次都要起一个 bash，慢机器上时间这条不稳），
而轮数才是语义：停对了不会跑满 6 轮。四条分别是：`subs` 在跑时看得见、跑完清空；
停一条之后父任务收到「被中断」那句且**自己接着收尾**；按总闸之后子任务跟着停；
停一条已经不存在的要回 `ok:false` 并说清原因。

判据（真像素，`SHOT_MODE=subloop bash pc/tools/ui-shot.sh pc/tools/steps/ui-substop.json`，15 步全绿）：
卡上 `停掉它` 55×21、`notFullWidth/rightOfLabel/insideSummary` 三条几何都对（`.btn` 全局
`width:100%` 那个坑这里又撞了一次，靠 `width:auto;flex:none;margin-left:auto` 收回）；
点下去 900ms 内 `subs:[]`、`running:false`，历史里那条 task 回复是
「子任务被中断（用户停掉了它）。中断前它说到：我再数一轮…」，父任务最后那句"照它的结论收尾"也在。

**这一批最该记的一条**：假网关自己认错了人。
分类"这是子任务在问还是父任务在问"最初写成 `body.contains(MARK)` ——
父任务历史里那条 assistant 的 `tool_call` 参数**就带着这个标记**（它就是照那句话派的活），
于是子任务明明已经停住，父任务却被当成子任务一路收到 shell 调用跑到第 6 轮，
症状和"停不掉子任务"一模一样。改成只看 `role=="user"` 的消息正文才对。
（`mock-openai.py` 里同一处判据从一开始就是拼 `content`，所以像素侧没被误导 ——
两边写法不一致反而救了一次：同一套逻辑的两份实现互相暴露了对方。）

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

## 接真模型跑过（本机 llama-server，不走网络）

云端那把 key 现在只认证不授权（`/chat/completions` 一律 404 `model is not found`，
`/models` 返回空数组），所以"真模型验收"改成本地模型：**这条路不依赖任何配额，随时能重跑**。

```
# 本机已有的 llama.cpp 构建与模型（见 G:\AI\AI Model\ 下的 *.bat 配方）
"G:\AI\llama.cpp\Vulkan\llama-server.exe" -m "G:\AI\AI Model\Agents-A1\Agents-A1-4B-Q4_K_M.gguf" \
  -c 8192 --host 127.0.0.1 --port 8131 -ngl 99 --flash-attn on --jinja --no-webui
haoai set base=http://127.0.0.1:8131/v1 model="G:\AI\AI Model\Agents-A1\Agents-A1-4B-Q4_K_M.gguf"
haoai doctor
```

`--jinja` 不能省（不套聊天模板就没有 tool_calls，表现是"模型只会说话不会动手"）。

跑了两条真任务，都是"读 notes.md → 按三条待办逐条做完 → 把 [ ] 改成 [x]"这一类多工具多回合活：

- CLI 一条：`read → read → write → write → edit×4 → read×2`，`sum.txt` 里真的是 42（7+13+22），
  最后一段自述与磁盘上的产物逐条对得上；
- 网页一条（ask 档）：`read → shell → glob → write → edit`，中途弹了三次审批、逐个放行，
  截图：[真模型弹的审批](G:/hbt/pc-demo/real-approval.png) ·
  [真模型跑完的界面](G:/hbt/pc-demo/real-done.png)。

**这一轮抓到的最值钱的缺陷**：`"content": null` 被解析成了四个字符的字符串 `"null"`。
OpenAI 兼容网关在"这一帧只有 tool_calls"时标准写法就是 content:null，
而 `JsonNull.jsonPrimitive.content` 返回的是 `"null"` 而不是 null ——
于是模型每调一次工具，界面上多一行 `HaoAI > null`，历史里也多一句 `null`，
模型下一轮看到的"自己上一句说的话"就是垃圾。
全仓 21 处 `?.jsonPrimitive?.content` 一律换成 `contentOrNull`，并补了一条流式回归测试。
假网关从来不发 null，所以 85 条测试全绿也照不出来：**这是"必须用真模型跑一遍"的实证理由**。

后来又用真模型跑了三条以前只有假网关验过的机制，各照出一件事：

| 机制 | 真模型结果 | 照出来的问题 |
| --- | --- | --- |
| 计划模式 | 给了完整方案（含"验收方式""不做什么"），**一个文件都没动** | 没问题，plan 档是真的闸住了 |
| 长输出溢出 | `seq 1 20000` 的 43 万字符进了 `.haoai-output/`，模型读回头尾报出 1 和 20000 | 没问题 |
| 跑到一半停止 | 停能生效，但要等**当前这一轮生成完**（设计如此，见 `Engine.stopRequested`） | 那一轮生成了 5792 token / 94 秒 |
| 回合上限 | — | 请求里**根本没有 `max_tokens`**：一次回合的等待与花费全交给模型心情 |

于是补了 `maxTokens`（默认 4096，设置抽屉与 `haoai set maxTokens=` 都能改，0 = 不发）：
被截断时引擎会明说一句"这轮被 max_tokens 截断了，去改设置或把步骤拆小"，
而**不再**把它当"网关抖动"退避重试三次（旧代码会白等 31 秒再说一句空话 —— 这条是
把 maxTokens 压到 200 让真模型当场截断才看见的）。
顺带补上 CLI：以前 `set` 只认 5 个字段，新加的设置项在命令行上是只读的，
现在数值项全部可改，写错格式会保持原值并说一声。

## 已验证到哪一步

- `gradle test` → **186 条全绿**（整套 29 秒）：21 条引擎流程（计划模式拒写且 write 不进 schema、
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
  429 标可重试 / 400 不可重试），
  4 条专防真网关才会发的东西：`"content": null` 不能变成字符串 "null"（第一次接真模型撞出来的，
  见上面那节）、请求体要带 `max_tokens` 且设 0 时不发、`finish_reason: length` 要原样带出去、
  被截断的空回合不能再当"网关抖动"去退避重试。
  最近几批各自的份数写在自己那一节里（回收站 5、排队 3、消息级 5、用量账本 5、
  工具开关 4、断点恢复 4、子任务单独停 4），这里不再逐条追账。
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

1. **界面还差的几样（对着桌面 agent 与手机端比出来的）**：跨端审批与会话镜像（在手机批桌面那条）、
   记忆与备份的两端同步（PC 与手机各写各的 `MEMORY.md` 与备份，没有汇合的那一步 —— 与下面第 3 条同源）。
   已经落地的：web_search（免 key 的 DuckDuckGo + 可选博查）、图片进上下文与流里缩略图、定时任务、
   手动压缩、思考强度、@ 提及文件、工作区切换与侧栏按目录分组、累计用量、按会话换模型、
   截图 Ctrl+V 与拖拽进附件、回收站放回、侧栏每组点开更多、搜索命中跳到那一条、
   跑着的时候排队与撤回、引用某句到输入框、删某一句、会话置顶、按回合落账的用量看板、
   HTML 产出沙箱预览、长代码块折叠、会话级工具开关、断点恢复、审批键盘决定、子任务单独停、
   MCP 客户端与设置抽屉里的管理段、子任务（`task`）。
2. **`pc/` 是仓库内的独立 Gradle 构建**，没并进根 `settings.gradle.kts`——
   根构建是正在出货的手机 App，AGP 9 的内置 Kotlin 与 `kotlin.jvm` 插件在同一条
   classpath 上会打架。方案里的 Phase 1（抽 `:core` 让两端共用）仍然欠着。
3. ~~桌面控制（浏览器 / 屏幕理解 / 点击级自动化）~~ —— 浏览器控制与屏幕/点击控制
   已经落地（都默认关）。Phase 2 还欠：`write_stdin` 的真 PTY（ConPTY）、
   MCP 客户端、跨端审批与会话镜像、记忆与备份同步。

## 下一批的施工图（写给恢复目标后的第一次动手）

这张施工图上的两件都已经落地了。下一批要从"还缺的功能"那串里挑，别再从这里找活：
（第 1 件断点恢复 → v0.41.0；第 2 件键盘决定 → v0.42.0。）

**1. 断点恢复** —— 已落地（v0.41.0，见上面"这一批：断点恢复"那一节）。

**2. 审批与提问的键盘决定** —— 已落地（v0.42.0，见上面"这一批：审批与提问可以按键盘决定"）。

## 代码地图

```
pc/src/main/kotlin/com/haoai/pc/
  Env.kt        状态根、环境事实、日志
  Settings.kt   设置落库 + HaoFlag 注册表
  Provider.kt   ChatClient 接口 + OpenAI 兼容流式网关
  Prompt.kt     系统提示（身份 / 环境事实 / 工作纪律 / 工具使用 / Windows 须知）+ Memory（项目说明与全局记忆）
  Tools.kt      18 把内置工具 + 溢出落文件 + 快照 + diff；allTools() = 内置 + 外部 MCP
  Mcp.kt        MCP 客户端：stdio 上的换行 JSON-RPC，把外部 server 的工具包成引擎的 Tool
  Policies.kt   S2 权限规则表：tool(pattern) 有序匹配 + 命令前缀归约 + alwaysAsk
  GitTool.kt    一把 git（子命令切分保留引号；只读不问人，改仓库走同一张规则表）
  Pty.kt        S3 常驻交互进程（open/send/read/close/list）+ 共用的 shell 启动器
  Browser.kt    CDP 浏览器控制 + 手写极简 WebSocket 客户端（CdpSocket）
  Desktop.kt    屏幕理解与点击级自动化：生成的 PowerShell 模板 + PsRunner
  Engine.kt     回合循环、档位闸、两级截断、上下文压缩、中断、会话持久化
  Compress.kt   压缩的两份产出：给模型的提示 + 确定性机器摘要（兜底）
  SessionIndex.kt 磁盘会话索引：列表 / 恢复 / 改名 / 删除（移进 .trash）
  Schedules.kt    定时任务：时间算法（刻意不补跑）+ HAOAI_HOME/schedules.json + 5 秒轮询线程
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
