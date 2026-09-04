package com.haoai.agent.agent.workflow

import com.haoai.agent.data.HaoJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Phase 6 工作流引擎：JSON 定义 + 触发器 + 审批门控。
 * 存储：filesDir/workflows/<id>.json（单文件单工作流，atomic 写）。
 * 语义（tinyflows 精神）：Agent 用 workflow_save 起草的必须经用户 UI 确认才
 * enabled；执行时每步仍走 PolicyEngine（headless 场景沿用定时任务的 YOLO 约定）。
 */
object WorkflowStore {

    @Serializable
    data class Trigger(
        /** manual | schedule | boot | notification */
        val type: String = "manual",
        /** schedule: "every:5m" | "daily:08:00"（复用 Scheduler spec 文法）；notification: 关键词；boot: 空 */
        val config: String = ""
    )

    @Serializable
    data class Step(
        /** "prompt"=以一段用户指令跑一次完整 Agent 循环；"tool"=直接调用单个工具 */
        val type: String = "prompt",
        /** prompt 步的指令文本。支持 {{prev}}=上一步输出、{{step1}}/{{step2}}…=第 N 步输出 */
        val text: String = "",
        /** tool 步的工具名 */
        val tool: String = "",
        /** tool 步的参数 JSON 文本，同样支持 {{prev}}/{{stepN}} 模板 */
        val args: String = "",
        /** 步骤失败是否中止整个工作流（prompt 步失败/工具被拒均算失败） */
        val stopOnError: Boolean = true,
        /** 条件分支：空=总是执行；prev_contains:文本 / prev_not_contains:文本（对上一步输出判断，不满足则跳过该步） */
        val condition: String = ""
    )

    @Serializable
    data class RunRecord(
        val ts: Long,
        val ok: Boolean,
        val summary: String,
        /** 本次运行耗时（毫秒），0=旧记录 */
        val durationMs: Long = 0,
        /** 触发来源：manual | schedule | boot | notification | external */
        val trigger: String = "manual"
    )

    @Serializable
    data class WorkflowDef(
        val id: String,
        val name: String,
        val enabled: Boolean = false,
        /** Agent 起草待确认：UI 确认后才允许 enabled=true。 */
        val pendingConfirm: Boolean = false,
        val trigger: Trigger = Trigger(),
        val steps: List<Step> = emptyList(),
        val createdAt: Long = 0,
        val lastRun: RunRecord? = null,
        /** 最近 20 次运行记录（新在前由 UI 处理，存储按时间正序追加）。 */
        val history: List<RunRecord> = emptyList(),
        /** 外部广播触发令牌（16 位）；懒生成，广播 extras 必须携带匹配值才触发。 */
        val externalToken: String? = null
    )

    @Volatile private var dir: File? = null
    private val lock = Any()

    fun init(filesDir: File) {
        dir = File(filesDir, "workflows").apply { mkdirs() }
    }

    fun list(): List<WorkflowDef> {
        val d = dir ?: return emptyList()
        return synchronized(lock) {
            d.listFiles()?.filter { it.extension == "json" }?.mapNotNull { f ->
                runCatching { HaoJson.json.decodeFromString(WorkflowDef.serializer(), f.readText()) }.getOrNull()
            }?.sortedBy { it.createdAt } ?: emptyList()
        }
    }

    fun get(id: String): WorkflowDef? = list().firstOrNull { it.id == id }

    fun save(def: WorkflowDef) {
        val d = dir ?: return
        synchronized(lock) {
            HaoJson.writeAtomic(File(d, "${def.id}.json"), HaoJson.json.encodeToString(WorkflowDef.serializer(), def))
        }
    }

    fun delete(id: String): Boolean {
        val d = dir ?: return false
        synchronized(lock) { return File(d, "$id.json").delete() }
    }

    fun newId(): String = "wf-${System.currentTimeMillis().toString(36)}-${(0..999).random()}"

    /** Agent 起草入口（workflow_save 工具）：pendingConfirm=true，enabled=false。 */
    fun draft(name: String, trigger: Trigger, steps: List<Step>): WorkflowDef {
        val def = WorkflowDef(
            id = newId(), name = name.take(40), enabled = false, pendingConfirm = true,
            trigger = trigger, steps = steps, createdAt = System.currentTimeMillis()
        )
        save(def)
        return def
    }

    /** 工具 spec 校验（复用 Scheduler 文法：hourly | every:<n><m|h|d> | daily:HH:MM）。 */
    fun validScheduleSpec(spec: String): Boolean =
        spec == "hourly" || Regex("^every:\\d+[mhd]$").matches(spec) || Regex("^daily:\\d{2}:\\d{2}$").matches(spec)

    /** 条件分支文法：空=无条件 | prev_contains:文本 | prev_not_contains:文本。 */
    fun validCondition(c: String): Boolean =
        c.isBlank() || c.startsWith("prev_contains:") || c.startsWith("prev_not_contains:")

    /**
     * 外部触发令牌：每工作流固定随机串，广播方（Tasker/adb）须携带匹配值。
     * 令牌为空时外部触发对该工作流无效——避免任何知道包名的人都能拉起流程。
     */
    fun externalToken(def: WorkflowDef): String {
        def.externalToken?.takeIf { it.isNotBlank() }?.let { return it }
        val t = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        save(def.copy(externalToken = t))
        return t
    }
}
