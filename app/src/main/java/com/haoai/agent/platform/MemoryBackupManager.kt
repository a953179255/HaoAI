package com.haoai.agent.platform

import android.net.Uri
import com.haoai.agent.data.AppContainer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 记忆数据安全层（痛点：所有记忆在应用私有目录，换机/重装/清数据即全丢）：
 * - exportZip：一键导出 zip 到用户选择的位置（SAF），包含长期记忆真源 MEMORY.md、每日日志、
 *   固化报告、工作区镜像（USER.md/DREAMS.md）与记忆设置快照
 * - autoBackup：每次固化后自动把 MEMORY.md 快照到内部 backups/ 目录，保留最近 5 份
 *   （与工作区故障域隔离：模型/用户改坏真源时仍有干净副本可回滚）；
 *   纯本地、不上云，与用户"记忆不出设备"的偏好一致
 */
object MemoryBackupManager {

    private const val KEEP_BACKUPS = 5

    /** 导出记忆全部数据为 zip，返回写入的文件条数。 */
    fun exportZip(c: AppContainer, uri: Uri): Result<Int> = runCatching {
        val ctx = c.appContext
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                var n = 0
                // 长期记忆真源（工作区 MEMORY.md，markdown 即数据）
                val memFile = c.memoryBank.storageFile()
                if (memFile.exists()) {
                    zip.putEntry("MEMORY.md", memFile.readBytes()); n++
                    val bak = File(memFile.parentFile, memFile.name + ".bak")
                    if (bak.exists()) { zip.putEntry("MEMORY.md.bak", bak.readBytes()); n++ }
                }
                // 记忆设置快照（不含 API 密钥等无关敏感项）
                val st = c.settingsFlow.value
                val snapshot = buildString {
                    appendLine("# HaoAI 记忆设置快照")
                    appendLine("memoryEnabled=${st.memoryEnabled}")
                    appendLine("autoLearn=${st.autoLearn}")
                    appendLine("deepDream=${st.deepDream}")
                    appendLine("dreamProviderId=${st.dreamProviderId}")
                    appendLine("dreamIdleMinutes=${st.dreamIdleMinutes}")
                    appendLine("lastConsolidationReport=${st.lastConsolidationReport}")
                }
                zip.putEntry("memory-settings.txt", snapshot.toByteArray()); n++
                // 每日日志（workspace memory/ 或内部回退目录）
                val journalDir = c.journal.currentStorageDir()
                journalDir.listFiles { f -> f.name.endsWith(".md") }
                    ?.forEach { f -> zip.putEntry("journal/${f.name}", f.readBytes()); n++ }
                // 工作区镜像与固化报告（默认工作区下才存在）
                WorkspaceDocs.workspaceRoot(c)?.let { root ->
                    listOf("MEMORY.md", "USER.md", "DREAMS.md").forEach { name ->
                        val f = root.resolve(name)
                        if (f.exists()) { zip.putEntry("workspace/$name", f.readBytes()); n++ }
                    }
                    root.resolve("dreaming").listFiles { f -> f.name.endsWith(".md") }
                        ?.forEach { f -> zip.putEntry("workspace/dreaming/${f.name}", f.readBytes()); n++ }
                }
                zip.finish()
                n
            }
        } ?: error("无法打开导出目标")
    }

    /** 固化后自动备份记忆真源 MEMORY.md，保留最近 KEEP_BACKUPS 份；失败静默（备份不应干扰主流程）。 */
    fun autoBackup(c: AppContainer) {
        runCatching {
            val src = c.memoryBank.storageFile()
            if (!src.exists()) return
            val dir = File(c.appFilesDir, "backups").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.CHINA).format(Date())
            File(dir, "memories-$stamp.md").let { tmp ->
                src.copyTo(tmp, overwrite = true)
            }
            // 按时间倒序保留最新 5 份
            dir.listFiles { f -> f.name.startsWith("memories-") && f.name.endsWith(".md") }
                ?.sortedByDescending { it.name }
                ?.drop(KEEP_BACKUPS)
                ?.forEach { it.delete() }
            c.updateSettings { it.copy(lastMemoryBackupAt = System.currentTimeMillis()) }
        }
    }

    private fun ZipOutputStream.putEntry(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }
}
