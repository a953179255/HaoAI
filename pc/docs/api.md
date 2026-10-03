# HaoAI PC 的 HTTP 接口

> 这份文档是**被测试钉着的**：`ApiDocTest` 从 `Server.kt` / `Lan.kt` 的路由表里抓出每一个真实存在的
> 路径，逐个要求它出现在下面三张表里（反向也查：文档里不许出现代码没有的路径）。
> 所以加一个端点却没写文档，`gradle test` 会红 —— 文档漂移这件事不该靠自觉。
>
> 一句话结论：**能对外承诺的只有「任务」这一小组**（下面第 1 节）。
> 第 2 节那一大批是网页壳自己用的，随版本会变，别写进你自己的脚本。

## 0. 先说清楚的两件事

**没有鉴权，靠的是"只听本机"。** `/api/*` 全部挂在 `127.0.0.1`，谁能够到这台机器的这个端口，
谁就能让 HaoAI 写文件、跑命令。所以：

- 不要把 `8712` 端口转发出去、也不要绑到 `0.0.0.0`；
- 要给**手机**用，走的是另一套 `/lan/*`（第 3 节）：6 位配对码、一次一用、120 秒过期，
  设备 token 只存 SHA-256，端点默认不监听。
- 审批默认开着：外部程序发起的任务照样会弹卡等人点（`ask` 档），
  全自动要么把会话切到 `auto` 档（高危仍然弹卡），要么写规则表。

**任务是异步的。** `POST /api/task` 立刻返回 `sid`，**不返回结果** ——
结果要从 `GET /api/events`（SSE）里读，或者轮询 `GET /api/state`。
一条会话同时只跑一个任务，不同会话可以并行。

## 1. 给外部程序用的（承诺稳定）

| 方法与路径 | 做什么 | 请求 | 响应 |
|---|---|---|---|
| `POST /api/task` | 起一个任务 | `{"text":"…","sid?":"…","images?":[…],"media?":[…]}` | `{"ok":true,"sid":"pc…"}`；同一条会话已经在跑 → **409** `{"ok":false,"error":"…"}` |
| `GET /api/events` | 事件流（SSE） | — | `event:` + `id: <sid>` + `data: <json>`，见下表 |
| `GET /api/state?sid=` | 一条会话现在的完整状态（不流式，适合轮询） | — | `{running,mode,model,modelNow,workspace,version,title,messages[],cites[],todos[],queue[],outputs[]…}` |
| `POST /api/stop` | 停掉这一轮（已经跑完的工具不会重跑） | `{"sid":"…"}` | `{"ok":true}` |
| `POST /api/decide` | 回答审批卡或 `ask_user` 的提问 | `{"id":"a1","decision":"allow_once\|allow_session\|allow_rule\|deny\|partial:101","answer?":"…"}` | `{"ok":true}` |
| `POST /api/mode` | 切权限档（plan/ask/auto） | `{"sid?":"…","mode":"ask"}` | `{"ok":true,"mode":"ask"}` |
| `GET /api/sessions?q=` | 会话列表（`q` 会连消息正文一起搜） | — | `[{id,title,workspace,updated,…}]` |
| `POST /api/new` | 开一条新会话 | `{"workspace?":"…"}` | `{"ok":true,"sid":"…"}` |
| `GET /api/runs` | 任务运行账本（谁在什么时候跑了多久、成没成） | — | `[{sid,title,trigger,turns,ms,stopped,out}]` |
| `GET /api/agents` | 多 Agent：专家实例（谁在跑）与跨专家收件箱 | `—` | `{ok,agents[{preset,name,state,stateName,sid,lastAt,turns,lastError}],inbox[{id,to,toName,from,text,state,created,doneAt,error,reply}],busy[]}` |
| `POST /api/agents` | 启停一个实例，或直接托一句话给它 | `{"op":"start\|stop\|ask","preset":"卡id","text?":"…","mode?":"sync\|background"}` | `{"ok":true,…}`；卡不存在 / 已被停 / 它正在忙 → `{"ok":false,"error":"…"}`（**不会**降级成"没有角色"继续跑） |
| `GET /api/usage` | Token 统计报表：区间 + 按专家 / 按模型切片 | `?from=&to=`（epoch ms，含首尾两天）、`&expert=`（卡 id 或显示名）、`&kind=main\|sub` | `{range{from,to,fromText,toText},summary{n,prompt,completion,cached,total,okRate,tps},byDay[],byExpert[],byModel[],experts[]}`；老键 `today/week/all/models/days/okRate/tps/rows` 一并保留（**按全量算**，不随区间漂） |
| `GET /api/usage/export` | 同一口径导成 .xlsx（汇总 / 按天 / 按专家 / 按模型 / 明细五张表） | 同上 | 二进制：`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` + `Content-Disposition: attachment`（数字是真数字，能在 Excel 里求和） |

