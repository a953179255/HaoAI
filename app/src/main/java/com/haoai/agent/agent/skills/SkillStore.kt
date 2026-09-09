package com.haoai.agent.agent.skills

import com.haoai.agent.data.HaoJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 技能元数据（策展式生命周期）：
 * - useCount/lastUsedAt：view 时自动累计
 * - source：user=用户手动创建（不参与自动归档），agent=自进化生成
 * - archived：闲置 90 天自动归档（不物理删除，可随时恢复）；pinned 豁免
 * - 系统提示索引只注入活跃技能的名字+60 字描述，正文按需加载省上下文
 */
@Serializable
data class SkillMeta(
    val name: String,
    val description: String,
    val updatedAt: Long = 0,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0,
    /** user=手动创建，agent=自进化；import_local/import_url/import_clipboard=外部导入（2.2）。 */
    val source: String = "user",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** 外部导入时间戳（source 为 import_* 时有值），技能卡片展示用。 */
    val importedAt: Long = 0,
    /** 5.7 最近一次使用结果："success" / "failed: <原因>"（自改进提示依据，技能列表展示）。 */
    val lastUsedResult: String? = null,
    /** 疗效遥测（Phase 2）：按 isError 分桶累计，promptIndex 排序预留。 */
    val successCount: Int = 0,
    val failCount: Int = 0,
    /** 连续失败计数，成功一次清零。 */
    val failStreak: Int = 0,
    /** failStreak≥2 置位：不再注入系统提示索引，待人审/修订；一次成功即复位。 */
    val needsRevision: Boolean = false,
    /**
     * 隔离晋升（Phase 5）：false=候选态——不进系统提示索引，等技能库页人工"验证并启用"。
     * 存量与用户手建缺省 true；仅导入（import_*）与 agent 新建（source=agent）默认 false。
     */
    val validated: Boolean = true,
    /** 导入内容扫描（SkillGuard）命中标签，供人审参考；验证启用时清除。 */
    val reviewNotes: String? = null
) {
    /** 最近活动锚点：用过看 lastUsedAt，没用过看创建/更新时间。 */
    fun anchor(): Long = maxOf(lastUsedAt, updatedAt)

    fun inactiveDays(now: Long = System.currentTimeMillis()): Long =
        (now - anchor()) / 86_400_000L

    /** 闲置 30 天的自进化技能不再注入提示词（仍在库里可搜索）。 */
    fun staleForPrompt(): Boolean {
        if (pinned || archived) return archived
        return source == "agent" && useCount == 0 && inactiveDays() > 30
    }
}

    /**
     * 技能库全局单例：引擎/工具/UI 共享同一把对象锁，避免多实例 @Synchronized 失效。
     * C5：技能落盘迁工作区 `skills/<slug>/SKILL.md`（人机共编辑权威资源，可 git）；
     * 状态目录 filesDir/skills/ 仅作初始化前的默认值与迁移来源，启动迁移为移动语义
     * （复制成功或工作区已有同名即排空源，残影不得跨启动存活）。SAF 工作区时保持状态目录
     * （SafFileBackend 无本地路径，工具经 SAF 访问工作区，技能读写仍走 File 落点不破坏）。
     */
object SkillStore {

    @Volatile
    private var baseDir: File? = null

    @Volatile
    private var fallbackDir: File? = null

    fun init(appFilesDir: File) {
        fallbackDir = File(appFilesDir, "skills")
        baseDir = fallbackDir
    }

