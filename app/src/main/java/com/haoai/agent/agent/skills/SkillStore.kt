package com.haoai.agent.agent.skills

import com.haoai.agent.data.HaoJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 技能库（上游 风格精简版）：把成功经验沉淀为可复用的 SKILL.md。
 * 目录：filesDir/skills/<name>/SKILL.md，frontmatter 存 name/description，正文为步骤。
 * 系统提示词只注入索引（名字+描述），正文按需加载省上下文。
 */
@Serializable
data class SkillMeta(val name: String, val description: String, val updatedAt: Long = 0)

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

    @Synchronized
    fun save(name: String, description: String, body: String): File {
        val safe = name.trim().replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fff]"), "-").take(40)
            .ifBlank { "skill" }
        // description 进入 frontmatter 单行区域，换行会破坏头部结构
        val descOneLine = description.trim().replace(Regex("[\\r\\n]+"), " ").take(120)
        val f = File(dir, safe).apply { mkdirs() }.let { File(it, "SKILL.md") }
        val front = "---\nname: $safe\ndescription: $descOneLine\n---\n"
        com.haoai.agent.data.HaoJson.writeAtomic(f, front + body.trim().take(8000))
        cachedList = null
        return f
    }

    @Synchronized
    fun list(): List<SkillMeta> {
        cachedList?.let { return it }
        val result = dir.listFiles { f -> f.isDirectory }?.mapNotNull { d ->
            val f = File(d, "SKILL.md")
            if (!f.exists()) return@mapNotNull null
            val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            val name = d.name
            val desc = Regex("^description:\\s*(.+)$", RegexOption.MULTILINE)
                .find(text)?.groupValues?.get(1)?.trim() ?: ""
            SkillMeta(name, desc, f.lastModified())
        }?.sortedByDescending { it.updatedAt } ?: emptyList()
        cachedList = result
        return result
    }

    @Synchronized
    fun view(name: String): String? {
        val f = resolve(name) ?: return null
        if (!f.isDirectory) return null
        val sf = File(f, "SKILL.md")
        if (!sf.exists()) return null
        return runCatching { sf.readText() }.getOrNull()
    }

    @Synchronized
    fun delete(name: String): Boolean {
        val d = resolve(name) ?: return false
        if (!d.isDirectory) return false
        val ok = d.deleteRecursively()
        if (ok) cachedList = null
        return ok
    }

    /** 供系统提示词注入的索引（Level-0，省 token）。 */
    fun promptIndex(): String {
        val all = list()
        if (all.isEmpty()) return ""
        return buildString {
            appendLine("## 已沉淀技能（用 skill 工具的 view 动作按需加载全文）")
            all.take(20).forEach { appendLine("- ${it.name}：${it.description.take(80)}") }
        }.trimEnd()
    }
}
