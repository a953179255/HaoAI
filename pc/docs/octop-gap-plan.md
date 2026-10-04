# HaoAI PC × Octop 功能补全计划

> 生成于 2026-10-03。依据：对 TencentCloud/Octop（v1.0.2b5）源码的实地调研（两份调研报告，要点已全部吸收进本文），以及对 HaoAI PC（`pc/` 目录，Kotlin 单进程 + 单 HTML 前端）代码的实地盘点。
> **本文档是给执行 Agent 的施工委托书**：按批次施工，每批独立可交付、独立验收、独立提交。动手前先读第 1 节工程约定。

---

## 0. 结论速览

Octop 与 HaoAI PC 的差距不在"有没有功能"，而在**三层深度**：

1. **配置深度**：Octop 的"个性化"是一个统一的 per-专家配置中枢（技能/子智能体/工具/插件/MBTI/记忆/通道七件套，全部收敛到 `config_json` 一个 JSON，引擎每轮模型调用前热生效）。HaoAI 的专家卡（Presets）只有 人设/模型/工作区/档位/展示字段，**没有"每个专家自带什么技能、开哪些工具、挂哪个子智能体、什么人格"这一层**。
2. **内容深度**：Octop 有随包内置库（技能 211 个、子智能体中文库 19 分类 272 个、插件 24 个、专家 17 个），内容即产品。HaoAI 只有 8 张内置专家卡。
3. **机制深度**：Octop 的记忆是四层漏斗（原始对话→候选→长期原子→画像）+ 情绪日记 + 主动关心；HaoAI 是单层条目注入。工具开关是"提示层剔除+执行层拦截"的目录化管理；HaoAI 是无分组的会话级开关。

**已对齐、无需补的**：会话管理（回收站/搜索/分屏/置顶）、三档权限+审批、定时任务+一句话排期+结果汇总、任务链、钩子、MCP 客户端、Git/终端/浏览器预览、用量账本（按模型/按天/成功率）、知识库检索（已有 `SearchKnowledgeTool` 工具化+语义索引）、手机联动、团队 v1、专家卡+内置库、回收站。

---

## 1. 工程约定（执行 Agent 必读）

- 代码位置：`G:\工作台\HaoAI\pc\`（**独立 gradle 工程**，构建/测试都要在 pc/ 目录下跑；根工程不含 :pc 模块）。构建：`gradle jar installDist`；测试：`gradle test`（全量，含 UiContractTest/ApiDocTest 等结构测试）。
- **冒烟（前端改动提交前必跑）**：`bash pc/tools/smoke.sh`——先 installDist 装新 + ui-check 静态自检 + ESLint 门（`tools/lint.js`：no-undef/重复键/不可达等 17 条"可能错误"类规则，真作用域分析，专拦正则近似挡不住的歧义名裸用），再起"假网关+新鲜状态根+无头 Edge"走 `tools/steps/ui-pages.json`：四页布局不变量/回会话恢复/Ctrl N 与命令面板两条跳转落位/Ctrl B 网格钉轨/导航「技能」落右栏技能页签/设置弹窗/mock 消息全链路/页面错误盘点（50 步 80 判据，约 1 分钟，退出码即结论）。像素级专项验收仍走 `bash pc/tools/ui-shot.sh <剧本>`。
- `/api/state` 的字段对齐有契约测试钉着（StateContractTest）：前端 applyState 读的每个字段服务端都必须发——加字段时两侧一起动，测试会替你盯另一侧。
- 前端是**单文件** `pc/src/main/resources/ui/index.html`（约 4700 行）：CSS 在 `<style>`、HTML 骨架、JS 面板对象遵循 **S6 模式**（`const X = (() => { let 状态; function paint(){}; return {init, render}; })(); X.init();`）。
- 后端新端点模式：`pc/src/main/kotlin/com/haoai/pc/Server*.kt` 里的 `internal fun WebServer.xxx(ex: HttpExchange)` 扩展函数 + `Server.kt` route 表登记；**新路由必须同步登记 `pc/docs/api.md`**（ApiDocTest 双向核对，漏了构建红）。
- 存储惯例：状态文件放 `Env.home`（HAOAI_HOME），`load()/save()/update()/remove()/find()/json()` 五件套（参考 `Presets.kt`、`Teams.kt`）；JSON 手写拼接时用 `js()/quote()` 转义。
- 每批做完：`gradle test` 全绿 → 浏览器实拍（深浅两主题）→ 提交（只 add 自己改的文件）→ 更新本文档的进度勾。
- 参考实现若需看 Octop 源码：`export https_proxy=http://127.0.0.1:7897` 后走 GitHub raw。