    /**
     * C 出厂预置：联网调研技能（mercury 式种子）。首次启动播种一次；
     * markerDir 里的标记文件保证用户删除后不复活。source=user：免验生效、不参与自动归档。
     */
    fun seedBundledResearchSkill(markerDir: File) {
        val marker = File(markerDir, "bundled_web_research_seeded")
        if (marker.exists()) return
        runCatching {
            if (!exists("web-research")) {
                save(
                    name = "web-research",
                    description = "联网调研标准流程：搜索→精读→交叉验证→收敛回答",
                    body = """
                        # 联网调研

                        需要查资料/新闻/对比/总结/预测时按此流程执行。

                        ## Procedure（步骤）
                        1. 用 web_search 搜索关键词；结果为空或质量差时换关键词重搜一次。
                        2. 从结果中挑最相关的 2-4 个 url，用 web_fetch 精读（同一轮可并行发多个）。
                        3. 关键事实至少交叉验证 2 个来源；来源互相矛盾时明说，不硬选。
                        4. 综合给出简洁回答：结论先行，附来源链接；不确定就明说不确定。

                        ## Pitfalls（坑与注意）
                        - 优先一手权威来源（官方文档/原始公告/权威媒体），不堆二手转述。
                        - 简单事实一次搜索加一两个来源即可，不要扩大搜索面。
                        - 永远不编造来源链接；引用必须来自实际抓取过的页面。
                    """.trimIndent(),
                    source = "user"
                )
            }
            markerDir.mkdirs()
            marker.writeText("seeded")
        }
    }

    /** C5：切落到工作区 skills/ 目录（幂等迁移由调用方完成）；null 回退状态目录（SAF 工作区场景）。 */
    @Synchronized
    fun useWorkspaceDir(dir: File?) {
        baseDir = dir ?: fallbackDir
        cachedList = null
    }

    private val dir: File
        get() = (baseDir ?: throw IllegalStateException("SkillStore 未初始化（需先调用 init）")).apply { mkdirs() }

    /** 名称 → 目录的受限解析：拒绝路径穿越，只允许 skills/ 直属目录。 */
    private fun resolve(name: String): File? {
        val d = File(dir, name.trim()).canonicalFile
        val root = dir.canonicalFile
        return if (d.path.startsWith(root.path + File.separator)) d else null
    }

    // 技能索引缓存：promptIndex 每轮注入系统提示，不能每轮扫盘读全部 SKILL.md
    @Volatile
    private var cachedList: List<SkillMeta>? = null

    @Volatile
    private var lastSweepAt: Long = 0L

    private fun skillFile(name: String): File = File(dir, name).apply { mkdirs() }.let { File(it, "SKILL.md") }

    /** 提取 frontmatter 区文本（首个 --- 围栏内，不含围栏本身）；无/未闭合 frontmatter 返回 null。 */
    private fun frontmatterOf(text: String): String? {
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", 3)
        if (end < 0) return null
        return text.substring(3, end)
    }

    /** 从既有 SKILL.md 解析 frontmatter 元数据（兼容旧格式与 agentskills.io 外部格式：未知字段忽略、缺省回退）。 */
    private fun parseMeta(text: String, name: String, fallbackMtime: Long): SkillMeta {
        // 只扫 frontmatter 区：正文出现 "useCount: 3" 之类的示例行不得污染解析
        val front = frontmatterOf(text) ?: ""
        fun line(key: String): String? =
            if (front.isEmpty()) null
            else Regex("^$key:\\s*(.+)$", RegexOption.MULTILINE).find(front)?.groupValues?.get(1)?.trim()
        val desc = cleanDesc(line("description")) ?: firstParagraphOf(bodyTextOf(text))
        return SkillMeta(
            name = name,
            description = desc,
            updatedAt = line("updatedAt")?.toLongOrNull() ?: fallbackMtime,
            useCount = line("useCount")?.toIntOrNull() ?: 0,
            lastUsedAt = line("lastUsedAt")?.toLongOrNull() ?: 0L,
            source = line("source") ?: "user",
            pinned = line("pinned") == "true",
            archived = line("archived") == "true",
            importedAt = line("importedAt")?.toLongOrNull() ?: 0L,
            lastUsedResult = line("lastUsedResult")?.takeIf { it != "null" },
            successCount = line("successCount")?.toIntOrNull() ?: 0,
            failCount = line("failCount")?.toIntOrNull() ?: 0,
            failStreak = line("failStreak")?.toIntOrNull() ?: 0,
            needsRevision = line("needsRevision") == "true",
            // 旧文件无此字段 → 存量默认 true（零迁移成本）
            validated = line("validated")?.let { it == "true" } ?: true,
            reviewNotes = line("reviewNotes")?.takeIf { it != "null" }
        )
    }

