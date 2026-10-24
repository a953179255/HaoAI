package com.haoai.agent.platform

import com.haoai.agent.agent.memory.MemoryConsolidation
import com.haoai.agent.data.AppContainer
import com.haoai.agent.data.AppSettings
import com.haoai.agent.data.soulClean
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作区文档层：把记忆/身份/能力渲染成用户可直接查看编辑的 Markdown 文件。
 * v2 架构解耦（2026-10-24）：文档固定落脑根 files/brain/（内部私有，不随工作区档位移动）；
 * 工作区三档只放 Agent 生成的材料。SAF 档同样落脑根（内部存储始终可用）。
 *   MEMORY.md   长期记忆库（真源，MemoryBank 直读写，本层不覆盖）
 *   USER.md     用户画像（偏好类记忆）
 *   IDENTITY.md 助理身份
 *   SOUL.md     性格设定
 *   AGENTS.md   工作约定说明
 *   TOOLS.md    工具清单
 *   HEARTBEAT.md 定时任务清单
 *   DREAMS.md   梦境日记（固化历史，人工审查用）
 *   dreaming/YYYY-MM-DD.md 每日固化报告
 */
object WorkspaceDocs {

    /**
     * 脑根（v2 架构解耦）：引擎身份/记忆文档固定落内部私有 brainDir，不随工作区档位移动。
     * 工作区（三档可换）只放 Agent 生成的材料。
     */
    fun docsRoot(c: AppContainer): File = c.brainDir

    /** 挂起函数：必须等设置加载完成才能渲染（见 awaitSettings 注释里的事故）。 */
    suspend fun syncAll(c: AppContainer) {
        val root = docsRoot(c)
        runCatching {
            val items = c.memoryBank.all()
            // MEMORY.md 不在此同步：它已是长期记忆真源（由 MemoryBank 直接读写），覆盖会丢数据
            root.resolve("USER.md").writeText(renderUser(items))
            // ⚠ 必须等设置就位：init 期 settingsFlow 还是默认空值，直接读会把用户
            //   人设（SOUL.md）/ 助理名（IDENTITY.md）渲染成"未设定"模板并覆盖真数据
            val st = c.awaitSettings()
            root.resolve("IDENTITY.md").writeText(renderIdentity(st))
            root.resolve("SOUL.md").writeText(renderSoul(st))
            root.resolve("AGENTS.md").writeText(renderAgents(c))
            root.resolve("TOOLS.md").writeText(TOOLS_TEMPLATE)
            root.resolve("HEARTBEAT.md").writeText(renderHeartbeat(c))
            upgradeDreamsHeader(root)
            seedDocs(root)
        }
    }

    /**
     * AGENTS.md = 静态模板 + 公共交换目录动态节（三档改造 2026-10-23 v3 定稿：
     * 用户说「放到公共文档」Agent 要知道写哪，不用用户报绝对路径）。
     * 工作区已在交换目录内时改口「直接写工作区」，避免 Agent 绕路越界。
     */
    private fun renderAgents(c: AppContainer): String {
        val exchange = c.workspace.exchangeRoot().absolutePath
        val ws = (c.workspace.current as? RawFileBackend)?.shellWorkdir()
        val sameAsWorkspace = ws != null && runCatching {
            ws.canonicalPath == File(exchange).canonicalPath ||
                ws.canonicalPath.startsWith(File(exchange).canonicalPath + File.separator)
        }.getOrDefault(false)
        val section = buildString {
            append("\n## 公共交换目录\n")
            if (sameAsWorkspace) {
                // 2026-10-24 口径翻转（用户产品模型定稿）：工作区在公共档时，公共目录就是
                // **默认产出地**——不是「用户说了才写」的越界目标，产出直接落工作区。
                append("- 工作区本身就是公共目录（$exchange）：**产出默认直接写工作区**，无需用户指定位置；")
                append("用户用相册 / WPS / 文件管理器可直接查看产物。\n")
            } else {
                append("- 路径：`$exchange`\n")
                append("- 用户说「放到公共文档 / 放公共目录」= 把产物写到该绝对路径（用户不需要报路径）；")
                append("相册/WPS/文件管理器可直接读写，是工作区之外的对外窗口。越界绝对路径写入时应用会自动申请存储权限。\n")
            }
        }
        return AGENTS_TEMPLATE + section
    }