---

## 2. 架构对照总览

| 维度 | Octop | HaoAI PC 现状 | 差距 |
|---|---|---|---|
| 运行时 | FastAPI 控制面 + octop-harness（LangGraph+中间件，每轮模型调用前热生效配置） | Kotlin 单进程 Engine（全局 PcSettings + 会话级覆盖） | **缺 per-专家配置对象**（P0 根因） |
| 专家模型 | `agents` 表（元数据+config_json）+ 独立工作区目录（SOUL.md/skills//agents//.octop/memory.sqlite） | `presets.json` 扁平卡（persona/model/workspace/mode/desc/icon/color/mbti/quick） | 专家无独立技能/工具/子智能体/记忆 |
| 内置内容 | 技能 211、子智能体 272（19 分类）、插件 24、专家 17 | 专家 8 张、技能订阅源 | 内容库几乎空白 |
| 子智能体 | 工作区 `agents/<slug>.md`（YAML 头：description 必填/tools/model）+ `task` 工具派发 | `task` 工具有 label/prompt/model/tools/mode/persona 参数，无文件定义、无库 | 缺定义文件形态与内置库 |
| 工具管理 | 38 工具目录化（分组/critical 不可关/插件工具），`tools_disabled` 提示层+执行层双拦截 | `toolsOff` 会话级开关（右栏页签平铺，无分组无 critical） | 目录化+每专家粒度 |
| 技能 | `skills/<slug>/SKILL.md` + 内置/市场/技能包四来源 + 启停过滤中间件 | `skills.json` 命令 + `SkillDocs`（`skills/<slug>/SKILL.md` 已同形状！）+ zip/URL 导入 + 订阅源 | 缺启停、缺内置库、缺 per-专家挂载 |
| 插件 | `plugin.yaml + main.py`，tool/skill/hook/ui 四扩展点，无沙箱 | 无 | 裁定缓做（见 §4.5） |
| MBTI | 16 型数据模块 + 28 题测验 + 渲染进 SOUL.md | `mbti` 字段纯展示徽标 | 缺数据/测验/提示生效 |
| 记忆 | 四层漏斗 Raw→Candidate→Atom→EntityPage + Episode 情绪日记 + 主动关心 + 迁移 | `memories.json` 单层条目（注入+tidy+DreamYield） | 漏斗与画像整体缺失 |
| 通道 | 10 个 IM 通道（独立 gateway 仓库，通道挂在专家上） | LAN 手机配对（只读看+代批） | 裁定暂缓（§4.8） |
| 知识库 | 每库 SQLite 向量 sidecar + `search_knowledge` 工具 + 引用格式 | `.haoai-kb/` 目录 + SemanticIndex（grep 0 命中回退）+ `SearchKnowledgeTool` | 形态接近，缺多库管理与引用 |
| Token 统计 | usage_log 表 + 按专家/模型/天聚合 + xlsx 导出 | usage.jsonl + 右栏页签（按模型/14 天柱图/成功率） | 缺按专家维度与导出 |
| 多用户/ACP/云协同 | JWT 多用户 / stdio ACP 双向 / Bridge 实例桥 | 无 | 裁定不做或缓做（§4.9） |

---

## 3. 个性化七件套：逐项差距与方案

### 3.1 P0 前置：专家配置中枢（一切的地基）