    /** 描述清理：去引号包裹、还原 YAML 转义、压平换行、截断（agentskills.io 的 description 可能是多行/带引号）。 */
    private fun cleanDesc(raw: String?): String? {
        val s = raw?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: return null
        val flat = s.replace(Regex("[\\r\\n]+"), " ")
            .replace("\\\"", "\"")
            .trim()
        return flat.ifBlank { null }
    }

    /** 正文首段兜底描述：取第一个非空、非标题/列表符开头的行。 */
    private fun firstParagraphOf(body: String): String =
        body.lines().firstOrNull {
            val t = it.trim()
            t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("---")
        }?.trim()?.take(120) ?: ""

    /** 把元数据写回 frontmatter（保留正文不动）。 */
    private fun writeMeta(f: File, meta: SkillMeta, body: String) {
        val front = buildString {
            appendLine("---")
            appendLine("name: ${meta.name}")
            appendLine("description: ${meta.description.replace(Regex("[\\r\\n]+"), " ").take(120)}")
            appendLine("source: ${meta.source}")
            appendLine("useCount: ${meta.useCount}")
            appendLine("lastUsedAt: ${meta.lastUsedAt}")
            appendLine("pinned: ${meta.pinned}")
            appendLine("archived: ${meta.archived}")
            if (meta.importedAt > 0) appendLine("importedAt: ${meta.importedAt}")
            if (meta.lastUsedResult != null) appendLine("lastUsedResult: ${meta.lastUsedResult}")
            if (meta.successCount > 0) appendLine("successCount: ${meta.successCount}")
            if (meta.failCount > 0) appendLine("failCount: ${meta.failCount}")
            if (meta.failStreak > 0) appendLine("failStreak: ${meta.failStreak}")
            if (meta.needsRevision) appendLine("needsRevision: true")
            // 只在候选态落盘：validated=true 为默认值，避免给存量文件添噪
            if (!meta.validated) appendLine("validated: false")
            if (meta.reviewNotes != null) appendLine("reviewNotes: ${meta.reviewNotes}")
            appendLine("updatedAt: ${meta.updatedAt}")
            appendLine("---")
        }
        HaoJson.writeAtomic(f, front + body)
    }

    private fun bodyOf(f: File): String = bodyTextOf(runCatching { f.readText() }.getOrDefault(""))

    /** 从文本提取 frontmatter 之后的正文（无 frontmatter 返回原文）。 */
    private fun bodyTextOf(text: String): String {
        if (!text.startsWith("---")) return text
        return text.removePrefix("---").substringAfter("---", "").trimStart('\n')
    }