### SSE 的事件类型

`id:` 字段就是这条事件属于哪条会话（浏览器侧读 `e.lastEventId`），所以一个连接可以同时看多条会话。

| `event:` | `data:` 是什么 | 什么时候来 |
|---|---|---|
| `delta` | 一段文本（字符串） | 模型流式吐字 |
| `answer` | 这一轮的完整正文 | 一轮答完 |
| `reason` | 思考过程的一段 | 推理模型 |
| `tool` | `{id,name,brief,state,subject}` 起手；`{id,name,ok,out,card,diff,note,sub,media[]}` 收尾 | 每次工具调用两条 |
| `sub` | `{label,kind,text}` | 子任务的中间过程 |
| `stats` | `{pt,ct,ms,turns}` | 每一轮结束 |
| `usage` | `{prompt,completion,turns}` | 整条任务结束 |
| `notice` | 一句中文（字符串） | 重试、降级、截断、到上限 |
| `err` | 一句错误 | 失败 |
| `approval` | `{id,title,detail,kind,tool,pattern,risk,riskLabel,riskWhy,hunks[]}` | 要人批准 |
| `ask` | `{id,question,options[]}` | `ask_user` 要人回答 |
| `todo` / `queue` / `title` / `mode` / `tools` / `settings` / `sessions` / `runstate` / `agents` / `kbs` / `hello` / `ping` | 各自的对象（`agents` = `{agents[],inbox[],busy[]}`，多 Agent 那一层的读数；`kbs` = 与 `GET /api/kbs` 同形） | 状态变了 |

`approval` 里的 `hunks[]` 只在**一次调用改了好几处**时出现：
`[{no,at,stat,text}]`，`at` 是旧文件里的行号（1 基）。
要逐块接受就发 `decision:"partial:101"`（一位一块，1=要，顺序与 `hunks` 一致）；
`allow_session` / `allow_rule` 是整条放行，不看勾选。

### 一个最小可用例子（curl）

```bash
# 1) 起任务
curl -s -X POST http://127.0.0.1:8712/api/task -H 'Content-Type: application/json' \
     -d '{"text":"把 notes.md 的第三段改成两句"}'
# 2) 看事件（另开一个终端；-N 关掉缓冲）
curl -sN http://127.0.0.1:8712/api/events
# 3) 弹卡时回答（id 从 approval 事件的 data 里拿）
curl -s -X POST http://127.0.0.1:8712/api/decide -H 'Content-Type: application/json' \
     -d '{"id":"a1","decision":"allow_once"}'
```

## 2. 网页壳自己用的（会随版本变，别依赖）

这些是界面点出来的，列在这里是为了"文档要覆盖每一条真实路由"，不是为了给你用。
其中少数几个（标 ★ 的）也适合脚本调用，但形状还没稳定承诺。

会话与消息：`/api/open`、`/api/rename`、`/api/delete`、`/api/pin`、`/api/trash`、`/api/untrash`、
`/api/purge`、`/api/export`、`/api/share`、`/api/edit`、`/api/cut`、`/api/delmsg`、
`/api/regenerate`、`/api/rollback`、`/api/rewind`、`/api/rewindturn`、`/api/checkpoints`、
`/api/resume`、`/api/abandon`、`/api/unqueue`、`/api/substop`、`/api/attach`

内容与文件：`/api/files`、`/api/file`、`/api/img`、`/api/media`、`/api/compact`

配置与能力：`/api/settings`、`/api/model`、`/api/models`、`/api/mode`、`/api/tool`、
`/api/rule`、`/api/memory`、`/api/memories`、`/api/skills`、`/api/mcp`、`/api/presets`、
`/api/secrets`、`/api/hooks`、`/api/workspaces`