**现状**：`Preset` 是一张扁平卡；会话创建时 `session.persona = preset.persona`，仅此而已。
**Octop 做法**：`config_json` 收敛全部启停状态（`tools_disabled / skills_disabled / plugins / persona / memory / skill_package_ids / knowledge_base_ids / mcp_servers`），`_build_harness_config` → 中间件每轮热生效。
**方案**：
- 新增 `AgentConfig`（`Env.home/agent-configs.json`，按 preset id 一份，缺省即默认）：
  ```kotlin
  data class AgentConfig(
      val presetId: String,
      val toolsOff: List<String> = emptyList(),      // 该专家关掉的内置工具
      val skillsOff: List<String> = emptyList(),     // 该专家停用的技能 slug
      val subagents: List<String> = emptyList(),     // 挂载的子智能体 slug（工作区 agents/ 之外的）
      val personaMbti: String = "",                  // 四字母，空=未设
      val personaExtra: String = "",                 // 人格补充段（MBTI 渲染之后追加）
      val knowledgeIds: List<String> = emptyList(),  // 挂载的知识库（B9 之后生效）
  )
  ```
- `newSessionId(at, preset)` 时把 `AgentConfig` 快照进 `Session`（新字段 `agentConfig`），Engine 在 `requestMessages()`/`schemas()` 处消费：系统提示渲染人格（MBTI 段+extra）、`schemas()` 过滤 `agentConfig.toolsOff ∪ settings.toolsOff`。
- 前端：专家页每张卡加「配置」入口 → 打开**个性化枢纽弹窗**（七个区块：人格/工具/技能/子智能体/知识库/记忆/高级，随各批次逐个点亮）。枢纽复用现有 `#dialog.wide` 壳。
- **验收**：给专家 A 关掉 `write` 工具 → 用 A 开会话，模型 schema 里无 write、尝试写时得到明确报错；专家 B 不受影响。单测：config round-trip + schema 过滤。

### 3.2 工具目录化管理（个性化/工具）

**现状**：`toolsOff` 全局平铺 + 会话级覆盖；无分组、无"必需工具"概念。**工具清单本身也缺**：现有 17 把（read/write/edit/glob/grep/shell/web_fetch/web_search/todo/task/ask_user×2/agent_list/ask_agent/search_knowledge/git/run_verify），Octop 有 38 把。
**Octop 做法**：`BUILTIN_TOOL_CATALOG` 38 项按 category 分组（filesystem/orchestration/web/interaction/media/memory/cron/knowledge/teams/misc）；`CRITICAL_TOOLS={ls,read_file,glob,grep,write_todos,task}` 不可关（API 400）；禁用=中间件从 `request.tools` 剥除（模型看不见）；另有**条件挂载**（如 `media_generation:false` 时图像/视频工具根本不注册）。
**方案**：
- `Tools.kt` 给 `Tool` 加两个字段：`category: String`（文件与终端/检索与网页/规划与子任务/交互/团队/知识/媒体）与 `critical: Boolean`（read/glob/grep/task/todo 标记 critical——write/edit/shell 允许关但关了要给模型一句"本专家未开启写入"的提示能力）。
- 右栏「工具」页签按分组渲染 + 每行显示「全局：开/关」+「本专家：跟随全局/关」两级开关（专家级写 `AgentConfig.toolsOff`）。
- 生效点已在 `Engine.schemas()`（P0 接好），只需并集逻辑。
- **工具清单补齐**（Octop 有而 HaoAI 没有的，随 B3 一并做）：

| 工具 | 价值 | 工作量 | 批次 |
|---|---|---|---|
| `desktop_screenshot` 桌面截图 | PC 本机就是桌面：Java 原生抓屏（Robot/辅助功能 API）给模型"看屏幕"能力 | 中 | B3 |
| `memory_search` / `memory_get` | 记忆从"自动注入"升级为"模型可主动检索"（依赖 B6 的候选漏斗） | 小 | B8 |
| `cron_list/create/toggle/run` | 模型可代建/查/停定时任务（"每天八点提醒我"一句话落地） | 小 | B3 |
| `generate_image` 生成图片 | 接网关图像接口（智谱 CogView 类）；手机端"真位图生成待接 API"是同一件事 | 中（依赖供应商） | P2 可选 |
| `send_file_to_user` | Octop 把文件推给用户；PC 等价物=产出页签置顶/资源管理器打开——**不做**，现有路径更顺 | — | 裁定不做 |
| 搜索多供应商并列 | Octop 拆 tavily/brave/google/kimi 四把；HaoAI 是单 `web_search`+provider 三选——**不拆**，一个开关比四个同义工具好 | — | 保持 |
| 移动六件套 / `acp_runner` | PC 无此场景 | — | 不做 |

