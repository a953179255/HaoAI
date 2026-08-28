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
    val source: String = "user",
    val pinned: Boolean = false,
    val archived: Boolean = false
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

    /** 从既有 SKILL.md 解析 frontmatter 元数据（兼容旧格式：缺省字段回退默认值）。 */
    private fun parseMeta(text: String, name: String, fallbackMtime: Long): SkillMeta {
        fun line(key: String): String? =
            Regex("^$key:\\s*(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
        val desc = line("description") ?: ""
        return SkillMeta(
            name = name,
            description = desc,
            updatedAt = line("updatedAt")?.toLongOrNull() ?: fallbackMtime,
            useCount = line("useCount")?.toIntOrNull() ?: 0,
            lastUsedAt = line("lastUsedAt")?.toLongOrNull() ?: 0L,
            source = line("source") ?: "user",
            pinned = line("pinned") == "true",
            archived = line("archived") == "true"
        )
    }

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
            appendLine("updatedAt: ${meta.updatedAt}")
            appendLine("---")
        }
        HaoJson.writeAtomic(f, front + body)
    }

    private fun bodyOf(f: File): String {
        val text = runCatching { f.readText() }.getOrDefault("")
        // 无 frontmatter 的旧格式文件：substringAfter 兜底返回 ""，回写会把正文整个清空
        if (!text.startsWith("---")) return text
        return text.removePrefix("---").substringAfter("---", "").trimStart('\n')
    }

    @Synchronized
    fun save(name: String, description: String, body: String, source: String = "user"): File {
        val safe = name.trim().replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fff]"), "-").take(40)
            .ifBlank { "skill" }
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
            archived = false
        )
        writeMeta(f, meta, body.trim().take(8000))
        cachedList = null
        return f
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
}