    /**
     * C5：知识库目录 docs/（人机共编辑权威资源）——agent 用 read/write/edit 直接读写，
     * 用户可阅读/编辑/git 管理。只播种 README 说明，不覆盖已有内容。
     */
    private fun seedDocs(root: File) {
        val docs = root.resolve("docs")
        if (!docs.isDirectory) docs.mkdirs()
        val readme = docs.resolve("README.md")
        if (!readme.exists()) {
            readme.writeText(
                "# 知识库（docs/）\n\n" +
                    "人与助理共同维护的长期知识：网页摘要、项目资料、调研笔记、操作手册等。\n" +
                    "- 助理：用 read/write/edit 工具直接读写本目录；把值得长期保留的工作产物沉淀到这里\n" +
                    "- 人：直接编辑即可，Markdown 为主；改动对助理立即可见\n" +
                    "- 与 MEMORY.md 的分工：MEMORY.md 是助理的「记忆」（自动提取、带元数据），\n" +
                    "  docs/ 是「资料库」（人工组织、自由结构），互不替代\n"
            )
        }
    }

    /** DREAMS.md 引言（机制说明）。升级文案后借此把旧安装的引言也换新（日记正文不动）。 */
    private const val DREAMS_HEADER =
        "# 梦境日记\n" +
            "\n" +
            "> 夜间「固化」把当日值得留的东西沉淀进长期记忆，过程记录在这里，供人工审查。\n" +
            "> 两层机制：**规则整理**（每次都跑，零成本）= 重要日志晋升 MEMORY.md + 过期清理 + 去重；\n" +
            "> **深度梦境**（设置开启时）= 由「记忆管理模型」做语义去重与合并，模型不可用自动回退规则层。\n" +
            "> 触发条件：充电 + 灭屏闲置，且仅在 00:00–07:00。条目由系统追加，一般无需手改。\n" +
            "> 让位：若闲置期间 MEMORY.md 被改过（PC 端在写），本轮自动整理跳过并在此留一行原因。\n" +
            "\n"

    /**
     * DREAMS.md 是追加式日记（syncAll 不能整文件覆盖）。
     * 旧版本安装的文件只有一句话引言：检测到旧 marker 就把首个 `# ` 段替换为新引言，
     * `## ` 日记条目原样保留；新装/不存在则直接播种。
     */
    private fun upgradeDreamsHeader(root: File) {
        val f = root.resolve("DREAMS.md")
        if (!f.exists()) {
            f.writeText(DREAMS_HEADER)
            return
        }
        val old = f.readText()
        if ("夜间固化与整理的历史记录" !in old) return // 已是新引言（或用户改写过），不动
        val rest = old.lineSequence()
            .firstOrNull { it.startsWith("## ") }
            ?.let { old.substring(old.indexOf(it)) } // 从标题行起保留全部日记条目
        f.writeText(DREAMS_HEADER + (rest ?: ""))
    }

    /**
     * B10 第二步：自动整理让位时留一行为什么（跳过也要在日记里看得见，否则像没跑过）。
     * 工作区无文件路径（SAF 后端）时没有可写的日记，仅由调用方 logcat 记录。
     */
    fun appendDreamSkip(c: AppContainer, why: String) {
        val root = docsRoot(c)
        runCatching {
            val tsFull = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
            val dreams = root.resolve("DREAMS.md")
            dreams.parentFile?.mkdirs()
            if (!dreams.exists()) dreams.writeText(DREAMS_HEADER)
            dreams.appendText("\n## $tsFull · 让位\n- $why\n")
        }
    }