- **验收**：工具页签出现分组标题；关掉某专家的 `web_fetch`，该专家会话 schema 无它、全局默认会话仍有；critical 工具开关按钮 disabled+悬浮说明；桌面截图工具出真图。单测：critical 不可关、并集过滤。

### 3.3 技能升级（个性化/技能）

**现状**：`SkillDocs`（`skills/<slug>/SKILL.md`+YAML 头，与手机端同形状）+ zip/URL 导入 + 订阅源（先看过再装）+ `skills.json` 旧命令。**形状已对**，缺四样：
1. **启停**：`AgentConfig.skillsOff` + 提示注入层过滤（现在技能如何进提示？`Prompt.kt` 未点名技能清单——需在系统提示加"可用技能"节：启用技能的 name+description 清单，正文让模型按需 read）+ 执行层：read/grep 落在 `skills/<slug>/` 路径时若命中 skillsOff 返回"该技能已停用"。
2. **内置技能库**：随包 `resources/builtin-skills/<slug>/SKILL.md`，首批 10~15 个高质量技能（对应 HaoAI 场景：git 提交规范、diff 评审、每周摘要、会议纪要、竞品调研简报、数据速览、Windows 批处理、截图理解、PPT 大纲、简历筛选）。首启扫描进"内置"区，只可启停不可删。**每专家可单独停用**（`skillsOff` 命中即过滤）。
3. **来源标注**：SkillDoc 已有 `.source.json`——列表 UI 按 已安装/内置/订阅源 三区渲染（技能包裁定不做，订阅源已覆盖"分发"）。
4. **个性化枢纽里的技能区**：每专家的启停列表（全局装、专家级停）。
- **验收**：停用某技能后，系统提示的技能清单不含它、read 其路径被拦；内置技能显示"内置"标且无删除按钮。单测：过滤逻辑。

### 3.4 子智能体库（个性化/子智能体）★ 内容工程最大项

**现状**：`task` 工具已支持 persona/model 参数（团队派发用的通路），但没有"子智能体"这个用户概念：无定义文件、无库、无管理界面。
**Octop 做法**：子智能体 = 工作区 `agents/<slug>.md`（YAML 头 `description` 必填——它是主 agent 选人的依据；`tools`/`model` 可覆写；正文=system prompt）+ 内置库 272 个按 19 分类 + `task` 派发 + 一键安装进工作区。
**方案**：
- **定义形态**：`Env.home/subagents/<slug>.md`，YAML 头 `name/description/model(可选)/tools(可选,逗号分隔)/color/emoji`，正文=system prompt。**与专家卡解耦**：子智能体是"干活的模板"，被专家经 `task` 派发时引用。
- **task 工具接线**：schema 增加 `subagent` 参数（slug）。`TaskTool.run` 解析 slug → 读 md → 注入 persona（已有通路）+ 应用 tools/model 覆写；slug 不存在时报"没有这个子智能体，可用的有：…"。
- **系统提示注入**：会话若挂了子智能体（`AgentConfig.subagents` 非空，或全局可用模式），在系统提示列"可派发的子智能体：slug — description"。
- **内置库**：首批自建 **中文 6 分类 × 5~8 个 ≈ 40 个**（工程/数据分析/写作/调研/运维/学习——参考 Octop 分类但按 HaoAI 用户场景收缩；每个 md 的正文人设 150~300 字，`description` 一句话）。放 `resources/builtin-subagents/<分类>/<slug>.md` + `divisions.json`（label/icon/color）。**质量重于数量**：宁 40 个能用的，不 272 个占位的。
- **UI**：个性化枢纽"子智能体"区：已挂载/全部目录 两 tab，按分类分组卡片，安装（写入 subagents/ 目录）/移除/新建（抽屉表单：名/描述/模型/工具集/人设正文）。
- **验收**：内置库出现在目录页；安装后 `task(subagent=slug)` 派发成功且子卡显示 slug 名；未挂载的 slug 派发得到明确报错。单测：md 解析（含 description 缺失跳过）、task 接线。