专家与团队：`/api/teams`（GET 清单（成员展开成摘要）；POST `op:'save'|'del'`，
`name`+`members`≥2 个 preset id；编制只存成员 id，人设跟着角色卡走）、
`/api/experts/library`（GET，随包内置专家卡，只读）、
`/api/expertfeed`（POST，远端专家市场清单，见下段）、
`/api/agent-config`（GET `?preset=<id>` 单查 / 不带参数回全部；POST `{preset,…}`
整份覆盖——每专家配置：`toolsOff`/`skillsOff`/`subagents`/`personaMbti`/`personaExtra`/
`knowledgeIds`/`providerName`+`baseUrl`（专属网关）；critical 工具写入即剔除）、
`/api/presets`（GET 清单；POST `op:'save'`（默认）`|'del'`|`'toggle'`|`'dup'`|`'export'`|`'import'`）。
角色卡除人设/模型/工作区/档位外还带：`welcome`（新会话第一屏的欢迎语）、
`quick:[{title,desc,prompt}]`（快捷提问：标题上按钮、正文才是发出去的那句；老卡的字符串形状仍能读入）、
`enabled`（关掉后不进新会话候选、不能被 `ask_agent` 点名、定时任务到点拒跑，并给出原因）、
`temperature`/`maxTokens`/`maxTurns`（**每卡运行参数**，`-1` = 跟全局；只影响这张卡开出的会话，
落到那条会话自己的设置副本上）。`op:'export'` 返回 `{ok,card}`，那份 JSON 原样交给
`op:'import'` 就能读回来，且**必然换新 id**（导入不该覆盖用户手里那张同名卡）。
`/api/state` 现在也报 `preset`：不报的话前端拿到"带角色的空会话"却不知道角色是谁，欢迎语画不出来。

`POST /api/presets` 的 `op:'default'` 把那张卡设为「默认专家」（`id` 传空串 = 取消默认）。
**一次只许有一张**：设这张时服务端会清掉别的卡那一位。默认卡影响的是

个性化与记忆漏斗（B1~B8 批次）：`/api/mbti`（GET `?what=types|questions|current&preset=`；
POST `op:'test'`（`answers:{题id:0|1}`，≥20 题）`|'apply'`（`preset,code` 写进
[AgentConfigs].personaMbti））、`/api/memory-funnel`（GET `?what=candidates|profile|episodes|care`；
POST `op:'promote'|'reject'`（`id`）`|'extract'`（`sid?` 后台提炼候选）`|'regenProfile'`（`sid`）`|'care'`
（`enabled:0|1` 主动关心开关，默认关））。候选晋升写进产生它的那条工作区的长期记忆，
画像存 `HAOAI_HOME/memory-profile.md`（`## My Notes` 段用户手写保留）。
`POST /api/new` 没点名 `preset` 时挂哪张卡；那张卡若已关掉或被删，就回落到"没挂专家"，
**不会静默换成另一张**（用户没点过的卡不该被塞给他）。