    @Synchronized
    fun save(
        name: String,
        description: String,
        body: String,
        source: String = "user",
        importedAt: Long = 0,
        bodyLimit: Int = 8000,
        reviewNotes: String? = null
    ): File {
        val safe = sanitizeName(name)
        val f = skillFile(safe)
        // 覆盖更新时保留历史计数；新建时记录来源
        val old = if (f.exists()) parseMeta(runCatching { f.readText() }.getOrDefault(""), safe, 0) else null
        val now = System.currentTimeMillis()
        // C 只读技能免确认：agent 新建的技能若正文只引用只读工具（调研/检索/读取类），
        // 自动晋升进索引——最坏情况只是搜得不好，且有疗效遥测兜底（连续失败 2 次出索引）。
        // 涉及写入/执行/设备控制的技能照旧走候选态人审；SkillGuard 扫描命中可疑内容的不放行。
        val autoValidate = old?.validated
            ?: (source == "user" || (source == "agent" && reviewNotes.isNullOrBlank() && isReadOnlyBody(body)))
        val meta = SkillMeta(
            name = safe,
            description = description.trim().replace(Regex("[\\r\\n]+"), " ").take(120),
            updatedAt = now,
            useCount = old?.useCount ?: 0,
            lastUsedAt = old?.lastUsedAt ?: 0L,
            source = if (old != null) old.source else source,
            pinned = old?.pinned ?: false,
            archived = false,
            importedAt = if (old?.importedAt ?: 0L > 0) old!!.importedAt else importedAt,
            // 覆盖保存不得丢疗效遥测与上次结果（lastUsedResult 曾在此被静默清掉）
            lastUsedResult = old?.lastUsedResult,
            successCount = old?.successCount ?: 0,
            failCount = old?.failCount ?: 0,
            failStreak = old?.failStreak ?: 0,
            needsRevision = old?.needsRevision ?: false,
            // 候选门控：新建时仅用户手建与只读 agent 技能免验；其余导入/写入类 agent 新建走 false。
            // 覆盖更新保留原状（agent 修订不得给技能解禁）
            validated = autoValidate,
            reviewNotes = reviewNotes?.ifBlank { null } ?: old?.reviewNotes
        )
        writeMeta(f, meta, body.trim().take(bodyLimit))
        cachedList = null
        return f
    }

