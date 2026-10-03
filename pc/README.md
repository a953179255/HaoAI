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

**不带任何参数 = 起 `serve` 并自动开浏览器**——这一条是给双击准备的：以前不带参数是打一屏帮助然后退 0，控制台窗口跟着一起关，看起来就是「闪退」。

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
                                                        # 步里 `must:[...]` 声明哪些字段必须为真 —— 断言打印出来是 false，
                                                        # 也算整轮失败（以前 eval 步只打印不判，等于假绿）
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
5. **1 倍采样的截图会自己造出"排版坏了"**：`dpr=1` 的 PNG 里 12px 的加粗中文被 ClearType 子像素   边缘 + 缩放采样画成"重影"，我两次把它读成"文字互相压住了"。量一下真实布局就知道是假的 ——
   `b.getClientRects()` 末段右边缘与后一句文本节点的左边缘 `gap=0`，没有重叠；
   `SHOT_DPR=2` 重拍同一块，加粗那段干净利落。判"看不清"之前先高倍率重拍一张，
   别把量具的锯齿算成产品的缺陷（真要有排版问题，几何判据也一定先红）。
6. **两个元素共用一个 id，会让"点不到"看起来像剧本的错**：`#pvClose` 同时是
   「浏览器预览」页签里那颗钮和「文件预览」弹窗里那颗钮。页面自己的代码侥幸没事（弹窗那颗用
   `$('#dialog').querySelector(...)` 限了范围），但 `getElementById` / 通用 `querySelector`
   只认文档里第一个 —— 于是剧本点"关闭"其实点在另一个页签里那颗 0 尺寸的钮上。
   现在弹窗那颗改叫 `#dlgClose`，并且 `ui-check.js` 多一条静态判据：**整份文档里每个 id 只能出现一次**
   （HTML 写死的与 JS 模板注入的算同一份文档；这条一上线就把上面这个真实历史案例报了出来）。

全量重跑所有像素剧本（按**退出码**判，不看打印）：

```
bash pc/tools/audit-steps.sh                 # 全量：跑法一律从本 README 原样抄（份数以脚本自己打印的为准）
bash pc/tools/audit-steps.sh ui-files        # 只跑名字里含该子串的
bash pc/tools/api-smoke.sh                   # 外部程序那条路：客户端发任务 → 终端批卡 → 文件真写出来
```

**审计的覆盖面本身也要有跑法可抄**（2026-09-29 补）：`ui-check.js` 只数"零判据的剧本"，
它假设每份剧本都在被跑。实际不是——`audit-steps.sh` 从 README 里抓命令，抓不到的那份**静默跳过**，
于是"跑了 47 份、红 0 份"里藏着 18 份从没进过审计的剧本（`ui-skill`、`ui-risk`、`ui-rewind`、
带 16 条判据的 `ui-memitems` 都在里面）。两个成因：README 里两种前缀混写
（从仓库根跑的 `pc/tools/…` 与从 pc/ 里跑的 `tools/…`），以及六处把 `ui-shot.sh` 打漏成
`bash pc/tools/steps/x.json`。现在两种前缀都认、**有文件没跑法直接算红**，并补上这几条：

```
bash pc/tools/ui-shot.sh pc/tools/steps/ui.json          # 全流程冒烟（83 步 / 22 条判据，#99 已清）
bash pc/tools/ui-shot.sh pc/tools/steps/ui-tour.json     # 首屏引导
bash pc/tools/ui-shot.sh pc/tools/steps/ui-ctx.json      # 记忆页与 @ 提及
bash pc/tools/ui-shot.sh pc/tools/steps/ui-memitems.json # 条目化记忆：加/改/忘（16 条判据）
```

最后那条**不能加 `PRE_MEMORY=1`**：这份剧本的判据是绝对行数（`rows.length==2`），
带上夹具种的 6 条记忆之后它一定红。第一次试跑就是这么红的，别把它记成产品回归——
判据写绝对行数的剧本，天生和夹具互相绑死。

这一轮跑出来的账：3 份剧本在点 0 尺寸的元素（其中一份是**第二个点击本来就是空操作**，
一份是 `#railBtn` 只点了一次＝收起侧栏之后再去点被收起的条目 —— 现在改成"收起→展开"各判一次），
外加我自己的抓法漏了带空格的引号环境变量（`PRE_RUNSTATE="$(cat ...)"`）导致 3 份红是假红。

一键跑这套验收（假网关 + 真服务 + 无头浏览器，全程不联网）：