`POST /api/expertfeed` 的 `{op:'load'}` 去 `settings.expertFeed` 那个地址拉一批角色卡，
返回 `{ok,from,note,cards:[{name,desc,card}]}`；`card` 是那张卡的**原文 JSON**。
**这里不装卡**：装是 `{op:'import',text:<card>}` 交给 `/api/presets`（换新 id、
拒掉"名字和人设都空"的卡），所以市场与本地文件导入共用同一道门 —— 别再开第二条写库的近路。
源端形状收两种：卡片数组，或 `{"cards":[…]}`；一份清单最多 40 张、响应体最大 200KB，
超了直接拒（坏源不该能把内存吃掉）。混进来的非对象项跳过并在 `note` 里说明，
而不是让整份清单一起失败。**默认没有远端**：`expertFeed` 空串时这个功能等于不存在，
`{ok:false,error:"还没配市场地址…"}`，页签显示空态 —— 不预置任何官方源，
那等于替用户决定信任谁。市场卡能带进来的只有人设/模型名/工作目录/档位/绑哪几个知识库，
这几样本身就敏感（一张 `workspace=C:\` 且 auto 档的卡等于一份"在这儿替你跑命令"的说明书），
所以导入前必须看得见它写了什么（页签里那张卡能展开看原文）。

<!-- expertfeed: 走 java.net.http.HttpClient，与 Embed/Lan/Browser 同一套；
     这个仓库里发 HTTP 不许出现第二种客户端（URL().openConnection() 在本机对任何地址都超时）。 -->

`/api/state` 的 `cites[]` 是知识库的引用出处（`{cid,kb,kbName,doc,how,score,snippet}`），
`messages[]` 里每条带 `cid`（那次工具调用的 id）。两边按 `cid` 配对 —— **不要改用消息下标配对**：
压缩与"删这一句/删到这里"会让下标整体前移，届时的表现是引用挂在别的卡底下。
SSE 的 `tool` 事件（结束那一条）同样带 `cites`，所以实时与回放两条路都要画；
只写 SSE 那一路的话，回合收尾那次 `hydrate` 会把它们全抹掉（跑的时候看得见、刷新就没了）。
`POST /api/share` 导出的那份只读快照也带同一份出处（渲染器收 `Row.cid` + 按 cid 分组的清单）。
`/api/kb`（GET `?sid=` 语料清单与检索可用性；POST `op:'import'`（name+text，纯文本 ≤200KB）
`|'del'`（name，删后语义缓存一并清）`|'test'`（q，跑一次语义检索返回命中））。
`/api/new` 可带 `team:<id>` 开团队会话（服务端现拼主持人人设，成员人设经 task 的 `persona` 参数随派工下发）。

排程与产出：`/api/schedules`、`/api/sched/parse`、`/api/workflows`、`/api/digest`、
`/api/kbs`（知识库：建库/导入含 docx-xlsx-pptx-pdf/删/重解析/预览/试检索；
`op:create` 回 `{ok,kb,name}` —— 不带 id 的话，"建完就绑到某张专家卡上"这种两步操作只能再拉一遍列表才认得出刚建的是哪一个）、
`/api/kbfile`（下载库里那份原文，文件名只取本体、库 id 要真存在），
`/api/digest/export`、`/api/backups`、`/api/backup`

Git 面板：`/api/gitstatus`、`/api/gitdiff`、`/api/gitstage`、`/api/gitcommit`

终端面板：`/api/shells`、`/api/shell/open`、`/api/shell/send`、`/api/shell/tail`、`/api/shell/close`

浏览器预览：`/api/preview/state`、`/api/preview/frame`、`/api/preview/open`、
`/api/preview/input`、`/api/preview/pick`、`/api/preview/close`

手机联动的桌面侧：`/api/lan`、`/api/lan/toggle`、`/api/lan/code`、`/api/lan/unpair`、`/api/lan/allow`

页面与静态资源：`/`、`/index.html`、`/md.js`、`/shared.js`

> **`/api/settings` 把整份设置对象原样回给前端**，所以密钥永远不进它 ——
> API key 存在 `HAOAI_HOME/apikey`、搜索 key 存在 `HAOAI_HOME/searchkey`，
> 接口只回掩码。这条是硬规矩，加字段之前先看一眼它会不会把秘密带出去。

## 3. 跨端：`/lan/*`（手机端 ↔ 电脑）

绑 `0.0.0.0`，但**默认不监听**：`haoai lan on` 或界面里打开才开始，而且要先配对。
每个请求要带 `X-HaoAI-Token: <配对后发的那串>`；服务端只存它的 SHA-256，比较走常量时间。

| 方法与路径 | 做什么 |
|---|---|
| `GET /lan/health` | 不用 token，只回答"这台有没有开联动" |
| `POST /lan/pair` | 用 6 位配对码换长期 token（一次一用、120 秒过期、错 8 次作废） |
| `GET /lan/sessions` | 会话列表（镜像） |
| `GET /lan/session?sid=` | 一条会话的消息 |
| `GET /lan/pending` | 正在等人批准/等人回答的清单 |
| `POST /lan/decide` | 替那一条做决定：`{"id":"a1","decision":"allow_once\|allow_session\|deny"}` |
| `POST /lan/send` | 手机派活给电脑（**默认关**，要电脑上勾「允许从手机派活」） |
| `GET /lan/digest` | 定时任务的运行结果（每条带 `t`，手机按它判重） |
| `POST /lan/unpair` | 解绑这台设备 |
| `GET /phone` | 手机网页端本身（配对、待批、只读镜像，390px） |

`/lan/pending` 的审批行会带 `hunkCount`（这次改动分几处）但**不带 diff 正文** ——
那边只有整条按钮，几十行块跟着 4 秒一次的轮询跑只是白占手机流量。

## 4. 用 Python 调（现成的最小客户端）

`pc/tools/haoai-client.py`，只用标准库，没有依赖：

```bash
python pc/tools/haoai-client.py task "把 README 里和代码对不上的部分改过来"
python pc/tools/haoai-client.py task --auto --sid pc1234 "只读一遍 notes.md"
python pc/tools/haoai-client.py state            # 当前会话现在什么样
python pc/tools/haoai-client.py sessions --q 降级 # 搜会话
python pc/tools/haoai-client.py runs             # 最近跑了哪些任务
```

`task` 会一直跟着事件流打印，遇到审批卡就在终端问一句（`y/a/r/n`，或直接回车看详情）。
`--auto` 表示把这条会话切到自动档（高危仍然会问 —— 那是保护，不是 bug）。
要跑一次真的端到端验证：`bash pc/tools/api-smoke.sh`（自己起假网关 + 真服务，
发一个任务、批一张卡、确认文件真的写出来了）。
