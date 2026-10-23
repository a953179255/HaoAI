package com.haoai.agent.platform

import java.io.File

/**
 * 脑分家（2026-10-24 v2 架构解耦，一次性）：
 * 旧版 Agent 的"脑"（MEMORY.md/USER.md/技能/日志/待办…）混在工作区根，档位一切换
 * 就被整树复制拖着跑（用户真机 7 万文件/975MB 的迁移事故根源）。v2 起脑固定内部
 * 私有 brainDir 永不移动，工作区只放材料——本对象负责把散在各旧位置的脑条目
 * 搬进 brainDir，之后引擎只认 brainDir。
 *
 * 安全口径（四重）：
 * 1. 移动 = 复制 → 字节数校验 → 才删源；校验不过保留源、删掉半成品目标
 * 2. 目标已存在 = 目标赢（上次分家成果优先），源原地保留（降级成材料，绝不误删）
 * 3. marker 落 brainDir；有任一条目失败就不写 marker，下次启动自动重试（幂等）
 * 4. 只搬白名单条目；材料目录（repos/、docs/ 等）一概不碰
 *
 * 来源顺序：当前工作区根优先（引擎一直在用的那份），内部 filesDir 根次之
 * （SAF 档 legacy 回退位与 todos/ 旧位）。
 */
internal object BrainMigrator {

    private const val MARKER = ".brain-split-v1"

    /** 脑文件（工作区根级 md）。 */
    val BRAIN_FILES = listOf(
        "MEMORY.md", "MEMORY.md.bak", "USER.md", "IDENTITY.md", "SOUL.md",
        "AGENTS.md", "TOOLS.md", "HEARTBEAT.md", "DREAMS.md"
    )

    /** 脑目录。 */
    val BRAIN_DIRS = listOf("memory", "skills", "todos", "dreaming")

    data class Result(val moved: Int, val skipped: Int, val failed: Int) {
        val ok: Boolean get() = failed == 0
    }

    /** 已分家过（marker 在）就直接返回，不做任何 IO。 */
    fun isSplitDone(brainRoot: File): Boolean = File(brainRoot, MARKER).exists()

    /**
     * 执行一次性分家。sources 按优先级排列（靠前的是权威源）。
     * 返回搬运统计；failed>0 时不写 marker（下次重试）。
     */
    fun splitIfNeeded(brainRoot: File, vararg sources: File?): Result {
        if (isSplitDone(brainRoot)) return Result(0, 0, 0)
        brainRoot.mkdirs()
        var moved = 0
        var skipped = 0
        var failed = 0
        for (src in sources) {
            if (src == null || !src.isDirectory) continue
            // 防自己搬自己（脑根恰是来源根时，canonical 相等）
            if (runCatching { src.canonicalFile == brainRoot.canonicalFile }.getOrDefault(false)) continue
            for (name in BRAIN_FILES + BRAIN_DIRS) {
                val s = File(src, name)
                if (!s.exists()) continue
                val d = File(brainRoot, name)
                if (d.exists()) {
                    skipped++ // 目标赢：源里的旧副本降级成材料，不删不覆盖
                    continue
                }
                if (moveEntry(s, d)) moved++ else failed++
            }
        }
        if (failed == 0) {
            runCatching { File(brainRoot, MARKER).writeText("v1 ${System.currentTimeMillis()}") }
        }
        return Result(moved, skipped, failed)
    }

    /**
     * 复制→校验→删源。目录递归；任一层校验失败都保留源、清理半成品目标。
     */
    private fun moveEntry(src: File, dst: File): Boolean {
        if (src.isDirectory) {
            dst.mkdirs()
            var allOk = true
            for (child in src.listFiles().orEmpty()) {
                if (!moveEntry(child, File(dst, child.name))) allOk = false
            }
            if (!allOk) return false
            // 子项全部落地才删源；删不干净（残留锁文件）也视为失败保留源
            val deleted = src.deleteRecursively()
            if (!deleted && src.exists()) return false
            return true
        }
        return runCatching {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = false)
            if (dst.length() == src.length()) {
                src.delete()
            } else {
                dst.delete() // 半成品清掉，源保留，下次重试
                false
            }
        }.getOrDefault(false)
    }
}