```
bash pc/tools/ui-shot.sh                 # 默认 tools 剧本，图落在 %TEMP%\haoai-ui-shot
SHOT_MODE=ask bash pc/tools/ui-shot.sh   # 换剧本（chat/tools/spill/ask/git/loop/multi）
SHOT_DPR=2 bash pc/tools/ui-shot.sh pc/tools/steps/ui-memitems.json  # 2 倍采样重拍一张
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
SHOT_MODE=ask_batch bash pc/tools/ui-shot.sh pc/tools/steps/ui-askbatch.json  # T2 整批问卷：本地出题 1/3→3/3、答完一次回传、落库前缀原文
SHOT_MODE=ask_batch SHOT_PERM=ask PRE_LAN=1 SHOT_MOBILE=1 SHOT_W=390 SHOT_H=844 bash pc/tools/ui-shot.sh pc/tools/steps/ui-phoneaskbatch.json  # 手机网页端答批量问卷：跨端配对 + 4 秒重画后状态还在 + 答案原文落库
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
SHOT_MODE=media PRE_CLIP=素材.mp4 bash pc/tools/ui-shot.sh pc/tools/steps/ui-media.json  # ffmpeg 六个子命令 + 产出直接播（真解码）
SHOT_MODE=git PRE_GIT=1 bash pc/tools/ui-shot.sh pc/tools/steps/ui-git.json  # Git 面板：看 diff / 勾文件 / 提交（含中文文件名）
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-runs.json  # 命令面板 Ctrl+Shift+P + 任务运行历史与 ↻
SHOT_MODE=chat PRE_MEMORY=1 bash pc/tools/ui-shot.sh pc/tools/steps/ui-pal.json  # 面板里那四个"动作"真的按得到（分屏/整理记忆/压缩/新会话）
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-term.json  # 终端页签：人与 agent 共用同一常驻进程（各一个游标）
SHOT_MODE=chat PRE_CLIP=素材.mp4 bash pc/tools/ui-shot.sh pc/tools/steps/ui-attach.json  # 音视频附件：胶囊 + 播放器 + 刷新重放
SHOT_MODE=code bash pc/tools/ui-shot.sh pc/tools/steps/ui-code.json  # run_code：跑 python 出图，图真的交回模型
SHOT_MODE=chat PRE_FLAGS=browser_control bash pc/tools/ui-shot.sh pc/tools/steps/ui-iab.json  # 「预览」页签：CDP 画面进网页、人点的位置送回页面
SHOT_MODE=chat PRE_LAN=1 bash pc/tools/ui-shot.sh pc/tools/steps/ui-lan.json  # 手机联动：开/关端点、配对码倒数、设备移除（端口每轮现挑）
SHOT_MODE=tools bash pc/tools/ui-shot.sh pc/tools/steps/ui-rewind.json  # 检查点：两步确认整轮撤销一次任务的改动
SHOT_MODE=rec bash pc/tools/ui-shot.sh pc/tools/steps/ui-record.json  # 录屏：真录桌面 → 停 → 界面里真能播
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-tools.json  # 会话级工具开关 + 切会话要重画面板
PRE_RUNSTATE="$(cat pc/tools/fixtures/resume-sessions.json)" SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-resume.json  # 断点恢复：横幅 / 续跑 / 丢掉，两条会话各管各的现场
SHOT_MODE=ask bash pc/tools/ui-shot.sh pc/tools/steps/ui-askkeys.json  # 审批/提问按键盘决定 + 输入框里打字不误伤
SHOT_MODE=subloop bash pc/tools/ui-shot.sh pc/tools/steps/ui-substop.json  # 子任务单独停 + 总闸连带停（几何三条 + 中断回话）
SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-backup.json  # 备份：区块/导出/两下确认/自动 pre-restore
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

验收：`PRE_DIRS="other" SHOT_MODE=chat bash pc/tools/ui-shot.sh pc/tools/steps/ui-ws.json`（真像素里看到：
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

## 这一批：备份导出/恢复（v0.44.0，缺口地图 B1）

状态根里跑着几十条会话、记忆、规则、技能 —— 以前没有任何一个能"整包带走"，
误删只能靠回收站捞会话文件。这一批把 `pc/**/*.kt` 里 grep `backup` 为零这件事补上。

- **`Backup.kt`**：zip 里 `manifest.json` + `payload/<范围>/<相对路径>`，
  manifest 记 `format/kind/at/scopes/keysIncluded` 与**每条 payload 的 size + sha256** ——
  **与手机端 `DataBackupManager` 同一套契约**（两端将来互认对方的包）。
  五个范围：`settings`（settings/rules/approvals/mcp/browser）、`sessions`、`memory`、`skills`、`tasks`。
- **恢复是先验后写**：全部 payload 抽到临时目录逐条校验，**任一条对不上（大小或 sha256）、
  路径想跑出状态根（`../`、绝对路径）、格式版本比现在新、不是本端的包 —— 整包拒绝，
  此时状态根一个字节都没动过**。全过了才写回，且写回前先自动导出一张 `pre-restore-*.zip` 当后悔药。
- **`apikey`/`searchkey` 默认不进包**，勾了才写（manifest 的 `keysIncluded` 记在明处）。
- **正在跑的会话一律拒绝导出/恢复**：引擎每轮 `persist()`，边写边读出来的包"看着完整其实缺半轮"。
- 恢复成功之后把内存里的引擎与设置丢掉、重读状态根 —— 不丢就等于"恢复了，但界面还在用旧数据"。

界面（设置抽屉最下面「数据与备份」）：勾"包含明文 Key" →「导出一份」→ 行内显示包名/条数/大小；
恢复与删除都要**点两下**（按钮自己变成"再点一次：恢复会覆盖现在的状态根"）。
刻意不用原生 `confirm()`：它会挡住无头验收，而且把"这一步会覆盖"放在按钮上更直白。

判据（Kotlin，`BackupTest` 8 条）：导出→改一个字节→恢复被拒且**盘上原样**；越界路径被拒且外部没落文件；
更新格式/外来包被拒；默认包里没有 key、勾了才有且 manifest 声明；新状态根恢复后逐字节一致 + 自动留了
`pre-restore` 包；**真有一条会话在跑时导出与恢复都被挡、停掉后能导出**；非 ASCII key 给人话错误。
（像素，`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-backup.json`，19 步：区块在、默认不勾 Key、
导出后行出现、第一下点只"上膛"不执行、第二下执行并重载、`/api/backups` 里出现 `pre-restore-*.zip`。）

**这一批最该记的三件事：**

1. **`readManifest` 的 `return@use` 只退出 lambda**，函数最后那句 `null` 才是返回值 ——
   于是 manifest 永远读不到，症状是"恢复永远拒绝"。测试第一条就红了。
2. **同一秒导出两次会拿到同一个包名**，后一份覆盖前一份（用户点两下就丢一份）。
3. **量具自己串台**：`shot.js` 起浏览器**之后**才探 `/json/version`，而上一轮没退干净的无头 Edge
   正占着 9339 —— 探测"成功"（连的是它），断言在新标签里跑、截图却落进它攒下的旧标签，
   我截到了**上一轮会话的图**（版本号都对不上）。修法三件套：
   `pickPort()` 开火前探端口、端口从 9340 起每次挑"真没人听"的、
   `tools/kill-stale-shot.ps1` 清僵尸（而且必须放在**挑端口之前** —— 放后面会把刚起的服务杀掉，
   页面整个打不开，这是同一个坑的第二半）。

## 这一批：媒体工具 ffmpeg + 产出能直接播（v0.45.0，缺口地图 B7）

你点名要"视频制作自动化、帮直播和创作视频"，而 `pc/**` 里 grep `ffmpeg|transcode` 是零命中 ——
模型连素材有多长都问不到，更别提剪切转码。这一批把这条从"没有"做到"跑完就能在界面里看"。

- **`Media.kt` 的 `Ffmpeg`**：定位顺序 = 显式指定 → 环境变量 `HAOAI_FFMPEG_DIR` →
  状态根里那行 `HAOAI_HOME/ffmpeg` → `PATH` → 四个常见解压落点（winget/手动/choco/scoop）。
  **找不到时把装法说出来**（winget / choco / ffmpeg.org + 不动 PATH 的两条后门），
  而不是回一个 exit=1 —— 静默失败会被读成"agent 在偷懒"。
  调用一律 **argv 直发 ffmpeg.exe，不经 shell**：媒体参数里全是 `scale=1280:-2` 这种带冒号的串，
  走 shell 必被二次解析吃掉引号（ShellTool 那条老坑）。实测中文文件名 `测试素材.mp4` 也正常。
- **一把 `media` 工具 + 六个子命令**（info/transcode/cut/frame/audio/cover），不做六把窄工具，
  理由与 GitTool 同源。参数全部白名单化后由我们自己拼 argv：宽高只收整数、时间只收数字串、
  编码器名限 `[A-Za-z0-9_.-]` —— 于是模型传什么都拼不出第二条 filter。
  `info` 只读不问，其余按写文件走权限档（计划模式拒得干脆）。
- **时间写法在入口归一**：认 `12`、`12.5`、`1:05`、`1:02:03`，不认就说是哪一项错了。
  直接甩给 ffmpeg 不行 —— `-ss 3sec` 会被它当 0，"抽第 3 秒"静默变成抽第一帧。
- **mp4/mov/m4a 一律加 `-movflags +faststart`**：moov 不挪到文件头，浏览器要等整份下完才起播。
- 产出默认进 **`.haoai-output/media/`**（子目录，刻意不放顶层）：溢出目录的清理只扫**直接子文件**，
  放顶层等于让用户生成的视频被"40 份日志"的回收规则删掉。
- **`/api/media`**：与 `/api/img` 同一条边界（canonical 后必须仍在这条会话的工作区内、
  类型由**魔数**认、512MB 上限），并实现 **Range**（206 + `Content-Range`）——
  不实现的话拖进度条就是坏的我。工作区外的产物不进 `media` 列表，正文里说清"界面不放"。
- 界面三处：工具卡底下的 `<video>/<audio>` 播放器、右栏「产出」点文件也放、
  **刷新之后还在**（`media` 与 diff/note 一样进历史与 stateJson）。
  产出音视频的卡**跑完不收起来** —— 那是用户要看的成品，收起来等于没给。

判据（Kotlin，`MediaTest` 14 条）：没装 ffmpeg 时那句话必须同时含 winget / 环境变量 / ffmpeg.org；
时间四种写法 + 三种坏写法；坏子命令/缺 input/坏后缀/坏 fps 全在动手前被拒；计划模式拒写且一次都不问；
文本改名成 `.mp4` 被魔数拒；Range 三种写法 + 越界退回整份；
**真跑 ffmpeg** 的四条（info 报出双流、抽帧真出 PNG 且像素递给了模型、音轨是 `audio/mpeg`、
剪切短于原片并说明关键帧代价）、转码后 `width=160` 且 moov 在前 2 KB、
输入输出同文件被拒且源文件还在、工作区外的产物不进 `media`。
（像素，`SHOT_MODE=media PRE_CLIP=素材.mp4 bash pc/tools/ui-shot.sh tools/steps/ui-media.json`，30 步：
播放器解码出 160×120/4.02s、`Range: bytes=100-199` 回 206 且正好 100 字节、
`../../../Windows/win.ini` 回 404、seek 到 2s 真的动了、刷新后播放器还在且仍解码、
「产出」页签点 `音轨.mp3`/`小.mp4` 各摆出能放的播放器。）

**这一批最该记的四件事：**

1. **无头页的 `visibilityState=hidden` 会让 Chrome 推迟音视频加载** ——
   同一个 `<video>` 在首屏解码得出 160×120，刷新之后 `readyState` 永远是 0、
   `networkState=2`、`error=null`，而 `fetch(同一个 URL)` 明明白白回 200 `video/mp4`。
   症状长得像"刷新后播放器坏了"这个产品缺陷，其实是量具条件：
   `--headless=new` 默认把页面当后台。修在 `shot.js`：`Emulation.setFocusEmulationEnabled`
   + 三个 `--disable-*-backgrounding/throttling` 启动参数。
   判据也跟着改硬：不看"元素在不在"，看 `loadedmetadata` 回来后的 `videoWidth`。
2. **探针返回对象时，值为 `undefined` 的字段会被 `JSON.stringify` 整条丢掉** ——
   我那条诊断打出 `{}`，看着像"什么都没查到"，其实是被丢掉了。
   断言探针从此**返回拼好的字符串**，宁可难看也不许沉默。
3. **`scrollIntoView` 默认是 smooth（异步）**：紧接着读 `getBoundingClientRect` 会读到滚动前的位置，
   于是"元素不在视口内"是假的。验收脚本里一律写 `behavior:'instant'`。
4. 两处工具链的转义坑各咬一口：`as` 是 Kotlin 硬关键字（`vararg as:` 直接编译不过）；
   bash heredoc 会把源码里的 `\\` 收成 `\`（候选目录那批 `"C:\\ffmpeg\\bin"` 全变成非法转义）。
   后者这次用正斜杠绕开 —— Windows 的 `File` 两种分隔符都认，正斜杠还免转义。

## 这一批：Git 面板 —— 人有地方看 diff、勾文件、写提交（v0.46.0，缺口地图 B5）

`git` 工具早就有了，但那是**模型**的手；vibe coding 的日常是"它改完，我扫一眼 diff，
挑两个文件提交，写一句人话"，而这件事在界面上**一个入口都没有**。
这一批补的是右栏新页签「Git」。

- **`GitPanel.kt`**：status / 暂存 / 取消暂存 / diff / 提交，四个 `/api/git*` 端点。
  **不过模型的权限闸** —— 点按钮的人就是用户本人，再弹一次"要不要提交你刚按下的提交"
  是把审批做成噪声。边界改由代码守住：只认这条会话工作区里的仓库、
  路径过 `safePath`（空、以 `-` 开头、绝对、`..` 逃出仓库的一律拒）、面板上没有 push 这类动词。
- **porcelain 用 `-z`**：默认输出会把中文文件名转义成 `"\346\226..."`，
  按行切必错，而前端拿到的路径就配不上仓库里的真文件。
- 界面：页签里是"分支 · 工作区干净/不干净 N 项 · 已暂存 N"、上次提交那一行、
  一行一个文件（勾选框 + `XY` 两列状态 + 省略号路径 + diff 小按钮）、
  提交说明输入框**独占一行**（挤成 80px 就没法写一句话），"只看已暂存"可筛。
  状态行只留最近一次动作的结果。

判据（Kotlin，`GitPanelTest` 8 条）：改动/暂存/未跟踪三种状态分得清（含中文文件名原样出现）；
暂存↔取消暂存往返；提交后 `git log` 里真有新提交且该文件从 status 里消失、界面报出新 hash；
空说明与"没暂存东西"都被拒且**没有凭空产生提交**；路径白名单挡住 `--no-verify` 与 `../../`，
且一批里有一个坏路径就整批不暂存；diff 里有 `+three`、未跟踪文件明说没有基线；
`repoFor` 只认往上真实存在的 `.git`。
（像素，`PRE_GIT=1 bash pc/tools/ui-shot.sh tools/steps/ui-git.json`，28 步：
面板列出 3 行且 `中文.txt` 没被转义、勾一下→"✓ 已暂存 1 个文件"且该行标成已暂存、
diff 弹窗有 +/-、提交后 `git log` 的 hash 变了且剩 2 项、
空说明被拒且状态行改口、`../../outside.txt` 与 `--no-verify` 各回一句能看懂的拒绝、
行内元素不溢出、提交框宽度 267px 且自己占一行。）

**这一批最该记的两件事：**

1. **`GitCli.out()` 对结果 `trim()`，而 porcelain 每行的**第一个字符就是状态列**。**
   未暂存的修改长这样：`" M a.txt"` —— 首字符是空格，被 trim 吃掉之后整行左移一位，
   于是**列表里的第一条永远被读成"已暂存"且路径少一个字符**（`a.txt` → `.txt`）。
   单测第一条就红了；`GitPanel` 从此自己读原始字节不 trim。
   教训：给"格式敏感"的输出用通用 helper 之前，先确认它没有顺手做"清理"。
2. **客户端的拒绝也要写进状态行。** 空提交说明原本只弹 toast，结果上一轮的
   "✓ 已提交：…" 还留在原地 —— 用户看着"已提交"以为自己刚点的那下成功了。
   量具也是这么发现的：断言读 `#gitState` 拿到的是旧文案，`refused:false`。

## 这一批：命令面板（Ctrl+Shift+P）+ 任务运行历史（v0.47.0，缺口地图 B6）

两件事都是"不敢开自动化"的那半边：**入口找不到**（能力散在斜杠命令、设置抽屉、右栏页签里）
和**跑完不留档**（定时任务半夜跑的那几次，第二天界面上什么都看不出来）。

- **`RunLedger.kt`**：一次运行（一个用户输入 = 若干次模型往返）跑完往 `HAOAI_HOME/runs.jsonl`
  追一行 —— 时间、哪条会话、问句、**触发方式**（手动/排队/重跑/改问重发/定时/续跑）、
  几轮、多久、是不是被停、结论首段。与 `UsageLedger`（按回合记 token）分两份：
  一份算钱、一份回看，一次运行有几十个回合，硬合只会两边都别扭。
  超 `KEEP=500` 自己收，坏行跳过不拖垮整份。
- **`Engine.runTrigger`** 由 `Server.startRun(trigger=…)` 写：定时任务与手点混在一起，
  "到底是不是它自己跑挂了"就查不出来。
- **右栏「用量」底部**多了运行历史（时间 · 触发 · 几轮几秒 / 第二行问句 + ↻）。
  ↻ **不直接重跑**，而是把那句话填回输入框、光标停在那儿等回车 ——
  一次运行花的是真 token，一键就跑比多点一下贵得多。
- **命令面板**：Ctrl+Shift+P 开，模糊搜「命令 / 跳转 / 会话 / 重跑」，工作区文件异步补在末尾，
  上下键/Tab 选、回车执行、Esc 或点外面关。跳转项里"设置：数据与备份"这类会**打开抽屉并滚到那一节、
  描一圈高亮** —— 抽屉是 fetch 回来才渲染的，所以那里是轮询等它出现（最多 2 秒）。

判据（Kotlin，`RunLedgerTest` 7 条）：跑三次出三条且最近的在最前；被停的要说"被停止"而不是装成失败；
三种触发方式分得开；结论里带换行必须压平（一行一条是 jsonl 的命根子）；
中间塞一行垃圾只丢那一行；灌 545 行后 `add` 要自己收到 500 且最新那条还在；
`/api/runs` 那份 JSON 有面板要画的每个字段。
（像素，`SHOT_MODE=chat PRE_TOUCH=notes.md bash pc/tools/ui-shot.sh tools/steps/ui-runs.json`，53 步：
真跑三次 → 账本 3 条 → 点 ↻ → 问句回到输入框且光标在那 → 回车 → **第 4 条**；
真按 Ctrl+Shift+P（`shot.js` 的 key 现在带修饰位）→ 34 项浮出 → 打 "git" 收到 1 项 →
↓ → 回车 → 右栏真的切到 Git；打"数据与备份"→ 回车 → 抽屉开着且那一节被描边；
点面板外面关得掉；在输入框里打 y 不会被抢键。）

**这一批最该记的三件事：**

1. **右栏只有 292px，"时间+触发+结论+按钮+问句"五样一行摆不下** —— 第一版把问句挤成 0 宽，
   界面上只剩"手动 跑了 1 轮"，看着像历史没记问句。改成两行（第二行问句 + ↻）后
   量具才量出 `goalW=227 / btnVisible=true`。**这类"结构都在但看不见"的错，只有像素能抓。**
2. **探针不能等过证据的寿命。** 设置那一节的描边只留 1.6 秒，而剧本在 `sleep 1600` 之后才读
   → `highlighted:false`，看着像跳转坏了。把探针提前到 800ms 就绿了。
3. **`shot.js` 的按键不支持修饰位**，所以快捷键本身（Ctrl+Shift+P）以前根本没法验。
   加了 `mods`（CDP 的 modifiers 位掩码）之后，这条入口是真的按出来的。

## 这一批：终端页签 —— 人和 agent 共用同一个常驻进程（v0.48.0，缺口地图 B3）

`shell_open/send/read/close/list` 五把工具早就有了，但**人看不到那个进程**：
agent 跑 `gradle` 弹确认时人插不上手，人在终端里敲的东西 agent 也看不见。
这一批把右栏第 8 个页签「终端」补上 —— 同一个 `ProcRegistry`，两边各一个游标。

- **`Pty.kt` 的输出从"一条队列"换成"带序号的环形缓冲 + 每消费者一个游标"**（`Live.push/since`，
  上限 2000 行）。这不是重构癖：共用一个 `ConcurrentLinkedQueue` 时**谁先 poll 走那行对方就永远看不到**，
  表现是"界面上明明有输出，模型却说它没看到"。面板的游标存在浏览器里，模型的游标（`toolCur`）在服务端。
- 五个端点：`/api/shells`、`/api/shell/open`、`/api/shell/send`、`/api/shell/tail?id=&after=`、
  `/api/shell/close`。**边界**：起进程只认 shell 名字（bash/pwsh/cmd，由 `ShellLauncher` 在本机找），
  不接受任意可执行文件；`cwd` 必须落在**这条会话的工作区里**。理由与 `/api/img` 同源 ——
  服务只绑 127.0.0.1，但同机任意页面都能打这个端口，"能起任意进程 + 任意目录"是不能给的。
- 界面：进程标签条（退出的划掉）、shell 选择 + 名字 + 开一个 / 关掉这个、
  等宽输出区（自己滚：用户在往回看时不抢滚动条，只留最近 600 行）、
  命令行 Enter 送并补换行、Shift+Enter 送但不回车（喂交互式提示用），每 900ms 轮询一次，
  离开页签就停轮询。

判据（Kotlin，`PtyTest` 新增 2 条，共 7 条）：**面板先到先看之后模型仍看得到同一行**，
且面板再轮询一次不拿重复行；缓冲有界（灌 2500 行后不超过 2000、留下的必须是最近的、
最老的被挤掉）。
（像素，`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-term.json`，29 步：
点「终端」→ 开一个 bash（回执"已开 p1（bash @ ws）"）→ 敲 `echo` 看得见 →
`after=0` 再取一次**还在**（证明没被谁 poll 走）→ `v=42` 再 `echo 值是 $v` 得 42（同一个进程）→
`cwd=../../` 与 `shell=../calc` 各回一句人话 → 关掉后列表空 →
控件溢出检查：`overflowing:none`、发送按钮 38px 完整在栏内、页面无横向溢出。）

**这一批最该记的两件事：**

1. **292px 的右栏会把按钮挤出可视区，而且"结构都在、就是点不到"。** 第一版"发送"整个跑到栏外，
   量具读 DOM 一切正常。现在每个新面板都带一条**几何断言**（遍历控件比 `right` 与 `rail.right`），
   并把 `flex-wrap` / `min-width:0` 当成默认写法。
2. **两个消费者共用一条队列是设计错误，不是实现细节。** 换成序号缓冲之后，
   "面板刷新把模型饿死"这类 bug 从结构上消失 —— 这类"改数据结构消掉一类 bug"的账，
   比在两边各加一个锁划算。

## 这一批：音视频附件 + `run_code`（v0.49.0，缺口地图 B9）

B7 让 agent 能剪视频、抽帧、抽音轨，但**人没法把素材递给它** —— 附件那一路只认图片，
拖进来一个 `.mp4` 会被当成"不是图片"静默丢掉。同时 vibe coding 里最高频的一个动作
"这段代码跑起来到底输出什么"一直只能走 `shell`，而 Windows 上带引号的命令每次都要
重新拼转义（`ShellTool` 的注释里记过这个坑）。这一批把这两条缝上。

- **附件分两条路**：扩展名命中音视频（mp4/mov/mkv/webm/avi/mp3/m4a/wav/flac/ogg/aac/opus）
  进 `media`，其余进 `images`。输入框的 chip 上音视频带 `▶` 并显示字节数；发出去之后
  用户气泡里直接是播放器，重开会话还是播放器（`media` 跟着消息落库）。
- **`media` 里放的是路径，不是内容**：`/api/run` 收到的 `media` 过 `insideMedia` 四道闸
  —— 必须在这条会话的工作区内、不超过 512MB、按魔数闻得出确实是音/视频、最多 2 个 ——
  只把绝对路径写进用户消息。**绝不能混进 `images`**：那一路会把文件编成 data URL 发给模型，
  一段素材就变成一堆谁也用不上的 base64。"看见画面"由工具做（`media` 抽帧、`run_code` 出图），
  附件这条路只负责播。
- **`run_code` 是第 21 把工具**：`lang=python|node`，代码先写成临时文件再执行，
  所以多行、引号、中文都不需要转义。跑在 `.haoai-output/runs/<时间戳>/`，
  那个目录里除脚本自身之外新出来的文件一定就是这次产物 —— 图片进 `images`（模型能看），
  音视频进 `media`（人能播）。这样既不用扫工作区猜产物，也不会把用户自己的文件误认成输出。
- **解释器按 `PYTHON_EXE`/`NODE_EXE` → PATH → 常见安装目录找**，找不到就回一句人话
  （告诉该装什么、或把目录写进哪个环境变量），不抛栈。
- **先过闸再落盘**：顺序是 `ctx.guard` → `mkdirs` → 写脚本 → 执行。被拒的那一次
  不该在用户工作区里留下目录和脚本。

判据（Kotlin 新增 11 条：`RunCodeTest` 8 + `AvAttachTest` 3，整套 236 条）：
中文 print 不糊（`你好 from python`、`42` 都在正文里）、stderr 并进同一条结果、
非零退出 `error=true` 但**输出仍然留着**（同时有 `exit=3` 和"前面还有话"）、
运行目录里那张 png 被认成 1 张图且正文清单报出 `out.png`、node 同样能跑、
不认识的 lang 回话里带得出可选列表、**计划模式拒跑且那个 run 目录压根没被创建**、
`timeout=5` 去跑睡 60 秒的脚本在 25 秒内收住并报"超时"；
另一侧三条：音视频落在 `user.media` 而 `user.images` 是空的、盘上没有的路径被丢掉
而不是污染历史、`media` 字段过完会话文件往返之后重开还在。
（像素，两份剧本共 29 步（附件 20 + 代码 9）：
`SHOT_MODE=chat PRE_CLIP=素材.mp4 bash pc/tools/ui-shot.sh tools/steps/ui-attach.json` →
chip 里同时有 `▶`、文件名和 `音视频 54 KB`、用户气泡里 1 个播放器、
**真解码** `videoWidth=320 videoHeight=240 duration=4`、发送后 chip 清空、页面不横向溢出、
刷新重放之后播放器还在且仍是 320×240；
`SHOT_MODE=code bash pc/tools/ui-shot.sh tools/steps/ui-code.json` →
工具卡 `exit=0`、stdout 可见、正文报出运行目录、**模型真的拿到了那张图**（缩略图 1 个、
`naturalWidth=1`）、卡片是开的且图在视口内、重放后 `pixelsHandedToModel=dot.png`。）

**这一批最该记的四件事：**

1. **管道里的 stdout 编码是产品问题，不是环境问题。** `RunCodeTest` 第一条就把中文打成
   了乱码 —— Windows 上 python 发现 stdout 不是控制台就退回 cp936。修法与 `ShellTool` 同源：
   起进程时给 `PYTHONUTF8=1` + `PYTHONIOENCODING=utf-8`。**凡是往管道里写中文的进程都要
   显式给编码**，以后每加一把新解释器都要回头看这一条。
2. **测试共用一个 `haoai.home` 会互相看见对方的会话。** `AvAttachTest` 里固定 id "av1"
   被三条测试轮流复用，第二条 `Engine()` 起来时把第一条的历史整段恢复进来，于是断言读到的是
   上一轮那条消息，现象是"莫名其妙多出一个播放器"。现在每条测试自己
   `System.setProperty("haoai.home", …)` 并用 `nanoTime` 造独立会话 id。
   凡"落盘再读回"的用例，隔离单位必须是目录，不是对象。
3. **"附件能显示"与"模型能看见"是两件事。** 第一版把 mp4 一并塞进 `images`，界面很好看，
   但请求体里多了一段谁也用不上的 base64。现在图片与音视频各走一条字段，判据也分两头写：
   一条断言 `images.isEmpty()`，一条断言播放器解码成功。

4. **打印出来的 `false` 不是失败 —— 量具自己也会给假绿。** 补「播放器放不出来才解释一句」这条时，探针返回 `{"hintOnErr":false}`，整轮却报 PASS：`shot.js` 过去对 eval 步**只打印不判**。现在加了 `must`：步里声明哪些字段必须为真，任一为假就把退出码置 1（老步不声明就保持原行为，因为有些步的期望值本来就是 false，比如 `running:false`）。顺带两个自己造的坑：① 探针用 `v.onerror=()=>r()` 等错误，等于把内联的 `medFail` 覆盖掉，于是"没提示"是探针的错不是产品的错 —— 等事件要用 `addEventListener`；② `PRE_CLIP` 要的是带扩展名的文件名，我传了 `PRE_CLIP=1`，ffmpeg 报的是"认不出输出格式"，看着像 ffmpeg 坏了 —— 现在 `ui-shot.sh` 一上来就挡掉。
   （同一批还改掉一处文案噪声：每个正常播放器底下都常驻一句"播不出来通常是编码问题"，看着像整片区域坏了；现在只在 `error` 事件真的来了才补那一句。）

## 这一批：浏览器预览面板（右栏「预览」）—— 人自己看一眼、自己点两下（v0.50.0，缺口地图 B8）

浏览器控制（CDP）早就有了，但那是**模型的眼睛**：它说"页面加载完了、按钮点到了"，
人只能信。视频/直播工作流里我要盯的是"这页到底长什么样、这个按钮我按得着吗"，
所以右栏多一个「预览」页签（现在一共 9 个）：把 CDP 的一帧画面搬进网页，人点的位置再送回页面。

- **画面**：`Page.captureScreenshot` 出 JPEG（quality 55，一帧 30–80KB），面板每 900ms 取一帧，
  走 `URL.createObjectURL` 而不是把 URL 塞进 `<img src>` —— 后者会被浏览器按同一 URL 缓存住，
  画面就定格了。**取消勾选「连续刷新」= 定格**，看长文的时候不再刷。
- **点回去**：图是等比缩在 292px 窄栏里的，所以"图上的点"要按视口比例换算成"页面上的点"
  （`PreviewPanel.mapClick`），再走 `Input.dispatchMouseEvent`。**这里刻意不用模型那条 `n.click()`** ——
  那跳过了命中测试，hover 才出来的菜单、只认 mousedown 的控件全都点不动；人看着画面点，
  就必须让页面自己去判断点到了谁。滚轮同理（`mouseWheel`）。
- **送字**走 `Input.insertText`（IME 那条路），中文不会散成一串 keydown；特殊键只放常用的那几个
  （Enter/Tab/Backspace/Escape/PageUp/PageDown），不做全键盘映射。
- **网址只收 http/https**，而且**在补协议之前**先把危险协议逐个点名拒掉：
  `javascript:alert(1)` 不带 `//`，只看"有没有 ://"会把它放过来变成 `https://javascript:alert(1)` ——
  看着挡住了，其实只是把脏东西挪了个位置。`file://` 也拒：这条通道能把任何 URL 渲染成图给人看，
  开了就是本地文件读取口。没写协议时 `localhost:5173` 这类补 `http://`（补 https 会连不上本地 dev server）。
- **光是打开页签、看画面、看状态都不会起进程**（`previewPeek` 只认已经在跑的那台）；
  只有人按「打开 / 送入 / 点一下 / 切标签」才允许 `getOrCreate()`。
- 面板上有「关掉这台浏览器」，且关闭会**停掉自己的轮询**。

判据（Kotlin 新增 13 条 `PreviewTest`，整套 249 条）：白名单收/拒各一条（含 `file:`/`javascript:`/`data:`/`about:`/`chrome:`、
控制字符、超长、带 `@` 的 host）、坐标换算 270×200→1080×800 正中得 (540,400)、越界夹进视口、
尺寸没量出来时一律 (0,0)、状态 JSON 带开关没开那句"去哪开"、标签页列表过 JSON 往返不丢字段，
外加 5 条真 HTTP：开关关着时 `/api/preview/state` 回 `on:false` 且**不动浏览器**、
坏网址在白名单就被拒（早于"要不要起进程"）、好网址 + 开关关着 ⇒ 回人话、
取帧回 404（面板靠这个把"没拿到帧"说成人话）、不认的动作不算成功。
（像素，`SHOT_MODE=chat PRE_FLAGS=browser_control bash pc/tools/ui-shot.sh tools/steps/ui-iab.json`，20 步：
起的是**真 Edge**（独立配置目录），靶页挂在假网关的 `/hello` 上 ——
视口量出 921×920、帧的 `naturalWidth=921`（真解码，不是"元素在 DOM 里"）、
靶页标题从 `预览靶页 0` → 送入"你好呀"后 `预览靶页 0｜你好呀` → **点画面正中**之后 `预览靶页 1｜你好呀`
（按钮在页面正中，所以"点图的正中＝点屏幕的正中"这条换算被真实验证了，不是自证）→
图在窄栏里 267×267 完整放得下、页面无横向溢出 → 切走页签后 `pvTimer===null`（轮询真的停了）→
按「关掉这台浏览器」后 5.5 秒内第二次关闭回"没有正在跑的浏览器实例"。）

**这一批最该记的三件事：**

1. **"关掉"会被自己的轮询顶掉。** 第一版 `previewState/previewFrame` 也走 `getOrCreate()`，
   于是按下关闭之后，下一次取帧又把 Edge 拉起来 —— 面板上那句"已关掉"当场变假话。
   修法是**把"看一眼"和"做一件事"拆成两个入口**（`previewPeek` 只读、`previewSession` 才允许起），
   顺带把"光是打开页签就 spawn 进程"这个副作用一起去掉。这类"读路径不该有写副作用"的分裂，
   比在关闭处加锁干净得多。
2. **`shutdown()` 只 destroy 手里那个 PID 是不够的。** msedge.exe 的启动进程会把真正的浏览器
   甩成另一棵进程树，进程没死、调试端口还在应答。现在先 `Browser.close`（CDP 自己退，带走整棵树，
   它通常不回包所以走 dispatch 直发不等响应），等端口真没人应答最多 4 秒，不退才 `taskkill /T /F` 兜底，
   并且**把真实结果回给人看**（"已关掉"/"已强制结束"/"端口还在应答，去任务管理器看一眼"）。
   收尾的 `kill-stale-shot.ps1` 也补了一条：按 `haoai-browser-profile-` 认这台机器上遗留的预览浏览器。
3. **量具的 `must` 门当场抓住我自己的假绿。** 剧本第 20 步第一次跑出 `{"gone":false}` 却整轮 PASS ——
   因为 eval 步以前只打印不判。加了 `must` 之后这一轮直接失败，才逼出上面第 1、2 两条真缺陷。
   （另一条同类：关闭最多花 4 秒，而第二次 close 会撞进第一次还没清空的 `current`，
   所以判据要**轮询到"没有正在跑的实例"为止**，不能"等 1.5 秒再看一眼"。）

## 这一批：手机联动（局域网端点 + 配对码 + 会话镜像 + 远程审批）（v0.51.0，缺口地图 B4 的 PC 侧）

这台 PC 上的 agent 会跑长任务，而人常常在沙发上。"跑到一半要人点允许"如果只能回工位点，
自动化就等于每晚停一次 —— 这正是用户把 PC 端定位成"和手机端 HaoAI 联动"时要解决的那件事。
这一批把 PC 侧做完：手机能**只读**看到桌面上这几条会话，并能替人点"允许一次 / 本任务允许 / 拒绝"。

- **`Lan.kt`**：`LanStore`（`HAOAI_HOME/lan.json`）+ `LanServer`（另一个端口的 HTTP 面）。
  端点**默认不存在** —— 要 `haoai lan on` 或界面里点「打开端点」才会去监听，
  而不是"开着但每个请求都拒绝"。少一个能被打的端口，就少一类能被量的面。
- **凭据口径**：配对码 6 位数字、120 秒、一次一用、**猜错 8 次连正确的码也不放行**；
  换到的设备 token 是 32 字节随机值，**盘上只存 SHA-256 哈希**，验证走 `MessageDigest.isEqual`
  （常量时间）而不是 `String==`。凭据单独一个文件，不进 `settings.json` —— 那份是整份回给前端的。
- **镜像只给该给的**：会话 id / 标题 / 档位 / 是否在跑 / 消息数 / **工作区目录名**（不是绝对路径）；
  正文每条截 600 字、最多 60 条。密钥本来就不在会话里，这里再多挡一层：
  测试直接断言镜像 JSON 里既没有绝对路径也没有 `sk-`。
- **手机上现在只有审批，没有问答**：`/lan/pending` 只列 `kind=="approval"`。
  开放问题要打字回答，而手机端还没有输入框 —— 与其显示一个答不了的卡片，不如不出现。
- **CLI 会先通知活着的服务**：`haoai lan on|off|code` 先打一次 `127.0.0.1:8712` 的 loopback 口，
  通知不上才只改盘上开关并**明说**"下一次 serve 才生效"。CLI 与 serve 是两个进程，
  这里不吭声就会重演"改了设置界面还是旧的"。

判据（Kotlin 新增 12 条 `LanTest`，整套 261 条）：配对码是 6 位且一次性、错 8 次作废、
错码配对回 403 且不发 token、**盘上没有明文 token**（只 64 位哈希）、反手 token 与空 token 都不被认、
`/lan/sessions|pending|session|decide` 四个口未配对一律 401（`/lan/health` 是唯一免凭据的）、
配对之后镜像读得到、手机点的决定真的落到 `LanHost.decide`、
只接受 `allow_once/allow_session/deny` 三种、解除配对后旧 token 立刻失效、
真 `WebServer` 的镜像不含绝对路径与密钥。
（像素，`SHOT_MODE=chat PRE_LAN=1 bash pc/tools/ui-shot.sh tools/steps/ui-lan.json`，16 步：
`PRE_LAN` 用 CLI 现开一个端点（端口每轮现挑）并预配一台"测试手机"→
「工具」页签底部那段显示 `开着：192.168.1.37:8720 · 已配对 1 台` →
点「生成配对码」出 6 位码并开始倒数 → 设备行"名字 + 移除"一行、指纹 + 时间一行（窄栏 292px 放得下、
页面不横向溢出）→ 点移除后服务端与界面同时归零 → 关掉端点 `running:false` → 再开还是同一个端口 →
`/lan/health` 真的在监听。**判据全部走 `must`**，任何一条为假整轮非零退出。）

**这一批最该记的三件事：**

1. **"关掉"必须是"那个端口真的没了"。** 上一版类似的坑（预览面板）是读路径把进程又拉起来；
   这里对应的设计是：端点默认不创建，`off` 走 `LanServer.stop()` + 落盘 `enabled:false`，
   服务重启时只有 `enabled` 为真才重新监听 —— 手机配对的 token 还在人手里，
   重启不该把人踢下线，但"人主动关掉"必须真的关掉。
2. **凭据不进 `settings.json` 这条规矩，比多写一个文件值钱。** `GET /api/settings` 是整份对象回给前端的，
   任何塞进去的 secret 都会出现在网页源码里（也就会进备份）。测试里那条
   "设置文件里没有 hash"就是替未来的人挡这一步。
3. **窄栏里的"一行三样东西"一定会挤爆。** 第一版设备行是 名字 + 指纹 + 时间 + 按钮 四段，
   在 292px 里把「移除」拆成了两行。改成"名字与按钮一行（`.mrow` 的两列网格）、指纹与时间单独一行"，
   并把时间缩成 `toLocaleTimeString()`。**这类问题只有真像素看得见**，DOM 结构检查全绿。

## 这一批：回到某次之前（检查点 / rewind）（v0.52.0，缺口地图 B12 的第一件）

已有的快照只解决"这个文件改坏了，退回上一版"（工具卡上那颗）。但 vibe coding 里跑歪的一次任务
往往不止动一个文件，还会新建两三个 —— 那时想按的钮是**"回到我按发送之前"**。
这一批把它做成「产出」页签底部一段"回到某次之前（检查点）"。

- **`Checkpoints.kt`**：`HAOAI_HOME/checkpoints.jsonl`，一行一条
  `{run, sid, ts, goal, path, snap}`。一轮开始时写一条头行（带任务名），
  每次 write/edit 动一个文件就追一条。**不复制第二份快照** —— 复用 `Snapshots` 已经写下的那个文件，
  这里只记指针，所以这轮没动文件时磁盘上是零额外开销。
- **回滚语义**（`rewind`）：同一路径在这一轮里被改三次，取**最早**那份快照（= 这轮开始前的样子）；
  这轮**新建**的文件（没有快照）就删掉；越出工作区的路径一律不碰并逐条说清楚；
  快照文件不见了就报"没能处理 N 个"，**不静默跳过** —— 回滚这件事上"没说"比"报错"危险。
- **两步确认**：第一下只把按钮变成"再点一次确认"（12 秒内有效），第二下才动手。
  6 秒试过一轮，实测太紧：脚本里两步之间要读一次文件列表就超了，人犹豫一下也会超时。
- **顺手修掉一个真缺陷**：快照文件名以前是 `时间戳-相对路径`，**同一毫秒里改同一个文件两次**
  （一轮里连续两个 edit 是常态）会撞名，`copyTo(overwrite=true)` 把**最早**那份盖掉 ——
  于是"回到这轮之前"退到的是中间态。现在撞名就加序号，且用 `overwrite=false`。

判据（Kotlin 新增 9 条 `CheckpointTest`，整套 270 条）：跑真 write/edit 之后 rewind 把两个文件都退回原样、
一轮里改三次退到**最早**那份、这轮新建的文件被删掉、
工作区外的路径不动且报出来、快照被删时如实报 missing 而不是说成功、
没登记过的 run 回一句人话、`runId` 为空（CLI 单发）不留任何账、
列表只列真动过文件的轮次并带上任务名与文件数、账本有界（灌 4200 条后 ≤4000 且留下的是最近的）。
（像素，`SHOT_MODE=tools bash pc/tools/ui-shot.sh tools/steps/ui-rewind.json`，16 步：
让 mock 真的建 `hello.txt` 并改第一行 → 「产出」页签里文件在 → 检查点段出现"最近 1 轮改动可以整轮退回"
与一行带任务名 + "2 个文件"的按钮 → **只点一下**：按钮变"再点一次确认"、`dataset.armed=1`、
**文件还在**（证明一步都不会误删）→ 再点第二下：`/api/files` 里 `hello.txt` 5ms 内消失、
`window.__ck===2`（两次都真点到了同一个节点）→ 行在 292px 窄栏里放得下、页面无横向溢出。）

**这一批最该记的三件事：**

1. **"两步确认"的计时窗是给人用的，不是给脚本用的。** 第一版 6 秒，像素剧本里点完第一下、
   中间读一次文件列表，回来就发现按钮已经自己变回去了 —— 看着像"第二下没点到"。
   加了 `window.__ck` 计数与节点记号 `__seen` 才分清：**不是没点到，是超时复位了**。
   ⇒ 判据失败时先分清"没送达"与"状态被重置"，这两种的修法完全相反。
2. **异步渲染出来的按钮要先 `goto` 再点。** 「产出」页签的检查点行是 fetch 回来才有的，
   量具在渲染前就取到了坐标，点下去打在空气上。现在剧本里固定插一步
   `{"goto":"#ckRows [data-ck]"}` —— 结构检查全绿也可能点空。
3. **接口回了坏 JSON，界面不能只是"什么都不显示"。** 第一版 `listJson` 拼出一个
   `{"ok":true,"ws":"ws",""count":1}`（多一个引号），前端 `r.json()` 抛错被 `.catch{}` 咽掉，
   表现是"这段永远是空的"，看着像功能没生效。现在 catch 里会写"读不到检查点账本"，
   而这类手搓 JSON 的坑以后应该改走序列化器。

## 这一批：录屏（`record`）—— 把"刚才那段屏幕"变成能播的文件（v0.53.0，缺口地图 B14）

B7 的 `media` 只能处理**已经在盘上的**文件，而"视频制作自动化 / 直播辅助"缺的正是第一步：
把刚才那段屏幕变成文件。OBS 依然更好（场景、混音、推流），但那是后置项 ——
先让 agent 能自己走完 `录 → 停 → 立刻在界面里放 → 交给 media 剪`。

- **`Record.kt`**：`Recordings`（进程注册表）+ 第 22 把工具 `record`，三个动作 `start / stop / status`。
  命令是 `gdigrab -i desktop` + `libx264 -preset veryfast` + `-movflags +faststart`，
  argv 直接拼、不经 shell（Windows 上带引号的命令走 shell 必被二次解析吃掉）。
- **停止走 stdin 的 `q`，不是 kill。** 硬杀会让 mp4 少掉尾部 moov box ——
  文件存在、大小正常，但播放器与 `media info` 都读不出时长，表现是"录到了却打不开"，
  而且看着像 B7 那个播放器有 bug。所以 `stop` 送 `q` → 等它自己收尾（最多 6 秒）→
  实在不退才强杀，并且**在回执里明说"强杀收尾，文件可能不完整"**。
- **护栏**：`maxSeconds` 默认 300、上限 1800，并真的发给 ffmpeg（`-t`），另有一条 watchdog 线程到点自停；
  同时最多两场；`area` 只认 `x,y,宽x高` 这种纯数字格式，`audio` 设备名不许带引号与换行；
  产出落 `.haoai-output/media/rec-<ts>.mp4`（撞名加序号）。
- **只有一场在录时 `stop` 不用带 id** —— 录屏的常态就是"开一场、干点事、停掉它"，
  逼人回去抄 id 是把工具当 API 用。
- **服务退出会 `stopAll()`**：孤儿 ffmpeg 会继续往盘上写，而且一直在录你的屏幕。

判据（Kotlin 新增 9 条 `RecordTest`，整套 279 条）：argv 三条路（全屏 / 区域 / 带音频）各验一遍
—— 包括"不录声音要显式 `-an`，否则 ffmpeg 会去抓默认设备"这种只有真用过才知道的；
`area` 写错、`audio` 带换行、没装 ffmpeg 三种失败都要回人话；
**ask 档审批被拒不许把进程起起来**（auto 档直接放行是设计，测试用 ask 才验得到闸）；
停止那条用一个**假的 ffmpeg**（读 stdin 的 `.cmd`，只有真读到那一行才写文件并 exit 0）——
于是"文件在 + 退出码 0 + 能被认成音视频"三样一起成立，才证明走的是收尾而不是 kill。
（像素，`SHOT_MODE=rec bash pc/tools/ui-shot.sh tools/steps/ui-record.json`，7 步：
mock 真的调 `record start` → `record stop` → **录到的是这台机器的真桌面 3840×1080**、
浏览器真解码出 `videoWidth=3840`、约 1s / 410248 字节、卡片是开的、画面铺在正文里不溢出。）

**这一批最该记的三件事：**

1. **"存在但放不出来"比"报错"糟得多。** 所以停止路径的判据不能只看"文件在不在"，
   要看**退出码 0**（自己退的）与魔数可识别 —— 这两样一起才等于"用户双击能放"。
   也因此录屏测试不能用真 ffmpeg 当唯一判据：锁屏时 gdigrab 会失败，测试就变成随机红；
   真录那一段交给像素剧本（跑的时候屏幕是醒的，且失败会直接印在断言里）。
2. **审批闸只在 ask 档生效。** 第一版测试用 `mode="auto"` 造 ctx，于是"被拒不该起进程"
   这条永远测不到（auto 直接放行）。凡是验"权限被拦"的用例，档位必须是 ask/deny。
3. **折叠的工具卡不进 `innerText`。** 判据里搜"开始录了"搜不到，因为卡片收起时正文是 `display:none`；
   改成 `textContent` 才对。这类"量具读不到 ≠ 产品没有"的坑，是文本断言里最容易中招的一种。

## 这一批：手机网页端 —— 不装 APK 也能联动（v0.54.0，缺口地图 B4 的手机那一半）

B4 的 PC 侧（v0.51.0）把局域网端点、配对码、只读镜像、远程审批都做出来了，但**没有客户端**：
用户手上要么去装一个 Android 包（要编译、要装机、Flyme 还要过安装闸门，而且手机流量只剩 200MB），
要么什么也用不上。所以这一批先做**能当天用的那条路**：局域网端口直接发一份手机尺寸的 HTML。
Android 那一半仍然欠着（ROADMAP B4 还是 🟡），但"躺在床上把电脑上卡住的审批点掉"这件事今天就成了。

- **`ui/phone.html`**（单文件、零外部依赖、刻意不复用桌面那份样式）：三屏 ——
  配对（六位码 + 设备名）/ 会话与待批两个页签 / 单条会话的只读镜像。
  深色、按钮 ≥28px、正文截断在服务器侧做（每条 600 字、最近 60 条），
  工具原始输出与文件内容**不经过这个通道**。
- **配对页先于鉴权**：`/`、`/index.html`、`/phone` 三条路由不需要 token ——
  人不把页面打开，就不知道往哪填码；这份 HTML 里没有任何凭据，数据口全在下面那些要 token 的 `/lan/*`。
- **token 与设备名都落 localStorage**：刷新页面不用重新配对，顶部也还认得出这是哪台电脑。
- **桌面侧同步补了两处**：工具页签里那行提示改成"手机浏览器打开 http://<局域网 IP>:<端口> 输配对码
  <六位>（<剩余> 秒内有效，只能用一次）"—— 原来只说"生成配对码"，人拿到码却不知道在哪输。

判据（Kotlin 新增 3 条 `LanTest`，整套 282 条脱网测试）：`/` 与 `/phone` 都回这份 HTML、
带"配对码"字样、且**不带 token 也拿得到 200**（而 `/lan/sessions` 不带 token 必须 401）。
（像素，`SHOT_MODE=approve SHOT_PERM=ask PRE_LAN=1 SHOT_MOBILE=1 SHOT_W=390 SHOT_H=844
bash pc/tools/ui-shot.sh tools/steps/ui-phone.json`，24 步：
桌面打字造出一条真在等批准的写文件 → 跳到局域网端口 → 填码点「配对」→
存储里真有 43 字符的 token → 不带 token 的请求回 401 → **刷新一次仍在列表页、徽章还是 1、设备名还在** →
**4 秒轮询真的在转**（盖章时间往前走了 4007ms）→ 切「待批」→ 卡上写着 `approved.txt` →
点「允许一次」→ 手机上读到工具回执"批准了，approved.txt 已经写下去" → 徽章消失 →
390px 下没有横向滚动、行高 ≥28px。）

**这一批最该记的四件事：**

1. **我写进文件的"看起来像凭据"的字面量会被工具链打码。** 存储键原本是一个 `-token` 结尾的串，
   落到盘上变成了三个星号 —— 页面照常跑（它读写的是同一个被打码的键），
   而测试按"原本那个名字"去读，读出空串，表现是"配对之后刷新就要重新配对"。
   同一份文件里更早还有一次把 `localStorage.getItem` 打成省略号的记录。
   ⇒ 往文件里写这类字面量之后，**必须用字节级手段验一遍**（`base64` 片段、或者只打印计数与哈希，
   别打印那个串本身）；测试不许硬写产品的存储键名，改读页面自己的常量，
   并且把判据换成**可观测行为**（刷新后还在不在列表页），这样键名再怎么变都测得到真东西。
2. **`--window-size=390` 量不到手机宽度。** 桌面 Edge 有最小窗口宽度，实测 `innerWidth` 还是 420 以上，
   于是"按手机宽度排版"这条判据悄悄量的仍是桌面 —— 一个不报错的假阴性。
   正解是 CDP 的 `Emulation.setDeviceMetricsOverride`（脚本里加 `--mobile`，用 `SHOT_MOBILE=1` 打开）。
3. **量具补了两道闸，都是为"点了没反应"这一类现象**：`click` 现在拒绝点 0 尺寸的元素（以前鼠标按在空中，
   前面所有步骤还全绿），`goto` 支持 `"visible":true`（`hidden` 区块里的元素 `querySelector` 一样命中，
   于是"等它出现"永远成功）。这两道闸加完，`ui`（83 步）、`ui-askkeys`（30 步）、`ui-lan`、`ui-iab` 一起重跑过，没有误伤。
4. **断言步的命令超时必须大于这一步自己的等待预算**，否则"没等到"永远只留下一句 `CDP 命令超时`，
   而那一步本来准备好的失败原因（`ok:false, note:…`）打不出来 —— 一个会把诊断吃掉的量具。
   `shot.js` 现在按表达式里的 `Date.now()-t0>N` 自动推预算（+15 秒余量）。这条是照着上面那条
   假回归查出来的：真实原因只是**我漏了 README 里写明的 `PRE_FLAGS=browser_control`**
   （同一类错误第三次犯，见记忆 [[feedback-harness-can-lie]] 第 43 条）。
5. **两个只有真点才暴露得出的产品缺陷**：`refresh(true)` 走的是"立刻刷一次但不排下一次"，
   于是配对成功或刷新之后徽章数字冻住，电脑上冒出新审批要人手动切一次页签才看得见（现在只留一条
   4 秒循环，且未配对时停掉）；以及刷新后顶部不再显示设备名（`boot()` 只认了 token，没认设备名）。

## 这一批：手机上派活给电脑（v0.55.0，缺口地图 B4 的最后一块 PC 侧）

v0.54.0 的手机网页端只能"看 + 批"。但用户要的是**联动**：躺在床上想起一件事，
手机上说一句，电脑就去干；干到一半要人点头，还是手机上一个按钮。所以这一批加上行。

- **`POST /lan/send`**（要设备令牌）：`{sid,text}`。`sid` 空 = **另起一条新会话**，
  不去挤用户正在电脑前聊的那条；非空 = 接在那条会话后面（正在跑就走排队，和桌面输入框同一套规则）。
- **默认不通**：`LanStore.allowSend()` 是 `lan.json` 里一个单独的开关，**默认关**。
  只读镜像和远程审批是"看一眼、替人点一下"，而这一条是"让这台电脑替手机动手" ——
  配错的设备、被偷走的令牌、家里别人的手机都能发，所以必须人在电脑上显式勾。
  桌面侧「工具 → 手机联动」多了那个勾选框，说清它改变的是什么；
  CLI 侧 `haoai lan allow-send on|off`。
- **权限档位照旧生效**：手机上发的句子照样会冒出审批卡（截图里那条"正在跑"的新会话
  顶上就有 1 个待批），所以"批"和"派"是两个分开的权限，勾了派活也不等于全自动。
- **`sid` 先验格式再验存在**：它会变成 `sessions/pc-<id>.json` 的文件名，
  网络来的字符串不能直接当路径用（`../` 那种必须挡掉）。
- **一次性回执单独一行**：`#note`。原来"已交给电脑"写进盖章那一行，
  而那一行每 4 秒被 `最近刷新 …` 覆盖 —— 人根本来不及看，测试也读不到。

判据（Kotlin 新增 6 条 `LanTest`，整套 287 条脱网测试）：没勾开关就该挡住且**不走到执行侧**；
勾了但没令牌一律 401；空正文与 2000 字以上回人话（说清上限）；
正文里的引号与换行原样到达、`\\n` 不许被折成换行；开关跟着 `lan.json` 活过端点开关。
（像素，`SHOT_MODE=approve SHOT_PERM=ask PRE_LAN=1 SHOT_MOBILE=1 SHOT_W=390 SHOT_H=844
bash pc/tools/ui-shot.sh tools/steps/ui-phone.json`，43 步一条链路走完：
配对 → 刷新还在 → 手机上点掉审批 → 文件真写出来 →
**先试派活被电脑拒绝**（回执里有"允许从手机派活"）→ 跳回桌面页勾上开关（1500×930）→
再跳回手机（390×844）发同一句 → 列表里真的多出第二条会话、标题就是那句话、点着绿点在跑 →
回执"已交给电脑：会话 pc…" → 两行都不横向溢出、行高够点。）

**这一批最该记的三件事：**

1. **网络口上的 JSON 解析不许用"分组上再套量词"的正则。**
   `((?:[^"\\]|\\.)*)` 在 JDK 里是**递归**的：实测正文到 2000 字直接 `StackOverflowError`，
   而那是个 `Error`，路由里的 `catch (e: Exception)` 接不住，表现是**连接被掐断、一个字节都不回**
   —— 客户端只看到 `HTTP/1.1 header parser received no bytes`，看起来像对端坏了。
   ⇒ 换成单遍手扫（`textField` + `unescapeJsonString`），并用一个独立的小程序把长度
   从 20 扫到 20000 复现过，才确认根因是解析器而不是"请求太大"。
2. **量具会把诊断吃掉**：断言步的 CDP 命令超时（20 秒）小于那一步自己声明的等待预算（60 秒）时，
   永远只能看到 `CDP 命令超时`，而那步准备好的 `ok:false, note:…` 一个字都打不出来。
   上一批那条假"回归"就是这么被误判的（真原因只是我漏了 `PRE_FLAGS=browser_control`）。
   ⇒ `shot.js` 现在按表达式里的 `Date.now()-t0>N` 自动推命令超时。
3. **手机剧本中途要换视口**。设备度量覆盖是跟着标签走的：带着 390px 回到桌面页，
   窄栏布局把右侧页签整个藏起来，于是"点不到「工具」页签"看着像产品坏了。
   ⇒ 剧本新增 `{"viewport":"desktop"|"phone"}`；`click` 对 0 尺寸元素直接报错，
   这类"按在空中"的假动作以后当场就暴露，不会拖到最后一张截图。

## 这一批：定时任务改说人话（v0.56.0，缺口地图 B13 的第一件）

原来的定时任务只有两种："每隔 N 分钟"和"每天 HH:MM"。而真正要排的是
"每周一三五 8 点把素材列个清单""直播结束后半小时把今天的录屏剪成 30 秒""每天 7:30 和 21:30 各跑一次" ——
都得去界面上拼字段，拼不出来就只能等下一次手动点。这一批把排期改成**一句话**。

- **`SchedulePlan.kt`**：规则解析，不叫模型。输入一句中文，输出 `kind/every/at/days/runAt` +
  一句复述（"每周一、周三、周五 08:00"）。为什么不用模型：这条链路的输出决定
  **"什么时候真的会起一条任务、花谁的钱"**，模型把"下午"理解反就是差 12 小时，而且没人会去核对；
  规则解析错，测试当场就抓到，界面上还会把复述摆出来让人确认。
  认得：`每 30 分钟`/`每隔2小时`/`每半小时`、`每天 7:30 和 21:30`、`每天早上九点`、
  `每天晚上十点半`、`每周一三五 8 点`、`每个周末 10 点`、`半小时后`、`明天早上9点`、`今晚十点半`、
  中文数字到 99、`晚上12点` 当零点。
- **看不懂就拒绝，绝不退回默认时间。** `每天`（没点）、`每周五`（没点）都回一句"没说几点，写成…这样"，
  并且**不落盘**。猜错的时间会在人睡着的时候起真任务、花真 token，比不跑糟得多。
- **调度改成"槽位"模型**（`Schedule.nextSlot`）：daily 支持一天多个时刻，新增 weekly 与 once。
  判据统一成"上一次跑过之后的第一个槽位 ≤ 现在"，所以睡过头只补那一次。
  顺带改掉一处旧语义：**下午两点新建"每天 09:00"不再当场跑一遍**（旧版会），
  要立刻看效果有点「跑一次」—— 保存一个排期就等于烧一次 token 不是人想要的。
- **一次性任务过点 6 小时就不补**：那条"半小时后剪片子"如果人已经忘了，半夜跑它只会莫名其妙。
- **界面上边打边预览**：`GET /api/sched/parse?when=…` 回"理解为：… 下次 09-30 08:00 周三"，
  绿字；看不懂就红字写缺什么。填了一句话就把原来的手填字段行藏掉（两套并存会让人以为是同时生效的）。
  列表里每一行显示的是复述句，不再是 `kind=interval` 这种内部字段。

判据（Kotlin 新增 15 条：`SchedulePlanTest` 14 + `ScheduleTest` 1，整套 302 条脱网测试）：
全部用**固定的"周一 06:00"**做 now，期望值也用同一时区的 Calendar 算 ——
否则"明天九点"这种断言会在跨午夜时自己红。
覆盖：间隔三种写法、中文数字与 `点/半/刻`、`晚上12点` 归零点、一天多时刻、
`每周一三五` 这种只有第一个带"周"的写法、相对时间落在哪一分钟、
已过点的今天该拒绝、缺几点该拒绝、复述句原文、以及槽位模型的四条边界
（新建 vs 睡醒补跑、跑过之后等明天、weekly 只在该跑的星期、写法不对就永远不跑）。
（像素，`SHOT_MODE=tools bash pc/tools/ui-shot.sh tools/steps/ui-when.json`，20 步：
打"每周一三五 8 点" → 预览出现"理解为：每周一、周三、周五 08:00 下次 …"且手填行被藏 →
加进列表 → 改打"每天" → 预览变红说"没说几点" → 改打"半小时后" → 预览给一次性 →
加第二条 → 点「跑一次」→ 侧栏真的多出一条标题为"睡前收工"的会话 → 盘上 `lastRun` 落了时间。）

**这一批最该记的三件事：**

1. **`requestURI.rawQuery` 里没有问号。** 照抄常见的 `[?&]name=` 正则去取查询参数，
   永远匹配不到，而"取不到"在这里的表现是**回一句"先写一句什么时候"** ——
   看着像解析器不认"半小时后"，实际是参数压根没读到。⇒ 要么 `(?:^|&)name=`，
   要么直接用现成的 query 助手；写之前先确认这个字段到底含不含 `?`。
2. **测试自己也会写错，而且错得很像产品缺陷。** 这一批我连踩三条：
   断言"列表里该有 30 分钟后"（存下来的复述其实是"一次性 09-28 12:40"，两个说法都对，
   但只有一个是产品选的）；点第二行的「跑一次」却去查第一条任务的名字；
   第二次点「加为定时任务」时忘了输入框已被清空（产品拒绝空正文是对的）。
   ⇒ 判据失败先问"我点的是哪一行、查的是哪一个字段"，再怀疑代码。
3. **一句话排期的"下一次"必须显示出来。** 只回"每周一、周三、周五 08:00"是不够的 ——
   人真正要确认的是"那它到底哪天会跑"。所以预览与列表都带 `下次 MM-DD HH:MM 周X`，
   写错写法时那一项会显示"不会跑（检查写法）"，而不是安静地不跑。

## 这一批：定时任务跑完之后，结果去哪（v0.57.0，B13 第二件）

v0.56.0 之后能一句话把任务排上了，但**跑完的那句话只留在那条新会话里**。
而定时任务的常态恰恰是"人不在电脑前"：早上 9 点的巡检、直播结束后半小时的剪辑，
跑完了没人知道它说了什么，得回工位翻侧栏。这一批把"结果"接上。

- **`Digest.kt`**：不另存一份数据，直接从运行账本（`runs.jsonl`）里挑 `trigger=定时` 的那些，
  倒序渲染成汇总。再抄一份就会出现"两份真相不一致"，而这一层本来只做取数与排版。
  手动 / 排队 / 续跑那些不算 —— 那些人在电脑前，看得见。
- **三个出口**：桌面「定时」页签里一块"跑完的结果"（时间 · 任务名 · 结果一句话 · 正文前 60 字）；
  「导出成 md」写进工作区 `.haoai-output/schedule-log.md`（文件名是常量，不接受调用方传名字，
  否则这就是一个任意路径写文件）；手机网页端多一个**「结果」页签**，跟着 4 秒轮询更新。
- **账本里 `out` 那一列从 200 字放宽到 600 字**：200 字常常把"结果"断在半句上，
  而它现在是运行历史与定时汇总里唯一能看到正文的地方。
- **`/lan/digest` 要设备令牌**：汇总里是任务名与结果正文，属于"电脑上发生了什么"，
  不给没配对的人。

判据（Kotlin 新增 6 条：`DigestTest` 5 + `LanTest` 1，整套 308 条脱网测试）：
只有定时的进汇总、顺序最近的在前；被停掉的那条要说"被停止"而不是伪装成一个结果；
正文里带引号与换行时 JSON 不许被折断；导出真的落在工作区里且**不在工作区外面留一份**；
空汇总也要回一句人能读的话；`/lan/digest` 没令牌 401。
顺带把 `LanTest` 里那份手抄的路由清单改成**从 `Lan.kt` 源码里扫** ——
每加一个接口都要记得改手抄清单，而"忘了改"的表现是这条测试红掉；红两次之后人就会把测试删了。
（像素：`ui-when.json` 25 步 —— 点「跑一次」→ 等汇总里出现那条 → 界面上那块列表真的跟着变 →
导出 → 读回 `.haoai-output/schedule-log.md` 确有内容；
`ui-phone.json` 46 步 —— 手机上切到「结果」页签，看到空态文案，且原有的配对/审批/派活链路不回归。）

**这一批最该记的两件事：**

1. **"只在打开页签时取一次数"是个假实时。** 第一次像素跑就红在这里：汇总接口明明已经有那条了
   （`dig:true`），界面上那块却还停在打开页签那一刻的空态 —— 因为定时任务是在后台跑完的，
   没人会为了看结果手动刷新。⇒ 现在页签开着每 4 秒拉一次、切走就不拉。
   这条和 v0.55 那个"配对后徽章冻住"是同一类：**后台会变的数，就必须有人替它刷新**。
2. **测试里手抄一份"产品清单"是会腐烂的。** 这份清单（`/lan/*` 路由）今晚改了三次，
   每次都是靠测试红才想起来补。改成从源码扫之后，加接口不用再动测试，
   而"源码里一个路由都没抓到"会被单独断言挡住 —— 免得清单读空了，测试变成永远绿的摆设。

## 这一批：任务链 —— 几句按顺序跑在同一会话里（v0.58.0，B13 第三件）

视频那类活天生是多步的："把今天的录屏剪成 30 秒"→"从片子里抽一张封面"→"照这条片子写一句标题"。
一步一步手动发，人就得守着等每一轮结束；而定时任务只能排一句话。这一批把"多步"接上。

- **`Workflows.kt`**：一条链 = 名字 + 几句步骤（**一行一步**，最多 8 步），存 `HAOAI_HOME/workflows.json`。
- **不引入新的执行机制**：跑链 = 第一步正常起一轮，剩下的句子**塞进那条会话已有的排队队列**，
  由每轮结束时的接力自动往下发（就是 v0.36.0 那套"运行中排队"）。
  于是每一步都看得见上一步的结果（同一段历史），按停止会像平时一样清掉队列，
  而"链跑到一半会话被删了""链和手动发的句子撞在一起"这类边角不用重新想一遍。
- **定时任务可以选择"跑一条任务链"**：`Schedule.flow` 非空时，`prompt` 那一句就不看了。
  跑之前**先记 `lastRun` 再起链** —— 不然调度每 5 秒会再触发一次，链被反复起头。
- **「跑完的结果」跟着改了口径**：原来只收 `trigger=定时` 的运行，但一条链只有第一步记成 `任务链`、
  后面几步都是 `排队` —— 照老筛法，三步链的汇总里只剩第一步，而人最想要的"最后说的是什么"看不到。
  现在改成：先认出哪些会话是定时/任务链起的，再**每个会话只留最近一轮**。

判据（Kotlin 新增 7 条：`WorkflowTest` 6 + `DigestTest` 1，整套 315 条脱网测试）：
一行一步的解析（空行丢掉、超上限截断）、带引号的步骤存进盘再读出来不变形、
**跑一条三步链 → 网关收到至少三次请求，且后两次仍带着第一步那句话**（这一条同时证明了
"同一条会话"与"按顺序"，比看界面诚实）、空链与不存在的链各回一句人话、
定时任务配了链就跑链且不把 `prompt` 发出去、跑完要记账。
（像素，`SHOT_MODE=tools bash pc/tools/ui-shot.sh tools/steps/ui-flow.json`，13 步：
填名字 + 三行步骤 → 加为任务链 → 列表出现"链流三步 · 3 步" → 定时任务的下拉里也能选它 →
点「跑一次」→ 侧栏出现那条会话、点开里面**三句步骤全在** → 「跑完的结果」里它只占一行且是最后一步。）

**这一批最该记的三件事：**

1. **手搓 JSON 少了一个闭括号，而读写两头都 `runCatching` —— 于是"存进去"永远成功、"读出来"永远是空。**
   `Workflows.save` 少了步骤数组后的那个 `}`，写出来的文件是坏的；`load()` 的 `runCatching` 把它
   变成"没有任务链"，界面上就是"我加了但列表一直是（还没配）"。
   ⇒ 这类"落盘 + 读回"的存储层，**判据必须走一遍真实 round-trip**（这条测试是第一个红的），
   并且 `runCatching` 吞掉的写失败要留痕，不然坏数据没人知道。
2. **汇总的筛选条件要跟着新写法回头改。** `trigger=定时` 在只有"排一句话"时是对的，
   加了链之后它悄悄变成"只显示链的第一步" —— 一个不报错、看着还挺正常的错。
   ⇒ 每次给"谁触发的"加新取值，都要回去看所有按它筛选的地方。
3. **`every` 的单位是分钟**，而界面上写着"每隔几分钟"、默认值 60。
   我按秒写测试，结果"到点了"永远不到（`nextDue` 比 `now` 远了 59 分钟）。
   ⇒ 时间单位这种约定要么在名字里（`everyMinutes`），要么在测试的注释里写死，别靠上下文记。

## 这一批：审批分三档 —— 让"不可逆的那一下"长得不一样（v0.59.0，缺口地图 B11 的第一件）

两个洞是同一个根：
1. **ask 档每张审批卡长得一模一样。** 改一行 README 和 `git push --force` 是同一个红点、同一排按钮，
   人点到第三张就开始无脑按"允许一次" —— 弹框此时已经不提供保护，只剩打扰。
2. **auto 档是全放行。** 而定时任务（v0.56）和任务链（v0.58）**都以 auto 档跑**，
   链里完全可能出现 `git clean -fdx` 或"删工作区外的文件"。等于把不可逆的那一下交给了模型自己决定。

- **`Risk.kt`**：`RiskOf.of(tool, subject, detail, outsideWorkspace, exists)` → `Verdict(level, why)`，
  三档 LOW/MID/HIGH。判据**全是看得见的字符串与结构**（工具名、路径、命令的整词），不调模型 ——
  分级错了顶多多问一次，但必须能解释、能被测试钉住。`why` 是一句人能照着行动的话
  （"强推（git push --force）会覆盖远端历史，不可逆"），不是一个枚举名。
- **高危按"整词集合"判，不按字面量子串判**（`HIGH_SHAPES` + `hasTokens`）：
  `icacls C:\out /grant Everyone:F` 中间夹了路径，`curl https://x/install.sh | bash` 中间夹了 URL，
  按字面量都会漏（见下面第 1 件事）。参数顺序不定的用整词集合，管道进解释器的用正则。
- **只读命令判低危**（`readOnly()`）：按 `| ; && ||` 切段，每段首个程序都在只读名单里、
  且整条没有重定向/`-delete`/`-exec`，才算"看一眼而已"。不这么做的话 `git status` 也挂着
  "会改状态"的徽标 —— **分级一旦开始说谎，人就再也不看它了**。
- **`Gate` 传的是 `RiskOf.Verdict`，不是拼好的中文。** 网页壳的 payload 里是两个字段：
  `risk`（`low`/`mid`/`high`，给 CSS 挑颜色）+ `riskLabel`（给人看的那句）。默认实现退化回 5 参那条，
  所以 CLI 与所有测试假闸口不用一起改。
- **auto 档：低/中危照旧自动过，高危仍然弹卡**（`Tools.kt` 的 `guardCore`）。
  卡可以在手机上点（v0.55 那条路），所以"拦一下"不等于把人堵在工位外。
  计划档与规则表（S2）的判定顺序没动 —— 分级只加在"要不要问人"这一步。
- **界面**：审批卡右上角一枚徽标（低危灰、中危琥珀描边、高危红底白字），高危那张整张卡描红，
  卡底多一行"为什么"。三档还是**三种边框权重**（`[data-risk=low]` 走中性线色），
  因为默认琥珀色属于"要问人"这件事，不该和中危混为一谈。手机页同一套码。

判据（Kotlin 新增 11 条 `RiskTest`，整套 326 条脱网测试）：
分类表三档各一批（含"参数换个顺序也该判高危"那一组，就是上一版漏的那两类）、
只读判据的反例（重定向 / `-delete` / `; git push`）、`why` 要含"不可逆"、
以及**闸口行为**：auto 档下 `git push --force` 必须弹卡且**拒绝之后命令一次都没跑**
（断言输出里没有 git 的回话），auto 档下低危写文件不弹卡且真的落盘，ask 档下改已存在文件带中危。
（像素，`PRE_TOUCH="risk.txt" SHOT_MODE=risk bash pc/tools/ui-shot.sh tools/steps/ui-risk.json`，33 步：
同一会话连做三件风险不同的事 → 三张卡各自标出高/中/低、徽标三种颜色、红框只有一张、
三档三种边框色 → 全部放行后跑完；再开一条 auto 档的会话 → **只弹高危那一张** →
点拒绝 → 两条低危写仍然执行、`risk-new.md` 真的在盘上。）

**这一批最该记的四件事：**

1. **字面量子串当判据，会漏掉真实写法，而且测试全绿。**
   第一版把高危写成 `"icacls /grant"`、`"curl | sh"` 这种样板文本，测试里也照抄同样的字面量 ——
   于是 8 条全绿，而真人敲的 `icacls C:\out /grant Everyone:F`、`curl https://x | bash` 一个都不命中。
   ⇒ 判据要认**结构**（整词集合、管道形状），测试用例要故意把参数顺序打乱写。
2. **界面按中文文案匹配 class ＝ 改文案就静默失效。**
   第一版 `rkCls(r)` 里写的是 `r=='高危'`。文案一旦改成"高风险"，红框消失且没有任何报错。
   ⇒ 协议里分两个字段（稳定码 + 文案），验收剧本再按 `data-risk` 这种语义属性断言，
   别按 `innerText` 断 —— 和审批按钮的 `data-dec` 是同一条规矩。
3. **测试桩自己的默认值会造出假绿。**
   `Spy` 的 `allow` 默认 `true`，而有一条断言写的是"拒绝之后不该真的跑起来" ——
   它验的其实是"允许之后跑起来了"。这条判据恰好是整批里最要紧的那条（高危命令绝不能在测试里真跑）。
   ⇒ 写完断言要回头问一句：**这条断言在桩的默认配置下有没有可能为假？** 永远为真的判据等于没判。
4. **"界面上没出现"不能只数元素个数。**
   任务跑完之后审批卡本来就不在 DOM 里（历史从 `/api/state` 重画），所以"auto 档没为中危弹卡"
   光看 `card.ask[data-risk=mid]` 计数为 0 是**假绿**。⇒ 配上正证：那两次写真的执行了、文件真的在盘上。

## 这一批：角色卡 —— 一段人设 + 模型 + 工作区 + 档位，一次带上（v0.60.0，B13 的预设 agent）

这台机器上的活天生是几种不同的角色：直播时口述要快（自动档、别啰嗦）、剪视频要的是媒体工具和那个装素材的目录、
写安卓代码要的是 HaoAI 仓库加"问一句再动手"。以前每次开会话都要**重挑四遍**，
而四件事里总会漏一件 —— 漏的那件通常要等跑错了才看得见（在错的目录里写文件最贵）。

- **`Presets.kt`**：`Preset(id, name, persona, model, workspace, mode)` 存 `HAOAI_HOME/presets.json`，
  读写与 `Workflows` 同一套路子（含那个 round-trip 判据）。**上限 12 张**：真正常用的就那几个，
  列表长了就没人看。**刻意不存"关掉的工具"** —— 会话级工具开关（v0.40）已经在那条会话上了，
  角色卡再叠一层白名单，出问题时得同时看两处才知道是谁关的。
- **人设走 `Session.persona` → `PromptCtx.persona` → 系统提示的「本次角色」一节**，
  每回合现拼（和 `extra` 一样不缓存）。不复用 `extra`：那一份是**工作区**的说明文件，
  换个会话还在，而角色是跟着这条会话走的。这一段**不改变安全边界** —— 角色写"全都自动通过"也不算数。
- **`persona` 与 `role`（角色名）一起落进会话文件**：不落盘的话，重启之后人设没了、
  而顶栏还写着角色名 —— 显示与事实分家。
- **`POST /api/new {preset:id}`**：模型 / 工作区 / 档位 / 人设一次带上。
  卡上的目录**打不开就明确拒绝，绝不退回全局**（和 v0.5x 那条"指定工作区失败不许兜底"同一条规矩）。
  非法档位（手改过的 json）回落全局默认，不当成 ask 也不当成 auto。
- **界面**：右栏第 10 个页签「角色」= 列表 + 一张表单（名字 / 人设 / 模型下拉 / 目录 / 档位），
  每行三颗钮「用它开一条 / 改 / 删」；顶栏多一颗 `角色 · 直播助手` 徽标，**没有角色时整颗藏掉**。

判据（Kotlin 新增 7 条 `PresetTest`，整套 333 条脱网测试）：
带引号/反斜杠/换行/行尾空格的人设**存进盘再读出来一字不差**（`Workflows` 那次的教训直接抄成测试）、
坏 json 读成空且还能被一次正常保存覆盖、上限拦得住、非法档位回落、
`/api/new {preset}` 之后 `/api/state` 报出的模型/工作区/档位/角色名就是卡上那四个、
**人设真的出现在网关收到的那次请求体里**（界面写着角色而模型读不到，是最难被发现的一类错）、
目录消失时拒绝且不悄悄建在别处、不存在的角色 id 回一句人话。
（像素，`SHOT_MODE=tools bash pc/tools/ui-shot.sh tools/steps/ui-role.json`，28 步：
开页签 → 存一张（档位=计划）→ 列表里名字与档位**没被省略号切掉**（`scrollWidth<=clientWidth`）、
长人设收在 `title` 里 → 点「用它开一条」→ 顶栏出现 `角色 · 直播助手`、档位按钮真的亮在「计划」→
发一句话，写文件被计划模式挡下且**不弹审批卡** → 「改」把表单回填 → 「删」回到没配。）

**这一批最该记的三件事：**

1. **加第 10 个页签把整条页签栏压坏了。** `.tabs{display:flex}` + `button{flex:1}` 在 300px 的右栏里
   给每个按钮只剩 26px，于是**每个**标签的两个汉字竖着排成两行，最后一个被挤出右边缘 ——
   结构断言（元素在、点得动）全绿，只有截图看得见。⇒ 页签栏改成 `grid-template-columns:repeat(5,1fr)`，
   并在剧本里加了**几何判据**（没有任何按钮高于 30px、没有任何按钮越过右栏边界）。
2. **列表行宁可长高也别用省略号。** `.rl span` 是 `white-space:nowrap + ellipsis`，
   第一版把"档位/模型/目录"放在第三个 `em`，被切掉的恰好是判据要看的那部分。
   ⇒ 行的断言不能用 `innerText`（被视觉裁掉的文本它照样读得到，是个假绿），
   要量 `scrollWidth` 与 `clientWidth`。
3. **`Edit` 工具替换函数头时会把那一行吃掉。** 我把整段角色卡 JS 插在
   `function paintCron(list){` 这一行之前，old_string 就是这一行 → 替换后函数没了开头，
   整个脚本变成语法错误。⇒ 用某个声明行做锚点时，new_string 末尾必须把它原样带回来；
   好在 `ui-check.js` 的"整段前端 JS 能解析"这一步就是为这种错准备的。

## 这一批：凭据条目 —— key 看得见、改得动、撤得掉，而明文不出盘（v0.61.0，B11 第二件）

密钥一直刻意**不进 `PcSettings`**（那份会被 `GET /api/settings` 整体发回前端），只落在
`HAOAI_HOME/apikey` 与 `searchkey` 两个裸文件里。约束是对的，代价是界面上**只能写、不能看、不能删**：
换 key 时不知道旧的还在不在，撤销一把泄露的 key 只能回 CLI 手删文件。
顺带还有一处小漏：`/api/settings` 以前会回传密钥的**前 6 位**（`keyHint`），这次一并收掉。

- **`Secrets.kt`**：`Slot(id, label, hint, file)` 两张条目（模型网关 / 搜索服务），
  加新 key 就在 `slots()` 里加一行。`read/set/mask/json` 都在这层，
  **撤掉 = 删文件**（留个空文件会让 `hasKey` 说"已设置"，那是假话）。
- **`/api/secrets`**：`GET` 列条目（只给掩码 + 长度 + 改于何时 + 文件路径），
  `POST {id,value}` 改，`value` 留空即撤。**`/api/settings` 从此不再接受 `key`/`searchKey` 字段** ——
  一把 key 只留一个写入入口，否则"改了没生效"要查两条路才知道哪条没走。
- **掩码只给前 2 后 2 + 总长**；不足 10 位整个遮成 `••••••`
  （一把 6 位的 key 露前 2 后 2，等于把中间 2 位的猜测空间砍到最小 —— 那不是提示，是漏）。
- **界面**：设置抽屉里两处 key 输入框下面各加一行状态（`已设置 sk…56（共 21 位） · 改于 …` /
  `未设置 —— 放在 <路径>`）加一颗「撤掉这把」。**没设过时那颗是灰的**（点不动的钮不该长得像能点），
  第一次点只把文案换成"再点一次：真的撤掉"、不动盘（撤掉一把 key 会让所有请求当场失败），
  5 秒内再点才算数。输入框**不回显旧值** —— 回显会被当成新值写回去。

判据（Kotlin 新增 6 条 `SecretsTest`）：
最要紧的三条是**搜不到**：设过 key 之后 `GET /api/secrets` 与 `GET /api/settings` 的原始响应文本里
都不许出现那串明文，连掩码用的中段也不许整段出现在 settings 里；
其余是"读回来一字不差（首尾空白清掉）""短 key 整个遮掉""空值=删文件且 `hasKey` 跟着变假"
""搜索工具读的就是我们写的这个文件"（只有一份真源）""不存在的条目回一句人话"。
（像素，`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-secrets.json`，19 步：
开抽屉 → 未设置且撤掉是灰的 → 填一把假 key 保存 → 重开抽屉看状态行（已设置 + 掩码 + 长度 + 时间）、
**整页 `body.innerText` 搜不到明文**、输入框是空的 → 点撤掉（只改文案、盘上还在）→
再点 → 回到未设置、按钮重新变灰。）

**这一批最该记的两件事：**

1. **断言"看不到明文"要写在响应文本上，不是写在界面上。**
   界面本来就不显示明文（`type=password`），拿它当判据等于没判。
   ⇒ 判据打在 `GET` 的**原始字符串**上（`resp.contains(SECRET)==false`），
   这样以后谁往 `settingsJson` 里加一个 `keyHint` 之类的字段，测试会当场红。
2. **"能写不能删"是一半功能。** 旧抽屉能改 key 却不能撤，`hasKey` 又只在"文件存在"上为真 ——
   空文件也算存在。⇒ 凡是"条目"类的东西（key、规则、角色卡、备份），
   读/写/撤三件事要一次给齐，只给两件的界面迟早会让人以为已经撤掉了。

## 这一批：钩子 —— 一轮跑完替你做一件不用开口的事（v0.62.0，B11 第三件）

"跑完之后顺手记一笔 / 推个通知 / 把产物挪进目录"是每次都做的事，
但不该占着会话里的一句话；定时任务与任务链（v0.56/58）能排"到点跑什么"，
排不了"跑完之后呢"。OpenClaw 的 session-memory / compaction-notifier、ZCODE 的 hooks 都是同一个形状。

- **`Hooks.kt`**：`Hook(id, name, event, command, shell, timeoutSec, enabled, last, lastAt)`
  存 `HAOAI_HOME/hooks.json`，上限 8 条。第一个也是目前唯一的事件是 **`run-end`**（一轮跑完）。
- **三条硬规矩**（都写进测试了）：
  1. **失败只记账，绝不打断会话** —— 整段 `runCatching`，结果只写进条目的 `last`。
     钩子坏了最坏该是"这次没记上"，不能是"我的 agent 跑挂了"。
  2. **异步跑** —— 丢进单线程 daemon 池。一条 40 秒的钩子不该让人盯着"还在跑"的转圈，
     顺带把多条钩子对同一份 `hooks.json` 的写入串行化。
  3. **命令只有用户能写** —— `/api/hooks` 只给界面用，引擎的工具表里没有这把工具，
     所以模型给自己加不了钩子。正因为如此它**不过审批闸口**：
     触发时人常常不在电脑前，弹一张没人看的卡会把会话钉死 300 秒。
- **上下文怎么给**：环境变量 `HAOAI_EVENT / SID / RUN / TITLE / MODEL / MODE / TRIGGER / STOPPED / WS / OUTFILE`，
  工作目录就是**这条会话自己的工作区**（所以 `Set-Content -Path hook.log` 落在对的仓库里）。
  **结论全文走文件不走 argv**（`$env:HAOAI_OUTFILE`）—— 几 KB 的回答塞进命令行一定被截或被改形。
- **执行机制与 `shell` 工具同一套**：命令进临时脚本文件、PowerShell 那份带 UTF-8 BOM、
  `redirectErrorStream` + 读线程 + `waitFor(timeout)` 到点 `destroyForcibly`。
  不复用 `ShellTool.run` 就是因为它前面挂着闸口（见第 3 条）。
- **界面**：「角色」页签改名**「自动化」**（`data-t="role"` 不动 —— 剧本与快捷键都指着它），
  角色卡下面加一组钩子：列表（事件、shell、超时、上次成/败与摘要）+ 名字/命令/事件/shell/超时表单 + 启停/删。

判据（Kotlin 新增 6 条 `HookTest`，真起子进程，没有 pwsh 的机器上自动跳过；整套 345 条脱网测试）：
命令文本 round-trip 不变形、**跑完一句话之后钩子真的执行了**（脚本按 `$env:HAOAI_SID`
写出文件，内容与那条会话的 id 一致）、**结论全文从 `$env:HAOAI_OUTFILE` 读得到**、
`exit 3` 的钩子只把 `exit=3` 记在条目上而会话照常收尾（`running:false` 且那句结论还在）、
停用的钩子不跑（文件不出现、条目上也没有运行记录）、空命令与不认识的事件各回一句人话。
（像素，`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-hook.json`，20 步：
「自动化」页签里角色卡与钩子共存 → 加一条 → 列表显示"一轮跑完 · pwsh · 40s · 还没跑过" →
发一句话 → 轮询 `/api/hooks` 直到 `last` 出现（实测 1.2 秒后 `exit=0`）→ 停用（按钮翻成「启用」、行变灰）→ 删。）

**这一批最该记的三件事：**

1. **收尾那一行不能放子任务。** `Engine.submit` 的记账处（`RunLedger.add` 那一段）
   子任务也会走到 —— 不加 `if (depth == 0)`，一次调研就能把通知刷两遍。
   顺带发现同一行**早就**在给子任务记运行账（`RunLedger` 里 `trigger=子任务` 的那些），
   这是既有行为、不在这次范围内改。
2. **`ui-check.js` 又抓住一个只有它能抓的错。** 我在模板字符串里手滑写成 `h.event'`，
   整段前端 JS 从那一刻起不解析 —— 而 Kotlin 全量测试与所有像素剧本**都不会**碰到这一行
   （剧本要点的那颗钮在坏语句之后，页面早就没脚本了）。
   ⇒ 改完 `index.html` 先跑 `node pc/tools/ui-check.js`，它比剧本便宜两个数量级。
3. **"异步"意味着判据要会等。** 钩子的结果不在会话收尾的那一刻，而在 1~2 秒之后 ——
   Kotlin 侧轮询文件、剧本侧轮询 `/api/hooks`，两边都不许用固定 `sleep` 猜。

## 这一批：技能导入（SKILL.md 的 zip / URL / 粘贴）+ 顺手挖出落库的半截文件（v0.63.0，B11 最后一件）

PC 端以前的"技能"只是**手写的 `/命令`**：一个名字 + 一段提示词，存在 `skills.json`，
输入框里打 `/` 把它填进去。手机端早就有另一套：`<目录>/SKILL.md` + YAML 头（`name`/`description`），
还带 URL/zip 导入与管理页。两端各定一套形状，用户就得记两遍"技能长什么样" ——
而技能正文往往是从网上抄来的，只有一种形状能通用。

- **`SkillDocs.kt`**：装进 `HAOAI_HOME/skills/<slug>/SKILL.md`，上限 60 份、单份 2MB、
  一个包最多看 500 个条目。`parse()` 认 YAML 头里的 `name`/`description`，其余整篇是正文；
  没有头的也收（就当一段说明）。**撞名不覆盖**：另存 `x-2`、`x-3` ——
  导入一批时"覆盖上一个"是最难发现的丢数据方式。
- **三个入口**：`import-text`（粘一份）、`import-url`（一个 `.md` 或一个 `.zip`，
  按 content-type 或开头的 `PK` 分辨）、`import-zip`（界面选本地文件，base64 传进来）。
  URL 那条**只认 http/https**：这个框里会粘进任何东西，`file:` 必须挡在门口。
- **越界防线只有一处**：`sanitize()` 把条目名折成安全目录名（分隔符变 `-`、点号只能在中间、限长 40、
  中文保留 —— 全折成横线的话"剪片子 v2"就变成 "v2"，列表上没人认得）。
  `../evil/SKILL.md` 这类条目被拒**并且要出现在 skipped 里**：静默丢掉一份技能，
  用户只会以为"没导入成功"。
- **`/api/skills` 长成一个面**：`GET` 把"手写的 /命令"与"导入的 SKILL.md"拼成同一份清单
  （每条带 `doc:true` 与 `slug`），删的时候按 `doc` 分流（`deldoc` 删目录，`del` 删列表里的一条）。
- **界面**：导入的三个入口放在「记忆」页签原有的技能区下面（不新开第 11 个页签 ——
  技能本来就在那儿）。列表里每条标着来源，状态行报"进了几份、跳过几份"。
- **这版刻意到哪儿为止**：技能是**用户用 `/` 唤起**的。不注入系统提示、也不给模型一把 `skill`
  工具去自选 —— 手机端那两样是成对做的（`promptIndex()` + `SkillTool`），
  只做其一会出现"模型知道有这份技能却读不到正文"的半截状态。

判据（Kotlin 新增 10 条 `SkillDocsTest` + 2 条 `AtomicWriteTest`）：
YAML 头拆得对、没头也能收、**中文技能名留下能认的目录名**、
`../` 与 `..\` 与绝对路径的条目**全被拒且被报出来、状态根里没多出文件、所有技能都在目录内**、
超限条目被跳过且不留半个技能、一个包里两份都进、同名两次不覆盖、
URL 导入 `.md` 与 `.zip` 各进一份且 `file:` 被拒、删目录删干净；
（像素，`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-skill.json`，19 步：
「记忆」页签里三个入口都在 → 粘一份 SKILL.md → 列表出现"剪片流程 · 30 秒竖版 · SKILL.md" →
输入框打 `/`，菜单里能看见它（与内置命令同列）→ 点删除 → 回到没配。）

**这一批最该记的两件事：**

1. **"刚建的会话在侧栏里凭空消失"不是玄学，是半截文件。** 排查这条时先出现的是
   `ScheduleTest` 偶发红（HookTest 在场时必红），第一反应是"测试互相污染"或"机器慢"。
   真相：会话文件每回合**原地重写**，侧栏一直在读它，读到半截 JSON 之后被读侧的
   `runCatching` 咽掉 —— "读不到"于是长得像"没有这条会话"。
   ⇒ 落盘改成"先写 `.tmp` 再替换"（`Env.atomicWrite`），读侧退避重试五次（`SessionIndex.read`）。
2. **Windows 上 `Files.move` 不是"更安全的写法"，它会直接失败。** 第一版只兜了
   `AtomicMoveNotSupportedException`，而目标文件被另一个读句柄握着时（Java 的读句柄不带
   `FILE_SHARE_DELETE`）报的是 `ACCESS_DENIED` —— 异常一路冒到 `persist()` 外层的
   `runCatching`，于是**整部落库都不做了**，三次 `ResumeTest` 全红，比原来严重得多。
   ⇒ move（ATOMIC → 普通）失败就退到 copy，三条路都不通才原地覆盖；
   压力测试里"读侧丢会话"从 32/120 降到 0。**改并发的时候，判据必须自己造并发**
   （`AtomicWriteTest` 一个线程狂写、一个线程用产品那个 `read()` 狂读）。

## 这一批：只读快照 —— 一条会话导成一个"打开就能看"的 HTML（v0.64.0，B12 第二件）

以前只有"导出 markdown"。markdown 是**给编辑器和机器**的形态：发给别人，对方得先有个能渲染的人。
OpenCode 的 `/share` 给的是成品 —— 这一批补的就是这个形态。

- **`Share.kt`**：`render(title, workspace, model, mode, madeAt, msgs)` → 一个**自包含**的 HTML 字符串，
  `write` 落到 `HAOAI_HOME/share/pc-<sid>-<时间>.html`。
  **不写进工作区** —— 那是个 git 仓库，导出的东西不该混进用户的 diff。
- **三条硬规矩**：① 页面里**一行 JS 都没有**（正文是模型写的，不转义就是"在别人机器上跑"）；
  ② **不引任何外部资源**（断网能看，也不泄露访问记录）；③ 读回来的 `name` 只认我们自己写出去的那种文件名
  （`safeName` + canonical 双重校验，`../` 与 `.htm` 与空名全拒）。
- **markdown 只渲染一小撮**（服务端渲染，页面依旧零脚本）：围栏代码块、行内代码、`#` 小标题、
  `-`/`*` 列表、`**粗**`。**先转义再套标签**，所以 `<img onerror=…>` 只会是文本。
  整套 md.js 搬不过来（要么引外部脚本，要么在 Kotlin 里重写一个渲染器），
  而快照里最常见的就是这几样 —— 少了它们，页面看着就像贴了源码。
- **接口**：`POST /api/share {sid}` → `{ok,name,path,url,blocks}`；`GET /api/share?name=` 把那份 HTML 发回来。
- **界面**：顶栏 ⇩ 旁边多一颗 ↗（命令面板里叫"导出只读快照"）。点一下写文件 + 新标签打开看一眼；
  地址同时挂在按钮的 `data-url` 上，剧本据此断言（不靠读 toast 文本）。

判据（Kotlin 新增 7 条 `ShareTest`）：
每条消息都在页面里、正文里的 `<script>` 被转义成文本、整页没有真 script 标签 / 没有 `src=` / 没有 http 外链、
空会话也出一页并写明"还没有说过话"、markdown 那一小撮真的变成结构（`<h4>`/`<li>`/`<code>`/`<pre class="code">`）
且围栏符号不再外露、没收尾的围栏不会死循环、`../` 与非法名一律读不出来、
端点写出的文件确实在 `share/` 目录里且 `GET` 回来的与写进去的是同一份。
（像素，`SHOT_MODE=chat bash pc/tools/ui-shot.sh tools/steps/ui-share.json`，12 步：
说一句话 → 点 ↗ → 读回那份 HTML（消息在、无脚本、无控件、无外链）→
**浏览器真的导航到那个地址**再量一次：`section` 有 2 段、`button/input/script` 数量为 0、
正文宽度 >320、深色底 —— 这是"给人看的成品"该有的判据，不是"文件存在"。）

**这一批最该记的一件事：**
**响应里的 JSON 要用手上的 `quote()`，别手搓引号。**
第一版写成 `"""url":"/api/share?name=${quote(name)}""""` —— `quote` 自己会加引号，
于是拼出来是 `"url":"/api/share?name="pc-x.html"`，一个非法 JSON。
Kotlin 侧表现为 `JsonDecodingException`，而界面侧只是"点了没反应"（`post()` 解析失败走了 catch 分支）。
⇒ 拼 JSON 的每个值都走同一个 `quote()`；这类错在测试里第一秒就红了，前提是**判据真的读那个字段**
（这条测试断言的是 `field(made,"name")`/`path`/`url`，不是"接口回了 200"）。

## 这一批：修掉"我说的话不见了"——每一帧 SSE 都得是合法 JSON（2026-09-28，v0.65.0 的前置）

做分屏时像素剧本第一条判据就红了：左栏那条会话里**没有用户自己那句话的气泡**，
而 `/api/state` 里明明白白存着那一句。查下来是 `publish("user", …)` 的负载多打了两个右括号
（`…}}}`），浏览器 `JSON.parse` 抛 `Unexpected non-whitespace character after JSON at position 48`，
`on('user')` 整个 handler 死掉 ⇒ 那句话当场画不出来，只能等这一轮跑完（或刷新）时
`hydrate` 重画才"自己回来"。**跑十分钟的任务里，表现就是"我说的话不见了"。**

**为什么 364 条测试一条都没抓到**：接口测试读的都是 `/api/state`、`/api/settings` 这些**返回值**，
没有一条去看 SSE 帧里的 `data:` 是什么。而"跑完会自愈"把它藏得更深 —— 短任务一秒就结束，
中间那段空白没人看见。定位花了四轮，因为**症状长得像前端渲染问题**：
① 先怀疑我重写的 `add()`（不是）；② 再怀疑"重放把气泡抹了"（`hydrated` 标记对开机就在跑的会话不可靠，
但那不是这次的原因）；③ 另开一条 `EventSource` 当探针，看到 `user` 帧**确实到了浏览器**（排除服务端没发）；
④ 最后挂一个 `window.addEventListener('error')` 才拿到那句 SyntaxError。
⇒ **通用做法："事件收到了但没画出来"先二分"没收到"与"收到但处理炸了"**，
一条 error 监听就够，比对着代码猜快一个数量级。

修法两件：① 括号改对（一个对象一个 `}`）；② `ApprovalFlowTest` 加一条
**"浏览器要 parse 的每一帧 SSE，data 都必须是合法 JSON"**，顺带断言 `user` 帧带 `t/imgs/media`。
这条测试**自己另开一条 events 连接**，不从共享的 `events` 队列里 poll ——
从共享队列取会把别的测试在等的事件吃掉，变成"单跑通过、合跑失败"（这份文件里已经为这件事写过注释）。

## 这一批：两条会话并排（分屏）—— 各看各的流、各滚各的（v0.65.0，B12 第三件）

对照代码与对照聊天时想同时看两条会话（ZCODE 的多标签就是这个用途）。地基早就在：
每条会话一份独立引擎、事件按 sid 分流、每会话一份自己的 DOM。缺的只是"同时摆出来"。

**入口**：顶栏 `▥` = 把**刚才看过的那条**并到右边（"我刚从 A 切到 B，想两条对照"是唯一自然的语义；
在只开过一条会话时它明说"并排得先打开过另一条"，不摆一个点了没反应的钮）。
顶栏出现「▥ 分屏中 · 退出」，左栏那一行打"并排"标签并画虚线框。

三条设计判断，都是做的时候才想清楚的：

1. **每一格自己是一条滚动容器**（分屏时 `#stream` 变 `overflow:hidden` 的 grid，`.view` 变 `overflow:auto`）。
   共用 `#stream` 那一条滚动条时，左边跟流会把右边一起拽着走 —— 而并排看的意义正好是"各看各的"。
   代价是所有"这条会话现在滚到哪"的判断都要过一层 `paneOf(v)`，所以判据也按两格写。
2. **贴底才跟着走**（`follow` / `grow`）。顺手补上了一个更早的洞：`scroll()` 原先只在 `show()` 里调过一次，
   **流式回答根本不跟** —— 一屏之外的正文一路往下长，人得自己滚。现在新内容与增量重画都问一次
   "这一格原本贴底吗"，贴底才 `pin`；往回看的人不被抢。
   坑：这个判断必须在**改内容之前**取（`grow` 里先取 `stick` 再 paint），改完再问已经被自己撑高了。
3. **进出分屏都要把两格各自拽到底**。切成两格的瞬间每格 `scrollTop=0`，而人本来看着的是最新那一句 ——
   一切换就跳回第一条，功能当场变成故障。

**"这句话发到哪一条"是显式状态**：每格顶上有一条 sticky 标题（`▸ 标题 · 输入到这里` /
`· 标题 · 点这一格换到这里输入`），点非目标那一格的任意位置就换过去（正在选文字、点在按钮上时不抢，
换的时候不动任何滚动条）。标题条用 `::before + attr(data-cap)` 而不是真节点：
会话重画历史时整段 `innerHTML` 会被清空，真插进去的节点会跟着没了，伪元素不会。

**结构性判据进了 `ui-check.js`**：分屏之后"看得见的会话"有两种（`.on` 与 `.duo`），
凡写死 `.view.on` 的选择器都会在分屏时漏掉右栏 —— 这类漏法单屏下测不出来。
现在逐条对账：要么同一条选择器也管 `.duo`，要么进"刻意只作用于输入对象"的白名单
（目前只有键盘决定审批卡那一处：快捷键只该打在"这句话发到哪一条"上）。

**已知边界**（不是没做完，是这一批的口径）：键盘 y/a/n 只作用于当前输入那一格，右栏的审批卡用鼠标点；
分屏是**这次页面加载内**的布局，刷新回单栏（跨页面记住布局要先把"哪两条"变成可序列化的状态）。

**验收**：`gradle test` 365 条全绿（这批新增的是上面那条 SSE 帧格式测试）；
像素剧本 `tools/steps/ui-duo.json` 32 步全绿，跑法
`SHOT_MODE=multi bash pc/tools/ui-shot.sh pc/tools/steps/ui-duo.json`。量到的事实：
两格各 **466×670**、`overflowY` 各自是 `auto`、两格都高过自己那栏；把左格滚到顶之后
它连来 14 条新内容**都不抢滚动条**（`↓ 14 条新内容` 的计数条同时亮着），右格一路跟到底；
第二句话只进目标那一格（另一格 user 气泡数不变，两格文本各自只含自己的关键词）；
点左格之后 `curId/duoId` 互换且标题条跟着换；退出后回到单栏、两条会话的内容都还在。
夹具这边给 mock 网关加了 `并行己`（一次答六十行，专门用来把一格撑出滚动条），
并给 `ui-shot.sh` 加了 `python -m py_compile` 前置 —— 我的脚本语法错被报成"假网关没独占端口"，白绕一轮。

## 这一批：剪辑链补齐 —— 字幕、烧字幕、中文标题封面、两段拼接（v0.66.0，B7 的第二段）

`media` 原先只有"探/转/剪/抽帧/抽音轨/封面"六下，做视频时缺的正是**成片那几步**：
一条录屏要发出去，得有字幕、得有能当封面的那张带字的图、两段要能拼一起。四个新子命令都长在**同一把工具**上
（不做四把窄工具，理由与 `git`/`browser` 一样：工具越多模型选错越多）。

- **`srt`**：把 `[{start,end,text}]` 写成 SRT。**不碰 ffmpeg**，所以它排在 `input` 存在性检查之前 ——
  "先写字幕、后录屏"是正常顺序，不能被"你没有源文件"挡掉。
  格式三件事各有测试：序号从 1、毫秒前是**逗号**（写句号整条不认）、块之间空一行。
  已存在的字幕默认**拒绝覆盖**（要 `force=true`）：字幕是人工对过时间的东西，静默盖掉的代价太高。
- **`subtitle`**：把 .srt **烧进画面**（播放器关不掉，这一点写在回执里）。
- **`caption`**：在封面/某一帧上叠一行大标题（视频封面、直播缩略图）。
- **`join`**：两段按顺序拼一段。用 concat **滤镜**而不是列表文件 —— 列表文件里"路径相对谁、怎么转义、
  非 ASCII 怎么办"在中文工作区上最容易出事；代价是必然重编码，而 `-c copy` 在两段时间戳对不上时是
  **花屏**（比慢更糟）。有没有音轨先 `ffprobe` 探：一段有一段没有就直接拒绝并说清怎么补，
  探测失败也拒绝 —— 不替用户猜要不要保留声音。

**这一批最值钱的一条实测结论：ffmpeg 过滤串里的 Windows 盘符冒号，转义是打不赢的。**
第一版按文档写 `fontfile=C\:/Windows/Fonts/msyh.ttc`，ffmpeg 回
`No option name near '/Windows/Fonts/msyh.ttc:...'` —— 它把 `C\` 当成值、后面全成垃圾。
**解法不是继续加反斜杠，而是让那个值里根本没有冒号**：把字体拷进工作区的
`.haoai-output/fonts/`（一份工作区只拷一次，按大小判断复用），过滤串里用不带盘符的相对路径。
同一招用在字幕上：用户给的 .srt 先复制成输出旁边的安全名字（`字幕版.srt`）再引用。
文字内容则一律走 `textfile=` 而不是内联 `text=` —— 标题里那个冒号（"第三期 : 冒号也要画得出来"）
现在是真的画出来了，见像素。

**验收（判据全部量到数）**：`gradle test` **371 条全绿**（`MediaTest` 14 → 20：SRT 格式与 round-trip、
坏时间轴拒绝、不覆盖、四个子命令各说清还缺什么、caption 的像素与 `cover` 不同、
烧完还是 5 秒且保留音轨、拼接后 4+4≈8 秒）；
像素剧本 `tools/steps/ui-cut.json` 11 步全绿（跑法 `SHOT_MODE=cut PRE_CLIP=素材.mp4 bash pc/tools/ui-shot.sh pc/tools/steps/ui-cut.json`）：
4 张工具卡 0 张红、两个播放器分别是 `320x240/4s`（字幕版）与 `320x240/8s`（拼接），
封面缩略图 `1280x960`，`/api/file` 读回的字幕正文以 `1\n00:00:00,300 --> 00:00:01,800` 开头，
「产出」里四个文件都在。**中文标题本身是看过像素的**：微软雅黑白字黑边压在画面下方，冒号没丢。

顺带两条工装账：mock 网关新加了 `cut` 模式（**没有**动 `media` 模式，老剧本 `ui-media.json` 的判据
不该被这批连带改掉）；`Plan` 多了 `writes`/`copies` 两个字段，让"喂给过滤串的小文件"必须等到
**审批通过之后**才落盘 —— 人点了拒绝，盘上就不该多出东西。

## 这一批：条目化长期记忆 —— 与手机端共用一份 `MEMORY.md`（v0.67.0，B10 的 PC 侧）

PC 原先的"记忆"是一个整文件编辑框：一粘贴就把已有内容盖掉，而且只能整篇喂给模型 ——
记忆越多越贵、越贵越不敢加。这批换成**一行一条**，并且**格式与手机端逐字对齐**，
这样以后做两端同步时只剩"怎么传"，不用先解决"两边写的是两种文件"。

**格式的对端是 `app/.../agent/memory/MemoryBank.kt`，而那边没有单测钉住格式** ——
也就是说"PC 写出去的文件手机能不能读回来"只有这边的测试能守。所以 `MemoriesTest` 里第一条
就是"照手机端真实写出的样子读"（含 `<!-- id:… imp:4 type:preference created:… uses:7 uq:3 src:model -->`
这种注释元数据、分节标题 `## 偏好（n）`、以及一条**手写的没有注释的行**），
第二条是 round-trip 幂等（`render(parse(render(x))) == render(x)` —— 否则每次打开界面都在改用户的文件）。

几条不可省的行为：

- **注入按预算挑，不整篇贴**：最多 8 条、单条 180 字、总 1200 字，打分
  `重合*2.5 + 重要度*3 + exp(-age/14) + min(uses,5)*0.2`（与手机端同一组常数与同一套权重，
  这样两端看到的记忆量是同一个量级）。超预算**整条丢掉而不是截半条** —— 半句话比没有这句话更容易被模型当成事实。
  `sup:archived` 与 `org:untrusted/system` 的一律不进提示。
- **去重不是覆盖**：归一化（去空白与中英文标点）后完全相同 → 只把重要度抬上去，并明说
  "已经有同样的一条，只把重要度抬了抬"。静默合并与静默覆盖都坏，但静默覆盖更坏。
- **"忘掉"是软取代**：文件里那一行留着、加 `sup:archived`，界面上消失。
  这样同步时不会因为一端删了就把它在另一端"复活"，也留得住回看的可能。
- **解析不了的行进 `## 其他` 原样写回**，绝不静默丢 —— 这条与手机端 `MemoryState.extras` 同一口径。

**这批修掉的一个设计错，值得记：搜索与注入不是一条路。**
第一版把注入那套"没有重合也按重要度给几条兜底"搬进了 `search()`，于是界面上搜 `gradle`
会把三条毫不相干的记忆一起列出来 —— 判据不是 Kotlin 测试抓的（那边断言的恰恰是"不许空手"，
**测试自己就是错的**），是像素剧本里 `onlyGradle` 那条红抓的。
现在：搜索**搜不到就是空**（界面显示"（没有匹配的条目）"），注入才按重要度与新鲜度兜底。

**界面**：「记忆」页签顶部新增一块条目区（搜索框 + 列表 + "记一条"：类型/重要度/标签），
原来那两个整文件编辑框保留在下面，标题改成「项目说明（整篇）」与「全局记忆（跨项目）」以示区别。
窄栏里"类型 + 重要度 + 标签"三个控件用两列网格（标签占满第二行）——
一行放三样在 292px 栏里一定会挤爆，这次是**先看像素才发现标签输入框的提示文字被截了**。

**已知边界（刻意留到同步那一批）**：① 注入侧的 `uses`/`uq` 计数还没接（手机端会累加，
PC 读回来会原样保留但不再涨）；② 手机端那条 `tidy()`（30 天不用降级、Jaccard>0.85 自动合并）没搬 ——
两边都开"自动整理"会互相整理对方的文件，得先定谁是主。

**验收**：`gradle test` **382 条全绿**（新增 `MemoriesTest` 10 条 + `EngineFlowTest` 1 条
"记忆真的进了模型看到的 system"，判据取的是网关收到的那条 system，不是文件写得对不对）；
像素剧本 `tools/steps/ui-memitems.json` 31 步全绿：两条记忆列出且不截断、
重复记一条条目数仍是 2、搜 `gradle` 只剩 1 条、点"忘"之后列表 1 条而文件里仍是 2 行且带 `sup:archived`。

## 这一批：子任务可以按任务换模型与工具（v0.68.0，对标 ZCODE subagents / OpenClaw 多 agent）

一条大任务里既有"把这三份日志读一遍归纳"（脏活，本地小模型就够）又有"据此定方案"（要强的模型）——
以前 `task` 只会整份继承父会话的模型与工具，等于**拿最贵的模型干最便宜的活**。
现在 `task` 多三个参数：`model`、`tools`（逗号分隔）、`mode`。

三条规则是这批的真正内容，功能本身只是外壳：

1. **档位只能比父会话更严，不能更松。** `plan` 的会话派不出一条 `auto` 的子任务去替它写文件 ——
   否则"只读调研"这个档位就成了摆设，模型只要把写操作塞进 `task` 就绕过去了。
   这与 v0.4x 修的那个洞同源（当时是"关着的工具凭训练记忆报个名字还能调"）：**权限的边界必须同时在
   schema 与执行侧各挡一次**，只挡一处迟早被绕。
2. **工具只能是父会话现在有的那个子集**，要一把没有的（或这条会话被关掉的）就**明确拒绝并把能用的列出来**，
   不许静默忽略 —— 静默忽略的表现是"子任务什么都能干"，与模型要的不是一回事，而没人会去核对。
3. **用了什么配置必须写在子任务卡的标题上**：`小模型读文件 · 模型 本地小模型 · 只给 read/grep`。
   写在展开体里不算 —— 跑完的卡是收起的；而**跑完那一下客户端会重读一次权威状态**（`on(run:false)` → `hydrate`），
   整张卡是从落库的过程记录重画出来的，所以配置还得进那份记录（`log.appendLine("配置：…")`）
   才活得过刷新。看不见的配置等于在用户不知情的情况下换了套权限在跑。

配套改的一处：`childClient` 从 `() -> ChatClient` 变成 `(PcSettings) -> ChatClient` ——
子任务的网关客户端要按**它自己要用的那份设置**造，否则换了模型名而请求还发旧模型
（与"改了设置要立刻反映到活着的引擎"那条老账同源）。

**验收**：`EngineFlowTest` +3 条（指定模型真的用上了 / "只给 read"时子任务 schema 真的只有 `read` /
plan 档派不出写的子任务且一个审批都不弹 / 要没有的工具被明确拒绝），整套 **385 条全绿**；
像素剧本 `tools/steps/ui-subcfg.json` 12 步（跑法 `SHOT_MODE=subcfg bash pc/tools/ui-shot.sh pc/tools/steps/ui-subcfg.json`）
量到：子任务卡**标题**是 `小模型读文件 · 模型 本地小模型 · 只给 read/grep`（收起状态也看得见）、
结论带回父会话、子任务再往下派一层被深度上限挡住、**刷新之后标题上那行配置还在**（而展开体里不重复列）。

## 这一批：记忆会自己长回去 —— 使用反馈回写 + 手动整理一次（v0.69.0，B10 的 PC 侧余款）

上一批把条目记忆读写了，但**打分公式里有一项永远是 0**：`min(uses,5)*0.2`。
没有"这条被用过几次"的回环，常用的一条升不上去、久不用的一条降不下来，
记忆库实际退化成"按写入顺序取前 8 条"。这一批补的就是这个回环，以及配套的"整理一次"。

**使用反馈（`Memories.markUsed` / `bumpUsage`）**，三条口径全部照抄手机端 `MemoryBank`，
少一条计数就变成噪音：

- **一条每小时最多 +1**。注入是每轮都做的事，不限流就等于"谁常驻谁转正"。
- **`uq` 只数没见过的查询指纹**。同一句话问一百遍算一次；指纹算法与手机端逐位相同
  （`Integer.toHexString(q.trim().lowercase().take(160).hashCode())`），否则两边的 `uq` 不可比。
- **打上 `rc:1`**：手机端靠它防"把召回来的老事实再提取一遍成新记忆"。

**只有真发请求才记账**：`requestMessages(countUse)` 多一个开关，`client.chat(...)` 那处传 `true`，
而顶栏"上下文占用"那个估算调用（`contextBreakdown`）传 `false` —— 打开界面不该刷计数。
回写走 `bumpUsage`：**重新读一遍文件**再改计数，不拿内存里那份盖回去。
两端共用一个文件，手机上刚新增的一条被这一写抹掉，比"少记一次使用"严重得多。

**顺手修掉一个跨端缺陷**：手机端写的 `rc:1`（以及任何这边不认识的元数据键）以前会在
PC 保存时被整段擦掉 —— 解析器只认自己建模的那 12 个键。现在不认识的键进 `Item.rawMeta`
原样带回去。共用一份文件的两边都没有"我读不懂就可以扔"的权力。

**整理一次（`Memories.tidy`）**：判据与手机端同一组（重要度≤2 且 30 天没用 → `dormant`；
归一化相同或词面 Jaccard>0.85 → 合到一条；失效满 30 天物理清掉），两处刻意不同：

- **合并是"指到保留那条的 id 上"而不是删掉**。手机读 `sup:<id>` 就是同一个语义，而误合的那条还能回看、能改回来。
- **手动，不自动**。手机端每轮自动 tidy，这边是记忆页签一颗钮，而且**第一次点击只要预览**
  （服务端不写文件），按钮上写出"将要动几条"，第二次点击才真写。两端共用一份文件时
  两边都自动整理＝互相整理对方的文件。

**验收**：`MemoriesTest` +8 条（未知元数据键活过一次重写 / 一小时限流 / 同一句话不加 `uq` /
回写不抹掉手机端新增的那条 / 降级不是删除 / 两种重复各走一条判据路 / 只清满月的 /
健康库整理报告是 0），`EngineFlowTest` +1 条（真发一次请求 `uses` 变 1、连点三次"看占用"仍是 1、
`rc` 标记落进文件），整套 **394 条全绿**；
像素剧本 `tools/steps/ui-memtidy.json` 17 步（跑法 `PRE_MEMORY=1 bash pc/tools/ui-shot.sh pc/tools/steps/ui-memtidy.json`，
新加的 `PRE_MEMORY` 夹具按**运行时**算时间戳 —— 写死时间戳的夹具过几天就永远命中不了，剧本会一直绿而什么都没验）
量到：6 条里预览"降级 2 / 合并 1 / 清掉 1"、按钮变成"确认整理（动 4 条）"且**不换行不挤爆**、
确认后列表剩 3 条且计数同步、再点一次说"不用整理"、每条显示"用过 N 次"。

## 这一批：回到这一句之前 —— 话和文件一起退（v0.70.0，B12 的最后一寸）

检查点（v0.52.0）与"删到这里"（v0.27.0）各自都在，但**它们各退各的**：
`/api/cut` 只截对话，工作区里那几轮改过的文件留在原地。于是"退回到问它之前"是假的 ——
人以为改动没了，下一轮模型 `read` 到的还是改过的内容。这一批把两半接成一颗钮。

**消息怎么对上轮次**：`Checkpoints` 的头行多记一个 `at` = 这一轮开头那句话在历史里的下标
（`Engine` 在 `begin()` 时取 `indexOfLast user`）。有了它才有 `runCovering(sid, index)`（点在这轮
任意一条上都算这一轮）和 `runsFrom(sid, at)`（这一轮**以及它之后**的所有轮）。
两条判据：

- **退要连着后面的轮次一起退**（`rewindAll`）。只退点中的那一轮，后面几轮改的文件还在，
  等于没退。同一路径取这批里**最早**那份快照 —— 那才是这一轮开始前的样子。
- **老账本没有 `at` 就拒绝回退，不许猜**。定时任务、手机派活、子任务都往同一条会话里追加消息，
  按时间猜轮次会退错版本，而"退错"比"不能退"危险得多。

**顺序刻意**：能校验的先校验完（会话在、没在跑、这一句确实属于某一轮），再截历史，最后才动文件 ——
动文件是这里唯一收不回的那一步，要让它出问题时用户至少看得见"话已经退到哪了"。

**顺带补的一处不一致**：回退之后右栏「任务」那块清单还留着被退掉那轮的步骤，
界面就在说一件已经不存在的工作。`dropTodosForRewind()` 把它一起清掉（返回值进响应，界面上看得见空清单）。

**验收**：`CheckpointTest` +3 条（下标对上轮次 / 跨会话不串 / 没有 `at` 的老记录一律不猜 /
多轮一起退退到最早那份快照，两轮各自新建的文件都要消失），整套 **397 条全绿**；
像素剧本 `tools/steps/ui-rewindturn.json` 12 步（`bash pc/tools/ui-shot.sh pc/tools/steps/ui-rewindturn.json`）
量到：跑两轮之后 `hello.txt` 在、清单有三条、那颗"回到这里（含文件）"就在动作行里
（**六个按钮仍一行不折行**，这一栏上次就是挤爆过）；点下去之后 toast 说
`回到这 2 轮之前：还原 0 个、删掉它新建的 1 个`、`hello.txt` 真没了、对话回到空状态、
右栏清单回到"还没开始"。

## 这一轮：验收量具自己修了三处（2026-09-28，不动产品代码、不占版本号）

起因是我在截图里看到"加粗中文重影"，怀疑排版坏了。查下来的结论是**三件事**，
两件是量具的错，一件是产品根本没有错：

1. **`noClip` 判据永远不可能成立**（`ui-memitems.json` 第 14 步）。它写的是
   `c.scrollWidth <= c.clientWidth + 1`，而 `c` 是 `getBoundingClientRect()` 的结果 ——
   Rect 上没有 `scrollWidth`，`undefined <= NaN` 恒为 `false`。
   也就是说 v0.67.0 那份"31 步全绿"的剧本**从来没真的全绿过**：退出码是对的（非零），
   但我当时把输出 `| tail` 之后只看了解析打印的数值就当通过了 —— 管道会把退出码换成 `tail` 的。
   现在改成量真正的元素（`sw`/`cw` 一起打出来），量到 `256/256`：**标签输入框其实一直没被裁**。
2. **缩略图判据会偶发假红**（`ui-cut.json` 第 6 步）。`naturalWidth` 在图片解码完成前是 0，
   而剧本上一步刚跑完 ffmpeg 就立刻量 —— 同一份剧本三次里红过两次、绿过一次。
   现在按"等到 `naturalWidth>0`，最多 9 秒"来量，连跑两遍都绿。
   （老账：懒加载的 `naturalWidth=0` 早就坑过我一次，这次坑在"我自己写的判据"上。）
3. **"重影"是 1 倍采样的锯齿，不是排版**。`b.getClientRects()` 末段右边缘与后一句文本节点
   左边缘 `gap=0`，布局没有重叠；`SHOT_DPR=2` 重拍同一块，加粗那段干净。
   `shot.js` 因此多了 `SHOT_DPR` 开关（默认仍是 1，与既有几十份剧本的判据保持可比）。

**顺带把"红"说在最后一行**：`shot.js` 收尾会打印
`全部判据通过（N 步）` 或 `x 判据没过 K 处：第 i 步 xxx`，`goto` 超时也计入。
中间那行 `!!` 太容易被划过去 —— 打印不是判据，**结尾那句才是**。

## 这一批：把两端共用的 `MEMORY.md` 格式钉死（v0.71.0，B10 的地基）

前几批一直写着"手机端 `MemoryBank.kt` 是唯一规格、**那边没有单测钉住格式**"。
这句话本身就是风险：PC 的"照手机端真实写出的样子读"用的夹具是**我手写**的样本 ——
也就是说"手机真的写出来的是不是这个形状"，两端都没测。这次把它钉上，并顺手修掉量出来的第一处不一致。

**手机端新增 `MemoryBankFormatTest`（6 条，纯 JVM 单测、不需要设备）**：用真实的 `MemoryBank` 写盘，
再按 **PC 解析器用的同一批正则**去量（`- [id · 重要度N] … <!-- … -->`、`## 偏好（n）` 那四个分节标签、
`id/imp/type/created/lastused/uses` 六个必需键、注入后的 `uses/uq/rc`、`sup:` 指向新的那条、
以及"写一条不许改动别条的字节"）。跑出来一次红：我自己的断言写了 `lastUsed`，
而两边解析时都把键统一小写 ⇒ 实际文件里是 `lastused`（产品是对的，测试错了）。

**量出来的第一处跨端不一致**：手机端在文件顶部写了两行 `>` 说明（"行尾 `<!-- -->` 是元数据，
修改内容时请整行保留"…），而 PC 的解析器只认 `- 条目` —— **存一次盘就把手机端那份说明擦掉**。
这与上一批修的"未知元数据键被擦"是同一条规矩的另一半：共用文件的一方没有"读不懂就可以扔"的权力。
现在 `Doc.prelude` 把第一个分节之前的行原样带着走，`render` 有 prelude 就照抄、不再换成自己那份标题；
空行不进 prelude、渲染时在 `# ` 标题后补回一个空行 ⇒ 两边各自的形状都能逐字节复原（幂等测试守着）。

**验收**：`MemoriesTest` +1（手机端那份文件过一遍 PC 的存盘，两行说明还在、标题没被换、
条目一条不少、再存一次逐字节相同），PC 整套 **398 条全绿**；
手机端 `gradle :app:testDebugUnitTest --tests "*MemoryBankFormatTest*"` 6 条全绿。
跑法与判据都是命令级：`bash pc/tools/audit-steps.sh` 会把 `steps/` 下的剧本按**退出码**全量重跑一遍
（上一轮就是这么发现"我以为绿了"的剧本其实从来没绿过）。

## 这一批：剪辑链第三段 —— 变速、淡入淡出、压背景音乐（v0.72.0，B7 第三段）

前两段有了剪切/抽帧/封面/字幕/拼接，但**做一条能发的视频还差三步**：
口播讲长了要**变速**、切片首尾不能硬切要**淡入淡出**、画面底下要**压一层背景音乐**。
都是 `media` 的新子命令，都真跑 ffmpeg 验（不是拼个命令看看退出码）。

- **`speed`**：`setpts` 与 `atempo` 一起动 —— 只改画面不改声音，出来的是"默片配原速解说"那种废片。
  `atempo` 只吃 0.5~2.0，所以 4 倍速必须本地拆成两级（2×2）再串起来，
  这是 ffmpeg 自己的限制，不是我们挑参数。倍速范围 0.25~8，非数字（`"2;rm"` 这种）一个字都不进过滤串。
- **`fade`**：淡出起点要按**真实时长**算（ffprobe 探，`dur` 可覆盖），
  探不到就说清楚为什么不动，而不是拿 0 当长度。
  两条拒绝：`fadeIn+fadeOut >= 整段` 会做出"整段都是黑的"，两个都给 0 等于什么都不做 —— 都要说出来。
- **`mix`**：音乐先 `volume=gain`（默认 0.18）再 `amix:...:normalize=0` ——
  不关 normalize 的话 amix 会按路数平分音量，把**口播一起拖小声**，听感是"人声发虚"而参数上看不出来。
  `duration=first` 让音乐**在口播结束处截断**（反过来会把视频拉长）；画面 `-c:v copy`，
  这一步只动声音，重编码画面是白花一分钟。

**测试里抓到的一处自己的错**：`fade` 一开始写 `parseTime(dur ?: "")`，
空串被 `parseTime` 当成 `0` ⇒ 总时长算成 0 秒，"淡入淡出比整段长"这条判据第一次就把正常用法拒了。
判据的默认值不能靠"传空进去看看"，要么显式分支、要么让它报错。

**验收**：`MediaTest` +5 条（两倍速后时长 2~3.2 秒且音轨还在 / 四倍速拆成两级 atempo、
倍速离谱与非数字都挡 / 淡入淡出不改时长且两种退化都拒绝 / 混音以主音为准截断且画面流还在 /
主素材没音轨与不给 `input2` 与 `gain` 超范围都拒绝），PC 整套 **403 条全绿**；
像素剧本 `tools/steps/ui-edit3.json`（跑法 `SHOT_MODE=edit3 PRE_CLIP=素材.mp4 bash pc/tools/ui-shot.sh pc/tools/steps/ui-edit3.json`）
量到：三个播放器都在、第一个时长是原素材的一半、链式产物接得上（每一步吃的都是上一步的输出）。

## 这一批：命令面板补齐后面几批的入口（v0.73.0，B21）

分屏、整理条目记忆、手动压缩、自动化页签这四样是 v0.60~v0.72 陆续加的，
**当时都没进 Ctrl+Shift+P**。命令面板的价值就是"不用记入口在哪"，
漏一项等于那半个功能在面板里不存在 —— 而它坏得很安静：条目列表看着挺满，谁也不会去数。

- `PALSET` 的目标现在可以是**函数**：函数那几条归到新的「动作」组，
  和「跳转」的区别是它不把你送到某页签就完事，而是替你把两步做完。
  第三格可以显式写组名（`#duoBtn` 这种点一下就行的也标成动作，别让人在"跳转"里找"切换主题"）。
- 新增 `palTabBtn(页签, 选择器)`：先开页签，再等里面的按钮出现（最多 2 秒）才点。
  页签内容很多是 fetch 回来才渲染的，直接 `querySelector` 会拿到 null。
  点之前先 `scrollIntoView`：页签常常比屏高，按钮在视口里而**它下面那行结果**掉在折痕外 ——
  整理预览就是这么"送到了但看不见"。

**看像素看出来的三处**（结构判据全绿也照样错）：

1. **面板是透明的**。`.pal .pin` / `.pal .pl` 铺的是 `--glass2`（7% 白），
   于是底下那条会话的气泡文字直接透上来和条目叠成一片，`/compact` 那行读不出来。
   盖在内容上的浮层用透明底就是假玻璃：改成整块实心 `--panel` + `var(--shadow)`，
   输入框那层保留 `--glass2` 当高光。剧本里加了 `palSolid` 这条判据（按 `backgroundColor` 里有没有 alpha 判）。
2. **分屏的提示说反了**。`setDuo` 的 toast 写「已并排：右边是…」，而并排那条**渲染在左边**
   （`layout` 里 duo 在前，`ui-duo.json` 早就量过 `duoIsLeft`）。改成说左边，
   并把「话」和「画」钉在同一条判据里：`duoLeft && toastLeft` 两个都要过。
3. **`palRender` 里的局部 `box` 撞了输入框的全局名**。文件那条结果选中之后的
   `box.value='';box.focus();box.dispatchEvent(...)` 打在结果列表 `<div>` 上，
   三句全是空操作（`insertAtCursor` 用的是全局那个 `box`，所以 @ 还是插进去了，
   看起来"没事"）。改名 `lst` 并把那三句删掉。

**测试**：`ScheduleTest` +1（daily 的第一格只看出生点、不看你在什么时候问），
顺带修掉一条**只在夜里红**的测试：`daily in the future does not fire` 用了默认的
`created = System.currentTimeMillis()`，而 daily 的 `nextDue` 算的是"出生之后的第一格"，
于是 23:00 以后跑全量它就红（23:33 实测）。现在把出生点钉成 `clock(7,0)`。
PC 整套 **404 条全绿**。

**验收**：像素剧本 `tools/steps/ui-pal.json` 65 步 / 70 条判据
（跑法 `SHOT_MODE=chat PRE_MEMORY=1 bash pc/tools/ui-shot.sh pc/tools/steps/ui-pal.json`），
八张图逐张看过。量到的事实：

- 面板打开时焦点在 `#palQ`（不是"看得见但打不了字"），空查询里命令 12 条 / 跳转 22 条 / 动作 4 条；
- 「分屏」这条走键盘：回车之后浮层关闭、`#stream.split` 亮、两格各占半宽、并过去那条确实是刚才看过的；
- 「整理条目记忆」两步之后 `#memTidyState` 的**头一行在页签可视区内**（`headSeen`），
  按钮变成「确认整理（动 2 条）」；同一条截图还顺带证明 v0.69 的用量回写在跑 ——
  起手那一轮把 6 条记忆全注入过，`lastUsed` 被刷成现在，所以"该降级的"从 2 条变成 0 条；
- 搜「摘要」同时列出 `/compact` 命令与那条动作（面板不替人挑路），用 `palMove` 走到动作那行再回车，
  用量页签被自己翻开、toast 说出结果；
- 十个页签排一行没被压成两行（`tabsFit`），跑完页面没有横向溢出。

`ui-check.js` 新增两条静态检查：**面板每条入口都真的存在**（数组里的字符串选择器，
上面那条 `$#+id` 扫不到），以及**动作要的页签与按钮能静态对上**。
翻转验过：把 `#duoBtn` 改成 `#duoBtnXX`、把 `palTabBtn('mem',…)` 改成不存在的页签，
三条漂移动全被抓出来；改回来就绿。

## 这一批：两端整理同一份 `MEMORY.md` 时不再互相吞条目（B10 第一步）

09-26 那份协同方案里"谁是权威源"已经拍板（**PC 为权威、手机为节点**），而"记忆同步/合并"是**刻意暂缓**的——
触发条件是两端真的出现"互不知道进展"的痛感。这一批不动同步协议，只补它前面的一块地基：
**两端各自的"整理一次"必须留下同样的痕**。之前不是这样：

- PC 端 `Memories.tidy()` 合并是留痕（被合那条打上 `sup:<保留那条的 id>`，30 天后才物理删）；
- 手机端 `MemoryBank.tidy()` 合并是**直接从文件里丢掉那一行**——
  而同一份文件里它自己的 `supersede()` 与 `mergeIds()` 早就是留痕的形状，只有 tidy 这一处不一致。

今天两端各指各的文件，撞不上；一旦共写一份，"手机晚上自动整理"就会把 PC 看得见的一条吃掉，
而且没人知道少了什么、也改不回来。所以先统一形状。

**顺带抓到一处真的会丢数据的**：手机端的合重循环以前不跳过"已失效"的条目，
于是**刚被降级成 dormant 的那条可以当上"保留者"，把还活着的那条判成重复并删掉**
（活的那条重要度更低时必然发生）。现在两端一样：已失效的既不参与合重，也不能当保留者。

- 手机端：`app/.../memory/MemoryBank.kt` 的 `tidy()`（存 `state.items` 而不是 `kept`）。
- 测试：`MemoryBankFormatTest` +4（留痕不丢行 / dormant 不吞活的 / 二次整理收敛且不改字节 /
  手机写出的痕能被电脑端正则读回），**整套 167 条全绿**；
  `MemoriesTest` +1（**夹具是手机端真实整理后的字节**，不是手写样本）：
  三条都读得回来、活着的两条、注入不带被合那条、PC 再整理一次报 0，
  且 `render(parse(手机那份))` 与原文**逐字节相同**。
- 翻转验过：把 PC 解析里的 `sup:` 忽略掉 → 新测试红；把手机端退回旧实现 → 4 条新测试里 3 条红。
  两边都确认判据会咬，才敢说它钉住了什么。

**还欠的（下一步）**：共写一份文件时手机端那个"充电+灭屏自动整理"该不该继续跑——
按"PC 为权威"的定调，它默认应该让位（设置里可手动）。再往后才是提案式三方合并（继续暂缓）。

## 这一批：B4 安卓那一半的第一片——手机端协议客户端 `PcLink`（不碰设备）

安卓那半边欠的是"不打开浏览器也能被通知到、也能点那一下"（`pc/ROADMAP.md` 的 B4 行）。
**先做不需要设备的那一段**：通知与后台常驻要设备才能看，而"后台常驻"恰恰是模拟器验不了的东西
（模拟器没有省电杀手，跑一小时不被杀是必然，报"常驻验证通过"就是假绿——当初把权威源定在 PC
的依据就是真机杀后台）。所以这一片只做协议客户端 + 存储无关的部分，设备那一段单独排。

- `app/src/main/java/com/haoai/agent/platform/PcLink.kt`：对着电脑端 `Lan.kt` 已经实现的口写客户端
  （`lan/health` / `pair` / `sessions` / `session` / `pending` / `decide` / `digest` / `send` / `unpair`），
  token 走 `X-HaoAI-Token` 头。三条口径：
  ① **只读 + 替人点决定**，本地不留可写状态（"PC 为权威、手机为节点"，节点不该有第二份能写的会话）；
  ② 失败一律回一句人话（`PcOut.Fail`），**不抛异常**——调用方是通知服务与设置页，
     "连不上那台电脑"本来就是要显示给人看的东西，不该是个栈顶异常；
  ③ `unauthorized` 单独开一个口子：它不是"再试一次"，而是"该跳回配对页并清 token"。
- `pcNormalizeBase`：人手打地址是整条链路唯一的入口，`192.168.1.5` / `:8720` / 带协议 / 末尾斜杠
  四种写法全都常见，而拼错的表象是"配对失败"、错误还是电脑端回的 ⇒ 在这里判"看不懂"并说清要什么。

**测试（`PcLinkTest` 9 条，不碰设备也不碰真网络）**：测试里就地起一个假 PC（JVM 自带的
`com.sun.net.httpserver`），按电脑端真实回的形状应答（中文 + 嵌套 payload + 风险三档）。
两条自己写出来、被测试挡住的错值得记：

1. **"合法主机名"我写成了 `isLetterOrDigit()`** —— `isLetter()` 对中文返回 true，
   于是 `主机名:8720` 被当成合法地址放过去了。判据改成只认 ASCII 形状。
2. **第一版假 PC 根本不看请求头**（只在 `tokenOk=false` 时装死），所以"客户端忘了带 token"
   只有一条测试会红。让假 PC 真的按协议拒绝无效 token 之后，同一次改动红 5 条 ⇒
   **量具放水的时候，缺陷看起来就不存在**（与下面「量具自己会坏」同源）。

翻转验过：把客户端那句 `b.header("X-HaoAI-Token", token)` 删掉 → 5 条红；改回来 → 全绿。
**手机端整套 `gradle :app:testDebugUnitTest` 184 条全绿**（`PcLinkTest` 9 + `PcPairingTest` 8；
再往上一片是 B10 第一步的 `MemoryBankFormatTest` +4）。

### 第二片：配对落盘与"有人在等"的判定（同样不碰设备）

`app/.../platform/PcPairing.kt`：`PcStore`（`filesDir/pc-link.json`）+ `PcWatch`（待批差集）。

- **token 绝不明文落盘**：加密失败就**拒绝保存**并说清原因（同一策略见
  `agent/mcp/McpServerStore.kt` 的 `encryptValue`，那儿的注释写着"绝不能返回空串冒充成功"）。
  地址在存之前先过 `pcNormalizeBase` —— 打错的地址不配被存下来变成常驻的困惑。
- **解不开就当没配对**：换机恢复 / 清除 Keystore 之后文件还在、token 解不开。这时候留着一条
  点什么都 401 的"假配对"，比老实说"重新配一次"更坏。
- `PcWatch` 钉的是后台轮询特有的两个坑：**同一批待批反复弹**（人关不掉）与
  **批完了通知还挂着**（人以为电脑仍在等他）。手机网页端撞不到这两个，因为它是"页面开着才刷新"。
- 测试 +8（`PcPairingTest`）。翻转验过：`enc()` 改成原样返回 → "明文不许落盘"与"Keystore 坏了该拒绝"
  两条红；`PcWatch` 改成每次都响 → 两条差集判据红。
- 一处假货差点骗过自己：`FakePcCipher` 第一版用 `android.util.Base64`，而单测里
  `isReturnDefaultValues = true` 会让它**静默返回 null** ⇒ "加密失败该拒绝落盘"那条测试
  一直在测一个假故障。换成 `java.util.Base64` 才是真的可逆假货。

### 第三片：配对界面 + 待批通知（真机跑通了，用户当场授权动真机）

`PcWatchdog`（轮询 + 通知 + 差集）+ `PcActionReceiver`（通知上三个决定按钮的落点）
+ `ui/settings/PcLinkScreen.kt`（设置 → 电脑联动），入口在设置页多一行、深链 `haoai://debug/pc`。

**真机端到端（MEIZU 20 Pro，Android 16 / SDK 36）**。两端网络要记下来，不然"连上了"这句话没上下文：
**手机走 WiFi 192.168.1.105，PC 走同一网段的 192.168.1.37:8720，安装走无线 adb（同一 WiFi），
全程不碰手机流量。** 跑通的一整条：

1. PC 生成六位配对码 → 手机页面上填地址与码 → PC 的设备列表出现 `MEIZU 20 Pro`；
2. PC 起一个 ask 档任务（假网关 `approve` 剧本）→ 审批卡挂住 → **20 秒内锁屏上出现通知**：
   「电脑上有 1 条在等你批 / 写入文件 theme.txt / 新建或覆盖，共 6 字符 /
   为什么算高危：新建文件，没动过别的东西」，下面挂着「允许一次 / 本任务都允许 / 拒绝」；
3. 点「允许一次」→ PC 工作区里真的长出 `theme.txt`（6 字节），回合继续跑到结束；
4. 下一轮轮询发现没有要批的了 → **通知自己收回**（`dumpsys notification` 里那条渠道计数归 0）。

**只有真机能发现的三个缺陷**（都在这一片里改掉了）：

1. **「解除配对」原来只清本地** —— 手机上变"没配对"了，电脑上那台设备还留在列表里，
   成了一条拿不到 token 的僵尸配对。改成**两边都忘**：先 `POST /lan/unpair`，
   连不上也照样清本地并说明（人要走不能被他拦在门外）。这条是点完按钮回头 curl 设备列表才发现的。
2. **成功消息用了 `color = error`** —— 截图上"配上了：MEIZU 20 Pro"是红的。按事实着色。
3. **一句话说了两遍** —— "已解除这台设备的配对：已解除这台设备的配对"（我冠的前缀 + 电脑回的那句）。

**顺手撞到的一个坐标坑**：界面上多出一行提示之后，下面那排按钮整体下移约 80px，
按上一次 dump 的坐标点就点到别的按钮上（我这一趟"解除配对"其实点成了"现在问一次"）。
⇒ 真机点按之前**每次重新 dump 一遍 bounds**，别复用上一屏的坐标。

**没修的一个缺口（记进 ROADMAP）**：电脑端的 `pendingJson` 只镜像**审批**，
`ask_user` 那种提问（"要绿色还是蓝色主题？"）没进这一份，所以手机上答不了提问 ——
网页端同样不能。要补的是把提问一起镜像 + 通知上挂选项按钮，改动跨两端。

**未验证（不写"通过"）**：这一趟是在**保活服务活着**的状态下验的。
"灭屏半小时、被 Flyme 省电策略回收之后还能不能收到"没测 —— 那需要一段刻意安排的等待，
下次单独跑，跑之前这条通道就该被描述成"服务活着时收得到"。


### 第四片：电脑上的一句提问也送到手机上（跨两端，v0.74.0）

上一片留下的那个缺口补上了：`ask_user` 现在和审批一样会出现在手机"在等人点"的那一份里。
一条链路动了五处：

| 位置 | 改动 |
| --- | --- |
| `Server.kt` `pendingJson()` | 待决列表不再只挑 approval：`Waiter("ask", …)` 归一成 `{title, options:[…]}`，与审批共用同一组字段 |
| `Lan.kt` `decide` | 多收一个 `answer` 字段，但**只对"这条是提问"时生效**（放开给审批，就等于随便写句什么都能替人放行） |
| `LanHost.pendingKind(id)` | 新增：局域网那一面从此分得清这条是审批还是提问 |
| `phone.html` | 提问是一张带 ❓ 的卡：问题原文 + 最多三个选项按钮 + 一个"自己写一句"的输入框（输入框一直在，不留"点了没地方填"的假出口） |
| `PcLink.kt` / `PcWatchdog.kt` | 安卓那侧读同一组字段；通知标题改成「电脑上有话要问你」，选项直接挂成通知按钮 |

**这一批抓出来的四个缺陷，有三个是"判据全绿"抓不到的**：

1. **手搓字符串拼 JSON，多写了一个 `}`。** 症状是手机待批页一片空白，看着像界面坏了；
   真相是 `/lan/pending` 回的整份 JSON 括号不配对，`r.json()` 当场抛，
   而那句错误提示只在角落闪 9 秒。`LanTest` 那几条全绿 —— 因为它断的是**假 host 手写的那份字符串**：
   桩是对的、产品是错的，两边一起把缺陷盖住。修了三层：
   ① 拼装改用手感啰嗦但**结构上不可能不配对**的 `buildJsonObject`，并挪成顶层函数 `lanPendingRow`；
   ② **桩不许再替产品重写一遍它要产的东西** —— `FakeHost.pendingJson()` 改成调那个真函数，
   于是路由测试顺带就在测产品的 JSON 了；
   ③ 界面上"读不到"与"真的没有"从此是两句话（`fail()` 把原因写进待批页），
   剧本里也多了一条 `rawParses`：先证明这份 JSON 能被 parse，再谈卡渲染得对不对。
   反向验过：把那个 `}` 塞回去，`LanTest` 当场 `24 tests completed, 2 failed`。
2. **答完提问，手机上浮出来的话是「已按你的决定放行：绿色」。** 这条**只有真看像素才会发现** ——
   按钮点到了、回合往前走了，判据全绿。但"放行"是审批的词，
   答一句选择题不等于替某个操作开了绿灯。改成 `decideNote(isAsk, value)` 两种说法，
   并且**这句回话也改由产品函数生成**（措辞从此落在测试覆盖里），剧本钉一条 `noteSaysAnswer`。
3. **`haoai lan on --port 8720`（空格写法）根本没生效。** 参数解析只认 `--port=8720`，
   值没被当成值，端口静默落回默认。而 `ui-shot.sh` 是"每轮现挑一个空端口"再传给它 ——
   挑到 8721 的那一轮，手机页连的还是 8720，那上面可能是**另一台 haoai**（这台机器的端口是共用的），
   配对码与待批列表全对错还一路绿灯。实测：修完后 `lan on --port 8897` 落盘的 `lan.json` 里是 `port:8897`。
4. **僵尸扫描器漏了假网关。** `ui-shot.sh` 只在 EXIT trap 里 kill 它，而 trap 在进程被 SIGKILL 时不跑
   （上一轮我停审计就是 SIGKILL），于是十几个 `mock-openai.py` 挂着不放端口。
   `kill-stale-shot.ps1` 加了一条按**脚本路径**匹配的规则（不按 `python.exe` 名字乱杀，别的 agent 也在跑 python），
   并让它报出每类清扫了几条；实测起一个再扫，端口从 1 个监听回到 0 个。

顺带把**通知上那个「打开写回答」按钮**改了：`PcLinkScreen` 只有配对与状态，没有回答输入框，
按钮写着"写回答"而打开的页面写不了，那就是个假出口。现在它开的是电脑上那个手机网页端
（这一批刚用像素证明过那里真能写），标签改成「在网页上答」。

**"回合往前走了"不能当"回答送到了"的凭据**：空回答在服务端会被换成
「用户未给出有效回答…」，回合照样跑到下一步审批。所以剧本最后一步跳回桌面页，
在**落库的消息**里找一条内容正好是「用户选择了：绿色」的 `tool` 结果
（B22 起结果带前缀，与手机端同一把尺子 —— 界面回显已答卡按前缀解析；
在此之前是裸原文「绿色」）。中间任何一环断掉
（按钮 → `answer` 字段 → `fut.complete` → 引擎）这一条都会红。

**验收**：`gradle test` **409 条全绿**（`LanTest` 25 条，这批新增 2 条：产品真身拼的行要能被 JSON.parse、
回话措辞要分得开提问与审批）；手机端 `gradle :app:testDebugUnitTest` **185 条全绿**；`node tools/ui-check.js` 全绿。
像素两份 —— 一份新的，一份旧的（旧那份是回归护栏：审批那条路不许被这批改动弄坏）：

```
SHOT_MODE=ask SHOT_PERM=ask PRE_LAN=1 SHOT_MOBILE=1 SHOT_W=390 SHOT_H=844 bash pc/tools/ui-shot.sh pc/tools/steps/ui-phoneask.json
SHOT_MODE=approve SHOT_PERM=ask PRE_LAN=1 SHOT_MOBILE=1 SHOT_W=390 SHOT_H=844 bash pc/tools/ui-shot.sh pc/tools/steps/ui-phone.json
```

前者 23 步 / 24 条判据全过：提问挂上 → 跳到局域网口配对 → 待批页一张 ❓ 卡（选项、输入框、徽章 1、
390px 下不横向溢出）→ 点「绿色」→ 回话说的是"送回电脑" → 列表里换成审批卡且提问销了号 →
批掉 → 列表清空 → 回桌面查 tool 结果原文。后者 46 步 / 32 条判据全过。
截图 `pa01-ask-card.png`（两个选项并排、输入框整宽）、`pa02-then-approval.png`。

**量具自己还有一笔账**：`ui-phone.json` 的跑法在 README 里原本写成 `tools/steps/…`，
而 `audit-steps.sh` 只认 `pc/tools/steps/…` —— 于是**手机联动这两份剧本从来没进过全量审计**。
上面两条命令按能被抓到的写法记，下次全量审计会带上它们（剧本数从 41 份变 43 份）。

**未验证（不写"通过"）**：安卓那侧的提问通知（选项挂成通知按钮、「在网页上答」）只过了编译与 JVM 单测，
**没在真机上看过**；真机那一页欠的还是上一片那条"灭屏半小时、被省电策略回收之后还收得到吗"。


## 这一轮：三十一份像素剧本一条判据都没有（2026-09-29 凌晨，不动产品代码）

给 `audit-steps.sh` 加上"判据数为 0 单独标红"之后跑了一遍全量：**43 份剧本，0 个真故障，31 份 RED** ——
而这 31 份的红不是产品坏了，是它们**只把 JSON 打印给人读，一条 `must` 都没有**。
也就是说：界面少了按钮、卡不再重画、文件没落盘，这些剧本照样"绿"，
过去几个月里它们只在被人盯着日志看的那几次才真的起了作用。

先做两件防它继续长的事：

1. **`ui-check.js` 加棘轮**：`ZERO_GATE_CEILING` 记着"零判据剧本的上限"，只许降不许升，
   每补完一份就把数字改小。跑一秒就出结果，不用等 55 分钟的浏览器审计。
2. **JSON 坏掉的报错要说清怎么坏的**：补判据时我自己连踩两次 ——
   note 里用 ASCII 双引号会把整行 JSON 截断、正则写 `\s` 在 JSON 字符串里是非法转义（要 `\\s`）。
   原来只回一句 `Expected ',' or '}'`，指不到是这两种。现在直接把两条规矩印在报错后面。

已经补掉的两份（都是"最近还在改、最容易回归"的面）：

- **`ui-ask` 0 → 18 条**：提问卡只有选项键号 1/2、**不该有那三个决定按钮**；
  答完之后下一步审批要自己冒出来（这条是因果点：答错了就不会有审批卡）；
  审批卡四个决定一个都不能少且每颗上面写着 y/a/r/n；批完不许留挂着的卡；决定要留在卡上。
- **`ui-askkeys` 0 → 23 条**：两道"输入框里打字不误伤"的闸门（提问卡按 2、审批卡按 y，
  框里有字时**什么都不许发生**），空框时才真的算回答，
  而且判据是「那一行写着蓝色」不是「有一行字」—— 按错键号也会留下一行字，留的是另一个答案；
  最后 `noteKept` 钉"允许一次"落在**落库的工具回执**里，不是只在界面上闪一下。

**补判据时反复用到的一个形状**：期望值是 `0` 的东西不能直接 `must`（`must` 判的是"真值"，
`0` 永远算失败），要在 eval 里自己算成布尔再交出去 —— `untouched:done==0`、`allCleared:cardsLeft==0`。
`mustMiss` 也接不住"返回一个裸字符串"的步（点号路径取不到键），那种步要么改成对象要么别 gate。

**验过的**：两份剧本各自按退出码重跑 —— `ui-ask` 21 步 / 18 条判据全过、
`ui-askkeys` 30 步 / 23 条判据全过；`node tools/ui-check.js` 全绿（棘轮降到 33）。
随后又补了 **`ui-mem` 0 → 11 条**（32 步全过）：两条路径要看得见、存完要说"多少字 + 何时生效"、
全局记忆要**从盘上读回**且落在 `MEMORY.md` 这个文件名上、技能存完列表正好一条且状态那句带"怎么用"、
`/` 菜单里要标出它是技能、点进去填进输入框的是**技能正文不是技能名**、删完列表要真的空掉。
再加 **`ui-git` 0 → 32 条**（28 步全过）：三条改动都要列出且**其中那条中文文件名不许丢**
（porcelain 的 `-z` 就是为了它）、diff 弹窗要有真实加减行、暂存只标那一条、
提交后界面说的数要和 `git status` 自己说的一致、空说明要挡住、
`../../outside.txt` 与 `--no-verify` 两种注入都要挡、
最后确认这一轮打的确实是**这次起的那个服务**（端口串台时数据看着全对，其实来自另一台 haoai）。

欠的是剩下 31 份，按"最近改过的面优先"继续补。


## 这一批：主模型被限流时换下一个（v0.75.0，补 PC 侧缺的那一半）

移动端早就有 `FallbackClient`（`AppContainer.kt:80-94`：单 provider 重试耗尽才切备用，
`fallback_chain` 配在设置里，UI 显示「已降级到 X」），**PC 侧一直没有**——只有 3/8/20 秒三档退避，
退完直接报错给人看。这在过去不算问题，因为报错是给人看的；现在定时任务与任务链都以 auto 档在夜里跑，
撞上一次 429 或"这个模型没余额"，就是整条链停在那儿等一个不在电脑前的人。

设置里多一项 `fallback`：逗号/分号/换行分隔，每项写 `模型名`（同网关）或 `模型名@https://别处/v1`（换网关）。
CLI `haoai set fallback=…`、设置抽屉一个输入框，两边都能改。

**三条刻意的边界，每条都有测试钉着**：

1. **只吃可重试的错**。400 是"请求本身不合法"，换模型也没用 —— 换了只会把同一个错误重复三遍，
   还让人以为"降级过所以没事"。
2. **换网关时不把主网关的 key 带过去**，除非两台是同一个主机（协议/主机/端口三项都比）。
   备用地址是用户自己填的，可能是本机 llama-server，也可能是陌生主机；
   为了"别断"而把 sensenova 的 key 发给陌生主机，是拿凭据换一个便利性。
   这条不是写在注释里的承诺：测试起了两个本地假网关，把对方看到的 `Authorization` 头原样抓下来对。
3. **账本按实际用的那一档记**（`modelNow`），不按设置里那个：降级发生过，数据里要看得见。

同档重试的间隔与"下一档怎么造"做成了构造参数（默认就是产品那套），因为**降级这条路径没法用真网关测**：
要等 31 秒退避，还要有一个真会 429 的模型。测试里换成 1ms 与假客户端，产品行为一行没改。

**验收**：`gradle test` **417 条全绿**（新增 `FallbackTest` 8 条：限流后换档并跑完 / 400 不换 /
链到头收口 / 没配链只试一次 / 换设置时链重建 / 三种写法都能解析 / 同主机判定 / key 不跨主机）。
变异验过：把降级分支写成永不触发 + 把 key 改成无条件带出去 ⇒ 同时 3 条红。

**这条批最该记的一件事：像素剧本抓出一个先前就存在的缺陷。**
`ui-fallback.json` 里"界面上要看得见『已降级』"那条判据是红的。先分清是谁的锅：
用 curl 打时间戳读 SSE，三条提示分别在 +3.1s / +11.1s / +31.1s 正常推出（服务端没问题），
而浏览器里 `.notice` 全程为 0 —— 同一时刻的 `delta`/`answer` 却渲染得好好的。
按仓库那条老教训（**配置类信息必须落库，不能只发 SSE 瞬时事件**），这不是选择器写错了，
是这条提示压根没进可重画的状态里（`runs.jsonl` + `/api/state` 才是它该待的地方）。
已立 `#111`。

**补记（同日，v0.76.0 之后）**：`#111` 查了一半 —— 顶栏那颗模型芯片改成**状态驱动**了：
`/api/state` 多报一个 `modelNow`，降级之后芯片显示实际在答的模型并带「（降级）」，
hover 写清「设置里是 X，这一条由 Y 回答」。原来它一直显示设置里那个模型，
而那句降级提示是 SSE 瞬时事件、跑完 hydrate 就没了 —— 回看界面只会以为"这句是主模型答的"。
判据：刷新一次之后芯片还写着降级（剧本第 8-10 步）。
剩下那半条还开着：**长回合进行中引擎发的 `notice` 在浏览器里渲染不出来**（服务端推出是量过的）。
剧本里那一步换成了"此刻还在跑 = 回合没被 429 打断"，替换理由写在那步的 note 里 ——
不是把判据写松，是要求已由更强的机制满足；留一条永远红的判据只会让整套审计变噪音。

两份剧本现在都进全量审计了：

```
SHOT_MODE=fallback SHOT_PERM=auto PRE_SET="model=primary-429 fallback=backup-ok" bash pc/tools/ui-shot.sh pc/tools/steps/ui-fallback.json
SHOT_MODE=outside SHOT_PERM=auto bash pc/tools/ui-shot.sh pc/tools/steps/ui-outside.json
```

前者 12 步 / 7 条判据全过，后者 5 步 / 5 条判据全过。
能验的部分先验了：剧本第 5 步（回答正文里带着「这句是备胎模型『backup-ok』答的」）与
第 7 步（回合真的收口）都是绿的，`ui-check.js` 也全绿。

顺带补了 `ui-shot.sh` 的 `PRE_SET="k=v …"`：有的链路要先多写几项设置才验得到（降级链就是），
仍然走同一个 CLI，不手改 `settings.json`。


## 这一批：沙箱第一层——自动档不再替人往工作区外写（v0.76.0）

`outside()` 从 v0.59 就在参与风险打分，"写到工作区之外"算高危、auto 档也不跳高危。
但那等于**夜里白等五分钟再自动拒**：定时任务与任务链都以 auto 档跑，没人看卡，
300 秒之后模型收到的那句是"超时未答，按拒绝处理"—— 分不清是边界问题还是网关问题，
于是它换个写法再试一次。这一批把它改成**当场拒 + 说清为什么 + 说清怎么显式允许**。

新开关 `HaoFlag.OUTSIDE_WRITE`（`outside_write`，默认关）在设置页「实验特性」里自动出现，
命令行 `haoai flags on outside_write` 也能开。**开了只摘掉"在外面"这一项风险**：
强推、删文件、敏感路径（`.ssh`、`System32`…）照样拦 ——
开关的名字只承诺"允许写到外面"，不能变成"关掉整个审批"的别名（`OutsideWriteTest` 有两条钉这个）。
`ask` 档**一行没动**：人在电脑前，弹卡让他点是正确的。

**这一批被测试和像素各逼回来一次，两次都是我的判断错了：**

1. **第一版按 `kind == "write"` 划范围，把 media/record 的导出也挡了。** 全量跑直接红：
   `MediaTest` 里那条 `files outside the workspace are not offered to the page` 钉的是既有行为 ——
   "素材库在 D:\" 是常态，视频自动化第一条就要往工作区外导产物。
   ⇒ 收窄成只管 `write`/`edit`（"按模型给的路径落文件"那两个），并补一条测试把 media 这条路钉住，
   免得下次"顺手收紧"再犯。**边界要挡的是改坏别人的文件，不是生成一个大文件。**
   我在动手前曾判断"内部临时写不走 guard，风险很低"——那是没核对过的猜测，是整套测试替我把它兜住了。
2. **拒绝文案的顺序被截图逼着改了两次。** 工具结果那一行只看得见前约九十个字符：
   第一版把绝对路径写在第二句，界面上"怎么办"整段被截没；第二版把命令名往后挪，
   结果只剩 `haoai flags on outside_w`。⇒ 定成**结论 → 怎么办 → 哪个文件**，
   路径用模型自己写的那个相对路径，也不用 `**加粗**`（这一行按纯文本渲染，星号会原样显示）。
   判据读的是渲染出来的文本，不是函数返回值 —— 这条只有真看像素才会发现。

顺带修了量具自己一个坑：`ui-shot.sh` 现在会先比源码与已安装二进制的**时间戳**，
源码更新就直接停。起因是我改完 `Tools.kt` 只跑了 `test` 忘了 `installDist`，
于是像素验的是 0.74.0 的旧程序，判据红成"新代码没生效"——和"BUILD SUCCESSFUL in 1s 可能什么都没编"同源。

**验收**：`gradle test` **425 条全绿**（新增 `OutsideWriteTest` 8 条：auto 当场拒不弹卡 /
开关真能放行 / 开关只放行"在外面"这一项 / ask 档仍弹卡且人拒了算拒 / 工作区内的普通写不受影响 /
media 导出不被这条挡 / shell 不在这层范围内（如实写出，不当已隔离吹））。
变异验过：把拒绝条件改成永不触发 ⇒ 相关测试红。
像素 `SHOT_MODE=outside SHOT_PERM=auto bash pc/tools/ui-shot.sh pc/tools/steps/ui-outside.json`
5 步 / 5 条判据全过，截图 `o01-outside-refused.png` 里那行字是完整的。

**还欠一条（已立 `#112`）**：`media`/`record` 在 **auto 档**导出到工作区外，仍然会弹卡等 300 秒 ——
这是 v0.59 就有的行为，本批没动它。夜里跑"剪一段视频导到 D:\ 工程目录"这种链，
会白等五分钟然后自动拒。该不该也给它一个"新建产物不弹卡、覆盖已有文件才弹"的规则，
要单独想清楚再改，不顺手带上。


### 第五片：定时任务跑完之后，结果主动到手机上（B13 的余款）

`Digest` 的三个出口（桌面定时页 / 导出 md / 手机「结果」页签）都是**人主动去看**。
这条改成"跑完了送到跟前"：`PcWatchdog` 每 20 秒那一轮里顺手问一次 `/lan/digest`，
有新跑完的就发一条通知，正文里直接带 `哪一轮 · 标题 / 结论 / 正文第一行`。
以前做不了是因为 B4 安卓那半边还没有；现在通道齐了，剩下的全是判重问题。

**四个刻意的取舍**（`PcDigestWatch`，6 条 JVM 测试逐条钉住）：

1. **第一次看到的一律不弹。** 手机上刚配好对时电脑上可能攒着三十条历史结果，
   没有这道闸，装上应用那一刻就会逐条震 —— 人只会把通知权限整个关掉，那等于这条功能不存在。
2. **没有稳定主键（`t`）的一律不弹。** 判重全靠那一轮的开始时刻；缺了它就每 20 秒重弹一次。
   宁可不提醒，也不能震到人关权限。
3. **走另一条通知渠道**（`haoai_pc_done`，`IMPORTANCE_DEFAULT`）：跑完一条简报不该盖过"在等你决定"那条。
   而且它是"告知"不是"在等"，所以 `setAutoCancel(true)`，划过就没，不像待批那条要一直挂着。
4. **点开之后打开电脑上那个手机网页端**（那里有「结果」页签，v0.57 像素验过），
   不是应用内 —— `PcLinkScreen` 现在没有结果列表，按钮指一个没内容的屏就是假出口。

**跨端契约钉在 PC 这边**：`LanTest` 新增一条 —— 真往 `RunLedger` 里写一轮，
`Digest.json()` 的每条都必须带正数的 `t` 与非空标题。
那是**另一端**用来判重的键，PC 改个字段名手机不会红，漏报或重弹要等到夜里真跑一轮才发现。

**验收**：`gradle :app:testDebugUnitTest` **191 条全绿**（新增 `PcDigestWatchTest` 6 条）、
`gradle test` **426 条全绿**（新增契约测试 1 条）。
变异验过：把"第一次只登记不弹"那道闸去掉 ⇒ 对应测试红。

**未验证（不写"通过"）**：这条通道的通知**没在真机上看过** —— 需要设备，
和「灭屏半小时还收得到吗」「提问通知在锁屏上长什么样」是同一趟。
现在能说的是：判重逻辑与契约有测试钉着，端上发通知的那段代码与已验过的待批通知同一套写法。


## 这一批：一次改动分成几块，人可以只要其中几块（v0.77.0，B5 的余款）

原来一条 `write` 改了三处，人只能整条批或整条拒 —— 两条都是亏的：忍着放行就得回头自己把那一处改回来，
全拒则连对的两处也要让模型重跑一遍。现在审批卡上把改动切成块，**勾掉的那块保持原样不写**。

切块用的是真的行级 LCS（`Hunks.of`），不是 `Diff.unified` 那份"公共前后缀之外整段算一坨"的近似 ——
后者在"一篇文章里改三处"这种最常见的情况下只会给一个块，而那正是要拆的东西。
中间段超过 1e6 格（约 1000×1000 行）就退回整段一块，人不会为了逐块在卡片前多等十几秒。

三条"什么时候不摆勾选框"是刻意的，不是没做完：

1. **只有一块**（新建文件、单处编辑）：勾选框只会让人多点一下，而它能说的话"拒绝"按钮已经说了；
2. **超过 12 块**：卡片变成一屏滚屏，逐块反而比整条更难读，也更难看出漏勾了哪块；
3. **CLI、定时任务、手机上点的**：那些场景没有"人在电脑前挑"这回事，闸口拿不到勾选就整条应用 ——
   与逐块功能出现之前逐字节一致。手机卡片上会写明「这次改动分成 3 处，手机上只有整条按钮」，
   但**不发 diff 正文**：几十行块跟着 4 秒一次的轮询跑只是白占流量。

三件事是这套东西的安全底线，每条都有测试钉着（`HunkTest` 25 条）：

- **全勾 == 整条放行**：`content()` 在全勾时直接返回模型给的那份，逐字节相同（连结尾换行的习惯都跟着它）。
  这条必须显式短路 —— 逐块合并是按行重拼的，"旧文件结尾有换行、模型给的那份没有"这种差异一重拼就会错。
- **退掉的块一个字节都不写**，其余块的位置不因为退了一块而漂移；CRLF 文件合并之后仍然是 CRLF
  （`Hunks` 自己拆行，把 `\r` 留在行里，不用 `String.lines()` —— 它会把结尾换行拆成一个空尾项，
  合回去就凭空多一行空行。这个坑先是把**测试夹具**弄错了，才反过来暴露了实现里同样的做法）。
- **卡片挂着等人看的几分钟里文件被改过 ⇒ 整条不写**，并回一句"重新 read 再重提"。
  按旧基线算出来的块往哪里落都是猜，而猜错的代价是盖掉别人的改动 —— 那种写坏的现场回不来。

**模型侧必须知道这是部分接受**，否则它以为整条都过了，下一轮就基于一个不存在的世界继续改。
所以工具结果第一句就是「已部分写入 notes.md：3 块里退了 1 块，第 2 块（旧第 9 行起，−1 / +1）保持原样」，
后面才是要它重读的话。界面上同一件事在三个地方说话：工具结果、卡片收起那行「允许一次：接受 2 块，退回 1 块」、
卡头那枚结论标（刷新之后只剩后者）。

顺手修掉两处一直在那里、只是这次才扎眼的东西：

- **diff 统计不再虚报**：改两处各一行，原来卡上写「−15 行 / +15 行」（中间 13 行一个字节没动），
  现在按块报「−2 行 / +2 行（2 处）」。人刚退回一块就看到一个 15，会怀疑自己刚才点了什么。
- **手机上点「拒绝」，回执原来写「已按你的决定放行：deny」** —— 动词反了还把内部码露给人看。
  现在拒绝就说"已拒绝，这条不会执行"，三个放行决定也各自有中文名。
- **diff 里「删行红」其实从来没红过**：`.dif .dm{color:var(--bad)}` 用的 `--bad` **两套主题里都没定义过**，
  未定义的 `var()` 让整条声明失效，字色一路继承正文（只有背景那层淡红骗过了眼睛）。
  这是给 `ui-review` 补判据时按 rgb 分量量出来的 —— 深色 `242,125,114`、浅色 `179,53,42`，两套主题各一条，
  现在钉在剧本里。同一个 `.atchip em:hover`、`.qchip em:hover` 也一直没生效过。

**验收**：`gradle test` **458 条全绿**（新增 `HunkTest` 25 条：切块/合并/逐字节回环/CRLF/工具两侧/协议两侧；#111 加 3 条、#112 加 4 条）。
像素剧本 `pc/tools/steps/ui-hunk.json` **17 步 / 45 条判据全过**，
命令：`SHOT_MODE=hunk SHOT_PERM=ask bash pc/tools/ui-shot.sh pc/tools/steps/ui-hunk.json`。
最后那张图读的是**模型自己 `read` 回来的那 20 行**：`2: X02`、`9: L09`、`16: X16` ——
被退的那块保持原样这件事，只有盘上的字节能证明，卡片措辞与 SSE 事件都由同一个函数生成，它们一致不算。
变异验过：把 `HunkPlan.content()` 改成"忽略勾选、永远写整份"，判据红 4 处
（`block2keptOriginal`、`block2NotWritten`、`showsL09`、`noX09`），而界面那 41 条**全绿** ——
说明真在管事的正是那几条读盘上的，其余只证明"卡片长对了样子"。

这一批的量具自己也被修了三处，都值得记着：

- **`click("[data-dec=allow_once]")` 命中的是第一张已经答完的卡**。同一页里可以同时挂着好几张审批卡，
  选择器必须自己带上 `.card.ask:not(.done)`。症状是"逐块完全没生效"（判据全 false），
  而实际上界面全对 —— 点击根本没送到。
- **读的是 `textContent` 不是 `innerText`**：答完的卡会自己收起，`innerText` 把收起的内容整段跳过，
  于是"卡片里那句话"永远量不到。同一个字符串，innerText 全 false、textContent 全 true。
- **两条反向判据要有正向证人**：`block2NotWritten`、`rowsStill20` 都是"不存在某行"，
  读不到正文时它们会一起真空为真。加了 `sawNumbered` 之后它们才有意义。
- 另外 `#stream` 上有 `scroll-behavior:smooth`，设完 `scrollTop` 同一拍读回来还是 0 ——
  截图脚本要 `scrollTo({behavior:'instant'})`，否则会红成"滚动没生效"。

顺手把 `ui-review`（diff 审阅视图那份）从"零判据"补到 **20 步 / 16 条判据**，`ui-check.js` 的棘轮
`ZERO_GATE_CEILING` 从 31 降到 **30**。它原来那句 `querySelector('.dstat')` 查的是一个产品里已经不存在的
class（全仓 0 命中）—— 没有断言，所以它一直绿着，谁也不知道它什么都没看。上面那条 `--bad` 就是这份补判据时掉出来的。

**未做**：`edit` 的逐块只在"一次调用命中多处"时才出现（`all=true`），单处替换本来就是一块；
IDE 那种"在编辑器里逐行点接受"（#5 的方案 B）不是这一批，网页壳里的卡片已经是它的后端能力。


## 这一批：引擎那几句小字不再"闪一下就没了"（v0.77.0 的下半段，#111）

`#111` 记的症状是"降级提示 SSE 发了、DOM 里没有"。这次按"先量再改"的规矩做了一次三读数诊断
（同一次轮询里同时读：第二条 EventSource 收到的帧数、`MutationObserver` 看到的 `.notice` 增删、
此刻 DOM 里还有几条），一次就把嫌疑范围收干净了：

| 读数 | 结果 | 说明什么 |
|---|---|---|
| 浏览器收到的 notice 帧 | 4 | 服务端到浏览器这一段是好的 |
| `.notice` 插入 / 移除 | 4 / **4** | 应用**确实**画出来了，画在正确的会话容器里，然后全被抹掉 |
| 回合结束后 DOM 里还剩几条 | 0 | 抹掉它的就是回合收尾那一下 |

所以原来那句"浏览器里全程为 0"是量具读晚了 —— 实时是好的，坏的是**收尾时 `hydrate` 执行
`v.el.innerHTML=''` 重建整屏，瞬时节点跟着一起没**。症状从"看不见"变成"看完答案之后查不出来"，
而后者才是要命的那种：人回头核对"这句是谁答的"时，界面上只剩顶栏那颗芯片。

修法照的是这个仓库已经用过两次的路子（审批结论 `Msg.note`、行级 `Msg.diff`）：
**瞬时事件要么落库，要么就是缺陷**。新增 `Msg.notice` —— 引擎发的小字挂在紧接着落的那条消息上，
跟着会话文件走，回放时重新画出来；循环之外发的那句（"已达单轮工具调用上限"、"已按你的要求中断"）
在收尾时贴到最后一条 assistant 上。三条硬规矩：

- **只给界面，不发模型**（测试直接断言两次请求里都没有"已降级/网关抖动"这几个字）；
- 落库与恢复同一条测试钉住（重建引擎之后 `notice` 字段还得是同一句）；
- 一条 assistant 都没有时**不硬造**消息 —— 宁可少一句历史，也不往历史里塞没有作者的行。

**验收**：`gradle test` 全绿（`FallbackTest` 新增 2 条、`EngineFlowTest` 新增 1 条）。
`ui-fallback.json` 从 8 条判据变 **9 条**，其中两条是这一批正名回来的：
`noticeLive`（跑的过程中就看得见 —— 这条原来因为 #111 被换成只判 `stillRunning`，现在恢复成它本来想判的样子）
与 `noticeBack`（**刷新之后还在** —— #111 的修复判据）。
截图 `fb02-fallback-after-reload.png` 里三句"网关抖动"+ 一句"已降级到「backup-ok」"整整齐齐排在答案上面，
顶栏芯片写着 `backup-ok（降级）`。

诊断脚本用完移进回收站了 —— 它没有判据，留在 `steps/` 里会被棘轮当成"又一份只打印不断言的剧本"
（事实上 `ui-check.js` 当场就把它拦下来了，这正是那个棘轮存在的理由）。

**欠着**：三条"网关抖动（HTTP 429 {…原始 JSON…}）"叠在一起是一堵黄墙，
把退避过程逐条铺开对排查有用、对阅读有害；要收成一行"重试 3 次都没成"需要能替换已发的那条，
那是事件模型的事（记在 ROADMAP 5.1）。


## 这一批：把产物导到工作区外面，夜里不该白等五分钟（v0.77.0 的收尾，#112）

v0.76 做沙箱第一层时，`write`/`edit` 改成"auto 档当场拒 + 说清怎么显式允许"，
但 `media` 那条留了一句"以后再说"。留着的结果不是"少了一层保护"，而是一个具体的坏：

定时任务与任务链都以 auto 档跑，而素材库、成片目录在 `D:\` 是常态 —— 导一段字幕到工作区外面，
`outsideWorkspace` 一律判高危，auto 不跳高危 ⇒ 弹一张没人看的卡 ⇒ 300 秒之后模型收到
「超时未答，按拒绝处理」⇒ 它分不清是边界问题还是网关问题，于是换个写法再试一次。
**一条正常该成的导出，夜里就这么白等五分钟。**

分界线按后果画，不按工具名画：**动没动别人已有的文件**。

| 情形 | 分级 | auto 档 | 为什么 |
|---|---|---|---|
| `media` 导到外面，目标**不存在** | 中危 | 直接过 | 只是新建一个产物，谁的文件都没动 |
| `media` 导到外面，目标**已存在** | 高危 | 弹卡 | ffmpeg 会盖掉它，那才是"改坏别人的东西" |
| `write`/`edit` 到外面 | 高危 / 当场拒 | 当场拒 | v0.76 那条一行没改 |
| 任何工具写到敏感位置（`.ssh`、`system32`…） | 高危 | 弹卡 | 排在前面，先命中 |

`ask` 档一行没动：人在电脑前，弹卡让他点是正确的。

**验收**：`RiskTest` 新增 1 条（新建产物=中危 / 覆盖已有=高危 / `write` 不受影响），
`OutsideWriteTest` 从 1 条 media 测试拆成 4 条（auto 不弹卡、覆盖仍弹且仍高危、ask 仍弹、当场拒那条照旧）。
新剧本 `pc/tools/steps/ui-product.json` **4 步 / 4 条判据全过**，
命令：`SHOT_MODE=product bash pc/tools/ui-shot.sh pc/tools/steps/ui-product.json` ——
判据是"全程没出现过一张卡"**并且**"模型自己 read 回来看得见盘上的字节"：
只验前者会把"被静默挡掉"当成通过。量到的收口时间是 **502ms**（原来 300 秒）。
变异验过：把 `product` 那一句关掉，四条判据一起红（卡又弹出来了）。


## 这一批：把接口写成能被外部程序用的东西（v0.78.0，B11 的对外那一半 #107）

之前"外部程序怎么调 HaoAI"只有两个答案：README 里散着的几条 curl，和 `haoai task "…"` 那条 CLI。
CLI 够用，但它只能"发一句然后等全部跑完"——**结果在 SSE 里，审批卡也在那条流上**，
一个脚本要能跑完一条任务，就得既读流又能回答。这一批把这件事做完，并且让文档没法漂。

**`pc/docs/api.md`** —— 分三节，故意把"承诺稳定"的面收窄到 10 条：
`POST /api/task` 起任务（立刻返回 sid，**不返回结果**）、`GET /api/events` 读事件、
`GET /api/state` 轮询、`POST /api/decide` 回答审批与提问、`/api/stop`、`/api/mode`、
`/api/new`、`/api/sessions`、`/api/runs`、`/api/usage`。
剩下 60 多条是网页壳自己点的，全部列在第二节并写明"会随版本变，别写进你的脚本"。
第三节是跨端的 `/lan/*`（token 配对那套），第四节是客户端用法。

**`pc/tools/haoai-client.py`** —— 只用标准库，`task / state / sessions / runs / usage / stop` 六个子命令。
`task` 会跟着流打印，弹卡时在终端问一句（`y/a/r/n`），一次调用改多处时还会把每一块摆出来问"不要哪几块"
—— 逐块那套 `partial:101` 的位串在这里是通的。Ctrl+C 只断开客户端，不会停掉电脑上的任务（要停得说 `stop`，
这句话打在屏幕上，免得人以为按了 Ctrl+C 就安全了）。

**`ApiDocTest`：文档与路由表不许各走各的。** 从 `Server.kt`/`Lan.kt` 抓出真实存在的路由字面量，
两边都查：代码有的必须写在文档上，文档写的必须真在代码里，客户端调的必须是存在的端点，
外加一条"承诺稳定的那节不许超过 16 条"。加端点不写文档 = 测试红。
这条测试自己也被量具坑过两次，都写在注释里了：
Kotlin 的块注释**会嵌套**，注释里写一个"斜杠加星号"就把后面整个文件吞掉；
正则直接抓 `/api` 会把 `bash pc/tools/api-smoke.sh` 和 `HAOAI_HOME/apikey` 当成文档编出来的路径。

**`bash pc/tools/api-smoke.sh`** —— 端到端验"外部程序真的能用"：起假网关 + 真服务，
用客户端发一个会弹卡的任务，从管道喂一句 `y`，然后断言
"客户端收到了 approval" + "文件真的写出来了" + "running 收口了" + "运行账本里查得到这一条"。
文档与单测能保证形状对，"一边读流一边回答"这件事只有真跑一遍才算数。

**验收**：`gradle test` **463 条全绿**（新增 `ApiDocTest` 5 条）；`bash pc/tools/api-smoke.sh` 两条 ok；
`md-check` / `ui-check` 全过。顺手修掉一个客户端侧的真缺陷：Windows 上把 stdout 重定向到文件时
Python 按系统代码页（这台机器 cp936）编码，中文全成乱码 —— 现在客户端启动就把 stdout 钉成 UTF-8。

**未做**：SDK 封装只到"一个能跑的 Python 客户端"，没有 Node 那份；也没有 API key/token ——
`/api/*` 的鉴权模型仍然是"只听 127.0.0.1"，文档第 0 节把这句话写在了最前面，
包括"别把端口转出去"和"要给手机用就走 `/lan/*` 那套配对"。


## 这一批：技能订阅源，以及"装之前先看见它会用哪几把工具"（v0.79.0，#110）

"要不要做插件市场"这条在名单里被标成"不做"。核对过代码之后拆开了一半：**市场里真正缺的不是市场，
是"有个地方能列出来"和"装之前看得见这东西会干什么"**。账号、评分、付费、自动更新要的是运营，
那几样确实不做。用户裁定原话是"会，但要我先看过"——所以这一批的重心在后半句。

**`SkillFeeds`** —— 订阅源存在 `HAOAI_HOME/skill-feeds.json`，上限 12 条，只认 http/https
（与 `SkillDocs.importUrl` 同一条边界，不在第二个入口上重开一次口子）。
清单认两种形状：`{"skills":[{name,url,desc}]}` 与裸数组。刷新一条源会把**每一项的正文也取回来扫一遍**
（1+N 个请求，共用一个 `HttpClient`：每条新建一个会连带新建它自己的线程池，刷一次源把机器拖住的不是网络）。
条目上限 40，超出的丢掉而不是整源作废；**正文拉不到的那一项照样列出来**，只是带一句"没扫到：清单地址回了 404"——
静默丢掉一个条目，用户只会以为这个源上没有这个技能。

**`SkillPerms.scan`** —— 权限清单两个来源，分开标注：
① YAML 头里作者自己声明的 `tools:` / `allowed-tools:`（逗号、空格、`- 项` 三种写法都认；
只读工具也照实列，标「作者声明，只读」，因为那是作者主动说的）；
② 正文里**以代码形式**点名的危险工具（`` `write` ``、`shell(`、"用 `media` 拼接"）。
第 ② 类只报会动东西的那几把，理由是 `read`/`grep` 这种词在提示词里出现概率接近 100%，
给每个技能都盖一个"它要读文件"的章，这张清单看两次就没人看了。

**危险那一批不是手抄的表，是从工具表推的**：`builtinTools().filter { it.kind != "read" }` 再加 `task`
（它自己不落盘，但派生的子任务能用上全部工具）。手抄那张表漏一把就是长期假绿，
所以有一条测试专门盯着这件事——把 `danger` 换成一份手抄清单，那条测试立刻红。

**闸长在布局上，不在说明里**：`装` 这个按钮**只在展开某一项之后才出现**，展开块里是正文开头 + 免责句 + 按钮。
而且它是**按条目**的：点开一份不会把其余几份一起解锁（`gateIsPerEntry`）。
免责句 `SkillPerms.NOTE`「这份清单是扫出来的，不是沙箱保证」跟清单同屏出现——只有清单没有这句话，
界面就把"扫出来的"说成了"保证得了的"。

**来源落盘走 sidecar**：`skills/<slug>/.source.json`，不写进 `SKILL.md`。
那是用户会跟手机端对拷的文件，我的元数据不该混进去；手机端 `SkillStore` 只认 `SKILL.md`，多一个文件它直接无视。
技能列表里那一行现在显示「来自 http://…」，**单独一行且允许换行**。

**这一批自己抓出来的两个坑，都值得记着：**
1. 第一版把「来自 …」塞进标题行那个 `<em>` 里，而 `.rl span` 是 `nowrap + ellipsis`——
   名字和说明一长，来源在屏幕上就没了，**但 `textContent` 仍是全文，判据全绿、像素上什么都没有**。
   现在量的是盒子几何：`scrollWidth<=clientWidth` 且 `scrollHeight<=clientHeight`（占两行可以，剪掉不行）。
   把 `.src` 改回 nowrap 会让这条判据红——验过了。
2. `shot.js` 的动词链是 `if sleep → else if eval → …`，所以**一步里同时写 `eval` 和 `sleep`，
   只有 sleep 会执行**，而那一步照样被标成"断言"。四步"截图前先把目标滚进视野"因此全是空转，
   整轮 34 条判据全绿，截图里却根本没有被测的东西。拆成两步之后才看见真画面。

**验收**：`gradle test` **489 条全绿**（新增 `SkillFeedTest` 26 条：scheme 闸、两种清单形状、
非数组的 `skills` 字段、条目上限、拉不到仍列出、声明/推断/认不出三档标注、
"危险集来自工具表"、免责句不许被删、来源 round-trip 且正文逐字节不变、退订不删已装）；
`bash pc/tools/ui-shot.sh pc/tools/steps/ui-skillfeed.json` **29 步 / 34 条判据全过**，5 张图人眼看过；
`ui-check`（id 唯一、JS 可解析、零判据剧本棘轮）与 `md-check`、`api-smoke` 全过。
三处变异检查各打红一次：手抄 danger 表、"拉不到就跳过条目"、去掉 scheme 闸。

**未做，并且是刻意的**：没有签名校验、没有来源白名单、没有自动更新、没有后台拉取——
所以"看过清单再装"这件事**由界面保证，服务端不拦**（`feed-install` 不检查有没有人预览过；
加了状态就变成"换个入口绕过"，真要挡得两端都挡，那已经是签名校验的范围）。
预览与安装之间也没有"这份正文没变过"的比对：源可以在你看完之后换掉内容，这一版不防这个。
手机端没有订阅源，只有 PC 装完之后打 `/` 用。


## 这一批：双击不再闪退，打包件从"构建成功但跑不起来"里救出来（v0.79.0 收尾）

用户报的是"直接打开 `pc/tools/haoai.cmd` 会闪退"。量完之后是**两个各自独立的缺陷**，
而且第二个更严重：能双击的那个东西，从来就没真的能跑。

**① 不带参数 = 打一屏帮助然后 0 退出。** 双击 `.cmd` 或 `.exe` 就是不带参数，
而控制台窗口跟着进程一起关——看起来就是"闪退"。现在**不带参数直接起 `serve`**（并顺手用默认浏览器
打开 `http://127.0.0.1:8712/`），帮助没丢：`haoai help` / `-h` / 打错命令都还给。
开浏览器只在"不带参数"这条路上做：脚本与测试里 `serve` 也常跑，弹窗口是干扰；
`rundll32 url.dll,FileProtocolHandler` 而不是 `cmd /c start`，因为后者要过一次 shell 解析。
打不开就算了——上面那行网址本来就是给人复制的，不因为"自动打开失败"把服务判成没起来。

**② `packageExe` 打出来的 exe 是坏的，而构建一路 BUILD SUCCESSFUL。** 真跑一次才看见：

```
错误: 加载主类 com.haoai.pc.MainKt 时出现 LinkageError
  java.lang.UnsupportedClassVersionError: ... class file version 69.0,
  this version of the Java Runtime only recognizes class file versions up to 65.0
```

产物是 Java 25 的字节码，捆绑进 exe 的 runtime 却是 Java 21 —— 因为 `findJpackage()`
只搜 `%USERPROFILE%\.gradle\jdks` 与 `C:\Program Files\*`，机器上唯一命中那里的是
Gradle 自己拉的 JetBrains 21；真正在编译的那把 Temurin 25 装在 **`%LOCALAPPDATA%\Programs\`**
（用户级安装），旧逻辑根本没搜到那儿。三处一起改：

- **先问产物要 Java 几**：直接读 `MainKt.class` 头两个字节的 class 版本（69），
  不靠"我以为 Gradle 用哪个 JDK"——那个数这几天就变过；
- **按版本挑 jpackage**：候选顺序改成 PATH/JAVA_HOME 上那个 java 的 bin → `JPACKAGE` →
  含 `%LOCALAPPDATA%\Programs\*` 的各安装根；**没有一个够新就直接报错**，
  宁可不打包也不打出一个双击只会崩的 exe；
- **打完必须真跑一次产物**：`doLast` 里执行 `HaoAI-PC.exe help`，要求退 0 且输出里有 `HaoAI PC`。
  用 `help` 是特意挑的：打印完就退，不会顺手起一个服务占端口。

顺带两处同类的"两处各写一遍"：**版本号**从写死的 `0.64.0` 改成读 `Main.kt` 的 `PC_VERSION`
（现在 exe 属性里是 0.79.0，和界面顶栏一致）；**删旧产物**要先清只读位——jpackage 产出的
`HaoAI-PC.exe` 是 `r-xr-xr-x`，Windows 上 `deleteRecursively()` 对着只读文件就是删不掉，
而它报的错写着"可能 HaoAI-PC.exe 正在运行"，进程列表里根本没有，白查一轮。

**验收**：`gradle test` 仍 **489 条全绿**（这批没动引擎与界面，所以既没有新单测也没有新剧本——
改的是 `main()` 的默认分支与构建脚本，判据走的是构建期的那次真跑）；
`gradle packageExe` 连跑两次都过（第二次才真的踩到"删旧目录"那条路径），
产物 127MB、内嵌 runtime `JAVA_VERSION="25.0.2"`；
`HaoAI-PC.exe serve --port 8771` 起得来、`/` 回 200、横幅打印正确，测完按 PID 收掉；
不带参数的路径用 installDist 那份验过（8712 起服务、HTTP 200）。

**还没解决的那一件**：现在设置里 `base=http://127.0.0.1:8106/v1`、`model=mock`，
是某次测试留下的假网关——**双击能打开界面，但发一句话必报错**。
这台机器上现在唯一稳的模型来源是自己起一个 llama-server（`api.b.ai` 这一阵连不通，
8080 上那个 opencode 代理一发对话就 429）。改的是用户真实配置，所以没替他们动。


## 这一批：30 份零判据剧本全部补上判据（#99 清零，只补量具不动产品代码）

**要解决的问题**：`ui-check.js` 的棘轮 `ZERO_GATE_CEILING` 钉在 30 —— 全量 65 份像素剧本里有 30 份一条 `must` 都没有，
界面坏了、按钮没了、卡不重画了，它们照样"绿"。这是整个验收体系最大的一笔债（v0.67 那条恒 false 的判据就是从这类剧本里混过去的）。

**做法**：逐份读剧本，把"只打印不断言"的字段改成**布尔判据**，期望按用例声明——
"没按钮"可能是设计，所以判据不写"必须有按钮"，写这一步**本该发生什么**（例：删工具回复那步的判据是"要被 Engine 拒绝并给出理由"，
而不是"接口返回 200"）。不确定的先跑一遍拿实测值，再按实测修期望——凡与原假设不符的，以产品代码为准改判据、不改产品。

**结果**：65 份剧本零判据归零，`ZERO_GATE_CEILING` 降到 **0**；全量判据 **1166 条**（原 35 份有判据的剧本一条未动）。

**补的过程中抓出来的真问题**（打印从不判、所以一个都没暴露过）：

1. `ui-msgops`：单独删工具回复这条路，`Engine.deleteAt` 明确拒绝（"工具回复不能单独删"）——原剧本把响应 JSON 打出来就完了。
   现在把"必须被拒 + 理由里带工具回复"钉成判据：悄悄删掉会在历史里留下没有调用方的孤儿结果。
2. `ui-tools` 的 `switched` **恒 false**：记录 `curId` 的那步排在点「新任务」之后，切完才记，之后再也没人切——判据写出来才看见。
   改成先记再点（note 里写明原顺序为什么永远等不出变化）。
3. `ui-compact` 想拿 `#cmpState` 当判据，而它**收尾即清空**（成功失败都置空，"压缩中…"只在途中）——恒空的字段当判据永远红。
   改判会话里那行"已压缩并摘要 N 条"的 notice。
4. `ui-ws` 原来量 `.sess .it.on`，而行上的类是 `cur`——这个字段恒 0 也从没人看见；"当前行会阻止分组排序"这类行为一并写进 note。
5. `ui-runs` 搜 `git` 实际**只命中一条**（命令/面板/会话/文件都不含 git），所以下方向键那步的真判据是"单条结果下选中态不越界、不换人"，
   回车落点由下一条"必须打开 Git 页签"钉住；跳设置节的 `.palnum` 高亮、Esc 关抽屉、聚焦时打字不抢键也都补上了。
6. `ui-at` 的斜杠菜单首条比对前要掐掉空白——模板串里 `<code>` 前有换行缩进，直接 `indexOf('/plan')===0` 恒 false（判据自己踩了一次）。
7. `ui`（主巡览）的终态判据（表格/代码块/停止态）把 `.cbar` 之后的等待从 2.5s 提到 6s：2.5s 时四轮工具调用常没收尾，
   "跑完一轮"的判据会撞在半路上；"删到这里"那步的终态（保留那一句、其后全清）现在是三条硬判据。

**验收**：`gradle test` **489 条全绿**；`node tools/ui-check.js`（棘轮 0，实测 0）与 `node tools/md-check.js` 全绿；
全量审计 `bash pc/tools/audit-steps.sh` 跑 **65 份**：64 份一轮过，`ui-product` 报 rc=127（量具瞬时故障、日志停在服务端启动后一行，复跑 4 条判据全过）。
另记两笔诚实账：① 曾误同时启动两轮审计互杀进程，留下 13 个红，全部在干净机器上复跑转绿——**并发跑审计等于没跑**；
② `ui-ask` 有一次红在"卡壳出来了、内容还没渲染完"的窗口（goto 与断言之间），复跑即绿，属既有偶发，不是本批引入。

## 这一批：本地 review 落地（#8 可做的那一半）+ embedding 源量完（#6 第一步）

**`haoai review <base> [--json]`** —— 把 base 之后的所有变更（含未提交、含未跟踪的新文件）读成三段：**改了啥 / 风险 / 建议**。
只读、零网络出口（连 Provider 都不构造），"外发 PR comment 永远要人点"那半按 §3.2 保持不做。

- **为什么是确定性规则不是先上模型**：自动 review 在没做真模型验收之前质量不可控；而密钥、大段删除、发布面、测试变少这类判据
  可解释、可测、两次结论一样。规则六条（`Review.kt`，每条都有 `ReviewTest` 钉方向）：
  新增行疑似密钥=高 / ≥200 行删除=中 / workflow·签名·gradle 属性·清单=中 / 测试文件整份删=中 / 二进制变更=低 / 源码动了测试没动=提醒。
- **判据看结果**：结论按高危翻转（有高危退出码 **2**，base 错/没 git 退出码 **1**——脚本能直接拿来当闸）。
  `ReviewTest` 12 条：包括"密钥只归到那一个文件"（按 patch 分段归属，不许一处密钥全文件刷屏）、
  "干净改动不许无中生有"（报告一惊一乍第二天就没人看了）。
- **冒烟抓到的真缺口**：第一版只跑 `git diff`，**未跟踪的新文件一个都看不见**——而新加的文件恰恰最容易藏密钥。
  已改成 `ls-files --others` 合成 numstat + patch 段喂给同一个纯函数（行数全文件流式数、patch 每文件只攒前 4000 行，内存有界）。
  自举冒烟：`haoai review HEAD` 在本仓库报出 1 条高危 = `ReviewTest.kt` 里的假密钥夹具（exit 2）——
  规则方向对，测试夹具撞真扫描器是已知现象，人看完该忽略就忽略；`review no-such-base` 正确 exit 1。
- 验收：`gradle test` **501 条全绿**（489 + 本批 12），`installDist` 已刷新（像素量具的"二进制不许比源码旧"闸仍会把住）。

**#6 embedding 源量测（ROADMAP §5.3 要求的"第一步不是写代码"）**——量完了，结论是**能用**：

- `G:\AI\llama.cpp\Vulkan\llama-server.exe`（薄壳 + `llama-server-impl.dll` 布局）带 `--embeddings`，
  Agents-A1-4B-Q4_K_M（2.6GB）**3.6 秒 ready**；`/v1/embeddings` 回 400（载荷形状不同），**legacy `/embeddings` 可用**，
  返回 `[{index, embedding:[[…]]}]`——多一层 batch 维，取向量要剥一层。
- 实测：**维度 2560**；同句余弦 **1.0000** / 无关句 **0.7453**（能区分）；同一进程 `/v1/chat/completions` 照常可用——
  一个服务同时当聊天网关与向量源，不用起两份。
- ⇒ #6 的前置（embedding 从哪来）已答：来源=本机 llama-server。**下一步是"要不要建索引"**——
  §3.3 留的那个问题仍然等你回答：*你现在真的搜不到东西吗？* `grep` + `@` 提及可能就够用了。

## 这一批：ConPTY 卡点判别并击破（#2 的第一步，回环已实证）

09-28 撞过的那堵墙（`CreateProcessW` + `EXTENDED_STARTUPINFO_PRESENT` 恒 87）**拆掉了**。
`ConPtyProbeTest`（JDK FFM 探针，pc 零第三方依赖所以不是 JNA）现在是一条绿判据：
cmd 起在伪终端上、父端写 `echo hello-conpty\r\n`、**从 `conOut.read` 把回显捞回来**（含横幅与提示符）；
普通管道自检（写 ABC 读 ABC）与"cb=120 必 87"的复现也在同一条测试里。

**三层原因，缺一不可**（详细数据在 pc/ROADMAP 第 2 条）：

1. `STARTUPINFOEXW.cb = 112`（旧实验记的 120 多算 8 字节——探针 case D 用 120 精确复现 87、用 112 通过）；
2. `InitializeProcThreadAttributeList(list, count, flags, &size)`——**第 2 个参数是 count**（先 `(NULL,1,0,&size)`
   问出 48 字节），表缓冲 8 对齐；`UpdateProcThreadAttribute` 第 2 个才是 dwFlags；
3. CreateProcess：**`bInheritHandles=FALSE` + `STARTF_USESTDHANDLES` + `hStd*=INVALID_HANDLE_VALUE`**
   （wezterm/node-pty 同款）。TRUE 会把 **JVM 被重定向的 std 傅给子进程**——"mode con 查得到控制台、
   echo 却永远不回来"的全部原因；成功后立刻关掉交给伪终端的两端。

**踩出来的实现课**（写 Pty.kt 时照抄）：`PeekNamedPipe(NULL 缓冲)` 这台 Windows 把可用字节写在第 5 出参；
peek **只看不消费**（拿到 n 必须 ReadFile 掉）；conhost 输出要**持续 drain**（管道满会互堵，症状像"没输出"）；
输入 UTF-8 + `\r\n`，**等客户端接上再发**。FFM 备忘：分配工厂在 `Arena.allocate(size, align)`
（`MemorySegment.allocateFrom` 不存在）、`structLayout` 要显式 padding、kernel32 要 `libraryLookup` 显式加载。

**验收**：`gradle test` **502 条全绿**（501 + 本探针 1 条）。⇒ #2 从"卡点待查"变成
**"实现批随时可开工"**（`Pty.kt` pump + `ProcRegistry` 换底 + `shell_open` 的 `tty` 参数 + 测试/像素），
路线见 ROADMAP 第 2 条末尾与 §5.4。

## 这一批：v0.80.0 —— ConPTY 落地（B2）：`shell_open` 的 `tty` 选项

上一批证明了机制，这一批把它变成产品能力。`shell_open` 新增 **`tty=true`**：起一个挂在
Windows ConPTY 上的常驻进程（`ConPty.kt`，JDK FFM 直调 kernel32，零第三方依赖），
配合 `shell_send`/`shell_read`/`shell_close` 原样工作。

- **拿到的四件管道给不了的东西**（也是这批的验收判据）：
  1. **`isatty()` 为真** —— `test -t 0; echo rc=$?` 必须是 `rc=0`（管道恒 1）。判据特意不写
     "echo isatty"：TTY 会**回显输入**，命令原文里就带着那些字，断言分不清是跑出来的还是被回显的，
     `rc=$?` 展开后只出现在输出里才分得开；
  2. **Ctrl+C 是真的信号** —— `sleep 30` 后送 `text="\u0003", enter=false`，6 秒内要等到
     `after-interrupt`（管道里 0x03 只是缓冲里的一个字节，得等 30 秒才轮到下一行）；
  3. 输入回显、行编辑、颜色/进度条（isatty 的下游，程序自己会用）；
  4. 状态跨 send 保留（`x=hello` 下一条还读得到 —— 和管道同一判据口径）。
- **边界**：默认仍是管道（`tty` 不传就是原行为，省资源且不碰 ConPTY）；`tty=true` 建不起来时
  **明确报错**（每一步带 GetLastError：CreatePipe/hr/建表/挂表/CreateProcess），绝不静默降级；
  只支持 Windows。终端**面板**（人用的那个页签）这一批仍是管道，换 TTY 等面板自己的批次。
- **实现要点**（都在 ConPty.kt KDoc，动它之前先读）：cb=112、参数序 (count,flags)、
  bInherit=FALSE+std=INVALID、建后关 pty 端、输出持续 drain（peek 只看不消费、半行安静 250ms 推出
  ——提示符没有换行，不推就永远看不见）、UTF-8 跨管道边界的劈字留 carry、关会话先 Terminate 再 ClosePseudoConsole。
- **验收**：`gradle test` **504 条全绿**（502 + `PtyTest` 新增 2 条 TTY）；`PC_VERSION` 升 **0.80.0-pc**；
  `installDist` 已刷新。⇒ B2 完成，**#17 Run/Verify 的前置（中断长驻进程）就位**。

## 这一批：Run/Verify 闭环（#17）：`run_verify` 工具

把「**检测项目类型 → 构建 → 启动 → 验证 → 停止**」一条链接成一个工具（第 23 把内置工具），
逐段报告，死在哪一段一眼可见——模型自己用 shell 拼这条链，最常见的烂法是
"构建完不起、起了不停、停用硬杀"。

- **检测**按文件猜（gradlew/build.gradle/pom/package/Cargo/pyproject…），**默认构建表**只给确定性命令：
  没把握的（python 无统一构建、npm 没有 `scripts.build`）**跳过而不是猜**——猜错的构建命令比不构建更糟；
  `build` 可覆盖、`build:"-"` 显式跳过。
- **启动**优先走 TTY（`shell_open` 的 tty 路径，v0.80.0），建不了退回管道并写明；
  **停止**先 Ctrl+C 礼貌中断（给进程收尾机会），5 秒没收敛再关闭兜底——验证失败的路径**同样停干净**
  （`RunVerifyTest` 有一条专测"失败不留僵尸"）。
- **验证**三选一可组合：`verify_file`（产物存在）/ `verify_port`（TCP 通）/ `verify_url`
  （HTTP **<500 即算活**——404 也是有服务在应答，那是"起来了"不是"没起来"），轮询到 `timeout_ms`。
- 构建失败/超时**就地停住**，不进启动与验证；全程一把 `guard`（审批标题列出整条链要跑的东西）。
- **验收**：`gradle test` **510 条全绿**（504 + `RunVerifyTest` 6）；工具总数钉在 23 的两处断言同步更新；
  `installDist` 已刷新。⇒ **#17 完成，§七 里"能自己开工"的名单再次清零。**

## 这一批：Codebase 语义索引（#6）：grep 的 0 命中回退

量测（见上一批）证明本机能出向量之后，这一批把索引接进了 **`grep` 的回退位**——
**文本 0 命中**且配置了 `settings.embedUrl` 时，附一段「语义近邻」（embedding 余弦 Top3）。
有字面命中时输出**逐字不变**；端点没配/挂了只追加一行原因——语义是增强，不是依赖。

- **用法**：`haoai set embedUrl=http://127.0.0.1:8199/embeddings`（本机 llama-server 的 legacy 端点，
  实测可用；清空 = 关闭）。查询语句就是 pattern 的字面——正则写法对语义这段无效（工具描述里写了）。
- **实现**（`SemanticIndex.kt` + `Embed.kt`）：按行攒 ~600 字/块；缓存在状态根
  `HAOAI_HOME/embed-index/<sha1(ws)>.json`，**按文件 hash 增量**（没动的文件绝不重新向量化）；
  向量 Float32 → Base64 落盘（2560 维 × 几百块若用 JSON 数字数组，上百万 JsonPrimitive 会把堆吃穿——
  量级算过才选的这条路）；两种端点形状都认（legacy `[{index,embedding:[[..]]}]` 与 OpenAI `{data:[…]}`）；
  `dim`/`ver` 不匹配整体重建；**刻意不做进程内缓存**（"文件改了但进程活着"会让索引永远陈旧，比慢更糟）。
- **判据**（`SemanticIndexTest` 8 条，假端点按"含配置"给向量）：**词不相同意思相近能搜到**（问"配置怎么加载"
  命中 `config.kt` 得分 1.0）；**增量**（文件没动只多 1 次请求=只嵌查询那句，改了才重嵌）；端点挂了给原因不抛；
  没配端点 grep 输出**逐字不变**；配上后 0 命中才附语义段、有命中不夹带。
- **验收**：`gradle test` **518 条全绿**（510 + 8）；`installDist` 已刷新。
  ⇒ §3.3 留的问题（"你现在真的搜不到东西吗"）现在有了答案工具：搜不到时它会多给你一次机会，
  搜得到时它一个字都不加。

## 这一批：结构对标落进 ROADMAP（§6），两份手抄清单当场钉死

对着 ZCODE / OpenClaw / Hermes / codex / pi 的**代码结构**（不是功能缺口——缺口在 §2）
过了一遍已有功能，ROADMAP 新增 §6「结构对标：已有功能的调整项」S1–S8，每条带查证出处。
其中两条查证当天就抓出实锤、当场修掉，顺手清一处死代码：

- **S3 · 命令表漂移是真的**：前端 `CMDS` 12 条、服务端 `BUILTIN_CMDS` 11 条，**少 `compact`**。
  后果不是报错而是静默：技能可以叫 `compact`（重名闸 :1340 拦不住），而前端内置在前
  （:3170），那个技能**永远点不到**。修法：`compact` 补进服务端清单（重名闸与下发的
  `taken` 字段一起变对），`BUILTIN_CMDS` 从类里提成顶层 `internal` —— 测试要直接引用它，
  不再扫源码（:81 的旧注释"两份实现靠这份对齐"就是漂移的邀请函，换成测试钉）。
- **S1 · SSE 事件契约**：`forward` 的 `when` 原来带 `else -> Unit` —— 新加的 `Ev` 变体
  编译不红、前端静默收不到（**#111"SSE 发了 DOM 没有"的同族病**）。去掉 `else`，
  `ApprovalRequest/AskRequest` 显式列分支（它们走 webGate 专用通道 :1944/:2002），
  从这版起**加事件忘表态 = 编译红**。
- **S4 · 顺手删死代码**：`Lan.quote` 0 个调用点。原计划"`quote/esc` 7 份收敛 1 份"查证后
  **降级**：7 处里 3 处根本不是 JSON（SQL/HTML/ffmpeg filter 各有各的域），4 处 JSON 都合法、
  `\t`/`\r` 的不同处理是有意的显示语义 —— 统一 = 改行为 = 像素重判。降级理由写在 ROADMAP §6 S4，
  免得下一轮又把这条当没查过的提出来。

判据（`UiContractTest` 4 条，全读**产品源码本身**，`ApiDocTest` 的模式）：

1. 命令表两份**逐条相等**（谁多谁少都算漂，断言消息里写着漂过的实锤）；
2. 面板列的每条命令 `switch` 里都有 `case`（点了没反应 = 红），反方向也钉；
3. SSE 事件名**两头对齐**：前端订了没人发 = 红、服务端发了没人订 = 红
   （`hello/ping` 是握手与保活，白名单放行）。**第一版就是红的**，而且红得有理：
   裸 `addEventListener` 把 `scroll/click/paste` 这些 DOM 事件算成了 SSE 订阅 ——
   正则收窄成 `es.addEventListener` 才对；
4. `BUMP` 未读表挂的事件必须是已订阅的（不然那个事件永远不会让会话亮起来）。

- **验收**：`gradle test` **522 条全绿**（518 + 4）；`ui-check`、`md-check` 全过。

## 这一批：面板模块化 —— 13 个面板收进对象，动 UI 美化之前先分区（ROADMAP §6 S6）

`index.html` 是 3813 行、183 个顶层函数的单文件：面板各自为政、状态散在全局、
`show` 与 phone 同名不同义 —— 你说的"UI 简陋"要动外观，在这上面改就是高风险手术。
这一批**先分区、不动一个像素**：行为零变化是判据，结构变化是全部内容。

- **13 个面板对象**（`Palette / Term / Ck / Lan / Pv / Git / Files / Mem / Skills /
  Cron / Presets / Hooks / Cfg`）：每个 = **状态 + `init()`（静态接线）+ `render()`（页签
  刷新）+ `dispose()`（只给真有得收的：Term/Pv）**。IIFE 求值时立即跑 `init()`，
  接线时机与从前逐字节一致；面板内声明**保持列 0 缩进**（ui-check 的正则不依赖缩进）。
- **Cron 是一个对象**：workflows / crontab / digest 本来就是同一个「定时」页签，
  只是文本被角色卡、钩子两段夹开 —— 输出顺序调成 Cron → Presets → Hooks（都是接线，执行序无影响）。
- **核心侧跨界调用逐处改写**（扫描出的全部引用，一只手数得过来）：页签分派 8 处走
  `X.render()/dispose()`；会话切换重置文件路径收进 `Files.reset()`；Escape 与
  Ctrl+Shift+P 走 `Palette.toggle/打开了没`；斜杠菜单读技能走 `Skills.all()`；
  命令面板跳设置走 `Cfg.open()`。**像素剧本同步改 3 处**：`Cron.sync()`、`Pv.timer()===null`、
  `Palette.move(...Palette.sel())` —— 判据语义一个字没变。
- **`shared.js`（两端共用件）**：查证后**只有 `esc` 两边逐字等价** —— 原判据写的
  `decide/answer/show` 同名**不同义**（phone 的 answer 是"把回答发回电脑"，index 的
  answer 是"画流式正文"），合到一起是埋雷，所以只抽 esc。两个服务都要发它：
  主服务 `Server.kt` 与 **Lan 端点 `Lan.kt`**（phone 是从 LAN 出的 —— 第一版只加了主服务，
  手机页会 404 出白屏，而症状会伪装成"配对页坏了"）。

判据（全静态、秒级，跑 `node tools/ui-check.js`）：

1. `shared.js` 存在、**两页都引**、**Lan 端点也发**（那条白屏陷阱钉死）；
2. 13 个面板都是 `const X = (() => {` 对象且 `X.init();` 被调（init 删了 = 接线全哑，
   语法照样绿，所以要单独挡）；
3. 页签分派 ≥8 处走 `X.render()`、且没有残留的裸 `loadGit()` 老名；
4. **面板私有成员不在对象外裸用**（裸用 = 运行时 ReferenceError，语法查不出来）——
   这条不是拍脑袋：第一轮像素就红了 `ui-at`（Escape 处理里残留一个 `palOpen`，
   整个 `hideSlash()` 被跳过，@ 列表关不掉），修完才想起来该有这道门。

- **验收**：像素审计 65 份——第一轮 **64 绿 1 红**（`ui-at`，上条说的那个），修完重跑
  **全绿**；`gradle test` **522 条全绿**；`ui-check`（含新 4 类判据）、`md-check` 全过。
- **范围的诚实交代**：聊天核心、会话侧栏、用量/任务页签**留在顶层** —— 它们不是面板，
  且是像素剧本的契约 API（`cur/finishAnswer/openSession/markSessions/mode/views` 点名要全局）。
  全量命名空间化留给 UI 美化批一起做，别在这一批里赌 64 份剧本。

## 这一批：Server.kt 拆域 + 引擎工厂单路径（ROADMAP §6 S5，B15 的地基）

`Server.kt` 3091 行单类 = 85 个路由 + SSE + 会话池 + 排队 + 审批闸 + 定时器 + LAN 宿主 +
终端/浏览器代理 + git + 备份 + 设置。这一批**不改一个行为**，只动结构：

- **引擎工厂（EngineFactory）**：以前 `Engine(...)` 这行两壳各写一遍（CLI `Sessions.create`、
  网页 `engineFor`），"构造时要接什么"只能对照两处代码。现在构造与会话级 overlay
  都在工厂里，**两壳 + 新会话（`newSessionId`，S7 又逮到的第三处直连）都走它**；
  唯一刻意留在外面的是子任务 spawn（不是"一条会话的开始"，理由注在调用点）——
  **B15 要两壳共用的是这层，不是那 3000 行网页壳**。
- **按域拆 8 个文件、搬 55 个方法**，`Server.kt` **3091 → 1484 行**：
  `ServerMessages`（消息级/附件/检查点）· `ServerFiles`（文件与媒体）· `ServerAbility`
  （规则/记忆/技能/订阅源/MCP/凭据/钩子）· `ServerAutomation`（定时/工作流/结果）·
  `ServerDevProxy`（终端与浏览器代理）· `ServerGit` · `ServerConfig`（备份/设置）·
  `ServerModel`（模型/导出/分享）。路由分派表、SSE、会话池、审批闸、编排留原地。
- **形状是同包扩展函数，不是独立 handler 类**（与判据原文的偏差，理由写在这）：
  类体拆成独立类要给每个内部引用加 `ctx.` 前缀（改动面 ×10、每处都可能改错），
  而 `internal fun WebServer.xxx` 是**纯搬移** —— 方法名未变、route 表一字未动，
  可见性只对搬走代码引用到的成员定向放宽（`settings/sessions/publish/send/quote/…` 与
  嵌套类 `Body/Managed/Waiter`）。
- **纯搬移纪律**：方法体连注释与段标记整体搬、**保持原缩进**（多行字符串与续行模板里的
  缩进是内容的一部分，dedent 就是改行为）、域文件头写明来历。

量具跟着拆分走（两个都是"只盯 Server.kt"读出的**假红**，本身证明名单该按全目录算）：
`UiContractTest` 与 `ui-check` 的事件名单原来只扫 `Server.kt`，拆完当场红出
`settings/model` 没人发 —— 其实它们从 `ServerConfig/ServerModel` 发得欢。
两个都改成扫 `com/haoai/pc` 全目录。

- **验收**：`gradle test` **522 条全绿**（WebApi/审批/定时/备份/git/媒体那些 HTTP 层测试
  正好压在搬走的域上）；`ui-check`、`md-check` 全过；代表域像素 8 份全绿
  （settings / git / preview / cron(+narrow) / skill(+feed) / tour）。

## 这一批：hooks 事件面扩到六种，`pre-tool` 是真闸（ROADMAP §6 S7）

对标 ZCODE 的 7 事件（`SessionStart / UserPromptSubmit / PreToolUse / PermissionRequest /
PostToolUse / PostToolUseFailure / Stop`），落地 6 种：

| 事件 | 触发点 | 形状 |
|---|---|---|
| `run-end`（既有，≈ZCODE Stop） | 引擎收尾 | 异步记账 |
| `session-start` | `EngineFactory.build`（两壳+新会话都从工厂过） | 异步记账 |
| `user-prompt` | `submit` 入口（只父会话，话走 `HAOAI_OUTFILE`） | 异步记账 |
| **`pre-tool`** | 工具循环，**同步闸** | **退出码 2 = 拦下** |
| `post-tool` / `post-tool-fail` | 工具执行后按 `res.error` 分流 | 异步记账 |

- **闸的语义**（对标 PreToolUse：0 放行 / 2 拦截）：拦下时**什么都没发生过** ——
  位置在四层可见性检查之后、`ToolStart` 之前，走"未知工具/被关掉"同款路：
  tool 回复写明「被钩子「名」拦下：理由」进历史（模型下一轮看得见）、`ToolEnd` 上屏、
  循环继续（每个 `tool_call_id` 都要有一条回复，协议硬要求）。**失败、超时、跑不起来
  只记账不拦** —— 硬规矩 1 对闸同样成立，反面是"配一次坏钩子，agent 从此什么都干不了"。
  这层与 `Risk.kt` 打分**互补**：一个拦机器判的高危，一个拦用户自己写的规矩。
- **`PermissionRequest` 刻意不做**（写进 Hooks.kt 文件头）：它的语义是"替审批做决定"，
  要把异步审批回环接进钩子；而"要不要放行"已经有 `Risk.kt` 确定性打分与审批闸口
  （fail-closed、可测试），再叠一个会跑脚本的决策层只会两头打架 —— 等 S8 把审批等待
  状态机化之后再议。
- **工具上下文进脚本**：`HAOAI_TOOL / HAOAI_TOOL_ARGS / HAOAI_TOOL_RESULT`
  （都截断 —— 写文件的 content 能有几 MB，环境变量块是有上限的）；下拉框摆中文标签
  （服务端 `eventLabels` 下发，事件 id 给机器、标签给人）。
- **顺手逮到第三个 `Engine(...)` 直连点**：`newSessionId`（`/api/new` 造完引擎就进表，
  `engineFor` 再也不会被调 —— session-start 一声不发，HookTest 用 `last=""` 逮住的）→
  收进工厂。**S5 那节"构造只在工厂一处"当时说早了**：真实构造点 = 工厂（两壳+新会话）
  + spawn 子任务（直连并注明理由：子任务不是"一条会话的开始"）。

判据（全真进程，cmd shell，Windows 自带不设跳过）：

- `HookGateTest` 5 条：exit=2 拦且 stdout=理由、exit=0 放行、exit=3 只记账不拦、
  超时不拦、六事件中文标签 + `load()` 不把新事件摔回 `run-end` + `json()` 带 `eventLabels`；
- `EngineFlowTest` +1（端到端核心判据）：真引擎真文件 —— 拦下的 `write` **没落地**、
  原因进历史、`禁写` 报得出名字、这一轮**照常收尾**；
- `HookTest` +2：`session-start` 真的在引擎构造时发（sid 经环境变量到脚本）、
  `user-prompt` 真拿到那句原话（OUTFILE 复制回来）。

- **验收**：`gradle test` **530 条全绿**（522 + 8）；`ui-check`、`md-check` 全过；
  像素 `ui-hook`（13 判据）+ `ui-tour`（14 判据）全绿。

## 这一批：审批等待收进状态机 + 工具 schema 一种写法（ROADMAP §6 S8 —— §6 收官）

**审批等待状态机（`Approval.kt` / `ApprovalBroker`，对标 OpenClaw `exec-approval-manager`）**：

- 状态只有一条线 `PENDING → ANSWERED / TIMED_OUT / ABORTED`，出路唯一、收摊在一处
  `finally`（maps 漏删的表现是"这条审批永远在等人"）；futures、载荷、id 序列从
  `WebServer` 的四个字段 + 两份手写 try/finally 里收编进来。
- **超时可注入**：`approvalTimeoutSec(300) / askTimeoutSec(900)` 是构造参数 ——
  旧实现 `fut.get(300, SECONDS)` 这条路径**从来没有测试**（没人等五分钟），
  现在测试用 1 秒真跑："没人应答 → 按拒绝处理 + 通知带真实秒数 + 收摊干净"。
- 行为逐字节同旧：超时审批=deny+通知、超时提问=`""` 且**不发通知**、异常=deny 不闹；
  `isAsk/sid` 在 complete **之前**取好（旧代码在两个地方各写了一遍这个时序注释，收进一处）。
- **死状态删除**：`pendingRule` 查证**只写不读**（`allow_rule` 用闭包里的 tool/pattern，
  手机端答复也走 await 回来的 `ans`）—— 随收编一并删掉。
- 每台服务一个实例（测试同 JVM 起好几台，状态绝不许全局）。
  接线：`webGate`（等待进状态机，"答案怎么解读"——逐块/落规则/挂结论——留在原地）、
  `decide` / `stopTask` / `stateJson` / `pendingJson` / `pendingKind` / 手机端 `decide` 全走它。
- **量具教训（写进 Approval.kt 头）**：第一版把回调包成 `out(...)`，
  `publish("approval")` 字面量立刻从 `UiContractTest` 和 `ui-check` 的源码扫描里消失
  （它们靠扫字面量对账事件名单）——当场红。回调**保持叫 `publish`、事件名保持字面量**：
  契约是靠"字面量可扫"活着的，别拆。

**工具 schema 一种写法（`schema(...)` 帮助器统一）**：

- 查证后形状收窄：不是"手搭 JsonObject 满天飞"——14 处已用帮助器，5 个文件
  （desktop / browser / git / shell_* / run_verify）各自手搭但**产出逐字节相同**。
  全部收进 `schema(...)`（Pty 的 `noParams/strProps/required` 三个本地 helper 删掉），
  纯去重零行为变化。
- `ToolSchemaTest` 全量钉形状：`type=object`、每个属性**恰好** `{type}`、
  `required ⊆ properties`、type ∈ 已知集合 —— 谁哪天又开始手搭、或悄悄塞 description/enum
  （要么都加要么别加），当场红。MCP 的外部 schema 不在此列（人家的地盘）。

- **验收**：`gradle test` **538 条全绿**（530 + 7 + 1）；`ui-check`、`md-check` 全过；
  审批链像素 5 份全绿（queue / review / risk / preview / tour）。
  **§6 的 S1–S8 到此全部落地。**

## 这一批：B15 第一片 —— `:core` 立起来，工具契约两端共用（ROADMAP §3 B15 🟡→部分）

**拓扑（解锁的部分）**：根构建新增 `:core` 子模块，`include(":app", ":core")`。

- 实测 **AGP 9.4.0 + `kotlin("jvm") version "2.4.10"` 直接可用**——`pc/settings` 注释里
  记的"内置 Kotlin 与 kotlin.jvm 同 classpath 版本打架"**没有复现**（当时炸的写法与现在不同）。
  `:core:test` 根构建直接跑 ✓；Android `project(":core")` ✓。
- PC 是另一个构建，走 **mavenLocal 消费**（与 pc/settings 注释的 Phase 1 一致）：
  改了 core 先 `gradle :core:publishToMavenLocal`，忘这步的症状是"pc 在用昨天的 core"。

**搬进 `:core` 的三件（两端真共用，各一处定义）**：

| 类型 | 取舍 |
|---|---|
| `AgentTool<C>` 工具契约 | `suspend` 取手机端形状、字段名取 PC 的 `desc/params`；**泛型上下文**吸收两端分歧（PC=ToolCtx 审批闸/快照，手机=ToolContext 无障碍/缓存）——接口共用、上下文各带各的 |
| `ToolResult` | 字段顺序沿用 PC（位置传参动一个就是全体红），手机的 `imageDataUrl` 挂末尾；`isError→error` 按 PC 为准 |
| `TextCap` + `takeSafe/takeLastSafe` | 取**手机端原版**（PC 旧版没代理对保护、还没有 tail）——"两端截断口径必须一致"这句老话第一次变成物理事实 |

**采用方式是"原地 typealias"**：同包同名，两端现有 import 一个不改（`typealias ToolResult =
com.haoai.core.ToolResult` 这种）。PC 的 25 把工具函数体加 `suspend`（引擎唯一调用点
`runBlocking` 包一层，调度语义不变；测试侧一个 `runB` 桥，111 处调用点只做文本替换）；
手机 69 处 `description/parameters → desc/params`、`isError → error`。

**过程中抓出的坑（都进了注释/提交）**：批量改名误伤 3 处——`Scheduler.run`（那是
**Thread.run** 不是工具）、Compose `OutlinedTextField(isError=)`、`ToolLoopGuard.observe`
的参数名；`globToRegex` 重写时步进差点改错（git diff 救回）；PS5.1 给 42 个文件偷偷加 BOM
（全剥）；`ChatViewModel` 里一句 "package installs" 被 import 插入正则误命中插进字符串。

**还欠第二片（会话模型）**：`Msg` vs `ChatMessage` 两端持久化字段名已分叉
（`calls/callId/pt/ct/ms` vs `toolCalls/id/promptTokens/...`），合型必须带 `@JsonNames`
旧名兼容解码，**不许裸改**（改坏=两边历史都读不出来）。见 ROADMAP B15 行。

- **验收**：`:core:test` 根构建直跑 ✓；`gradle test`（pc）**538 全绿** ✓；
  Android `testDebugUnitTest` 全绿 ✓；`assembleDebug/Release` ✓；
  工具类像素 3 份绿（tour/term/git）✓；`ui-check`/`md-check` 全过 ✓。

## 这一批：真机三条收官（ROADMAP §5.2）—— ②③ 实证 + ① 四条发现

**先说模拟器（查清了，按用户指示改用真机）**：`.android\avd` 空、Studio 的 SDK（E:\Android）
无 system-images、`emulator -list-avds` 空、注册表无 MuMu/夜神/雷电/WSA —— **本机当前没有任何
可用模拟器**；但 `.android` 里留着 `modem-nv-ram-5554/5556/5558`，过去至少跑过三个 AVD（镜像
后来被清了，复用要重下 ~800MB）。

**真机链路（MEIZU 20 Pro，adb TLS 直连）**：手机上装的是 release 签名 0.18.6 → 打
`assembleRelease`（release.jks）`install -r` 过、**配对与数据无损**；全程 `uiautomator dump +
input tap/text` 导航到「设置→电脑联动」，120 秒内完成配对（`lastSeen` 实时刷新）。

### ② 提问通知（含锁屏）✅ 端到端实证
- `ask_user` 任务 → PC 挂起 q1 → 后台轮询 → **通知带两个选项按钮**；
- **锁屏实拍**：[phone-ask-lock.png](G:/hbt/pc-demo/phone-ask-lock.png)（20:40，标题
  "电脑上有话要问你" + 火锅/烧烤按钮 + 保活通知同屏）；
- **按钮不是摆设**：`input tap` 点"火锅" → PC 端 pending 立即清空、会话正常收尾 ——
  锁屏上点一下，答案真的送回了电脑。
- 操作约束记档：息屏 2 分钟自动锁（解锁要指纹/密码，代理做不了）；**screencap 不需要解锁**。

### ③ 定时结果通知 ✅ 送达，且抓到一个真机才照得出的 bug
- 每分钟 schedule → 跑完进 digest → `haoai_pc_done` 通道通知送达（autoCancel、正文
  "10-01 20:48 · S6验收定时"）；判重的"一次多条只弹一条、正文说还有几条"与设计一致。
- **产品 bug（已修）**：批量≥2 时标题是 `电脑上有 [PcDigestItem(…), …].size 次跑完了` ——
  Kotlin 模板 `$fresh.size` = `${fresh}` + 字面量 `.size`，列表原样吐出。单条走 else 分支
  一直躲着，**一攒批就现形**（正是"真机看一眼"的价值）。改 `${fresh.size}` 并已重打包装回。

### ① Flyme 省电回收 —— 四条发现（#66 的实测答案）
1. **充电 + 息屏 30 分钟，Flyme 没有杀进程**：pid 稳定 40+ 分钟（充电态是原因之一）。
2. **系统手段模拟"回收"全被挡**：`am kill` 杀不动前台服务、`am crash` 只对 debug 包生效、
   shell `kill -9` 无权限 —— 真·严格省电回收要在**设置里手动把 HaoAI 调成严格限制**（需解锁，
   留给人工复测这一条）。
3. **更隐蔽的失效形态找到了：息屏后台约 6 分钟后轮询静默** —— `lastSeen` 从 20:57 冻结到
   21:37（整整 40 分钟无请求），而**进程活着、保活通知挂着、局域网 ping 通**（11ms 0%丢包）。
   即"冻结式"后台限制：不杀你，但让你发不出请求 —— 通知形同虚设，**比杀进程更难察觉**。
4. **恢复能力 OK**：人一碰手机（前台恢复）→ `lastSeen` 立刻刷新 → 挂了 6 分钟的 ask 通知
   **当场补发**（actions=2 的提问卡）—— 链路本身没坏，坏在后台静默期。
- 附带实锤：这台机存在**分身空间 user999**（`am crash` 打错用户的原因）；观察 E：长时间
  静默后 prime（"首见不响"）没有误伤恢复首条 —— 因为静默是"不发请求"而非 Fail-重置，设计自洽。
- **遗留一项（需你解锁配合）**：设置 → 省电 → HaoAI 设为"允许后台/不清理"的**反面**（严格
  省电）后重跑一遍，才能拿到真 Flyme 杀进程后的恢复结论。

### 验收
`assembleRelease` 重打包装回（含 `$fresh.size` 修复）✓；PC `gradle test` **538 全绿**、
Android 单测全绿 ✓；`ui-check`/`md-check` 全过 ✓；截图存档
[G:/hbt/pc-demo/phone-ask-lock.png](G:/hbt/pc-demo/phone-ask-lock.png)；
Octop 已收录进 ROADMAP §0 证据来源（单进程 ADR、HarnessProcessor 统一入口、tool_guard、ACP 双向）。

### 第二片：会话模型统一（Msg/ToolCall 下沉 core，🟢 全落）

**先查清的事实决定了对齐方向**：两端**磁盘格式是各自独立的**——PC 手写 JSON 落盘
（字段名即格式，538 条里有持久化锁）、移动端存独立的 `StoredMessage` DTO + 显式
`toModel/toStored` 转换。**运行时模型改名碰不到任何磁盘**，所以规范名取 PC 侧
（`calls/callId/name/pt/ct/ms`），移动端独有 7 字段（`id/error/imageData/audioPath/
videoPath/model/ts`）作为并集进 core、PC 不写不读。

- **core**：`Msg.kt`（含 `ToolCall` + `ROLE_*` companion）——纯 data class，不需要任何
  序列化注解（全仓没有一处直接序列化 ChatMessage，grep 验过）。
- **PC**：Provider.kt 原地 typealias → **零调用点改动**，538 全绿即磁盘格式锁的证明。
- **移动端**：ChatMessage.kt/ToolCallData 改 typealias（同包同名，旧 import 全活）；
  ~360 处字段改名 + `content: String?` 的可空涟漪（`?: ""` 逐处兜）；两个转换函数是
  **唯一的改名边界**（磁盘字段名一个没动）——`SessionStoreRoundTripTest` 3 条锁往返
  保真与 DTO 可空缺省的兜底（pt/ct/ms→0、name→空串）。
- **改名纪律（这次学到的，已入档）**：**报错行所在的类型**才是判据 —— 同名字段满天飞
  （`ApiMessage.toolCalls`、`AssistantStats.promptTokens`、`ChatRow.durationMs`、
  `StoredMessage.*` 一个都不能动），编译器指哪、人工核型到哪；按行盲替连坐过
  `st.promptTokens`、把 `observe(toolName=)` 改坏、把 ApiMessage 线上参数改错 ——
  每一处都靠"这行的接收者到底是谁"逐个定案（磁盘侧函数误判了三次，git checkout 救回）。
- **验收**：`:core:build/publish` ✓、pc **538 全绿**（对新 core 重跑）✓、Android 单测
  全绿（含新保真测试）✓、`assembleRelease` 装回真机 ✓。

## B22：`ask_user` 同步对齐（T1：跟进手机端 10-01 夜里的 6 笔调整）

逐笔判过那 6 笔：**没碰 `core/` 与 `pc/`**（无机械必须跟）；glasslab 是移动端调试面板
（N/A）；180s 工具看门狗豁免在 PC 没有对应物（PC 本无该看门狗）。要判的是**能力一致**：
同名工具 `ask_user` 在两端的契约已经分叉 —— 做了 T1（工具契约 + 双端 UI + 提示词）：

| 维度 | 以前的 PC | B22 之后（=手机端） |
| --- | --- | --- |
| options | 字符串数组、可选、无校验 | **`{label,description}` 对象数组，2~4 校验**（字符串按 0 个拒，错误文案同款） |
| 参数 | 只有 question | + `allow_free_text` / `confirm` / `recommend`（缺省全 true） |
| 结果 | 回答原文 | **前缀三支**：`用户选择了：/用户回答：/用户未给出有效回答…`（界面回显已答卡的数据源） |
| 提示词 | 一句"一次问清" | SystemPrompt 同款两条（confirm 按风险二选一、问卷 recommend=false） |
| 桌面卡 | 点击即答 | 确认步（点选 → 「回答」，同一颗键/号按两次也提交）+ 推荐徽标 + 选项含义说明 |
| 手机卡 | 点击即答 | 同款确认步 + 徽标 + desc；**通知按钮仍直答**（与手机端原生一致：确认步属于卡面） |

- **闸口升级不逼任何人**：`Gate.ask(AskReq)` 新增，**默认把标签桥回旧签名** ——
  与 `approveRule` 一族同套路，CLI/28 个测试替身零改动；`ApprovalBroker.awaitAsk` 载荷
  带对象选项与三开关；`lanPendingRow` 归一成 `{label,description}`+三开关（旧字符串
  载荷兼容收下，老夹具照跑）。
- **没跟的（记档）**：`ask_user_batch` 是 **T2 未移植** —— 所以 PC 提示词没有那句
  batch 分流（不引导模型用不存在的工具）；CLI 终端的确认步/徽标是无物之物
  （打字本身审慎、没有徽标可标），等价快速模式但 honor `allow_free_text`（只收编号）；
  `askTimeoutSec` 仍 900 秒（手机端提问无限等 —— 有意保留：无人值守不能挂死）。
- **schema 形状规则放宽**：`ToolSchemaTest` 属性键 {type} → {type,description,items}
  （items 只许对象数组、子属性同规则）—— 富度对齐才叫同一个工具；其余 builtin 仍全是 {type}。
- **判据**：`gradle test` **545 条全绿**（新增 `AskUserToolTest` 7 条、LanTest 对象载荷、
  ApprovalBroker 载荷形状、EngineFlowTest 前缀与 desc/开关到闸口）；`ui-check`/`md-check` 过；
  像素三条全绿：`ui-ask` 24 步/21 条、`ui-askkeys` 33 步/26 条、`ui-phoneask` 28 步
 （三份剧本各补了确认步判据、终点锚改成前缀原文，mock 夹具选项改对象 + 带 desc）；
  桌面卡与手机卡截图亲验（徽标、desc 列表、置灰的「回答」键都在该在的位置）。

## T2：`ask_user_batch` 移植到 PC（整批问卷本地出题，N 题只占 1 次往返）

手机端 10-01 落了 `ask_user_batch`（性格测试/满意度/知识测验这类"连续多题的同型问法"）。
B22 当时把它记成 **T2 未移植**，这一批补上 —— 契约、结果文本、题量上限都对齐手机端。

| 维度 | 做法 | 为什么这样 |
| --- | --- | --- |
| 出题在哪 | **界面本地循环**：第 i 题点选即答、自动跳下一题，全程不回模型 | N 题只占 1 次模型往返；答到一半不烧 token |
| 闸口 | `Gate.askBatch(title, questions, allowFree)`，**默认桥=逐题走 `ask`**（confirm/recommend 关掉） | CLI 与 28 个测试替身零改动就天然拿到"一题一问"；网页壳覆盖它拿整批等待 |
| 题量 | 1~100 题、每题 2~6 个互斥选项（题干空、选项畸形都**点名报错**，不静默丢题） | 与手机端同一把尺子；题组长就分批提交（提示词里写了 30~50 题一批） |
| 结果文本 | `用户已按顺序回答 N 题：` + 逐行 `i. 作答`（缺答补"未作答"） | 这是历史回显"已答卡"的数据源，两端逐字节同格式 |
| 桌面卡 | 头部"第 i / N 题 · 选完自动下一题"、题干、整行选项键、`allow_free_text` 开时给"其他…"出口 | 与单题卡同一套玻璃，不另起样式 |
| 手机网页端 | 同款卡，状态挂 `window.__batchSt` | 那页每 4 秒重绘一次，状态不挂出去答到第二题就丢 |
| 通知（安卓） | 批量行**不摆选项按钮**，改口成"N 题问卷 · 到网页上逐题作答" | 通知按钮是给一句话提问的；问卷要逐题答 |
| 回执 | `decideNote` 批量分支只说题数 | 答案原文不外泄进回执 |

- **连带修了安卓侧的一处死活问题**：B22 起 PC 的 `options` 发的是 `{label,description}` 对象，
  而 `PcPayload.options` 按 `List<String>` 解 —— 解不下不是"少几个选项"，是**整份 pending 解码失败**、
  `decode` 回 Fail、轮询把通知全撤掉，**审批通知跟着一起哑**。改成自定义解码
  `PcOptListSerializer`（字符串取原文、对象取 `label`、元素畸形只丢那一个）。
  这条不装机就会哑，所以**必须装回真机**（见下"还欠"）。
- **交接过来时抓到的两处**：① `PcOptListSerializer` 里 `String.serializer()` 少了
  `kotlinx.serialization.builtins.serializer` 这个 import（1.9.0 把 `Decoder/Encoder` 搬进
  `encoding` 包之后连带暴露），`:app:compileDebugKotlin` 四处红；② 新加的三条 `PcLinkTest` 夹具
  **`"payload":` 后面漏了一个 `{`**，JSON 不合法 → 两条用例红，而红话说的是"电脑回的不是 JSON"
  —— 看着像产品坏了，其实是夹具自己没造出来。补上 `{` 之后两条转绿，并做了变异检查：
  把自定义解码摘掉，对象选项那条立刻红（证明判据真的咬在产品代码上，不是咬在夹具上）。
- **判据**：`gradle cleanTest test` **555 条全绿**（新增 `AskUserBatchToolTest` 6 条 + `LanTest` 转义答案 1 条 + `ApprovalBrokerTest`
  批量载荷 + `EngineFlowTest` 走默认桥端到端 + `LanTest` 批量行 + `HunkTest` 批量回执措辞，
  工具计数锁 23→24）；安卓 `:app:testDebugUnitTest` **238 条全绿**（`PcLinkTest` +2 条形状用例）；
  `ui-check`/`md-check` 过；像素两份全绿 —— 桌面 `ui-askbatch` **20 步 / 14 条**、
  手机网页端 `ui-phoneaskbatch`（390×844）**23 步 / 35 条**，各四张图亲验
 （第 1 题两选项、第 3 题题干与选项、提交后卡片收成"已答"、落库原文 `用户已按顺序回答 3 题：…`）。
- **手机页那份像素跑出来才发现的两个真缺陷**（桌面全绿照不出来，因为走的是另一套渲染 + 另一条口）：
  ① 批量分支在 `map` 里 `return` 了一个 **DOM 元素**，而外层是 `innerHTML = ps.map(...).join('')`
 —— 元素被字符串化成 `[object HTMLDivElement]`，手机上整块待批只剩这一行字，问卷根本答不了。
  改成"先占位字符串、DOM 就位之后再回来挂事件"（`mounts` 那一层）。
  ② 手机上答完，电脑上落的是 **`1. 未作答 2. 未作答 3. 未作答`** —— `/lan/decide` 用 `field()` 取
  `answer`，那个正则 `"([^"]*)"` 遇第一个引号就停、也不反转义，而手机交的是 `JSON.stringify([...])`，
  三题只截到一个 `[`，`parseBatchAnswers` 解析失败回空表。改走 `textField()`（派活那条口早就用它）。
  两条各钉了一道判据：像素里的 `noStringifiedNode`/`answerLanded`，与 `LanTest` 新加的
  `an answer survives escaped quotes`（把 `answer` 换回 `field()` 它立刻红 —— 变异验过）。
- **还欠**：~~装机~~ **已装并当场验过**（2026-10-02 14:35，`app-release.apk` = versionCode 39 / 0.18.6 装回
  MEIZU 20 Pro，数据没清、无障碍权限补回）。装完之后顺手把这台手机和电脑的联动真跑了一遍：
  电脑上局域网端点原本是**关**的（CLI 那处只认 `--port=` 的坑在这轮修过），`lan on --port 8953` 打开、
  起真 `serve`、手机上重新配对（新地址、新配对码），两端都读到成对结果 ——
  手机侧 chip「上次轮询 1 秒前」、点"现在问一次"回「问过了：电脑上没有要批的」，
  电脑侧 `lan devices` 有这台 `MEIZU 20 Pro`。之后跑了一次灭屏心跳实验，只净剩 5 分钟（15:09 被另一路 scrcpy 点亮），量到的与没量到的都记在 ROADMAP 5.2 B4。

## 这一批：命令行那两行时间戳改成人话（装机复测就是被它绊的）

装机复测要看的是"手机上次活动离现在几秒"，而 `haoai lan status` / `lan devices` 打的是
`上次 1790924465189` —— 得先心算两个十三位数才能判断"轮询到底死没死"。**功能没坏，读数坏了**，
而读数坏了的下一步通常是误判（10-01 那次差点把"进程活着、轮询协程死了"判成"ROM 把设备回收了"）。

- `agoLabel(ms, now)`：0 说"从没"，60 秒内说秒、1 小时内说分、1 天内说小时，跨过一天切成 `MM-dd HH:mm`；
  未来的时间戳（两端时钟不一致时会遇到）按 0 秒算，不打负数
- `lan devices` 那行的"加于"也从裸毫秒切成日期 —— 它是"这台设备什么时候配上的"，本来就是历史时刻，不需要相对量
- 改完真跑了一遍（这台机器上就是配好并在跑的那台手机）：

```
$ haoai-pc lan status
局域网端点：开（记在 …\HaoAI\lan.json）
端口：8953    内网地址：192.168.1.37:8953
已配对设备：1 台
  20f7f148c1  MEIZU 20 Pro  上次活动 5 秒前      ← 原来是 `上次活动 1790924465189`

$ haoai-pc lan devices
20f7f148c102  MEIZU 20 Pro  加于 10-02 15:00  上次 5 秒前
```

- **判据**：`CliAgoLabelTest` **9 条**（四档边界 + "从没" + 未来时刻 + 标签里绝不出现原始毫秒串 +
  默认取当前时间），其中最后一条是**源码级**的：`Main.kt` 里 `上次活动` 必须过 `agoLabel`、
  且两处调用都在。变异验过 —— 把其中一处改回 `${it.lastSeen}`，只有这条源码级判据红
  （其余 8 条只钉函数本身，回退发生在调用处时一条都不会响，这跟"参数收了没用"是同一类缺陷）
- 全量：`gradle cleanTest test` **564 条全绿**（555→564）；安卓 `:app:testDebugUnitTest` 238 条全绿（未动 `app/`）
- 真机那侧的量：装机后跑了一次**灭屏心跳**窗口，量到的是"确认 Dozing 的 5 分钟里 20 秒一跳、零漏拍、pid 未变"，
  没量到的是"半小时"——15:09 另一路 scrcpy 把屏幕点亮并进了电脑联动页，窗口被打断（逐条写在 ROADMAP 5.1 #10 / 5.2 B4）。
  这个窗口能用外部尺量，正是因为电脑侧有"上次活动"这个读数——原来那串毫秒没法一眼看出断没断

## 这一批：文档表格的格数有了判据 —— 顺带挖出"只改文档时测试根本不重跑"

给 ROADMAP/README 的表格补判据（`DocTablesTest`），是因为同一份文件里这类毛病已经手工修过第三次了：
markdown 表格**少一格不会报任何错**，渲染出来那一格是空的、或者两格并成一格，读台账的人根本不知道自己少看了什么。
扫下来两处现成的：`B15` 行拿**全角 `｜`** 当分隔符（于是"依赖"那格是空的，跟 B22 那次一模一样）、
5.1 第 1 行少一个 `|`（"欠账"和"现状与证据"并成了一格）。两处都补了。

**而真正值钱的是补完之后那次变异检查**：把 ROADMAP 里一个分隔符改回全角，
`gradle test --tests DocTablesTest` 回的是 `BUILD SUCCESSFUL in 1s` —— **测试压根没重跑**。
Gradle 只把 classpath 当输入，读盘上文件的测试（`DocTablesTest`/`UiContractTest`/`ApiDocTest`/`SkillDocsTest`/
`CliAgoLabelTest` 这一类）在"只改文档"时会被判 UP-TO-DATE。也就是说：**判据存在、也绿，但它从没为那次改动跑过**
——比没有判据更糟，因为它是假的安心。

- 修法：`pc/build.gradle.kts` 的 `tasks.test` 里把这些文件声明成 `inputs.files(...)`
  （三份 md、`docs/api.md`、`src/main/kotlin`、`src/main/resources/ui`、`tools`）
- 验它真的修好了：基线绿 → **只改文档、不加 `cleanTest`** → 红（`DocTablesTest > 每张表的每一行格数都和表头一致 FAILED`）
  → 还原 → 绿。三段都跑过，不是"看起来会重跑"
- `DocTablesTest` 的两条判据：① 每张表的分隔行必须紧跟表头、且每行格数与表头一致
  （少格且行内有全角 `｜` 时，红话直接点名"多半是它被当成了分隔符"）；
  ② 转义过的 `\|` 算正文不算分隔符（第 10 行那种 `plan\|build\|edit\|yolo` 是合法正文，不然会假红）。
  外加一条"一张表都没扫到就红"——量具自己会坏，读不到文件时必须响
- 全量：`gradle cleanTest test` **566 条全绿**（564→566）

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

## 这一批：Token 统计做成一页（汇总 / 按天 / 按专家 / 按模型 / 指定某天 / 导出 Excel）

用户点名要对标 Octop 的那张 Token 统计页。原来右栏只有三行字（今天 / 近 7 天 / 全部）加一张按模型的小表，
差的不是一点样式，是**量不出来的三件事**：这笔钱是谁花的、缓存帮我省了多少、以及能不能带走。

| 缺的维度 | 为什么原来给不出 | 这一批怎么补 |
| --- | --- | --- |
| 按专家 | `usage.jsonl` 只有 `model/sid/prompt/completion`，压根没记是谁花的 | 落账时带上 `expert`（当时顶栏那个显示名）+ `preset`（卡 id）：**显示名留历史，id 留索引**。读的时候再去会话文件反查被否了 —— 会话会删会改名，反查回来的是"现在叫什么"而不是"当时谁花的" |
| 缓存输入 | 网关早就回 `prompt_tokens_details.cached_tokens`，`Provider` 也解析进了 `Usage.cachedTokens`，**账本把它丢了** | 加 `cached` 列。缓存命中直接决定这一轮多少钱，不记等于把最便宜的那部分账扔掉 |
| 主会话 vs 子任务 | 团队会话贵在哪看不出来 | 加 `kind`（main/sub）：派工花掉的钱记在成员名下，不糊在主持人头上 |
| 指定区间 / 某一天 | 只有固定的今天·近 7 天·全部 | `report(from,to,expert,kind)` 按**本地日**切齐、`to` 那天含整天；页面给快捷窗口 + 两个日期框 + 「只看这一天」 |
| 导出 | 没有 | `Xlsx.kt`：**不引 POI**（离线构建是红线），xlsx 本质就是 zip 里装五份 XML，手搓一份最小可用的；`UsageExport` 出五张表（汇总/按天/按专家/按模型/明细），**数字以数字写入**，表头加粗 + 冻结 + autoFilter |

### 三条只有跑起来才会发现的（都不是"看着像就行"）

1. **手搓 JSON 借了一段结构，剪了头没剪尾。** `aggFull(rs).substring(1)` 想去掉开头的 `{`，
   于是尾巴那个 `}` 留在了中间：`{"key":"甲",…,"ms":100},"sub":0}` —— 对象提前闭合，整份报表是非法 JSON。
   15 条测试同时红，红法还各不相同。**借一段结构就得把两段都剪掉**，剪一半比不剪更隐蔽。
2. **`listOf(表头) + 数据.map{}` 少写一个括号**，导出出来的每张表都只有"一行"：表头和所有数据行
   被 `+` 拼成了**一个 List 元素**。四张表全 `size==1`，而写入器与聚合逻辑各自都测过、各自都对。
   判据这次按"合计要等于汇总"写，才把它逮住（`every slice adds up to the same total`）。
3. **下拉框的 key 和表格的分组 key 不是同一把钥匙。** 表格按显示名分组，下拉却给了卡 id：
   一个组里既有挂了卡的行、也有改名之前留下的裸行（`preset` 空），按 id 筛就漏掉裸行 ——
   症状是"表上写着这个专家花了 31，筛完只剩 11"。现在两边都用分组名，`preset` 只当附带字段，
   并加了一条判据：**下拉里选任何一个 key，筛出来的回合数必须等于表上那一行的数**。

### 顺带修掉的一处真故障，和一处"一直红的判据"

`node pc/tools/ui-check.js` 在本批接手时就是红的（HEAD 上就红，不是这轮引入）。它报的三行里：

- **`Cfg.openCfg 在对象外裸用`是真的** —— 导航栏「设置 / 技能」那一行调 `openCfg()`，
  而这个函数关在 `Cfg` 那个 IIFE 里，对象外拿不到 ⇒ 点那行**静默抛 ReferenceError，抽屉永远打不开**。
  改成 `Cfg.open()`（面板本来就把 `open: openCfg` 导出了）。像素里补了一步：点导航行之后
  `#fMax` 那个输入框必须看得见 —— 这条在改之前是红的。
- 另外两行（`Files.kb`、`Presets.presets`）是**判据自己错了**：它把字符串里的一段文字当成了标识符引用
  （`'/api/kb'`、`'#tabs button[data-t=kb]'`、`fetch('/api/presets')`）。修法是按**行**扫引号状态
  （整份文件扫一遍会被中文注释里的一个撇号带偏，一旦错位就静默变成永远绿，比永远红更坏），
  外加一条：别处自己声明的同名（另一个 IIFE 里也有一份 `ROLE_MODES`）是遮蔽，不算裸用。
- 修完做了变异检查：把 `Cfg.open()` 改回 `openCfg()`，判据当场红；改回来，绿。
  **"判据现在是绿的"不等于"它还会为下次改动红"** —— 这条本项目已经吃过一次（`--bad` 没定义那次）。

### 还顺手改掉的一处旧设计

团队派工原来是"主持人把成员的整段人设抄进 `persona`"。抄出来的那份**没有任何一处会校验**：
少抄一段、把 A 的规矩抄给 B，成员照样跑、卡照样上墙，只是跑出来的不像那个人。
现在 `task` 收 `preset`（卡 id），人设与模型由 `Engine.spawn` 按卡现取，取不到就明确报错；
`persona` 退化成"临时给一个没建卡的角色"时才用。

### 验收

- `:test` 本批新增 **33 条**：`UsageSliceTest` 11（四个新维度、区间含尾整天、空天也出行、
  筛选口径一致、**顶层键不许写两遍**）、`XlsxTest` 7（真用 `ZipFile` 读回来逐部件断、
  数字不能是文本、XML 元字符与控制字符、表名去重与 31 字符上限）、`UsageExportTest` 9
  （四张表合计都等于汇总、筛选一路走到文件里、明细截断但聚合不截断）、`TokenPageTest` 6
  （**JS 里引用的每个 `#tk*` 元素必须真在标记里** —— 拼错一个字母页面只画一半而单测全绿）
- 像素：`bash pc/tools/ui-shot.sh pc/tools/steps/ui-tokens.json` → **36 步 / 42 条判据全过**，
  截图四张（汇总、按专家、按天、窄口检查）。第一版截图就露出三处判据抓不到的：
  第二行两张卡被 `flex:1` 摊满整行、"只看这一天"被拉成一条横幅、**两张"不同页签"的截图长得一模一样**
  （表在折叠线以下，没滚下去拍等于没看）—— 都改了，剧本里加了滚动那一步
- 一处已知未动：`<h4>` 与 `<b>` 里的加粗中文在这台机器上有重影（预存在缺陷，见任务 #88），
  本批只是没再往新文案里加粗

## 这一批：定时任务能指定"由哪个专家来跑"（对标 Octop 第 3 条）

Octop 的 `cron_jobs` 第一列就是 `agent_id`。HaoAI 这边原来定时任务只能跑一句**裸句子**：
用的模型、目录、档位全是全局默认，用户挑好的那张专家卡只在手动开会话时生效。
"每天早上把**那个仓库**的报错看一遍写份摘要"这句话，缺的正是"哪个仓库、谁来看"。

| 改了什么 | 落点 |
| --- | --- |
| `Schedule` 加 `preset`（卡 id） | `Schedules.kt`：存 `schedules.json`，老文件没这一键读成空 = "不指定" |
| 到点起的那条会话跟着卡走 | `startRun(..., preset)` → `newSessionId(Presets.workspaceOf(card), card)`：**人设、模型、档位、目录**四项一起生效 |
| 卡被删了**不静默降级** | `scheduleExpert(s)` 单独一个纯函数：返回 `(卡, 错误话)`；`runSchedule` 拿到错误就记 `lastError` 并把 `lastRun` **退回 0**（修好卡之后下一槽照常触发，不会被算成"已经跑过"） |
| 界面上能选、能看见 | 右栏「定时」表单多一个 `#crPreset` 下拉（第一项是"不指定专家（全局默认）"）；右栏每一行 + 日程整页的卡片都写"专家：X"，卡没了红字写"到点不会跑" |

一处自己踩出来的坑值得记：加 `preset` 时把 `save()` 里那行 `"created":${s.created}` **顶掉了**，
于是所有新建任务的 `created` 读回来都是"现在"，`interval` 的第一次触发被无限往后推。
只断"preset 在不在"的测试会一路绿到用户发现"到点没跑"。所以现在有一条
`save writes every field load reads back`：**整条 Schedule 存一圈回来必须一个字段都不少**
（data class 的 `equals` 天然覆盖所有字段，比逐字段断言更不容易漏）。

判据：`ScheduleExpertTest` 8 条（往返、老文件兼容、整字段往返、卡在与不在两条路、
服务端接线、`startRun` 带目录、界面两处都显示）；像素 `ui-cronexp.json` **25 步 / 22 条判据全过**，
含"手改请求塞一张不存在的卡 → 现在就拒、错误话里说是专家卡、且不许写进盘"。
跑法：`bash pc/tools/ui-shot.sh pc/tools/steps/ui-cronexp.json`
（这行不是装饰：`audit-steps.sh` 只认 README 里的原样跑法，没写就等于这份剧本永远不进全量审计。）

## 这一批：多 Agent —— 专家实例注册表 + 跨专家收件箱（对标 Octop 第 2 条）

`task` 早就有了，但那是**父子**关系：子任务没有身份、没有自己的目录、父会话结束它就没了，
也不能被第二个人点名调用。Octop 那一层是**同事**关系：每个专家是一个有状态的实例
（运行中 / 待命 / 已停止 / 出错），有自己的常驻会话与工作目录，别的专家能给它发消息。

| 补的东西 | 为什么这么做（而不是更省事的写法） |
| --- | --- |
| `Agents` 实例注册表（`agents.json`） | 状态只有"跑过没有、在不在忙"这些事实；人设与模型**仍然只在角色卡上**，不建第二真源。重启后 `running` 一律降级成 `idle` —— 那个线程已经没了，状态不能说谎 |
| `ask_agent`（`mode=sync\|background`） | 同步就地等回答；后台进收件箱、立刻回一句**带编号的回执**。"已发送"这种空回执不算交付 |
| `agent_list` | 没有它，模型只能凭训练记忆猜卡名，猜错就自己动手干了 —— 派工的前提是知道有谁能派 |
| **同一个专家串行、不同专家并行** | 并行派给两个专家是快；并行派给同一个专家是两个会话在同一个目录里抢着写文件 |
| 回信送回**发信人那条会话** | 只把答案留在收件箱里没人读，等于没回。这一步才是"同事之间话接下去了" |
| 执行口是注入的（`runner` / `followup`） | 和 `Gate`、`Scheduler.fire` 同一套路：调度与执行分开，这一整层能脱网脱模型测 —— 判据打在状态机上，不是打在"模型答得好不好"上 |

### 像素这一步抓到的三条（都不是断言能抓到的）

1. **后台消息在 2.5 秒时还是 `running`。** 真跑一句话要走完 mock 网关的整轮工具循环，
   判据必须**等到它动完**而不是睡固定时长 —— 睡够为止的写法在慢机器上会假红、在快机器上会假绿。
2. **投出去的消息在专家页上看不见**（`#xpInbox` 一直停在"还没有跨专家的消息"）。
   根因是我把收件箱那块挂在了 `refreshBadge()` 里，而 SSE 的 `agents` 事件走的是
   `applyAgents()` —— 数据到了、没刷它。改成 `paintInbox()` 独立一个函数，
   开页 / `paint()` / 事件到达 三处都调。
3. **停用之后又被我"重新启用"了**：那一步原本要验的是"停了就不该接活"，
   脚本里却先点了启用按钮再问 —— 判据自然全绿而什么都没验到。改成"停 → 问 → 必须被拒"，
   最后再单独一步把现场启用回来。

### 契约测试这次是帮了忙的

新加两把工具、一个新端点、一个新事件，四条**早就在那儿**的判据同时红：
`ApiDocTest`（`/api/agents` 没写进 docs/api.md）、`UiContractTest`（`agents` 事件前端没人接）、
`DesktopTest` + `EngineFlowTest`（内置工具数 24→26，注释里就写着"变了要同步改另一条"）。
这四条不是障碍 —— 它们正是"加了东西但另一处没跟上"的那类缺陷唯一的探测器。

判据：`AgentsTest` 12 条（卡不存在 / 同步问答 / 执行口没接上 / 失败留原因 /
**同一专家串行、不同专家并行** / 后台投递与回信 / 停用不许绕过 / 收件箱上限 /
名单带卡 id / 重启后状态不说谎 / JSON 形状 / 接线）；像素 `ui-agents.json` **22 步 / 29 条判据全过**；
跑法：`bash pc/tools/ui-shot.sh pc/tools/steps/ui-agents.json`
`:test` 全绿、`ui-check` 绿。

## 这一批：知识库做成"库"（对标 Octop 第 5 条）

原来右栏那个「知识」页签只是**当前工作区的文件列表**（`/api/kb` 单层列目录）：
没有"库"这个单位、没有解析、没有分块、没有检索，更没有"哪个专家带哪几本"。
Octop 那一层是 `knowledge_bases` → 文档（状态 / 块数 / 失败原因）→ `agents.knowledge_base_ids`
绑定 → `search_knowledge` 工具。这一批按同构补齐，并把"导入完先查一把"这步做进去了。

| 补的东西 | 为什么这么做（而不是更省事的写法） |
| --- | --- |
| `Knowledge`：库 / 文档 / 原件 / 抽出正文四层落盘（`kbs/<id>/`） | 解析会失败，而失败必须留痕：状态 `ready\|failed` + 原因原文，界面上照常列出这一行。"解析失败就从列表消失"是最坏的一种做法 —— 用户以为导进去了 |
| 解析：文本类直读，docx / xlsx / pptx 走 OOXML，pdf 走 `pdftotext` | OOXML 用 `java.util.zip` + 自己扫 `<w:t>`，不引新依赖（离线构建是红线）；PDF 不硬撑，本机没有 `pdftotext` 就在页眉写明"PDF 收不进"，而不是静默吞掉 |
| 块 800 字 / 重叠 120；检索走向量，没配 `embedUrl` 时走字面 | 没配云端 embedding 时**字面匹配照样能用**，页眉标"字面检索（没配 embedUrl）" —— 不把功能绑死在要联网的组件上 |
| `search_knowledge` 工具 + 每回合自动带上 | 绑定关系决定"这个专家能查到哪几本"；没绑卡的会话退回"每回合自动带上"那几本。`catalog` 只列绑定的库，模型不会看见自己查不到的东西 |
| 试检索面板：命中片段 + 出处（库名 / 文档名）+ 走的是哪种检索 + 分数 | Octop 没做这块，而这恰恰是导入后最该有的一步：查不到就等模型用错再回头查，成本差一个量级 |
| 库那一页反查"哪些专家绑了我" | 只有单向列表的话，删库之前没人知道会牵连谁 |

### 这批真正难的是量具，不是功能

1. **我把 `askText` 写重了。** 顶层早就有一个 `askText(title,tip,value,ok)`
   （「编辑并重发」「重命名会话」在用），这批新加"输入一句话"的弹层时又写了一个
   `askText(title,ph,ok)`。同名两次 `function` 声明**语法完全合法**，而后一份在提升阶段
   把前一份整个覆盖掉：四参数调用点传进来的第三个参数被当成 `ok` 回调、真回调落到第四位没人收，
   点「确定」只抛 `ok is not a function` —— 界面上的现象是"按钮点了没反应"，
   而 642 条 JVM 测试与 `ui-check` 其余各条全绿。新那份改名 `askOne`，并给 `ui-check`
   加了 **6.8) 同一作用域内不许有两次同名 function 声明**（按作用域分桶：各面板各自的
   `paint / load / card / open` 是两份独立作用域，合法，只按名字查会一次报出十几行假故障）。
   变异检查：把 `askOne` 再复制一份 → 红；撤掉 → 绿。
2. **6.6 / 6.7 从来没看过那五个新面板。** 旧写法是 `indexOf('const ' + p + ' = (() => {')`，
   而新面板写作 `const Kbs=(()=>{`（没有空格），找不到就 `continue` ——
   于是 Experts / Tokens / Kbs / Kb / SchedPage **一片绿是因为压根没跑**。
   现在面板区间由"行首 `const X = (() => {` … 行首 `})();`"配对算出来（18 个），
   名单不再手抄，并加了一条棘轮：面板数少于 18 就红（哪天被拆回顶层散函数会立刻显形）。
3. **`shadowedAt` 那张表也是半死的。** 它的正则写成 `\(\s*\)`（要的是 `() =>`），
   文件里是 `(() =>`，只匹配到 6 处；另外单行的 `const smenu=(()=>{…})()` 被当成
   "面板一直开着"，把后面 Cfg 的收口吃掉，Cfg 也跟着漏 —— 遮蔽判断错的方向是"少报"，
   所以它一直绿。
4. **字面量里的文字要跳过，而"跳过"的规则自己会错。** 新加的 `tools/js-literals.js`
   负责标出"每个下标是不是跨行模板串里的文字"，第一版不认正则字面量：
   一行 `re=/['"]/ ` 就把后面整段带偏，`<table class="kb-docs">` 里的 `table`
   被当成 `Tokens.table` 的裸用。它自带 10 条断言（`node tools/js-literals.js`），
   自己坏了会直接非 0 退出，而不是静默变成"永远绿"。
5. **打字打到了看不见的元素上。** 脚本往右栏旧「知识」页签的 `#kbQ` 里写"液态玻璃"
   （整页版那颗叫 `#kbxQ`）：`value` 改进去了、`input` 事件也派发了，判据全绿，
   而真正的检索一步都没跑 —— 先报错的是下一步"按钮 0 尺寸点不到"，因与果隔了四步。
   `shot.js` 的 `type` 现在先量目标，0 尺寸直接报错；顺带把"`enter` 默认 true 会顺手把弹层关掉"
   写进了步骤说明（要点「确定」钮的那一步必须写 `"enter":false`）。

### 像素这一步另外抓到的：表格把按钮裁了

七列文档表在 1280 宽的窗口下把最右边那颗「删」裁掉了半笔，而所有结构判据全绿 ——
`cols===7` 是真的，"看得见"不是真的。改成表头与操作列一律不换行、整表给最小宽度、
外层壳横向滚动，并补一条**几何判据**：先把壳滚到最右，再逐颗量操作按钮
（文字不许截断、右边缘不许超出可视区），外加表头不许折成两行。
变异检查两次：撤掉三条防裁规则 → `oneLine` 红；撤掉滚动壳 → `wrap / scrollable / btnOk` 红。
（这条判据的第一版把单行高度阈值写成 26px，被上下 padding 顶出来的 28px 误判成折行 ——
判据自己错了比没有判据更坏，已改成 34px 并加 `white-space` 计算样式核对。）

判据：`KnowledgeTest` 17 条（建库改名删库与重名拒绝、纯文本落块、docx 按段落分行、
xlsx 共享字符串与 pptx 按页顺序、html 去标签去 script、假 docx 必须留失败原因、
未知扩展名拒绝且路径压平、重导同名换内容留原时间、重解析从原件重抽、每库文档上限、
没配 embed 时退回字面匹配、查不到就明说而不是编一个、卡绑定与"每回合自动带上"兜底、
JSON 告诉界面能收哪些格式、提示里写的那把工具真的接上了）；
像素 `ui-kbs.json` **48 步 / 40 条判据全过**（截图 kb01 就绪、kb02 失败可见、kb03 命中带出处、
kb04 反查绑定、kb05 三把新工具进表）；跑法：`bash pc/tools/ui-shot.sh pc/tools/steps/ui-kbs.json`；
`ui-check` 绿（新增 6.8）、`js-literals` 自测绿、
`md-check` 绿、`:test` **642 条全绿**。

## 这一批：专家卡补齐（对标 Octop 第 1 条）

Octop 的「我的专家」之所以好用，不在卡片画得漂亮，而在那几张卡**各自带着自己的规矩**：
开关、欢迎语、能写清楚"点了会产出什么"的快捷提问、以及自己的运行参数。
HaoAI 这边的角色卡原本只有"人设 + 模型 + 工作区 + 档位"，加上展示层的
desc/icon/color/mbti，其余都跟全局走 —— 于是"口述用的卡"和"改代码的卡"
在温度与回合上限上是同一份设置，而新会话打开后是一片空白。

| 补的东西 | 为什么这么做（而不是更省事的写法） |
| --- | --- |
| `enabled` 开关（卡级，不是实例级） | 关着的卡**四件事同时成立**才算真关：落库 false、卡片压暗带「已关」徽标、`/api/new` 与 `startRun` 都拒并说原因、`agent_list` 不再把它列给模型。只在界面上灰掉＝假开关；而模型名单里留一个问不动的名字，只会换来一次失败的派工 |
| `welcome` 欢迎语 + 空会话第一屏的快捷提问 | 画在**一条话都没说过**的那屏，说开就不再有 —— 挂在会话流里就是广告。数据从面板已拉到的那份卡里取，不发第二发请求（两条路读同一份卡，早晚对不上） |
| 快捷提问升级成 `{title, desc, prompt}` | 按钮上的字要短到一眼扫完，发出去的那句要写全。以前是同一句话：要么按钮撑爆，要么提示词被削到模型看不懂。`desc` 做悬停说明。**老卡的字符串形状仍能读入**（prompt 留空 = 就发标题） |
| 每卡 `temperature` / `maxTokens` / `maxTurns` | 哨兵用 `-1` 不用 `0`：温度 0 是"要确定性输出"，`maxTokens` 0 是"不发这个字段"，用 0 当"没设"就永远表达不了"我要设成 0"。**没做 `top_p`**：全链路搜不到这个参数，加一个输入框只会变成"收了不用的死参数"（那种缺陷在移动端已经踩过一次） |
| 复制 / 导出 / 导入一张卡 | 复制是为了改：副本换新 id、默认是开着的。导入**必须换 id** —— 带着原 id 进来会覆盖用户手里那张同名卡，"导入"变成"删掉我的" |
| 卡片 / 列表两种视图 | 24 张上限下"谁开着、谁绑了库、温度多少"扫一眼比翻卡片快。列表那张表与知识库同一口径：表头不换行、操作列不换行、装不下由壳横向滚 |

### 这批抓出来的四个缺陷，全都只有像素能抓

1. **`/api/state` 不报 `preset`。** 字段在**落盘的会话文件**里有，接口没往外发 ——
   于是前端拿到一条"带角色的空会话"却不知道角色是谁，欢迎卡永远画不出来。
   数据层有 ≠ 界面上有，这条判据（点开始对话后 `.xw` 必须出现）才把它钉住。
2. **类名 `.qrow` 撞了。** 新加的"快捷提问一行三格"起名 `.qrow`，而 `.qrow` 已经被
   输入框上面那行"运行中排队"占了（`display:none` + `.on` 才显示）—— 后写的赢，
   三格输入框**永远不显示**：元素在 DOM 里、`querySelector` 找得到、
   而 ui-check 的"class 有没有规则"照样绿（它只问有没有，不问谁赢）。
   改名 `.qfields`，并给 ui-check 加了 **2b) 同一个 class 不许被两条顶层规则写成互相冲突的 display**。
   这条规则一上线又揪出 `.navcol` 与 `.ctx` 两处 —— 查下来是 `@media` 里的窄屏覆盖，
   属合法，于是判据限定为**只算顶层规则**（否则它自己就是一条长期红的假故障）。
3. **卡底九颗按钮把中文标签折成两行**（"开始/对话"、"关/掉"）。结构判据看不见，
   是看截图看见的。加了几何判据：一颗钮的高度上限 34px、文字不许截断。
4. **右栏「角色」页签加到第四颗按钮后，名字被挤成竖排**（截图里"师阿真"一个字一行）。
   现在那一行是"名字 + 已关徽标 + 设置"占满第一行、四颗按钮换到第二行，
   并补了几何判据（名字一行高度上限、按钮不许截断、按钮必须在名字下面）。
   关着的卡在这里同样压暗 + 打徽标 + 「用它开一条」真的 disabled ——
   只在专家页标、右栏照点，等于把同一句话讲两遍（点了才被服务端拒）。
4. **`quick` 从字符串变对象，卡上按钮会渲染成 `[object Object]`。**
   渲染层统一走 `quicks(p)`（两种形状都吃），JVM 侧走 `parse` 里的兼容分支，
   两边各有一条判据盯着老形状。

### 顺手把三处字段清单收成一份

`Presets` 的 `save` / `json` 以前各抄一遍字段清单（`load` 第三遍），
加一个字段要记得改三处，漏掉那处的表现是"表单里填了、重开就没了"。
现在收成一份 `fields(p)` + 一份 `parse(el)`，并由 `PresetFieldsTest` 逐字段比
（不用 `assertEquals(p, back)` 整体比：整体红看不出是哪个字段丢的）。

判据：`PresetFieldsTest` 9 条（每个字段存得进也回得来 / 接口带齐表单要填的每一项 /
老卡字符串形状仍能读 / 默认值就是哨兵 / `applyTo` 只动设过的那几项 /
关着的卡按名字拒并说清 / 复制换新 id 且默认开着 / 导出→导入往返 /
读不懂的 JSON 必须拒）；像素 `ui-xpcards.json` **71 步 / 85 条判据全过**
（xp01 卡与参数、xp02 空场欢迎卡、xp03 关着的态、xp04 导出、xp05 列表视图、xp06 右栏「角色」页签的开关与压暗）；
`ui-check` 绿（新增 2b）、`md-check` 绿、`:test` **651 条全绿**。

## 这一批：内置专家库那八张卡也得有新字段（对标 Octop 第 1 条的收尾）

字段补齐之后回头一看，最该用它的地方反而是空的 —— `expert-library.json` 里八张内置卡的
`quick` 仍是三条长句、`welcome` 一个都没有。于是用户点「启用内置专家」拿到的那张卡，
恰好不带这两个新功能：这比没做更难看，因为「我的专家」里自建卡有、内置卡没有。

- 每张卡补一句欢迎语（不超 120 字，与表单那格的 `maxlength` 对齐 —— 库里写超了，启用后一编辑就被截，
  而截掉的半句用户看不见）、三条 `{title, desc, prompt}`（标题短到能进按钮，正文才是发出去的那句）。
- 温度按角色性质给：数据分析师 0、代码工程师 0.1、运维值班 0.1、调研分析 0.2、会议纪要 0.2、
  学习教练 0.7、文案写手 0.9；**小通故意留 -1**（跟全局）—— 不然「不覆盖」这条路在内置库里
  从没被走过，哨兵值读得对不对就没人验过。
- 「内置专家」页签上的卡也跟着补了展示：原来只画名字 + 简介 + 人设折叠，启用之前根本看不出
  这张卡会带哪三句、第一屏写什么、温度设了没。现在那三句画成**条子而不是按钮**
  （`.quick span`，光标 `default`）：卡还没启用，点了没有会话可发，画成按钮就是骗人。
- 新增 `ExpertLibraryTest` 5 条：库能解析且展示字段齐 / 每张卡都有欢迎语且不超表单上限 /
  快捷提问的标题与正文不许一模一样（那还分两栏干什么）/ 温度只能是 -1 或 0..2 且至少一张留 -1 /
  **每张卡走一遍同一套解析器（`Presets.importJson`），字段一个都不许丢**。
  在这之前 JVM 侧没有任何一条判据打开过这个文件：它坏了的表现是页面显示
  「（没有匹配的内置专家）」，看起来像这个库本来就该是空的。
  对着旧库先跑了一次，红了 3 条 —— 那 3 条正是「库里没有这些字段」的样子（变异检查顺带做完）。
  补完库再跑，红的那条是**我自己的测试**：`assertEquals(key, card.name)` 拿 `copy-writer`
  去比「文案写手」。测试桩自己错也算一次收获，但它只在"库已经改对了"之后才露出来。

### 又补了量具自己的两个洞（都是「绿着但没跑」那一类）

1. `tasks.test` 的 `inputs.files(...)` 里没声明 `src/main/resources/expert-library.json` ——
   只改这个库时 Gradle 判 UP-TO-DATE，新加的 5 条测试根本不会重跑（10-02 做变异检查时
   被同一件事糊过一次，这次的教训写在那条注释里）。
2. `ui-shot.sh` 的「二进制比源码旧就不跑」只盯 `*.kt` 与 `*.html`，**改 json 资源不报** ——
   而资源是打进 jar 的，于是那一轮像素验的还是上一版程序。改成盯 `src/main` 下所有文件。

### 全量剧本审计这一轮：68 份跑完，红 3 份，没有一份是功能坏了

| 剧本 | 现象 | 真因 | 处置 |
|---|---|---|---|
| `ui-msgops` | 第 11 步 `null.click` | 3ea9efe 把置顶/重命名/删除收进了 `⋯` 菜单，剧本还在点行上的 `[data-a=pin]` | 改按真流程：点 `.more[data-a=menu]` → 断言菜单开了、条目文字齐、没跑出视口 → 点 `.mi[data-m=pin]`；取消置顶那条钉「文字必须翻成取消置顶」 |
| `ui-pal` | 第 10 步 `palSolid=false` | 75ccffe9（v6 皮肤层）把 `--panel` 改成半透明并给浮层自挂 `backdrop-filter:blur(24px)`，「必须是实心 rgb()」这条已经不成立、也不该成立 | 判据改钉真实主张：alpha 够（0.7）+ 真磨砂（blur 16px 以上）+ 颜色格式仍必须是 rgb()/rgba()（`palKnown`，防格式一换两条都算过）。改之前看过 `p00-palette-open.png`：条目全部可读，背后只剩一层糊光 |
| `ui-phone` | rc=2 语法错 | **我在审计跑着的时候改了 `ui-shot.sh`**：bash 按字节偏移边读边执行，往前面插几行，正在跑的那份就会在后面的某一行炸掉（它 46 步 32 条判据其实全过） | 等审计结束再动量具；重跑确认全绿 |

那份 v6 提交的说明里写着「像素剧本 66 份不受影响」—— 它没跑过。这是「慢实现不等于深验证」的
又一次：改了皮肤层的人必须自己跑一遍全量剧本，否则那句话只是愿望。
顺带一条老坑复发：**单跑一份剧本前先把 README 里那行完整命令抄过来**。`ui-pal` 少了
`PRE_MEMORY=1` 就是 `rows=0 / armed=false`，红得像「记忆整理坏了」，其实是夹具没造出来。
`audit-steps.sh` 从 README 抓命令就是为了不再犯这条。

### 验收

`:test` **656 条全绿**（+5 条内置库判据）；`ui-xpcards.json` 从 71 步 / 85 判据扩到
**90 步 / 110 判据**，新增那几段是一条完整的链：内置页签的卡有欢迎语与三条条子 →
点「启用」后表单被预填（名字 / 欢迎语 / 标题不等于正文 / 温度 0.9 / 开着）→
存下来读服务端字段一个不丢 → 用它开一条会话，第一屏出现欢迎卡且三颗是**能点的按钮**。
`ui-msgops` 28 步 / 23 判据、`ui-pal` 65 步 / 75 判据、`ui-check` / `js-literals` / `md-check` 全绿；
图 `xp07-library`、`xp08-enable-prefill`、`xp09-lib-welcome` 逐张看过。
跑法：`bash pc/tools/ui-shot.sh pc/tools/steps/ui-xpcards.json`

## 这一批：「我的团队」第一次被人眼看一遍（Octop 第 1 条剩下的最后一角）

补完内置库回头扫了一遍 `steps/*.json`：搜"团队"两个字**零命中**。也就是说团队页签、新建表单、
成员勾选、"至少 2 名"的拒法、开团之后顶栏那颗章，一直只有 JVM 契约测试守着（编制存法、
主持人现拼人设、上限 8 支），没人看过一眼像素。补了 `ui-xpteams.json`。

一条链走完：两张卡打底 → 团队页空态要说人话并给下一步 → 新建表单里的成员格是从「我的专家」
现生成的（`boxes==0` 就等于这张表只能看不能勾，而它会静默显示成一句提示，肉眼容易当成正常）→
只勾一名点保存要**当场三样都成立**：话说了、盘上没写、表单还开着（只断言 toast 的话，
"弹了提示但还是存进去了"抓不到）→ 补齐两名存下来（服务端 + 卡片头像 + "2 名成员"三头对齐）→
开团 → 顶栏那颗章 → 真发一句话拿到回答（主持人那条路的引擎、档位、人设都接上了才叫能用）→
编辑回填两个勾 → 解散只删队不删卡。

**抓出来一处真缺陷（改了代码）**：顶栏章的模板是「角色 · X」，而团队会话的 role 本身就带
「团队 ·」前缀，直拼出来是「角色 · 团队 · 项目冲刺组」—— 两颗小章挤在一颗里。
现在带前缀的角色原样显示，判据 `isTeam` / `noDouble` 钉住。

**一处"看着像缺陷、结果是我看错了图"**：右栏「团队速览」那一行在小图上像被划了删除线，
而 `.rl` 的样式里没有任何 line-through。与其猜，把 computed `text-decoration-line` 与行高钉成判据
（`asideNoStrike` / `asideTall`），再按 `SHOT_DPR=2` 重拍一张 —— 干净，是 dpr=1 缩放的假象。

**一处判据自己写错**：原本写 `closed = !document.querySelector('#xtName')`。`close()` 只摘遮罩与
onclick、不清 innerHTML，字段留在原地但已经点不着 —— 正确的期望不是"元素消失"，
而是"关掉的表单不能再吃输入"（`formInert` 量 `offsetParent`）。这与上一批"往看不见的同名输入框
打字，value 照样生效"是同一件事的两面。

验收：`ui-xpteams.json` **41 步 / 39 条判据全过**（截图 xt01 空态、xt02 拒、xt03 卡片、
xt04 会话、xt05 聊过一轮、xt06 编辑回填、xt07 解散）；跑法：`bash pc/tools/ui-shot.sh pc/tools/steps/ui-xpteams.json`；
`ui-check` 绿、`:test` **656 条全绿**（这批没加 JVM 判据：团队的数据层已有 `ExpertTeamKbTest`）。

## 这一批：团队派工跑通到记账，并修掉「钱回到主持人头上」

上一份剧本验到「开团能聊」就停了。这一份接着往深走一层 —— **主持人把活派给成员，
那笔钱记在谁名下**（Octop 的"按专家"就靠这个，而"这个队里谁最贵"恰恰是开团队的人要看的数）。

- 假网关加了 `team` 模式（`SHOT_MODE=team`）：主持人一轮吐 `task{label,preset}`，
  成员那一轮吐结论，主持人再汇总。父子两份剧本按派工标记分道 —— 只按 `tool_rounds`
  取行的话，成员的第一轮会被当成主持人的第二轮（`subloop` 早就踩过，同一个办法）。
- 新剧本 `ui-xpteam.json`（**32 步 / 17 条判据全过**）：两张固定 id 的卡 → 开团 →
  顶栏章确认是团队会话 → 说一句让它派工 → 界面上出现一张挂「前端老张」的子任务卡 →
  跑完打勾 → 主持人消化后收尾（认那句「汇总：」）→ `/api/usage?kind=sub` 里这一组是
  「前端老张」且带卡 id `xpteam-a` → Token 统计「按专家」那一行看得见 →
  删掉一个成员的卡，团队卡在**开团之前**就红字说缺员。截图 xt10–xt13 逐张看过：
  专家分布里「团队 · 冲刺小队 2,842」与「前端老张 1,321」是分开的两笔。

### 修掉的这一处是真缺陷（`Engine.spawn` 的归属条件）

原来两行是 `s.role / s.preset = if (persona.isNotBlank()) …`。看着对，其实错在条件选错了：
**卡可以只填名字不填人设**（「新建专家」只要求二者之一），这种卡派出去时 `persona` 是空的，
于是这笔钱退回主持人头上 —— 恰恰是那段注释声称要避免的事。另一半是 label：模型爱写什么写什么
（"帮我查一下"、重名还会被 `uniqueSubName` 加成"… 2"），拿它当专家名就是**在统计里造一个假人**，
而那支卡的名字本来就是已知的。改成以「这张卡在不在」为条件、专家名取卡名：

```kotlin
s.role = card?.name ?: if (persona.isNotBlank()) label else session.role
s.preset = if (card != null || persona.isNotBlank()) opts.preset else session.preset
```

新增 `SubAttributionTest` 8 条（按 id 派工记到那张卡 / **只有名字的卡也要记到那张卡** /
label 不许造出假专家 / 没点卡的普通派工仍跟父会话 / 卡没了要当场拒且不留下账）。
对着旧代码先跑：三条红（正是上面三种坏法），两条绿（不回归的基线）。修完 **665 条全绿**（后面几条判据见下面两节：一次派两名、提示与循环口径一致、一条回合两个调用）。

### 顺手抓到的第二处：主持人提示向模型承诺了一件系统不做的事

提示里原来写着「同一回合可以并行派多个成员」，而 `Engine` 那一圈是 `for (call in turn.calls)` ——
**顺序执行**。这不是措辞问题：模型按"同时"安排工作，一次派两条然后抱怨慢，而没人会去查提示与循环谁在说谎。
改成「一条回合里可以连着派多条，但它们会一条接一条跑完，不是同时跑」，并加一条两头一起钉的判据：
文案不许出现"并行"、要说清"一条接一条"，同时源码那一圈还得是那个 `for`（真有人并行化了这条会红，
逼着改文案的人与改引擎的人在同一处碰面 —— 与 `WallpaperWiringTest` 的"参数收了没用"同一类源码级判据）。
另加一条：同一条团队会话连着派两名成员，账上要留下两个专家名与两个卡 id
（第一版没筛账本 —— 同一个临时 home 里前面几条测试也派过工，量出四个名字，红在测试自己身上）。

### 这一批自己踩的两条量具坑（都写进判据注释了）

1. **报表里那一列叫 `name` 不叫 `expert`**：判据写成 `rows.find(x=>x.expert=='前端老张')`
   永远拿不到，`hasExpert=false / tokens=0` 看着像"账没记上"，实际是读了一个不存在的键。
   同一份响应里 `presetIndexed=true` 才把方向掰回来（行在、归属也在）。
2. **中文引号进 JSON 字面量会被规范成 ASCII 双引号**，整份剧本当场不解析（这条坑之前就记过，
   这次是在 note 里用了一对引号）。剧本里的引用一律用「」。

跑法：`SHOT_MODE=team bash pc/tools/ui-shot.sh pc/tools/steps/ui-xpteam.json`

### 又往深一层：一条回合派两名成员（并行工具调用）

`ui-xpteam.json` 添了「两个都派」这一段（现在 **40 步 / 25 条判据**）：主持人一次返回两个 `task`
调用 → 界面上两张子任务卡（前端老张 + 测试小李）→ 两张都打勾 → 主持人并成一句汇总 →
账本里两个人各一笔。**小李那张卡只有名字、人设是空的**，量到 `xpteam-b / 测试小李 / 1,321 token`
才说明上一节修的归属条件在真链路上成立（像素 `xt14-team-dual.png` 就是这两张卡并排）。

这一段第一次跑是红的，而且红得很有误导性：界面上只有一张卡、账上只有一个人，
主持人却宣称两件都办完了 —— 看起来像"引擎吞了第二个 tool_call"。三条判据把它切开：

1. `SubAttributionTest.one round that returns two task calls runs both members`（脚本客户端直接喂
   两个调用）→ **绿**，说明回合循环没吞；
2. `ProviderStreamTest.two tool calls in one stream both survive parsing`（真 HTTP + 真 SSE，
   按假网关的分片形状交错发两个调用）→ **绿**，说明流式解析没吞（这条判据本身是留下来的资产：
   原来那份只测了一个调用的分片拼装，并行工具调用在 Provider 层是零覆盖）；
3. 留一份现场（`KEEP_HOME=1`）读落库的会话历史 → 第 5 条消息直接是收尾那句，
   中间**一个 tool 调用都没有** ⇒ 假网关根本没派活。

真因在量具：假网关按**整条会话累计**的 `role=="tool"` 数取剧本行，而同一条团队会话里先跑过
一段单人派工（留下一条 tool 回复），于是"两个都派"这一回合的索引直接跳到剧本最后一句收尾。
改成按**回合内**的轮数取行。症状值得记一句：模型没派任何活、却宣称两件都办完 ——
这种"叙事完整、动作没发生"的假绿，只有把落库的历史翻出来才看得见。

## 这一批：绑两个库时，第二个库的字面兜底被饿掉了（Octop 第 5 条）

`Knowledge.search` 的字面兜底条件写的是 `hits.isEmpty() || embedUrl.isBlank()`，而 `hits` 是
**跨库累计**的：一张卡绑两个库，只要第一个库语义命中，第二个库的字面那条就再也不跑。
症状是"第二个库里明明有一模一样的词，模型却老实回答『知识库里没查到』" —— 它没编，是真的没查到。
改成只问一句：**这个库自己命中了吗**。

新判据 `a second bound kb still gets its literal fallback` 顺带把**语义那条路第一次接上真测**
（原来 JVM 只测过"没配 embedUrl 走字面"，向量这一路一直是零覆盖）：起一个假 embed 端点，
按大小写敏感分向量（含 `alpha` → [1,0]，否则 [0,1]）；A 库正文里有 `alpha`，B 库里只有
`AlphaCorp` —— 语义算不到 B，而字面那条不区分大小写，B 里明明命中。两条主张一起钉：
两个库都得有命中、每条命中都得说自己来自哪个库、是语义还是字面。

变异检查做过：把条件改回 `mine.isEmpty() && hits.isEmpty()`，这条立刻红，
报的正是 `expected [A 总纲, B 数据表] but was [A 总纲]`。

验收：`:test` **666 条全绿**；`ui-kbs.json` 在新二进制上重跑 **48 步 / 40 条判据全过**
（跑法：`bash pc/tools/ui-shot.sh pc/tools/steps/ui-kbs.json`）。

## 这一批：专家卡上看得见绑了哪几个库，点一下就跳到那个库

库那边早就能反查"哪些专家绑了我"，反过来不行 —— 想知道一张卡绑没绑库，得去「知识库」页
一个个翻。现在卡片上多了一排库标签：**活着的库是一颗钮**（带份数，点了跳到那个库并选中它），
**被删掉的库不许画成钮** —— 服务端要等下次保存才剪掉失效 id，平时它是悬空的，
点开一个空列表等于骗人一次，所以画成灰色条子写着「库已删除 + id」。

顺带修一处空转：`/api/kbs` 的 `op:create` 原来写的是 `base?.let { }` —— 看着像在用新建出来的
那个库，其实什么都没做，也不把 id 回给调用方。于是"建完就绑到这张卡上"这种两步操作只能再拉一遍
列表才认得出刚建的是哪一个。现在回 `{ok,kb,name}`，`docs/api.md` 跟着改了。

像素这一段是 `ui-xpcards.json` 的结尾几步（整份现在 **100 步 / 121 条判据全过**）：
建两个库并导一份文档 → 把两个都绑到一张卡上 → 删掉其中一个 → 卡上必须同时出现
"一颗钮 + 一个库已删除" → 点那颗钮要落到知识库页、左边选中「剪辑规范」、右边列出 `rule.md`，
并且那一页的反向链接里写着「绑到这张库的专家：绑库测试」（两头指同一件事，不是各存一份真相）。
截图 `xp10-card-to-kb.png` 看过。

一处量具的假红值得记：新入口本来叫 `openAt`，而专家面板里已经有一个私有 `openAt` ——
ui-check 的私有名判据把 Kbs 导出里那一行认成了"专家面板的名字在对象外裸用"，直接 FAIL。
改名 `openFor` 之后绿。这是 #130 的又一个具体例子：**它没有跨面板分辨同名的能力，
连注释里提到那个名字都会被抓**（第二版注释里写了名字，同样 FAIL —— 注释里也别留）。

## 这一批：答案下面写得清「这句参考了知识库哪几段」（Octop 第 5 条的最后一角）

库里查出来的东西上了屏，却看不出**上屏的是查来的还是编的** —— 这是前面几批知识库剩下的
最后一块：`search_knowledge` 的结果只进工具卡正文，而那一张卡回合一收尾就 `open=false` 收了，
用户想核对出处得先点开、再在一堆散文里找库名。Octop 是在回答下面直接给来源的，现在这边也跟上了：
检索卡**外面**多一条竖线勾出来的引用带 —— 一行「参考了知识库 · 库名 / 文档名 · 去这个库」，
底下最多三段带命中方式与分数的原文片段（其余的仍在卡里，不重复占地方）。

三个决定值得留着：

- **出处从工具层结构化地交出来**（`ToolCtx.found` → `Ev.ToolEnd.cites`），不是让前端正则拆
  `out` 那几行文本：那是给模型看的散文，文案一改引用就丢，而且丢在界面上看着一切正常。
- **按 `tool_call` id 关联，不按消息下标**：压缩、删这一句、删到这里都会让下标整体前移，
  按下标配对的表现是"引用挂在别的卡底下"。`/api/state` 因此每条消息多报一个 `cid`，
  顶层多一个 `cites` 清单；`Msg` 是两端共用的结构，**没往里加 PC 专属字段**（加了手机端读不懂）。
- **画在卡片外面**：卡片收了，出处不能跟着收 —— 而"跑完这一次"恰好会 `hydrate` 重画整屏，
  所以实时（SSE）与回放（state）两条路径共用同一个 `citeStrip()`。#111 那条降级提示就是
  只写了 SSE 那一路，跑的时候看得见、刷新全没了。

被这条测试抓出来的**真缺陷（我自己这批写的代码）**：`cites` 字段一开始声明在 `init { … }` 之后，
Kotlin 的属性按声明顺序初始化，`restoreSummaryMeta` 刚从会话文件读进来的那份引用**被后面的
`= LinkedHashMap()` 整体抹掉** —— 表现正是"落盘有、重开就没了"，而这条只有"重开会话再看一遍"
的判据抓得到。文件里 `lastCompactedChars` 上方本来就写着同一个坑的警示注释，我照样踩了一遍。
落盘时还按活着的 `callId` 收一遍孤儿引用（话删了出处必须跟着走，不然界面挂着已经不存在的来源）。

判据：新增 `KbCiteTest` 4 条（检索交出结构化出处 / cid 与消息配对并重开还在 /
失败与空命中不留引用 / 删掉那一轮引用跟着删）。像素 `ui-kbcite.json` **25 步 / 37 条判据全过**：
建库并把「每回合自动带上」打开 → 问一句 → 出处带出现且**卡片是收起的**、片段里真有那句话 →
点「去这个库」落到知识库页并选中它 → `location.reload()` 真重载后再按标题找回那条会话，
出处还在、且 `state` 里 `cid` 与 `cites` 对得上 → 新开一条空会话不许挂着上一条的出处 →
切回来还在。截图 `kc01-live-cite` ~ `kc05-back-to-cite` 五张看过。

跑法：`SHOT_MODE=kb bash pc/tools/ui-shot.sh pc/tools/steps/ui-kbcite.json`
（假网关新加了 `kb` 模式：第一轮真调 `search_knowledge`，第二轮才答，词与库里那条逐字相同，
所以字面检索的命中是确定的，不靠碰运气相似）。

### 这一批被像素与测试各抓出一处，都不是"功能没做"而是"看着像做了"

- **手搓 JSON 少一个 `]`**：`stateJson` 尾部原来是 `sb.append("]}")`（关 messages 数组 + 关对象），
  我插入 `cites` 时把它换成 `"],"cites":["` 却只在末尾补了 `'}'` —— 于是 `/api/state` 回的是
  `…"cites":[}`。`gradle test` 一次红 **32 条**（所有会解析 state 的用例），
  报错信息里直接把残缺的尾巴打了出来。同族的旧账是手机页空白那次（ask 分支多一个 `}`）：
  **手搓 JSON 的每一处括号改动，都要有一条"读回来解析一遍"的判据跟着**。
- **`window.Kbs` 这个守卫永远是假**：那颗「去这个库」钮写的是
  `if(window.Kbs&&Kbs.openFor)…else setView('kb')`。而壳子里面板是顶层 `const Kbs=(()=>{…})()` ——
  **顶层 const 不挂到 window 上**，所以守卫判假，走进兜底那一支：只切页、不加载。
  截图 `kc02` 上那一页是**空白的知识库**（左边一个库都没有、右边只剩导入框），
  而步 8 的 `clicked/id` 两条判据全绿 —— 因为钮确实点了、id 确实带着。
  修法是直接叫 `Kbs.openFor(…)`（同一个脚本作用域里本来就看得见），
  并把那条假兜底删掉：**没有把握的降级路径比报错更坏，它把坏结果演成正常**。
  这条与 #113「参数收了没用」、以及"关着的浮层上打字判据全绿"是同一类：**判据要落在
  用户看得见的那个结果上**（这里补的是第 10 步的 `picked/docShown/autoTag`）。
- 顺带两处观感：字面命中的分数是占位的 `1.0`，画成"字面 1"看着像百分之百可信 ⇒ 不再画分数；
  标题行原来把库名/文档名重复列一遍，只留「参考了知识库 N 段」，名字交给下面每一条。

### 同批补上：只读分享快照里也要看得见出处

`/api/share` 导出的那份单文件 HTML 原来把工具行**只留正文第一行并截 400 字**，于是"这句参考了
哪几段"在快照里只剩偶然带出来的第一条命中名字，既没有片段也没有其余几条 —— 而拿到快照的人
（转发给同事看结论的那种场景）最需要核对的恰恰就是来源。现在每条检索下面会跟一段绿竖线的
`<p class="cite">参考了知识库 库名 / 文档名 · 字面 + 那段原文</p>`。

`Share.render` 的入口因此从"三元组"扩成 `Row(role, name, text, cid)` + 一份按 `cid` 分组的
`CiteLine` 清单（**不按行序配**：导出时消息可能被裁过，按位置配会把出处挂到别的行底下）。
三元组那条老入口保留为重载 —— 代价是 `ShareTest` 里 `emptyList()` 推不出类型了，
编译器报的是"推不出类型参数 T"（看着像产品写坏了），已就地写明原因。

判据：`KbCiteTest` 加第 5 条，**照抄服务端那两行映射**（`messages → Row(带 cid)`、
`citesSnapshot → groupBy(cid)`）再渲染 —— 快照的错从来不在渲染器里而在配对那一步，
只测 `render` 喂一份手搓的 map 的话，产品里那两行写错了照样全绿；同时钉住两条负判据
（清单为空不许凭空长出 `class="cite"`、旧入口不许被改坏）。像素 `ui-kbcite.json` 因此从
25 步 / 37 条扩到 **31 步 / 46 条全过**：导出 → 真跳进那份快照 → 等 `p.cite` 出现并**看得见** →
断言库名/文档名/片段/命中方式都在、且 `document.scripts.length===0`（快照承诺过零脚本）。
截图 `kc06-snapshot-cite.png` 看过。

## 这一批：上下文压缩自己那一次模型调用，进了 Token 统计（Octop 第 4 条的口径）

Octop 那张统计图顶上挂着一句免责："部分消耗（如记忆总结、召回等）尚未纳入统计"。
这边把同一件事做得更实一点：**把它算进来**。全仓只有这一个非回合的模型调用点
（`Engine.compactOnce` 里那句 `client.chat(...)`），而它原来只取 `.text`、把 `.usage` 丢了 ——
于是"上下文压缩"每次花的 1200/30 之类的数在账本、Token 统计页、导出的 xlsx 里**全都不存在**，
而页面上那个最大的数写着「总 TOKENS」。

落法：`UsageLedger` 多一档 `kind=compact`，`Engine` 多一个 `bookCompact()`
（累加会话累计 + 落一行账），报表的 `compact` 计数收在 `aggFull`/`summary` 两处共用的形状里，
Token 页在卡片下面多一行口径说明：「已含系统开销：上下文压缩 N 次（不算回合，但真花了 token）」，
没发生过也要说一句「这段时间没有发生上下文压缩」—— 沉默会被读成"又在藏什么"。
**不冒充回合**：`n` 是"人说了几句 / 模型答了几轮"，把压缩算进去会让"每次对话多少 token"虚高。

判据：新增 `CompactLedgerTest` 2 条 —— ① 连着说五轮逼出一次真压缩，断言假客户端确实被问了
`tools=[]` 那一次、账本里多出且只多出 `kind=compact` 的那一行（1200/30/缓存 700 都在）、
会话累计也跟着涨了；② 报表里 `compact` 这个口径存在且不为 0，且按 `kind=main` 筛的时候
不许把压缩混进来。假客户端**按"不带工具表"认压缩**，不按提示词里的词认 ——
文案一改就静默不再触发的那种判据，红了也不知道为什么。
像素 `ui-compact.json` 尾部加 4 步（现在 40 步 / 20 条判据）：先按接口确认 `summary.compact>=1`，
再进 Token 统计页，断言那行说明**看得见**且**句里的数与接口一致**（数对不上的口径说明就是装饰）。
截图 `k06-compact-counted.png`。

### 顺手用二分把自己排除掉：第 16 步 `fewer` 是预存在的红

`ui-compact.json` 第 16 步（按「压缩一次」之后气泡要变少）在这次跑里是红的。
我没有把结论停在"应该不是我"：把 `index.html` 换成 10:23 那份（我这两批之前）重新构建再跑，
**同一处照样红**，而新加的三步在旧 UI 上按预期红（说明它确实依赖我这批的改动）。
所以这条红不是这两批引入的，是"按下去之后当前这一屏没重画，刷新才变"这一类问题
（`said:true` 说明压缩真发生了、右栏也写着"已折进摘要 7 条"，盘上会话也确实从 14 条折成 4 条）。
已单独立任务 #135，别当成"审计里的一条噪音"划掉。


## 这一批：「默认专家」那枚徽标真的管事了（对标 Octop 专家卡上的"默认"）

对着 Octop 那张专家页逐元素比下来，HaoAI 这边**已经有**搜索框（`#xpQ`，按名称/专长过滤）、
卡片/列表两种视图、导入卡、概览计数、卡上开关与欢迎语 —— 上一轮我说"还差搜索和两种视图"
是**没读代码就下结论**，读了一遍才发现真缺的是另外两样：卡上看不见 **专家 id**，
也没有**「默认」**这个概念（Octop 那张"小通·通用助手"卡上写着 `专家 ID main` 和一枚"默认"）。

落法：

- `Preset.defaultOne` 一个位，`/api/presets` 多一条 `op:'default'`（`id` 给空串 = 取消）。
  为什么不做成"设置里存一个 defaultPreset id"：**卡被删了那个 id 就悬空**，还得再造一套
  "清掉失效 id"的活；位打在卡上，删卡即清。`Presets.setDefault` 在写的时候把别的卡那一位居掉 ——
  两张都写"默认"的表现是点新对话用哪张全看文件顺序。
- 「新对话该挂哪张」收成纯函数 `Presets.presetForNewSession(requested, team, defId)`，
  Server 与测试走同一份逻辑。三条边界：**点了卡就用点的**、**团队会话不许被默认卡顶掉**、
  **默认卡关了/删了就回落到"没挂专家"** —— 最后这条是重点：用户没点过任何卡，
  静默换成另一张才是错（点名要一张关着的卡仍要拒并说明，那是显式选择）。
- 卡上多一个 `id prxxxx` 的复制钮（对外接口、定时任务、别的专家点名都要用这个 id）。

判据：`DefaultPresetTest` 4 条（存的下也读得回 / 设新的清旧的 / 指到不存在的卡要拒 /
那三条边界逐个钉，含"关了默认卡之后新会话 preset 为空"）。像素 `ui-xpdefault.json`
**20 步 / 25 条判据全过**：两张卡轮着设默认 → 徽标只在一处、钮文字从"设为默认"变"取消默认" →
点「新对话」后 `/api/state` 的 `preset` 与顶栏角色**真的**是那张默认卡 → 把默认卡关掉再走接口
新建一条（必须指定工作区：点 `#newBtn` 在"这条还一句没说过"时会复用旧会话，那是刻意的，
不复用就量到上一条）→ `preset`/`role` 都空 → 点名要那张关着的卡仍被拒。
截图 `xd01-default-set` ~ `xd03-id-and-off` 看过。

跑法：`bash pc/tools/ui-shot.sh pc/tools/steps/ui-xpdefault.json`

## 已验证到哪一步

- `gradle test` → **677 条全绿**（2026-10-03 实测；566 → Token 统计 +33、定时任务指定专家 +8、
  多 Agent +12、知识库 +17、专家卡补齐 +9、内置专家库 +5、子任务归属 +8、Provider 并行分片 +1、多库检索兜底 +1、
  知识库引用出处 +5、压缩那次调用入账 +2、「默认专家」+4 与另两处小改；
  下面那串分项细账是 522 时代的构成，只当历史看，
  各批最新份数写在自己那一节：订阅源与权限扫描 26、本地 review 12、ConPTY 探针 1、ConPTY 实现 2、
  run_verify 6、语义索引 8、结构契约 4、ask_user 对齐 7、批量问卷 6+1、命令行读数 9、文档表格格数 2）（整套约 75 秒，媒体那 14 条要真跑 ffmpeg、代码那 8 条要起解释器，所以慢）：21 条引擎流程（计划模式拒写且 write 不进 schema、
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
  工具开关 4、断点恢复 4、子任务单独停 4、备份导出恢复 8、媒体工具 14、Git 面板 8、运行历史 7、终端面板（Pty 新增 2）、代码执行 8、音视频附件 3、浏览器预览面板 13、手机联动（局域网）12、检查点回滚 9、录屏 9，这里不再逐条追账。
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
   HTML 产出沙箱预览、长代码块折叠、会话级工具开关、断点恢复、审批键盘决定、子任务单独停、备份导出/恢复、
   MCP 客户端与设置抽屉里的管理段、子任务（`task`）、媒体工具 ffmpeg 与产出直接播、
Git 面板、命令面板与任务运行历史、终端页签（人和 agent 共用常驻进程）、
音视频附件、`run_code`（python/node 跑一段代码并把产出的图交回模型）、浏览器预览面板（人自己看画面、自己点）、
   手机联动 PC 侧（局域网端点 + 配对码 + 只读会话镜像 + 远程审批；手机端那一半还欠）、
   检查点回滚（「产出」页签底部「回到某次之前」，整轮撤销一次任务的改动）、
   录屏（`record` 工具：录桌面 → 停 → 立刻在界面里放 → 交给 `media` 剪）。
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
  Tools.kt      23 把内置工具 + 溢出落文件 + 快照 + diff；allTools() = 内置 + 外部 MCP
  Mcp.kt        MCP 客户端：stdio 上的换行 JSON-RPC，把外部 server 的工具包成引擎的 Tool
  Policies.kt   S2 权限规则表：tool(pattern) 有序匹配 + 命令前缀归约 + alwaysAsk
  Risk.kt       审批风险分级：三档判据（整词集合 / 管道形状 / 路径落点）+ 一句"为什么"
  GitTool.kt    一把 git（子命令切分保留引号；只读不问人，改仓库走同一张规则表）
  Pty.kt        S3 常驻交互进程（open/send/read/close/list）+ 共用的 shell 启动器；
                输出是带序号的环形缓冲，模型与终端页签各拿一个游标
  Media.kt      ffmpeg 定位与调用 + 按魔数认音视频（MediaMime）+ Range 解析（边播边拖）
  RunCode.kt    `run_code`：临时脚本 + python/node，运行目录里新出来的图/音视频交回两侧
  GitPanel.kt   人用的 Git 面板后端：status/暂存/diff/提交（不吃 porcelain 行首空格）
  PreviewPanel.kt 预览面板的纯逻辑：网址白名单 + 图上坐标→页面坐标 + 状态 JSON
  RunLedger.kt  一次运行一行的 HAOAI_HOME/runs.jsonl（最近 500 条，供「用量」页回溯与 ↻）
  Lan.kt        手机联动：配对码与设备 token（盘上只存哈希）+ 局域网 HTTP 面（默认不监听）
  ui/phone.html 手机网页端：配对 / 会话与待批 / 只读镜像（同一份 HTML 由局域网端口发出去，不装 APK 就能用）
  Checkpoints.kt 一轮动过哪些文件的账本（HAOAI_HOME/checkpoints.jsonl）+ 整轮回滚
  Record.kt     录屏：ffmpeg gdigrab 的进程注册表，停止走 stdin 的 q（不是 kill，否则 mp4 放不出来）
  Browser.kt    CDP 浏览器控制 + 手写极简 WebSocket 客户端（CdpSocket）
  Desktop.kt    屏幕理解与点击级自动化：生成的 PowerShell 模板 + PsRunner
  Engine.kt     回合循环、档位闸、两级截断、上下文压缩、中断、会话持久化
  Compress.kt   压缩的两份产出：给模型的提示 + 确定性机器摘要（兜底）
  SessionIndex.kt 磁盘会话索引：列表 / 恢复 / 改名 / 删除（移进 .trash）
  Schedules.kt    定时任务：时间算法（刻意不补跑）+ HAOAI_HOME/schedules.json + 5 秒轮询线程
  SchedulePlan.kt 一句话排期："每周一三五 8 点""半小时后" → kind/at/days/runAt + 一句复述（看不懂就拒绝，不猜默认时间）
  Digest.kt       定时结果汇总：从 runs.jsonl 里挑 trigger=定时 的那些，给桌面/手机/导出的三个出口（不另存一份真相）
  Workflows.kt    任务链（工作流）：一行一步，跑在同一条会话里 —— 第一步起头，其余交给已有的排队队列接力
  Presets.kt      角色卡（预设 agent）：人设 + 模型 + 工作区 + 档位，「用它开一条」一次带上
  Secrets.kt      凭据条目：key 的读/改/撤与掩码（明文只进磁盘，任何响应都不回）
  Hooks.kt        钩子：事件（run-end）挂一段用户自己写的命令，异步跑、失败只记账不打断会话
  SkillDocs.kt    技能目录：HAOAI_HOME/skills/<slug>/SKILL.md（与手机端同一形状）+ 粘贴/网址/zip 三个入口
  Share.kt        只读快照：一条会话导成一个自包含 HTML（零脚本、零外链，markdown 在服务端渲一小撮）
  Memories.kt     条目化长期记忆：工作区 MEMORY.md 一行一条（与手机端 MemoryBank 同一格式）+ 搜索/软取代/按预算注入
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