### 3.5 MBTI 人格系统（个性化/MBTI）

**现状**：`Preset.mbti` 纯展示徽标。
**Octop 做法**：16 型硬编码数据模块（昵称/摘要/维度百分比 50-85/6 条行为指导/颜色）+ 28 题测验（4 维×7 题，多数极判型，≥20 题）+ `apply` 后渲染进 SOUL.md。
**方案**：
- `Mbti.kt`：16 型数据（code/name_zh/nickname_zh/summary/dimensions[ei,sn,tf,jp 各为主导极+百分比]/behaviors 6 条中文/color）——**数据自己写中文版**（Octop 的梗昵称如"紫老头/小瓶子"可参考但重写）。~400 行纯数据。
- `GET /api/mbti/types`、`POST /api/mbti/test`（题库内置 28 题，判型：各维多数极；百分比=`50+dominant/total*35` clamp [50,85]）、`POST /api/mbti/apply`（写 `AgentConfig.personaMbti`）。
- **提示生效**：`requestMessages()` 渲染人格时：`## 人格（MBTI：INTJ 建筑师）` 节 = summary + 6 条 behavior + 维度描述；其后接 `personaExtra`，再接原 `persona`。
- **UI**：个性化枢纽"人格"区：16 型卡片网格（昵称/摘要/维度条/颜色）+ 「测一测」（28 题逐题卡，复用 ask 卡样式，答完出型+一键应用）+ 预览渲染结果。
- **验收**：选 INTJ 后新会话系统提示含 INTJ 人格节；测验 20 题起判、不足报错。单测：判型逻辑（已知答案向量→确定四字母）、渲染。

### 3.6 记忆中心（个性化/记忆）★ 机制深度最大项

**现状**：`Memories.kt` 单层条目（`memories.json`：text/importance/dormant/命中注入/tidy 留痕/DreamYield 让位）。质量不差但只有"一层"。
**Octop 做法**：四层漏斗 RawEvent→Candidate(待审)→Atom(长期)+EntityPage(画像)+Episode(情绪日记)+Journal(审计)+主动关心（随机调度+episodes 摘要+轻量 LLM 短消息推送）。
**方案（分两期，先骨架后血肉）**：
- **一期（漏斗简化版）**：
  - 数据：`memories.json` 现有条目**就是 Atom**（不迁移，字段兼容）；新增 `memory-candidates.json`（`{id,type(Fact|Decision|Preference|Task),title,assertion,quote,status(pending|promoted|rejected),created}`）。
  - 抽取：会话空闲（复用 DreamYield 的闲置窗口机制）或回合结束时，把**本轮用户消息**交给一次轻量 LLM 调用（用当前模型，提示词要求输出 0~3 条候选，JSON 格式，失败静默）→ 写 candidates（status=pending）。**不自动晋升**。
  - 审阅 UI：记忆页签加"沉淀待审"区（badge=待审数），逐条 晋升/拒绝；晋升=写入 memories.json（走现有 tidy 合并语义，天然去重）。
  - 概览统计卡：条目数/待审数/本周对话轮数（usage 账本有）。
- **二期（画像与主动关心）**：
  - 用户画像：`memory-profile.md`（`## My Notes` 段保留用户手写）——由"晋升满 N 条后触发一次 LLM 重渲染"生成，摘要注入系统提示（`PromptCtx.kb` 同款通路加 `profile` 字段）。
  - 情绪日记：Episode 简化版——抽取时顺带产 0~1 条 `{date,mood(1-5),summary(≤120字)}`，`memory-episodes.jsonl` 追加；UI 时间线。
  - 主动关心：定时任务体系复用——内置一条特殊 schedule（可关）：随机 1~3 天间隔，跑一次"读最近 episodes+日记 → 生成 ≤100 字关心语 → 推送"（推到 PC 通知/最后活跃会话）。**先做 PC 通知版**（复用 haoai_pc_done 通知渠道）。
