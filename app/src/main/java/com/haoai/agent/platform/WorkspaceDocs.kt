package com.haoai.agent.platform

import com.haoai.agent.agent.memory.MemoryConsolidation
import com.haoai.agent.data.AppContainer
import com.haoai.agent.data.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 上游 式工作区文档层：把记忆/身份/能力渲染成用户可直接查看编辑的 Markdown 文件，
 * 落在默认工作区（Android/data/com.haoai.agent/files/workspace）：
 *   MEMORY.md   长期记忆库
 *   USER.md     用户画像（偏好类记忆）
 *   IDENTITY.md 助理身份
 *   SOUL.md     性格设定
 *   AGENTS.md   工作约定说明
 *   TOOLS.md    工具清单
 *   HEARTBEAT.md 定时任务清单
 *   DREAMS.md   梦境日记（固化历史，人工审查用）
 *   dreaming/YYYY-MM-DD.md 每日固化报告
 * 仅在默认工作区（真实文件目录）下同步；SAF 目录跳过。
 */
object WorkspaceDocs {

    fun workspaceRoot(c: AppContainer): File? =
        (c.workspace.current as? RawFileBackend)?.shellWorkdir()

    fun syncAll(c: AppContainer) {
        val root = workspaceRoot(c) ?: return
        runCatching {
            val items = c.memoryBank.all()
            // MEMORY.md 不在此同步：它已是长期记忆真源（由 MemoryBank 直接读写），覆盖会丢数据
            root.resolve("USER.md").writeText(renderUser(items))
            val st = c.settingsFlow.value
            root.resolve("IDENTITY.md").writeText(renderIdentity(st))
            root.resolve("SOUL.md").writeText(renderSoul(st))
            root.resolve("AGENTS.md").writeText(AGENTS_TEMPLATE)
            root.resolve("TOOLS.md").writeText(TOOLS_TEMPLATE)
            root.resolve("HEARTBEAT.md").writeText(renderHeartbeat(c))
            if (!root.resolve("DREAMS.md").exists()) {
                root.resolve("DREAMS.md").writeText(
                    "# 梦境日记\n\n夜间固化与整理的历史记录（自动追加，供人工审查）。\n"
                )
            }
        }
    }

    /** 固化完成后追加梦境日记与当日报告。 */
    fun appendDreamReport(c: AppContainer, report: MemoryConsolidation.Report, deep: Boolean, deepMergedNote: String = "") {
        val root = workspaceRoot(c) ?: return
        runCatching {
            val tsFull = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
            val tsDay = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
            val tsTime = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
            val mode = if (deep) "深度梦境" else "规则整理"
            val dreams = root.resolve("DREAMS.md")
            dreams.parentFile?.mkdirs()
            if (!dreams.exists()) dreams.writeText("# 梦境日记\n")
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
        appendLine("- 平台：Android 手机全能助理")
        appendLine("- 版本：v0.17.9")
    }.trimEnd() + "\n"

    private fun renderSoul(st: AppSettings): String = buildString {
        appendLine("# 性格（SOUL.md）")
        appendLine()
        if (st.soul.isBlank()) appendLine("（首启引导时未设定，可在引导后由用户补充）")
        else st.soul.lineSequence().forEach { appendLine(it) }
    }.trimEnd() + "\n"

    private fun renderHeartbeat(c: AppContainer): String = buildString {
        appendLine("# 定时任务（HEARTBEAT.md）")
        appendLine()
        appendLine("> 到点由系统自动执行并通知；在应用内「设置 → 定时任务」管理。")
        appendLine()
        val tasks = com.haoai.agent.agent.schedule.ScheduleStore.load().items
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

本工作区属于 HaoAI——运行在用户 Android 手机上的全能智能助理。

## 约定
- 记忆分三层：会话上下文（工作）、memory/YYYY-MM-DD.md（每日情景，7 天过期）、MEMORY.md（长期记忆真源）
- MEMORY.md 是长期记忆的存储本体（上游 式"文件即记忆"）：可直接查看，也可小心编辑——保留每行行尾 <!-- --> 元数据；
  去重/上限/冲突检测等结构性修改建议仍走 memory 工具（有护栏）；文件被改动后下一轮对话自动生效
- 每日凌晨自动「固化」：每日日志中重要性 ≥4 的条目晋升进 MEMORY.md；开启深度梦境时由端侧模型做语义去重合并
- 低价值记忆 30 天未用会降级进 MEMORY.md「已归档」节（不再注入，仍可见可捞回），归档 30 天后物理清理
- 固化历史见 DREAMS.md 与 dreaming/ 目录
- 技能沉淀在应用内部（skill save/list/view/delete 工具管理）
- 长任务先建 todo 清单；上下文将满时调用 handoff 五段式交接

## 文件说明
| 文件 | 用途 |
|------|------|
| MEMORY.md | 长期记忆真源（可直接查看编辑，行尾元数据请保留） |
| USER.md | 用户画像（自动维护的镜像） |
| IDENTITY.md / SOUL.md | 身份与性格 |
| HEARTBEAT.md | 定时任务清单 |
| DREAMS.md | 梦境日记（固化历史） |
| dreaming/ | 每日固化报告 |
| memory/ | 每日情景日志（YYYY-MM-DD.md） |
""".trim() + "\n"

    private val TOOLS_TEMPLATE = """
# 工具清单（TOOLS.md）

HaoAI 代理可调用的工具（共 27 个）：

| 类别 | 工具 | 说明 |
|------|------|------|
| 文件 | read / write / edit / glob / grep | 工作区内读写检索 |
| 执行 | bash | POSIX shell（默认目录模式可用） |
| 网络 | web_fetch / web_search | 抓取网页正文转 Markdown；Bing 搜索（免 key） |
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
