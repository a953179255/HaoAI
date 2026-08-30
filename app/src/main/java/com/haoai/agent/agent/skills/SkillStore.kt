package com.haoai.agent.agent.skills

import com.haoai.agent.data.HaoJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 技能元数据（上游 curator 式生命周期）：
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
    val importedAt: Long = 0
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
 */
object SkillStore {

    @Volatile
    private var baseDir: File? = null

    fun init(appFilesDir: File) {
        baseDir = File(appFilesDir, "skills")
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

    /** 从既有 SKILL.md 解析 frontmatter 元数据（兼容旧格式与 agentskills.io 外部格式：未知字段忽略、缺省回退）。 */
    private fun parseMeta(text: String, name: String, fallbackMtime: Long): SkillMeta {
        fun line(key: String): String? =
            Regex("^$key:\\s*(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
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
            importedAt = line("importedAt")?.toLongOrNull() ?: 0L
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
        bodyLimit: Int = 8000
    ): File {
        val safe = sanitizeName(name)
        val f = skillFile(safe)
        // 覆盖更新时保留历史计数；新建时记录来源
        val old = if (f.exists()) parseMeta(runCatching { f.readText() }.getOrDefault(""), safe, 0) else null
        val now = System.currentTimeMillis()
        val meta = SkillMeta(
            name = safe,
            description = description.trim().replace(Regex("[\\r\\n]+"), " ").take(120),
            updatedAt = now,
            useCount = old?.useCount ?: 0,
            lastUsedAt = old?.lastUsedAt ?: 0L,
            source = if (old != null) old.source else source,
            pinned = old?.pinned ?: false,
            archived = false,
            importedAt = if (old?.importedAt ?: 0L > 0) old!!.importedAt else importedAt
        )
        writeMeta(f, meta, body.trim().take(bodyLimit))
        cachedList = null
        return f
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
     * 描述截断 60 字符——上游 同款渐进披露。
     */
    fun promptIndex(): String {
        val active = list().filter { !it.staleForPrompt() }
        if (active.isEmpty()) return ""
        return buildString {
            appendLine("## 已沉淀技能（用 skill 工具的 view 动作按需加载全文）")
            active.take(15).forEach { appendLine("- ${it.name}：${it.description.take(60)}") }
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
        save(base, doc.description, doc.body, source = importSource, importedAt = System.currentTimeMillis(), bodyLimit = 20_000)
        return ImportOutcome.Done(base)
    }
}