    /**
     * C 只读判定：正文不得提及任何写入/执行/设备控制类工具名（提及即视为有副作用风险，
     * 走候选态人审）。只查英文工具名——中文叙述（"写入文件"）不构成工具引用。
     */
    fun isReadOnlyBody(body: String): Boolean {
        val risky = listOf(
            "bash", "edit", "write", "spawn_agents", "screen", "tap", "type_text", "key",
            "scroll", "wait", "find", "launch_app", "open_uri", "config_set", "config_get",
            "browser_click", "browser_input", "browser_navigate", "browser_search",
            "browser_open", "browser_scroll", "browser_back", "browser_screenshot",
            "vscreen_launch", "vscreen_tap", "vscreen_text", "vscreen_scroll", "vscreen_close",
            "schedule", "workflow_save", "delegate_to_vision", "delegate_to_transcribe_audio",
            "camera", "calendar", "contacts", "clipboard", "notifications", "alarm"
        ).any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(body) }
        return !risky
    }

    /** 技能名净化：与 save 一致，供导入通道预检/重命名使用（去尾部连字符避免 "name-" 形态）。 */
    fun sanitizeName(raw: String): String =
        raw.trim().replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fff]"), "-").take(40)
            .trimEnd('-').ifBlank { "skill" }

    fun exists(name: String): Boolean {
        val d = resolve(sanitizeName(name)) ?: return false
        return File(d, "SKILL.md").exists()
    }

    @Synchronized
    fun list(): List<SkillMeta> {
        maybeSweep()
        cachedList?.let { return it }
        val result = dir.listFiles { f -> f.isDirectory }?.mapNotNull { d ->
            val f = File(d, "SKILL.md")
            if (!f.exists()) return@mapNotNull null
            val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            parseMeta(text, d.name, f.lastModified())
        }?.sortedByDescending { it.anchor() } ?: emptyList()
        cachedList = result
        return result
    }

    @Synchronized
    fun view(name: String): String? {
        val d = resolve(name) ?: return null
        if (!d.isDirectory) return null
        val sf = File(d, "SKILL.md")
        if (!sf.exists()) return null
        val text = runCatching { sf.readText() }.getOrNull() ?: return null
        // 使用遥测：累计次数 + 最近使用时间（curator 淘汰依据）
        val meta = parseMeta(text, d.name, sf.lastModified())
        writeMeta(sf, meta.copy(useCount = meta.useCount + 1, lastUsedAt = System.currentTimeMillis()), bodyOf(sf))
        cachedList = null
        return text
    }

    /**
     * 5.7 技能自改进闭环：skill 工具执行后由引擎回写使用结果（不影响 useCount 遥测）。
     * [result] "success" 或 "failed: <原因>"。
     * Phase 2 疗效分桶：连续失败 2 次置 needsRevision（出索引待人审），一次成功即复位。
     */
    @Synchronized
    fun recordUseResult(name: String, result: String): Boolean {
        val d = resolve(name) ?: return false
        val sf = File(d, "SKILL.md")
        if (!sf.exists()) return false
        val text = runCatching { sf.readText() }.getOrNull() ?: return false
        val meta = parseMeta(text, d.name, sf.lastModified())
        val updated = if (result.startsWith("failed")) {
            val streak = meta.failStreak + 1
            meta.copy(
                lastUsedResult = result.take(160),
                failCount = meta.failCount + 1,
                failStreak = streak,
                needsRevision = meta.needsRevision || streak >= 2
            )
        } else {
            meta.copy(
                lastUsedResult = result.take(160),
                successCount = meta.successCount + 1,
                failStreak = 0,
                needsRevision = false
            )
        }
        writeMeta(sf, updated, bodyOf(sf))
        cachedList = null
        return true
    }

    @Synchronized
    fun delete(name: String): Boolean {
        val d = resolve(name) ?: return false
        if (!d.isDirectory) return false
        val ok = d.deleteRecursively()
        if (ok) cachedList = null
        return ok
    }

    @Synchronized
    fun setPinned(name: String, pinned: Boolean): Boolean {
        val d = resolve(name) ?: return false
        val sf = File(d, "SKILL.md")
        if (!sf.exists()) return false
        val text = runCatching { sf.readText() }.getOrNull() ?: return false
        val meta = parseMeta(text, d.name, sf.lastModified())
        writeMeta(sf, meta.copy(pinned = pinned), bodyOf(sf))
        cachedList = null
        return true
    }

    /** Phase 5 候选晋升：人工确认后进系统提示索引；启用时清 reviewNotes（审阅完成）。 */
    @Synchronized
    fun setValidated(name: String, validated: Boolean): Boolean {
        val d = resolve(name) ?: return false
        val sf = File(d, "SKILL.md")
        if (!sf.exists()) return false
        val text = runCatching { sf.readText() }.getOrNull() ?: return false
        val meta = parseMeta(text, d.name, sf.lastModified())
        writeMeta(sf, meta.copy(validated = validated, reviewNotes = if (validated) null else meta.reviewNotes), bodyOf(sf))
        cachedList = null
        return true
    }

    /**
     * curator 式时间衰减归档：90 天无活动的非置顶技能移入归档态（不物理删除）。
     * 每天最多执行一次。返回本次新归档数量。
     */
    @Synchronized
    fun sweep(now: Long = System.currentTimeMillis()): Int {
        lastSweepAt = now
        var n = 0
        dir.listFiles { f -> f.isDirectory }?.forEach { d ->
            val f = File(d, "SKILL.md")
            if (!f.exists()) return@forEach
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            val meta = parseMeta(text, d.name, f.lastModified())
            if (!meta.archived && !meta.pinned && meta.inactiveDays(now) > 90) {
                writeMeta(f, meta.copy(archived = true), bodyOf(f))
                n++
            }
        }
        if (n > 0) cachedList = null
        return n
    }

    private fun maybeSweep() {
        val now = System.currentTimeMillis()
        if (now - lastSweepAt > 24 * 3600_000L) runCatching { sweep(now) }
    }

    /**
     * 供系统提示词注入的索引（Level-0）：
     * 只列活跃技能（排除已归档与长期闲置的自进化技能），按最近活动排序取前 15 条，
     * 描述截断 60 字符——渐进披露。
     */
    fun promptIndex(): String {
        val active = list().filter { !it.staleForPrompt() && !it.needsRevision && it.validated }
        if (active.isEmpty()) return ""
        // C 披露排序：常用（useCount 降序）> 常青（无活跃度数据时按最近活动）；
        // 疗效差的（failStreak>0 但未到 needsRevision 线）沉底。
        val ranked = active.sortedWith(
            compareByDescending<SkillMeta> { it.successCount > 0 || it.failCount == 0 }
                .thenByDescending { it.useCount }
                .thenByDescending { it.anchor() }
        )
        return buildString {
            appendLine("## 已沉淀技能（用 skill 工具的 view 动作按需加载全文）")
            ranked.take(15).forEach { appendLine("- ${it.name}：${it.description.take(60)}") }
        }.trimEnd()
    }

    // ---------- 外部导入（2.2，兼容 agentskills.io 规范） ----------

    /** 解析外部 SKILL.md 文本的结果（供 UI 预检与导入共用）。 */
    data class ParsedSkillDoc(
        val name: String?,
        val description: String,
        val body: String,
        val error: String?
    )

    /**
     * 解析外部技能文档：frontmatter（--- 包围）提取 name/description；
     * metadata/license 等未知字段忽略不报错；缺 name 由调用方以文件名兜底；
     * 缺 description 取正文首段。无 frontmatter 时全文视为正文。
     */
    fun parseDoc(text: String): ParsedSkillDoc {
        val t = text.trimStart('\uFEFF')
        if (t.isBlank()) return ParsedSkillDoc(null, "", "", "内容为空")
        if (!t.startsWith("---")) {
            // 无 frontmatter：正文导入，name/description 全部兜底
            return ParsedSkillDoc(null, firstParagraphOf(t), t, null)
        }
        val end = t.indexOf("\n---", 3)
        if (end < 0) return ParsedSkillDoc(null, "", "", "frontmatter 未闭合（缺少结束的 ---）")
        val front = t.substring(3, end)
        val body = t.substring(end + 4).trimStart('\n')
        fun line(key: String): String? =
            Regex("^$key:\\s*(.+)$", RegexOption.MULTILINE).find(front)?.groupValues?.get(1)?.trim()
        val name = line("name")?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()
        val desc = cleanDesc(line("description")) ?: firstParagraphOf(body)
        return ParsedSkillDoc(name, desc, body, null)
    }

    sealed class ImportOutcome {
        /** 导入成功，name 为最终目录名。 */
        data class Done(val name: String) : ImportOutcome()
        /** 名字与现有技能冲突（仅 overwrite=false 时出现，由调用方决定覆盖或改名）。 */
        data class Conflict(val name: String) : ImportOutcome()
        data class Failed(val message: String) : ImportOutcome()
    }

    /**
     * 导入单个技能文档。名字优先级：nameOverride（冲突改名用）> frontmatter name > suggestedName > "imported"；
     * 与既有技能重名时返回 Conflict（由调用方决定覆盖或改名后重试）。
     * 外部技能正文上限放宽到 20000 字符（社区技能普遍比自沉淀的长）。
     */
    @Synchronized
    fun importDoc(
        text: String,
        importSource: String,
        suggestedName: String? = null,
        overwrite: Boolean = false,
        nameOverride: String? = null
    ): ImportOutcome {
        val doc = parseDoc(text)
        if (doc.error != null) return ImportOutcome.Failed(doc.error)
        val base = sanitizeName(nameOverride ?: doc.name ?: suggestedName ?: "imported")
        if (base.isBlank()) return ImportOutcome.Failed("技能名无效")
        if (exists(base) && !overwrite) return ImportOutcome.Conflict(base)
        // 内容扫描：命中不阻断导入，记入 reviewNotes 供人审（候选态本就不进索引）
        val hits = SkillGuard.scan(doc.description + "\n" + doc.body)
        save(base, doc.description, doc.body, source = importSource, importedAt = System.currentTimeMillis(),
            bodyLimit = 20_000, reviewNotes = hits.joinToString("、").ifBlank { null })
        return ImportOutcome.Done(base)
    }
}
