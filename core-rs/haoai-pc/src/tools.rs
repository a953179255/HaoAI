// 由 pc/tools/gen-rust-tools.mjs 从 Tools.kt / AgentConfig.kt 生成，勿手改。
// 重新生成：node pc/tools/gen-rust-tools.mjs
// 校验：cargo test --release（golden 的「工具说明」字数 + /api/state 逐字节门禁）

use crate::utf16::{utf16_len, utf16_take};

pub struct ToolMeta {
    pub name: &'static str,
    pub kind: &'static str,
    pub desc: &'static str,
    pub params: &'static str,
    pub category: &'static str,
    pub critical: bool,
}

pub const TOOLS: [ToolMeta; 32] = [
    ToolMeta { name: "read", kind: "read", desc: "读取文件内容，返回带行号。大文件用 offset/limit 分段读。", params: "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\"},\"limit\":{\"type\":\"integer\"}},\"required\":[\"path\"]}", category: "文件与终端", critical: true },
    ToolMeta { name: "write", kind: "write", desc: "新建或整篇覆盖一个文件。改已有文件请优先用 edit，整篇覆盖会丢掉你没看到的行。", params: "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}", category: "文件与终端", critical: false },
    ToolMeta { name: "edit", kind: "write", desc: "文件内精确替换。old_string 必须在文件中唯一，除非 all=true。", params: "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"old_string\":{\"type\":\"string\"},\"new_string\":{\"type\":\"string\"},\"all\":{\"type\":\"boolean\"}},\"required\":[\"path\",\"old_string\",\"new_string\"]}", category: "文件与终端", critical: false },
    ToolMeta { name: "glob", kind: "read", desc: "按通配找文件，如 **/*.kt。返回相对工作区的路径列表。", params: "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"}},\"required\":[\"pattern\"]}", category: "文件与终端", critical: true },
    ToolMeta { name: "grep", kind: "read", desc: "在文件内容里搜正则，返回 文件:行号:内容。找符号、定位问题用它，别整篇读。文本 0 命中且配置了 settings.embedUrl 时，会附带「语义近邻」（把 pattern 的字面当自然语句去问向量端点）——适合\"意思相近但用词不同\"的找法；正则写法对语义这段无效。", params: "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"glob\":{\"type\":\"string\"},\"ignore_case\":{\"type\":\"boolean\"}},\"required\":[\"pattern\"]}", category: "文件与终端", critical: true },
    ToolMeta { name: "shell", kind: "exec", desc: "执行命令并返回 exit code + 合并的 stdout/stderr。shell=pwsh（默认）或 bash（Git Bash，需要管道、heredoc、$() 时用它）。超时用 timeout 秒控制。", params: "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"},\"shell\":{\"type\":\"string\"},\"timeout\":{\"type\":\"integer\"},\"cwd\":{\"type\":\"string\"}},\"required\":[\"command\"]}", category: "文件与终端", critical: false },
    ToolMeta { name: "shell_open", kind: "exec", desc: "起一个**常驻**的交互式进程（bash/pwsh/cmd），之后用 shell_send 喂输入、shell_read 捞输出。适合 ssh、mysql>、python -i、需要回答确认提示的构建命令。command 非空则启动后立刻执行。tty=true 走 Windows ConPTY 真伪终端（isatty 为真：有颜色/进度条/行编辑，Ctrl+C 真的是中断——shell_send 传 text=\"\\u0003\" 且 enter=false）；建不了会明确报错，不会静默降级。只要读输出、不需要 TTY 时保持默认（管道模式更省资源）。", params: "{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\"},\"shell\":{\"type\":\"string\"},\"command\":{\"type\":\"string\"},\"cwd\":{\"type\":\"string\"},\"tty\":{\"type\":\"boolean\"}}}", category: "文件与终端", critical: false },
    ToolMeta { name: "shell_send", kind: "exec", desc: "往常驻进程的 stdin 写一行输入（enter=false 可发不带换行的原始字符；tty 会话里传 text=\"\\u0003\" 且 enter=false = 发 Ctrl+C 中断当前命令，管道会话发了也没人当信号）。", params: "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},\"enter\":{\"type\":\"boolean\"}},\"required\":[\"id\"]}", category: "文件与终端", critical: false },
    ToolMeta { name: "shell_read", kind: "read", desc: "取常驻进程新产生的输出。wait_ms 是「等多久算这一轮说完」，默认 1200。", params: "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"wait_ms\":{\"type\":\"integer\"},\"max_chars\":{\"type\":\"integer\"}},\"required\":[\"id\"]}", category: "文件与终端", critical: false },
    ToolMeta { name: "shell_close", kind: "exec", desc: "关掉一个常驻进程。shell_list 可看现有的；空闲 20 分钟会自动回收。", params: "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}", category: "文件与终端", critical: false },
    ToolMeta { name: "shell_list", kind: "read", desc: "列出当前常驻进程：id、在跑什么、多久没被碰、是否还活着。", params: "{\"type\":\"object\",\"properties\":{}}", category: "文件与终端", critical: false },
    ToolMeta { name: "git", kind: "exec", desc: "在工作区仓库里跑 git。sub ∈ status|diff|log|show|blame|add|commit|branch|checkout|restore|stash|rev-parse。args 传该子命令的参数串。只读子命令不问；改仓库的按权限规则走。", params: "{\"type\":\"object\",\"properties\":{\"sub\":{\"type\":\"string\"},\"args\":{\"type\":\"string\"},\"cwd\":{\"type\":\"string\"}},\"required\":[\"sub\"]}", category: "文件与终端", critical: false },
    ToolMeta { name: "todo", kind: "read", desc: "维护本次任务的步骤清单（整体替换，每次传完整列表）。status ∈ pending|doing|done|cancelled", params: "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\"}},\"required\":[\"items\"]}", category: "规划与子任务", critical: true },
    ToolMeta { name: "ask_user", kind: "read", desc: "向用户提出带选项的问题并暂停等待回答（运行挂起，用户点选后继续）。遇到分叉、歧义或要替用户做假设时，先调用本工具问清再动手，不要自己猜：例——用户说「设个 9 点的闹钟」没说早上还是晚上 → 问；「让回答更有创意」可以调温度也可以改提示词 → 问；两个方案都可行、删除/覆盖等不可逆操作前 → 问。不要用于纯闲聊，也不要连续高频调用。把推荐项放在第一个；用户总可以看到自由输入出口。低风险、选错也无代价的问题加 confirm=false 让用户点选即回答，交互更省事；问卷/测试等无优劣之分的选择加 recommend=false 隐藏「推荐」徽标。", params: "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\",\"description\":\"完整提问：一句话说清背景与要决定的事，以问号结尾\"},\"options\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\",\"description\":\"选项短标签（≤12 字），卡片上直接显示\"},\"description\":{\"type\":\"string\",\"description\":\"该选项的含义/代价/后果，一行话；可省略\"}},\"required\":[\"label\"]},\"description\":\"2~4 个互斥选项；推荐项放第一个\"},\"allow_free_text\":{\"type\":\"boolean\",\"description\":\"是否允许用户自由输入其他回答，默认 true\"},\"confirm\":{\"type\":\"boolean\",\"description\":\"是否需要用户点选后再按确认按钮（防误触）。默认 true。低风险、选错也无代价的事实/偏好选择（如早上还是晚上）设 false：用户点选项即回答、任务立刻继续；删除/覆盖/花钱等不可逆或高代价的分叉必须保持 true\"},\"recommend\":{\"type\":\"boolean\",\"description\":\"是否给第一个选项标「推荐」徽标，默认 true。仅当你确实倾向该选项时才标；各选项无优劣之分的问题（测试问卷、量表打分、抽签类）设 false，不要标推荐\"}},\"required\":[\"question\",\"options\"]}", category: "交互", critical: false },
    ToolMeta { name: "ask_user_batch", kind: "read", desc: "整批问卷/测试题一次提交并挂起等待：界面本地循环出题，用户选完自动跳下一题、全程不回模型，答完所有题一次性把全部答案返回给你（N 题只占 1 次往返）。连续多题的同型问法用本工具：性格测试、满意度调查、知识测验、偏好批量收集等 ≥5 题的题组。题组较长时分批提交（每次 30~50 题，答完一批再提交下一批）。单个决策分叉/一次性提问仍用 ask_user。题目与选项描述是写给用户看的：不要夹带内部记分、题号编排等元信息。", params: "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"description\":\"题组标题，显示在卡片头部（如「MBTI 性格测试」）\"},\"questions\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\",\"description\":\"题干，一句话，以问号或句号结尾\"},\"options\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\",\"description\":\"选项短标签（≤12 字）\"},\"description\":{\"type\":\"string\",\"description\":\"该选项含义的一行补充，可省略\"}},\"required\":[\"label\"]},\"description\":\"2~6 个互斥选项，各题可不等长\"}},\"required\":[\"question\",\"options\"]},\"description\":\"1~100 道题，按出题顺序排列\"},\"allow_free_text\":{\"type\":\"boolean\",\"description\":\"每题是否提供「其他…（自由输入）」出口，默认 false\"}},\"required\":[\"title\",\"questions\"]}", category: "交互", critical: false },
    ToolMeta { name: "web_fetch", kind: "net", desc: "抓一个网页转成纯文本（max_chars 默认 8000，最多 30000）。", params: "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},\"max_chars\":{\"type\":\"integer\"}},\"required\":[\"url\"]}", category: "网页与检索", critical: false },
    ToolMeta { name: "web_search", kind: "net", desc: "搜网页。query 给关键词（可以带 site:、年份这类限定），返回若干条标题 / 链接 / 摘要。要读某条全文，接着用 web_fetch 那条 url。", params: "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}},\"required\":[\"query\"]}", category: "网页与检索", critical: false },
    ToolMeta { name: "browser", kind: "net", desc: "控制一台本机浏览器（Edge/Chrome，走 CDP）。sub ∈ status|open|navigate|read|eval|click|type|screenshot|shot|tabs|close。shot = navigate + 截图一步到位。read 拿正文，eval 跑 JS，screenshot 存 PNG 到工作区。首次调用会自动拉起一台用独立配置的浏览器。", params: "{\"type\":\"object\",\"properties\":{\"sub\":{\"type\":\"string\"},\"url\":{\"type\":\"string\"},\"selector\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},\"script\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"max_chars\":{\"type\":\"integer\"}},\"required\":[\"sub\"]}", category: "桌面与媒体", critical: false },
    ToolMeta { name: "screen", kind: "exec", desc: "看/操作这台电脑的屏幕。sub ∈ capture(全屏截图)|windows(列窗口)|ui(读某个窗口的控件树)|focus(当前键盘焦点在哪个窗口)|click(按坐标点)|invoke(按控件名字点，优先用它——不依赖坐标)|type(键入)|key(按键)。流程：windows 找窗口 → ui 看树拿控件名 → invoke；只有没有控件名的地方才用 click 点坐标。**type/key 之前先 focus**：键盘输入发给的是此刻的前台窗口，不一定是你刚点过的那个。type 走 Unicode 直发，中文/特殊字符都不会被输入法改写；key 走 SendKeys 语法（^=Ctrl、%=Alt、{Enter}），输入法处于中文模式时 ^a 这类组合键可能被输入法截走。", params: "{\"type\":\"object\",\"properties\":{\"sub\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"button\":{\"type\":\"string\"},\"double\":{\"type\":\"boolean\"},\"text\":{\"type\":\"string\"},\"keys\":{\"type\":\"string\"},\"max_depth\":{\"type\":\"integer\"},\"max_nodes\":{\"type\":\"integer\"}},\"required\":[\"sub\"]}", category: "桌面与媒体", critical: false },
    ToolMeta { name: "task", kind: "read", desc: "派一个子任务去做一件独立的事：它有自己的上下文，做完只把最终结论带回来。适合两类活：会刷出一大堆中间结果的调研、能并行做的几块。一次调用只交代一件事，要并行就同一回合里多调几次。可选 model（这条子任务用哪个模型，例如脏活交给本地小模型）、tools（只给它哪几把工具，逗号分隔；不写就是父会话现在能用的那些）、mode（plan/ask/auto；**只能比父会话更严，不能更松**）、preset（派给哪一张角色卡：填卡 id，人设与模型由系统按卡现取）、persona（临时给一个没建卡的角色时才用它，有 preset 就别抄人设）、subagent（子智能体库的 slug —— 给这次派发一个固定身份：定义里的人设按需自动带上，可派的清单见系统提示「可派发的子智能体」）。", params: "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"},\"label\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"tools\":{\"type\":\"string\"},\"mode\":{\"type\":\"string\"},\"persona\":{\"type\":\"string\"},\"preset\":{\"type\":\"string\"},\"subagent\":{\"type\":\"string\"}},\"required\":[\"prompt\"]}", category: "规划与子任务", critical: true },
    ToolMeta { name: "media", kind: "exec", desc: "用 ffmpeg/ffprobe 处理音视频素材。sub ∈ info（探测时长与流，只读）| transcode（转码/改分辨率）| cut（按时间剪切）| frame（抽一帧成图，图本身会递给你看）| audio（抽音轨）| cover（封面图）| caption（给封面/某一帧叠一行大标题，做视频封面与直播缩略图）| srt（把「几点到几点说什么」写成字幕文件，不需要源文件也不碰 ffmpeg）| subtitle（把 .srt 烧进画面，出的是新视频）| join（两段按顺序拼成一段，会重编码）| speed（整段变速，rate=2 是两倍速，口播讲快了用它把时间压回去）| fade（开头淡入、结尾淡出，切片首尾不生硬）| mix（把一段 BGM 压在口播下面：input2 是音乐，gain 是音乐的音量倍数，默认 0.18）。input 是源文件（工作区相对路径或绝对路径）；output 可省，默认写进 .haoai-output/media/。start/dur 认 12、12.5、1:05、1:02:03 四种写法。caption 要 text，subtitle 要 subs，join 要 input2，mix 也要 input2，speed 要 rate，fade 用 fadeIn/fadeOut（秒，默认各 1 秒），srt 要 items（JSON 数组：[{\"start\":\"0\",\"end\":\"2.5\",\"text\":\"第一句\"}]）。除 info 外都会写文件，按权限档走。", params: "{\"type\":\"object\",\"properties\":{\"sub\":{\"type\":\"string\"},\"input\":{\"type\":\"string\"},\"output\":{\"type\":\"string\"},\"start\":{\"type\":\"string\"},\"dur\":{\"type\":\"string\"},\"width\":{\"type\":\"integer\"},\"height\":{\"type\":\"integer\"},\"fps\":{\"type\":\"string\"},\"crf\":{\"type\":\"integer\"},\"preset\":{\"type\":\"string\"},\"codec\":{\"type\":\"string\"},\"reencode\":{\"type\":\"boolean\"},\"timeout\":{\"type\":\"integer\"},\"text\":{\"type\":\"string\"},\"items\":{\"type\":\"string\"},\"subs\":{\"type\":\"string\"},\"input2\":{\"type\":\"string\"},\"size\":{\"type\":\"integer\"},\"pos\":{\"type\":\"string\"},\"font\":{\"type\":\"string\"},\"force\":{\"type\":\"boolean\"},\"rate\":{\"type\":\"string\"},\"fadeIn\":{\"type\":\"string\"},\"fadeOut\":{\"type\":\"string\"},\"gain\":{\"type\":\"string\"}},\"required\":[\"sub\"]}", category: "桌面与媒体", critical: false },
    ToolMeta { name: "run_code", kind: "exec", desc: "跑一段代码并返回输出。lang=python（默认）或 node。代码写进临时文件再执行，所以多行、引号、中文都没问题。要产出文件就写到 result 里告诉你的那个运行目录 （.haoai-output/runs/…），那里面新出来的图片会直接给你看、音视频会在界面播放。timeout 秒，默认 120，最多 900。", params: "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"},\"lang\":{\"type\":\"string\"},\"timeout\":{\"type\":\"integer\"},\"cwd\":{\"type\":\"string\"}},\"required\":[\"code\"]}", category: "文件与终端", critical: false },
    ToolMeta { name: "record", kind: "exec", desc: "录屏（Windows 桌面抓取，产出 mp4）。action=start 开录（可选 fps 默认 15、maxSeconds 默认 300 上限 1800、area=\"x,y,宽x高\" 只录一块、audio=\"dshow 设备名\" 顺带录声音）；action=stop id=… 结束并收尾文件；action=status 看现在在录什么。录像是长任务：开完继续做别的，要停了再 stop。", params: "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\"},\"id\":{\"type\":\"string\"},\"fps\":{\"type\":\"integer\"},\"maxSeconds\":{\"type\":\"integer\"},\"area\":{\"type\":\"string\"},\"audio\":{\"type\":\"string\"}},\"required\":[\"action\"]}", category: "桌面与媒体", critical: false },
    ToolMeta { name: "run_verify", kind: "exec", desc: "把「检测项目类型→构建→启动→验证→停止」一条链接着跑完，逐段报告结果。type 可手动指定 gradle|npm|maven|cargo|python（不传就按工作区文件猜）；build 传构建命令覆盖默认表，传 \"-\" 跳过构建；start 传要起的长驻命令（给了才会启动并在最后停掉，停止先 Ctrl+C=shell_send text=\"\\u0003\" enter=false 再兜底关闭）；verify_url / verify_port / verify_file 至少给一个当验证判据（可多个，全过才算过；url 状态码 <500 即算活，404 也是有服务在应答）；timeout_ms 是验证轮询上限（默认 30000）。", params: "{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\"},\"build\":{\"type\":\"string\"},\"start\":{\"type\":\"string\"},\"verify_url\":{\"type\":\"string\"},\"verify_port\":{\"type\":\"integer\"},\"verify_file\":{\"type\":\"string\"},\"timeout_ms\":{\"type\":\"integer\"}}}", category: "文件与终端", critical: false },
    ToolMeta { name: "agent_list", kind: "read", desc: "列出这台机器上的专家（角色卡）与各自的状态：谁在跑、谁空着、谁被停了。要请别的专家做事，先用它认名字，再把 to 填进 ask_agent。", params: "{\"type\":\"object\",\"properties\":{}}", category: "团队", critical: false },
    ToolMeta { name: "ask_agent", kind: "read", desc: "请另一个专家做一件事（它有自己的会话、人设、模型和工作目录）。to 填专家卡名或 id（不确定的话先调 agent_list）；text 把要它做什么、要什么产出写清楚。mode=sync 就地等它的回答；mode=background 先回一句「已投进收件箱」，它忙完会把回信送回你这条会话。同一个专家正在忙时同步问会被拒 —— 要等它就改 background，别在这里排第二件。", params: "{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},\"mode\":{\"type\":\"string\"}},\"required\":[\"to\",\"text\"]}", category: "团队", critical: false },
    ToolMeta { name: "search_knowledge", kind: "read", desc: "在知识库里查一句话，返回命中的片段与出处（库名 / 文档名）。问的是制度、规范、手册、接口文档里的事实就查它，别凭印象编。kb 可以留空：默认查这张角色卡绑定的那些库（没绑就查标了「每回合自动带上」的库）。没查到会说没查到，不会硬凑一个答案给你。", params: "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"},\"kb\":{\"type\":\"string\"}},\"required\":[\"q\"]}", category: "知识", critical: false },
    ToolMeta { name: "cron_list", kind: "read", desc: "列出定时任务：id、名字、人话排期、下次运行、启用状态。要停用/改动某条，先用它拿 id。", params: "{\"type\":\"object\",\"properties\":{}}", category: "定时", critical: false },
    ToolMeta { name: "cron_create", kind: "write", desc: "新建定时任务。phrase=什么时候（一句话排期，如「每天早上八点半」「每周一三五 17:00」「每隔 30 分钟」）；prompt=到点跑什么（会起一条新会话去跑，不搅乱当前对话）；name=任务名（可选）。排期看不懂会被拒绝并给例子——照它说的改。改已有任务：cron_list 拿 id，删了重建（或去界面「定时」页签）。", params: "{\"type\":\"object\",\"properties\":{\"phrase\":{\"type\":\"string\"},\"prompt\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"required\":[\"phrase\",\"prompt\"]}", category: "定时", critical: false },
    ToolMeta { name: "cron_toggle", kind: "write", desc: "启用/停用一条定时任务（id 见 cron_list）。", params: "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"enabled\":{\"type\":\"boolean\"}},\"required\":[\"id\",\"enabled\"]}", category: "定时", critical: false },
    ToolMeta { name: "memory_search", kind: "read", desc: "在长期记忆里检索与 query 最相关的几条（事实/偏好/决定）。不确定某件事记住没记住、或要引用用户说过的话时用它。", params: "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}", category: "记忆", critical: false },
    ToolMeta { name: "memory_get", kind: "read", desc: "按重要度列出长期记忆条目（要紧的、最近记的排前面）。limit 默认 20。", params: "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}", category: "记忆", critical: false },
];