- **验收（一期）**：聊几轮后出现待审候选；晋升后条目进 memories.json 且下回合注入；拒绝即弃。单测：抽取 JSON 解析容错、晋升走 tidy。
- **不做**（明确裁定）：记忆树、迁移 .hmpkg、slim、PostgreSQL——单机文件存储不需要。

### 3.7 通道（个性化/通道）——裁定暂缓

单用户 PC 的"第二入口"已经有 LAN 手机配对。IM 通道（微信/QQ/飞书…）每个都是独立协议工程且要账号生态，**建议不做**；触发条件：用户真实提出"我想在微信里使唤 PC 上的 HaoAI"。届时优先做 Telegram/Bot API 类（无审核、个人 token 即用），参考 Octop 的"通道挂在专家上 + 消息路由进完整 harness"架构。个性化枢纽里给"通道"区留占位卡（说明+暂不可用），与七件套结构对齐。

### 3.8 插件——裁定缓做，用"技能+子智能体+任务链"承接

Octop 插件的本质=「Python 代码注册工具/技能/钩子/页面」，且无沙箱。HaoAI PC 是编译型 Kotlin，动态工具等价物已有三条更安全的路：技能（提示层扩展）、子智能体（人设+工具子集）、任务链（流程编排）。**裁定：不做通用插件系统**。若未来需要"第三方加工具"，优先做「远程 MCP server 目录」（连接器形态，见 §4.3）而不是本地代码插件。

---

## 4. 七件套之外：其余差距与裁定

### 4.1 Token 统计页（工作量小，建议随手做）
现状：右栏用量页签已有 按模型/14 天柱图/成功率/账本行数。缺：**按专家（角色卡）维度** 与 导出。方案：usage.jsonl 落账行带 sid → 会话有 role → 聚合时按 role 分组；导出 CSV（不引 xlsx 库）。验收：用量页签多"按专家"分组块 + 导出按钮。

### 4.2 知识库 v2（中工作量）
现状已接近（每工作区 `.haoai-kb/` + 语义检索 + `SearchKnowledgeTool` + 系统提示名单）。Octop 的增量点：多库管理、引用格式（库名/文件名带进答案）、对话中临时勾选挂载。方案：知识页签支持多"库"（= `.haoai-kb/` 下的子目录），`SearchKnowledgeTool` 结果带 `[库名/文件名]` 前缀；专家可挂默认库（`AgentConfig.knowledgeIds`）。引用格式是低垂果实，优先做。

### 4.3 连接器（裁定：只做"远程 MCP 目录"）
已有 MCP 客户端 + 设置里的 MCP 配置。Octop 的增量是"目录驱动的凭证卡片 + 每连接器 allowed_tools 白名单"。方案：设置里 MCP 区升级为卡片列表（名称/URL/启用/工具白名单），不改协议层。P2。

### 4.4 工作台/远程桌面/Browser AI+
PC 已有 终端（ConPTY 多标签）/浏览器预览（CDP）/Git/文件产出，**形态不低于 Octop**。Octop 值得抄的一点：**Browser 控制权 handoff（agent 与人共用同一浏览器会话）**——记入 backlog，不排期。远程桌面不做（PC 本机就是桌面）。

### 4.5 多用户 / ACP / 云端协同 —— 裁定不做
单用户产品。多用户留"形状"：新存储文件一律带可扩展的 JSON 结构即可。ACP 双向（把 HaoAI 暴露给 Zed / 委派给 Claude Code）记入 backlog 不排期。

### 4.6 其他可选 backlog（调研中发现、暂不排期的小项）
- **会话 fork**：把某条会话从某句开始复制成新会话（Octop threads.fork；HaoAI 已有 编辑重发/回到之前，fork 是锦上添花）。
- **浏览器操作录制回放**（Octop record_replay）：把一段网页操作录成可重放技能——等浏览器自动化用出真实需求再说。
- **专家发布/共享**（published_expert / is_shared）：多用户能力，随多用户一起不做。
- **记忆迁移 .hmpkg**：跨宿主打包——单机不需要。

---

## 5. 分期施工计划