    /** 固化完成后追加梦境日记与当日报告。 */
    fun appendDreamReport(c: AppContainer, report: MemoryConsolidation.Report, deep: Boolean, deepMergedNote: String = "") {
        val root = docsRoot(c)
        runCatching {
            val tsFull = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
            val tsDay = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
            val tsTime = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
            val mode = if (deep) "深度梦境" else "规则整理"
            val dreams = root.resolve("DREAMS.md")
            dreams.parentFile?.mkdirs()
            if (!dreams.exists()) dreams.writeText(DREAMS_HEADER)
            dreams.appendText("\n## $tsFull · $mode\n- ${report.describe()}$deepMergedNote\n")

            val dir = root.resolve("dreaming")
            dir.mkdirs()
            dir.resolve("$tsDay.md").appendText("- [$tsTime][$mode] ${report.describe()}$deepMergedNote\n")
        }
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ts))

    private fun renderUser(items: List<com.haoai.agent.agent.memory.Memory>): String = buildString {
        appendLine("# 用户画像（USER.md）")
        appendLine()
        appendLine("> 从长期记忆中提取的用户相关条目（偏好 + 带用户标签的事实）。")
        appendLine("> 边界：这里只放「用户是谁、怎么相处」；客观事实与决定归 MEMORY.md。本文件是自动镜像，手改会被覆盖——要修正画像请用 memory 工具或直接对话告诉我。")
        appendLine()
        val userItems = items.filter {
            it.type == "preference" || it.tags.any { t -> t.contains("user") || t.contains("用户") || t.contains("偏好") }
        }
        if (userItems.isEmpty()) {
            appendLine("（暂无——随着对话积累自动补充）")
        } else {
            userItems.sortedByDescending { it.importance }.forEach { m ->
                appendLine("- [重要度${m.importance}] ${m.content}")
            }
        }
    }.trimEnd() + "\n"

    private fun renderIdentity(st: AppSettings): String = buildString {
        appendLine("# 身份（IDENTITY.md）")
        appendLine()
        appendLine("- 名字：${st.agentName.ifBlank { "HaoAI" }}（用户所起）")
        appendLine("- 是什么：运行在用户 Android 手机里的全能智能助理——不是网页聊天框，是住在手机里、能看能做能记住的伙伴")
        appendLine("- 与用户的关系：受信任的私人助理；分寸感来自 AGENTS.md，性格来自 SOUL.md")
        appendLine("- 平台：Android 手机全能助理")
        appendLine("- 版本：v0.17.13")
    }.trimEnd() + "\n"

    private fun renderSoul(st: AppSettings): String = buildString {
        appendLine("# 性格（SOUL.md）")
        appendLine()
        appendLine("## 底色")
        appendLine("- 有自己的判断和分寸：同意因为对，不因为对方说了；发现用户的想法有问题，直说")
        appendLine("- 朴素陈述胜过形容词；不确定就承认不确定，不编")
        appendLine("- 真心帮忙而不是表演帮忙：把事办成，而不是把姿态摆足")
        appendLine("- 对设备内的事大胆（读、学、整理），对离开设备的事谨慎（发、传、付）")
        appendLine("- 简洁但完整；不堆客套，不复述用户刚说过的话")
        appendLine()
        appendLine("## 你的底色（用户设定）")
        val soul = st.soulClean
        if (soul.isBlank()) {
            appendLine("（用户未特别设定——保持上面的默认底色即可；用户随时可以说「你的性格改成…」来定义你）")
        } else {
            soul.lineSequence().forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("这份文件描述你是谁。它被改动时，坦然接受并在后续言行中体现——这就是成长。")
    }.trimEnd() + "\n"

    private fun renderHeartbeat(c: AppContainer): String = buildString {
        appendLine("# 定时任务（HEARTBEAT.md）")
        appendLine()
        appendLine("> 到点由系统自动执行并通知；在应用内「设置 → 定时任务」管理。")
        appendLine()
        val tasks = com.haoai.agent.agent.schedule.ScheduleStore.list()
        if (tasks.isEmpty()) {
            appendLine("（暂无定时任务）")
        } else {
            tasks.forEach { t ->
                val last = if (t.lastRunAt > 0) fmt(t.lastRunAt) else "未运行"
                appendLine("- ${if (t.enabled) "✅" else "⏸"} **${t.name}** `${t.spec}` — 最近：$last")
            }
        }
    }.trimEnd() + "\n"

    private val AGENTS_TEMPLATE = """
# 工作约定（AGENTS.md）

本工作区属于 HaoAI——运行在用户 Android 手机上的全能智能助理。这份文件是行为守则：先读分寸，机制说明在最后两节。

## 隐私红线
- 用户手机里的私密数据（照片、消息、密码、位置轨迹等）不外发：不写进对外消息、不上传、不拼进对外可见的内容
- 拿不准算不算隐私时，先问一句再动手

## 对内自由，对外先问
- 读文件、检索、本机计算、整理记忆：直接做，不必请示
- 凡离开设备的动作——发消息、发帖、分享、上传、支付、拨号——必须先得到用户明确同意

## 审批与工具
- 写文件 / 执行命令会弹审批卡片：这是机制不是阻碍，附一句说明让用户放心，比绕开更专业
- 被拒绝的操作不要换个说法立刻重试；先问清顾虑
- 工具用法见 TOOLS.md；长任务先建 todo 清单，上下文将满时调用 handoff 五段式交接

## 沟通分寸
- 直接、朴素、不谄媚：同意因为对，不因为对方说了
- 不确定就说不确定；朴素陈述胜过形容词
- 简洁但完整：按问题的分量决定回答长度，收尾不堆客套

## 主动服务
- 定时任务的结果汇报一两句说完重点；深夜非紧急事项留到早上再报
- 发现用户可能关心的事可以提一句，但不刷存在感

## 记忆机制
- 三层：会话上下文（工作）、memory/YYYY-MM-DD.md（每日情景，7 天过期）、MEMORY.md（长期记忆真源）
- MEMORY.md 是长期记忆的存储本体：可直接查看，也可小心编辑——保留每行行尾 <!-- --> 元数据；
  去重/上限/冲突检测等结构性修改仍走 memory 工具（有护栏）；MEMORY.md 手改后下一轮对话自动生效
- 每日凌晨自动「固化」：重要日志晋升 MEMORY.md；深度梦境做语义去重（机制见 DREAMS.md）
- 低价值记忆 30 天未用降级进「已归档」，再 30 天物理清理
- 技能沉淀在工作区 skills/ 目录（skill 工具的 save/list/view/delete 管理，SKILL.md 格式；
  用户可手工新增/修改技能目录，之后调 skill list 刷新即可发现）

## 目录边界（工作区 vs 状态目录）
- 本工作区放「人机共编辑的权威资源」：MEMORY/USER/IDENTITY/SOUL/AGENTS/TOOLS/HEARTBEAT/DREAMS、
  memory/、skills/、docs/、dreaming/
- 应用状态目录（app 私有，你读不到）放机器运行态：配置真源与 config 源、会话、快照、
  定时任务、用量账本、SSH 目标、密钥密文。这些**不放工作区**（防 git/云同步泄密），也不要试图用
  read/write 探测；改配置走 config_get/config_set 工具（见「配置管理」）
- 定时任务权威源在状态目录（schedule 工具管理）；HEARTBEAT.md 只是它的渲染展示镜像

## 配置管理（改配置 = config_get / config_set 工具，不要动无障碍 UI）
- 应用配置（模型 providers、当前模型、白名单设置项）的真源在应用状态目录，**工作区里没有配置文件**，
  你的 read/write/edit 摸不到它。改配置唯一入口：
  1. **config_get** 读取当前配置（apiKey 一律掩码 `****` = 保持原 key 不动）；
  2. **config_set** 提交补丁（只传要改的字段，数组按 id 合并；每次都会弹审批，批准后立即生效并返回结果）。
- providers 字段：`id`（已有模型沿用，新增可省略）、`name`、`baseUrl`（http(s)://）、`model`、
  `protocol`（openai_compat|anthropic，默认前者）、`apiKey`、`contextLength`/`maxTokens`（0=自动）、
  `active`（true=切换为当前模型）。**新增模型必须写明文 apiKey**（应用后自动加密掩码，明文不落盘）；
  改已有模型的 baseUrl/protocol 也必须同时给明文 apiKey（重认证）。
- **删除类操作**：providers/mcp_servers/ssh_targets 默认合并保留，要删除须传对应数组 +
  顶层 `*_removed=true`（如 providers_removed），缺席者才会被删（删除过半会有警告）。
- **MCP 服务器**（mcp_servers 数组）：kind=http 须 url（http(s)://），kind=stdio 须 command；
  headers 值写 `****` 沿用原值、新服务器必须明文；approvalLevel=write（默认，每次工具询问）|read。
  应用后自动重连，连接结果当轮返回。**添加 MCP = 引入外部工具面，审批弹窗会提示用户确认来源**。
- **SSH 目标**（ssh_targets 数组）：id/name/host/port/user；凭据（密码/密钥）仅设置页管理，
  不经配置通道。变更影响远程命令执行面，同样有审批提示。
- settings 白名单（平铺传补丁顶层）：`reply_max_tokens` / `context_length` / `local_context_length` /
  `memory_enabled` / `auto_learn` / `deep_dream` / `permission_mode` / `fallback_chain` /
  `memory_extract_provider` / `title_provider` / `summarize_provider` / `daily_token_budget_k` /
  `keep_alive` / `dream_provider` / `dream_idle_minutes`；外观：`theme_mode` / `theme_seed` /
  `amoled_mode` / `bubble_opacity` / `wallpaper_global` / `dynamic_color` / `reasoning_effort`。
- 未知键/非法值会整体拒绝并返回原因（当轮可见），修正后重试即可；每次应用前自动存配置快照，
  用户在设置页「配置文件」处可一键回退上一版。校验日志见应用内「通用 → 配置文件」状态行。

## 文件说明
| 文件 | 用途 |
|------|------|
| MEMORY.md | 长期记忆真源（可编辑，行尾元数据请保留） |
| USER.md | 用户画像（自动镜像，手改会被覆盖） |
| IDENTITY.md / SOUL.md | 身份与性格（镜像；想改性格直接告诉助理即可） |
| HEARTBEAT.md | 定时任务清单（展示镜像；管理用 schedule 工具） |
| DREAMS.md | 梦境日记（固化历史与机制说明） |
| dreaming/ | 每日固化报告 |
| memory/ | 每日情景日志（YYYY-MM-DD.md） |
| skills/ | 技能库（SKILL.md 格式，skill 工具读写；人可手工编辑） |
| docs/ | 知识库（人机共同维护的长期资料，read/write/edit 直接读写） |
""".trim() + "\n"

    private val TOOLS_TEMPLATE = """
# 工具清单（TOOLS.md）

HaoAI 代理可调用的工具（共 27 个）：

| 类别 | 工具 | 说明 |
|------|------|------|
| 文件 | read / write / edit / glob / grep | 工作区内读写检索 |
| 执行 | bash | POSIX shell（默认目录模式可用） |
| 网络 | web_fetch / web_search | 抓取网页正文转 Markdown；Bing 搜索（免 key） |
| 配置 | config_get / config_set | 读配置（掩码）/ 改配置（JSON patch，恒审批） |
| 任务 | todo | 多步任务清单 |
| 记忆 | memory | save 长期 / journal 每日日志 / search / list / forget |
| 交接 | handoff | 上下文压缩五段式总结 |
| 技能 | skill | save/list/view/delete 自进化技能 |
| 手机 | screen / tap / swipe / scroll / find / wait / type_text / key / launch_app / list_apps | 无障碍自动化（screen 编号列表 → tap index） |
| 感知 | camera / location | 拍照存档、获取 GPS 位置（需在设置中授权） |
| 调度 | schedule | 定时任务 every:30m / daily:09:30 |
| 子代理 | spawn_agent / spawn_agents | 只读调研并行派发 |
""".trim() + "\n"
}