/// `Engine.readOnlyTool`：计划模式允许哪几把 —— 只读类，加计划本身要用的两件。
/// 生成的表里 todo / ask_user / ask_user_batch 的 kind 本来就是 read，后两个条件看着多余；
/// 留着是照抄 Kotlin：**判据只写一遍**，可见性过滤（`schemas()`）与执行侧闸口（`exec`）
/// 共用它，否则会出现"模型看得见却调不动"或"看不见却调得动"两种裂缝。
pub fn read_only(t: &ToolMeta) -> bool {
    t.kind == "read" || t.name == "todo" || t.name == "ask_user"
}

/// `Server.stateJson` 的 tools 段：`desc.take(90)` 是 **UTF-16 单元**意义上的截断，
/// 32 条里有 17 条会被截，所以必须走 utf16_take 而不是 chars().take()。
/// 注意与 `schemas_string` 的分工：`toolInfos()` 是 **map 全部工具并标 off**（界面要能
/// 看见被关掉的那几把好再打开），只有喂给模型的 `schemas()` 才把 off 过滤掉。
pub fn tools_json(off: &[&str]) -> String {
    TOOLS.iter().map(|t| {
        format!(
            "{{\"name\":{},\"kind\":{},\"desc\":{},\"off\":{},\"gated\":{},\"category\":{},\"critical\":{}}}",
            crate::state::quote(t.name),
            crate::state::quote(t.kind),
            crate::state::quote(utf16_take(t.desc, 90)),
            off.contains(&t.name),
            false,
            crate::state::quote(t.category),
            t.critical
        )
    }).collect::<Vec<_>>().join(",")
}

/// 复刻 `Engine.schemas().toString()`：`ToolSchema` 是 data class，Kotlin 生成的
/// `toString()` 形如 `[ToolSchema(name=read, desc=…, params={…}), …]`，
/// **desc 原样拼进去、不做任何转义**（含换行与逗号）。
/// `contextBreakdown` 里的「工具说明」字数量的就是这个串的 UTF-16 长度，
/// 所以要连这个非 JSON 的格式一起复刻，不能图省事序列化成 JSON。
pub fn schemas_string(off: &[&str]) -> String {
    let items: Vec<String> = TOOLS.iter().filter(|t| !off.contains(&t.name))
        .map(|t| format!("ToolSchema(name={}, desc={}, params={})", t.name, t.desc, t.params))
        .collect();
    format!("[{}]", items.join(", "))
}

/// 「工具说明」的字数（UTF-16 单元，与 Kotlin `String.length` 同口径）。
pub fn schemas_chars(off: &[&str]) -> usize {
    utf16_len(&schemas_string(off))
}