> **进度（2026-10-03，全部批完）**：B1 ✅（8daaa55）｜B2 ✅｜B3 ✅｜B4 ✅｜B5 ✅｜B6 ✅｜
> B7 ✅（另一会话已先做：Token 统计页含按专家+导出）｜B8 ✅｜B9 ✅（另一会话已先做 Knowledge.kt 多库
> +引用，本计划收编为"已存在"）｜B10 ✅（b4b10a5）。B2-B6/B8 合并提交 34e71d6，内容库（Mbti 16 型、
> 内置技能 15、内置子智能体 40）随批入库。全量 700+ 测试绿；installDist 与 packageExe 已重打。
> 执行中的范围微调：①provider/baseUrl 放 AgentConfig 而非 Preset（开关类归配置层）；
> ②记忆漏斗 UI 放枢纽「记忆」区（对齐 Octop 七件套结构），右栏记忆页签保留；
> ③cron_run 不做（立刻跑归界面定时页签）；④子智能体"新建/编辑"UI 未做（v1 可直接放
> `HAOAI_HOME/subagents/*.md`，表单入口列入 backlog）；⑤主动关心不推送只落 journal+页签展示
> （推送链路另开一笔）；⑥send_file_to_user 等清单见 §4.6/§3.2 裁定表。

> 原则：每批独立可用、独立验收；先做"配置中枢+数据浅层"，内容库（子智能体/技能）紧随，记忆漏斗殿后。批次内条目即任务清单。

**B1 专家配置中枢 + 个性化枢纽页（P0，地基）**
- [ ] `AgentConfig` 存储 + Session 快照 + Engine 消费点（人格节渲染位、schema 过滤位）
- [ ] `Preset` 加 `provider` 字段（每专家可带自己的网关 BaseURL，留空跟全局——Octop 专家独立 providers 的单用户简化版）
- [ ] 专家会话起手卡：欢迎语（内置库 manifest 已有 welcome_message）+ 快捷提问 chips 直接在聊天起手卡上可点
- [ ] 专家卡「配置」入口 + 枢纽弹窗骨架（七区块导航，未实现的区块显示"下一批"）
- [ ] 单测：config round-trip、toolsOff 并集过滤
- 规模：后端 ~200 行 + 前端 ~250 行

**B2 MBTI 人格系统（P0，纯增量、见效快）**
- [ ] `Mbti.kt` 16 型中文数据 + 判型 + 渲染
- [ ] `/api/mbti/*` 三端点 + api.md
- [ ] 枢纽"人格"区：16 型网格 + 测一测 + 应用
- 验收见 §3.5。规模：~700 行（数据占 400）

**B3 工具目录化 + 工具清单补齐（P0）**
- [ ] Tool 加 category/critical；工具页签分组渲染 + 两级开关
- [ ] critical 禁关 + 说明
- [ ] 新工具：`desktop_screenshot`（Java 原生抓屏）、`cron_list/create/toggle/run`（模型代管定时任务）
- 验收见 §3.2。规模：~400 行

**B4 技能升级（P1）**
- [ ] 系统提示"可用技能"节（启停过滤）+ 路径拦截
- [ ] 内置技能库首批 10~15 个（中文， resources/builtin-skills/）
- [ ] 枢纽"技能"区（已安装/内置/订阅源三区 + 专家级启停）
- 规模：后端 ~150 行 + 内容 15 篇 + 前端 ~200 行

**B5 子智能体库（P1，★内容工程）**
- [ ] `subagents/<slug>.md` 定义 + 解析器（description 缺失跳过）
- [ ] task 工具 `subagent` 参数 + 系统提示可派发清单
- [ ] 内置库首批 ~40 个（6 分类，中文正文）+ divisions.json
- [ ] 枢纽"子智能体"区（已挂载/目录/新建抽屉）
- 规模：后端 ~250 行 + 内容 40 篇 + 前端 ~300 行

**B6 记忆中心一期：漏斗+审阅（P1）**
- [ ] candidates 存储 + 空闲抽取（LLM 一次调用，失败静默）
- [ ] 记忆页签"沉淀待审"区 + 晋升/拒绝
- [ ] 概览统计卡
- 规模：后端 ~250 行 + 前端 ~150 行

