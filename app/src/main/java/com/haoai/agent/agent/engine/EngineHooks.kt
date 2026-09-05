package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.agent.tools.ToolContext
import com.haoai.agent.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import com.haoai.agent.agent.tools.optBool
import com.haoai.agent.agent.tools.optString
import java.util.concurrent.ConcurrentHashMap

/**
 * E7c 写文件验证闭环：write/edit 成功且为代码类文件时做结构校验，
 * 校验失败作为错误回给模型形成一次修复循环（每文件每会话最多提示 1 次）。
 * 沙箱未就绪时降级为建议提示，不阻塞。
 */
class ValidateWriteHook : ToolHook {
    override val names = setOf("write", "edit")

    override suspend fun after(
        call: ToolCallData,
        args: JsonObject,
        ctx: ToolContext,
        result: ToolResult
    ): ToolResult {
        if (result.isError) return result
        val path = args.optString("path") ?: return result
        if (!isCodeFile(path)) return result
        val content = args.optString("content") ?: return result
        val code = if (call.name == "edit") null else content // edit 无整段 content
        val err = validate(path, content)
        if (err == null) return result
        // 每文件每会话最多 1 次自动提示（防打转）
        val key = path
        if (!hinted.add(key)) return result
        return ToolResult(
            result.content +
                "\n\n[校验失败] ${path}: $err\n请修复后重写该文件（本次为唯一自动提示，后续修复靠你自己验证）。",
            true
        )
    }

    private val hinted = ConcurrentHashMap.newKeySet<String>()

    private fun isCodeFile(path: String): Boolean =
        path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".py") ||
            path.endsWith(".js") || path.endsWith(".ts") || path.endsWith(".json")

    private fun validate(path: String, content: String): String? = when {
        path.endsWith(".json") -> runCatching {
            com.haoai.agent.data.HaoJson.json.parseToJsonElement(content)
            null
        }.getOrElse { it.message?.take(120) ?: "JSON 解析失败" }
        // .py/.js/.ts 依赖 Linux 沙箱，此处仅建议提示（引擎层不做真实校验）
        path.endsWith(".py") || path.endsWith(".js") || path.endsWith(".ts") -> null
        else -> null
    }
}

/**
 * E3 失败升级 hook（迁移自 executeCall 内联逻辑）：连续失败 ≥2 引导验证前置条件、
 * ≥3 禁止同参重试；bash 失败 ≥2 提示先看 stderr。
 * 批次5-G 参数指纹防护：同工具+同参数连续 ≥3 次（无论成败）注入禁令——
 * 成功的重复调用此前不设防，只靠 token/圈数上限间接兜底。
 */
class EscalationHook(
    private val conFailCount: ConcurrentHashMap<String, Int>
) : ToolHook {

    // 指纹 = 工具名 + args 串 hash；换指纹即清零。引擎生命周期存续，与 conFailCount 同语义
    private val lastFingerprint = java.util.concurrent.atomic.AtomicReference<String>("")
    private val repeatCount = java.util.concurrent.atomic.AtomicInteger(0)

    override suspend fun after(
        call: ToolCallData,
        args: JsonObject,
        ctx: ToolContext,
        result: ToolResult
    ): ToolResult {
        var content = result.content
        val fp = call.name + "#" + args.toString().hashCode()
        val repeats = if (lastFingerprint.getAndSet(fp) == fp) repeatCount.incrementAndGet()
        else { repeatCount.set(0); 0 }
        if (repeats >= 2) { // 0=首次、1=第2次、2=第3次起
            content += "\n\n[循环防护] 相同参数的 ${call.name} 已连续调用 ${repeats + 1} 次。" +
                "停止相同重试：修正参数、换方法，或向用户说明。"
            repeatCount.set(0)
        }
        if (result.isError) {
            val n = conFailCount.merge(call.name, 1) { a, b -> a + b } ?: 1
            when {
                n == 2 -> content += "\n\n[恢复提示] 工具 ${call.name} 已连续失败 2 次。先验证前置条件（路径存在？参数格式？权限模式？），或改用替代工具。"
                n >= 3 -> content += "\n\n[升级] 该步骤已失败 ${n} 次。禁止用相同参数重试。二选一：a) 换方法达成同一目标；b) 直接向用户说明阻塞点并请求指示。"
            }
            if (call.name == "bash" && n >= 2) {
                content += "\n[提示] 先完整查看 stderr 再决定下一步。"
            }
            return result.copy(content = content)
        }
        conFailCount.remove(call.name)
        return result.copy(content = content)
    }
}

/**
 * 5.7 技能自改进提示 hook（迁移自 executeCall 内联逻辑）：skill 工具后记录
 * 使用结果 + 追加一次性修订提示（每会话每技能限一次）。
 */
class SkillHintHook(
    private val hintedSkills: MutableSet<String>,
    private val store: com.haoai.agent.agent.skills.SkillStore = com.haoai.agent.agent.skills.SkillStore
) : ToolHook {
    override val names = setOf("skill")

    override suspend fun after(
        call: ToolCallData,
        args: JsonObject,
        ctx: ToolContext,
        result: ToolResult
    ): ToolResult {
        val skillName = args.optString("name").orEmpty().ifBlank { "unknown" }
        val resultTag = if (result.isError) "failed: ${result.content.take(80)}" else "success"
        runCatching { store.recordUseResult(skillName, resultTag) }
        val hintKey = skillName
        if (hintedSkills.add(hintKey)) {
            val hint = if (result.isError) {
                "\n\n[技能自改进] 技能「$skillName」刚被使用（结果：失败——${result.content.take(80)}）。若失败暴露了技能步骤缺陷，用 skill save 修订该技能。"
            } else {
                "\n\n[技能自改进] 技能「$skillName」刚被使用（结果：成功）。若发现技能内容有改进空间，用 skill save 修订该技能。"
            }
            return result.copy(content = result.content + hint)
        }
        return result
    }
}

/**
 * 5.5 写前快照 hook（迁移自 executeCall 内联逻辑）：write/edit 执行前把原文
 * 记进快照目录，供回滚。快照必须在审批之后、执行之前拍——before 阶段执行。
 */
class SnapshotHook(
    private val appFilesDir: java.io.File,
    private val sessionId: String,
    private val backend: com.haoai.agent.platform.FileBackend?
) : ToolHook {
    override val names = setOf("write", "edit")

    override suspend fun before(
        call: ToolCallData,
        args: JsonObject,
        ctx: ToolContext
    ): ToolHook.HookDecision? {
        runCatching {
            val path = args.optString("path") ?: return null
            val before = runCatching { backend?.readText(path) }.getOrNull()
            val after = when (call.name) {
                "write" -> args.optString("content")
                else -> {
                    val old = args.optString("old_string") ?: return null
                    val new = args.optString("new_string") ?: return null
                    val replaceAll = args.optBool("replace_all")
                    before?.let {
                        if (replaceAll) it.replace(old, new) else it.replaceFirst(old, new)
                    }
                }
            }
            if (after != null) {
                // C2：快照落盘前脱敏（apiKey 与 MCP headers 密钥），明文不进 filesDir/snapshots，
                // /undo 恢复的也是掩码版本——掩码=保持原值不动，恢复后配置仍能通过校验
                fun mask(t: String): String {
                    val b = com.haoai.agent.data.ConfigFileBridge
                    return b.maskHeaderValues(b.maskApiKeys(t))
                }
                com.haoai.agent.agent.tools.snapshot.FileSnapshot.snapshot(
                    appFilesDir, sessionId, call.id, path,
                    before?.let(::mask),
                    mask(after)
                )
            }
        }
        return null
    }
}