**B7 Token 按专家统计 + 导出（P2，小）**
- [ ] 账本按 role 聚合块 + CSV 导出
- 规模：~120 行

**B8 记忆中心二期：画像/日记/主动关心 + 记忆工具化（P2）**
- [ ] 画像 md 生成+注入；Episode 简化版；主动关心（复用定时器+PC 通知）
- [ ] `memory_search` / `memory_get` 工具（模型主动检索，不止被动注入）
- 规模：~400 行

**B9 知识库 v2（P2）**
- [ ] 多库（子目录）+ 引用格式 + 专家挂默认库
- 规模：~200 行

**B10 知识页签升级+枢纽收尾（P2）**：个性化枢纽七区块全部点亮（通道/插件放"裁定说明"占位卡）。

**建议执行顺序**：B1 → B2 → B3 → B4 → B5 → B6 → B7 → B8 → B9 → B10。B2/B3 可与 B4/B5 并行（不同文件域）。

---

## 6. 需要用户拍板的裁定项

1. **通道（IM）**：不做（触发条件见 §3.7）——默认按此执行。
2. **插件系统**：不做，用 技能+子智能体+远程 MCP 目录 承接——默认按此执行。
3. **多用户/ACP/云协同**：不做——默认按此执行。
4. **内置子智能体库规模**：首批 40 个（6 分类）够不够？要不要照 Octop 冲到 100+？（内容可后续无限加，先 40。）
5. **主动关心的推送渠道**：PC 系统通知（默认）还是写进某条会话？
6. **write/edit/shell 是否 critical**：本计划按"可关但提示模型"处理，若你想要"绝对不可关"改一行标记即可。

---

## 7. 附录：源码索引

### Octop（调研结论出处，供执行时对照）
- 个性化页签结构：`dashboard/src/pages/Agent/Personalization/index.tsx`
- 技能：`src/octop/api/routers/skills.py`、`infra/skills/*`、`harness/src/octop_harness/middleware/skill_filter.py`
- 子智能体：`src/octop/infra/agents/subagents/{catalog,library}`、`harness/.../subagents/loader.py`、`api/routers/subagents.py`
- 工具：`infra/agents/settings/tool_catalog.py`（38 项+CRITICAL_TOOLS）、`harness/.../middleware/tools_filter.py`、`api/routers/agent_tools.py`
- 插件：`infra/agents/plugins/{manager,seed}`、`bundled/`（24 个样例）
- MBTI：`infra/agents/persona/{mbti_profiles,loader}.py`、`api/routers/mbti.py`（28 题在 router 内）
- 记忆：独立仓库 `TencentCloud/octop-memory`（types.py 四层模型、pipeline/extractor|promotion）、`harness/.../middleware/memory.py`、`infra/proactive/*`
- 通道：独立仓库 `TencentCloud/octop-gateway`（ChannelKind 十种）、`api/routers/channels.py`
- 知识库：`infra/knowledge/{embed,chunk,retrieve,tools}`（800/120 切块、SQLite 向量 sidecar、余弦暴力搜）
- 用量：`infra/db/repos/usage.py`（缓存三桶+model_calls+source）
- 会话模型：`infra/gateway/threads.py`（session_key→thread→checkpoint 三层）

### HaoAI PC（改动落点）
- 专家/配置：`Presets.kt`（本轮已扩展）、新建 `AgentConfig.kt`
- 引擎消费：`Engine.kt`（`requestMessages()` 人格/技能/子代理清单注入、`schemas()` 工具过滤）、`Prompt.kt`（`PromptCtx` 加 personaMbti/skills/subagents 节）、`Tools.kt`（category/critical、task 的 subagent 参数）
- 存储：`Memories.kt`（候选/晋升）、`SkillDocs.kt`（启停）、新建 `Mbti.kt`、`Subagents.kt`
- API：`Server.kt` route 表 + `ServerExpert.kt`/新 `ServerPersonalization.kt`
- 前端：`index.html`（个性化枢纽弹窗 = Experts 对象旁新 S6 面板对象；工具页签分组；记忆页签待审区；用量按专家块）
- 资源：`resources/builtin-skills/`、`resources/builtin-subagents/`、`resources/expert-library.json`（已有）
