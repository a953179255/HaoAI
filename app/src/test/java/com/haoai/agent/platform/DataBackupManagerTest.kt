package com.haoai.agent.platform

import com.haoai.agent.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 备份包核心约定单测（不依赖 AppContainer）：manifest 的校验和必须对得上真实字节，
 * 默认导出必须把两份配置真源里的密钥全部剔掉。这两条错了，恢复侧就是拿一个坏包救命。
 */
class DataBackupManagerTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private fun zipOf(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                out[e.name] = zin.readBytes()
                e = zin.nextEntry
            }
        }
        return out
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun pack(payload: List<DataBackupManager.Payload>): LinkedHashMap<String, ByteArray> {
        val out = ByteArrayOutputStream()
        DataBackupManager.packEntries(out, payload) { items ->
            DataBackupManager.manifestJson(
                packageName = "com.haoai.agent",
                appVersion = "0.18.4",
                scopes = setOf(BackupScope.SESSIONS, BackupScope.MEMORY),
                includeKeys = false,
                items = items,
                createdAt = 1_700_000_000_000L
            )
        }
        return zipOf(out.toByteArray())
    }

    @Test
    fun manifestChecksumsMatchRealEntryBytes() {
        val f = File.createTempFile("session", ".json").apply {
            writeText("{\"id\":\"a\",\"messages\":[1,2,3]}")
            deleteOnExit()
        }
        val zip = pack(
            listOf(
                DataBackupManager.Payload.OfFile("payload/sessions/a.json", f),
                DataBackupManager.Payload.OfBytes("payload/memory/MEMORY.md", "# 记忆\n中文与 \$ 混排".toByteArray())
            )
        )
        assertEquals(
            listOf("payload/sessions/a.json", "payload/memory/MEMORY.md", "manifest.json"),
            zip.keys.toList()
        )
        val m = DataBackupManager.readManifest(zip.getValue("manifest.json").decodeToString())!!
        assertEquals(DataBackupManager.FORMAT_VERSION, m.formatVersion)
        assertEquals("com.haoai.agent", m.packageName)
        assertEquals(listOf("SESSIONS", "MEMORY"), m.scopes)
        assertFalse(m.keysIncluded)
        // manifest 不列自己：恢复侧遍历 items 校验时不该撞上一条无法自证的记录
        assertEquals(2, m.items.size)
        assertTrue(m.items.none { it.path == DataBackupManager.MANIFEST_NAME })
        m.items.forEach { item ->
            val bytes = zip[item.path] ?: error("包内缺 ${item.path}")
            assertEquals("sha256 对不上：${item.path}", sha256(bytes), item.sha256)
            assertEquals(item.path, bytes.size.toLong(), item.size)
        }
    }

    @Test
    fun manifestSurvivesJsonRoundTrip() {
        val items = listOf(DataBackupManager.Item("payload/workspace.json", 42L, "ab".repeat(32)))
        val text = DataBackupManager.manifestJson(
            "com.haoai.agent", "0.18.4", setOf(BackupScope.SETTINGS), true, items, 123L
        )
        val m = DataBackupManager.readManifest(text)!!
        assertEquals(items, m.items)
        assertTrue(m.keysIncluded)
        assertEquals(123L, m.createdAt)
    }

    /** 真源里出现过的三种密钥形状：settings.json 的密文、配置桥的明文、备用 Key 池数组。 */
    private val settingsWithKeys = """
        {"version":3,"activeProviderId":"p1","providers":[
          {"id":"p1","name":"商汤","apiKey":"sk-plain-1","apiKeyPool":["sk-a","sk-b"]},
          {"id":"p2","name":"智谱","apiKeyCipher":"BASE64CIPHER","apiKeyPoolCiphers":["C1","C2"],
           "nested":{"apiKey":"deep-should-go"}}
        ],"searchApiKeyCiphers":{"zhipu":"SEARCHCIPHER","bocha":"BOCHACIPHER"}}
    """.trimIndent()

    @Test
    fun keysAreStrippedByDefault() {
        val out = DataBackupManager.transformSecrets(settingsWithKeys, emptyMap())
        listOf(
            "apiKey", "apiKeyPool", "apiKeyCipher", "apiKeyPoolCiphers",
            "sk-plain-1", "BASE64CIPHER", "deep-should-go",
            // 搜索服务的 key 同一条规矩：默认不出包
            "searchApiKeyCiphers", "SEARCHCIPHER", "BOCHACIPHER"
        ).forEach { assertFalse("脱敏后仍含 $it", out.contains(it)) }
        // 非密钥字段一个都不能丢
        assertTrue(out.contains("\"activeProviderId\":\"p1\""))
        assertTrue(out.contains("\"name\":\"智谱\""))
    }

    @Test
    fun explicitIncludeWritesPlaintextAndDropsCipher() {
        val out = DataBackupManager.transformSecrets(
            settingsWithKeys,
            mapOf(
                "p1" to listOf("sk-plain-1", "sk-a", "sk-b"),
                "p2" to listOf("decrypted-2"),
                "search:zhipu" to listOf("sk-zhipu-plain")
            )
        )
        assertTrue(out.contains("\"apiKey\":\"sk-plain-1\""))
        assertTrue(out.contains("\"apiKeyPool\":[\"sk-a\",\"sk-b\"]"))
        assertTrue(out.contains("\"apiKey\":\"decrypted-2\""))
        assertFalse(out.contains("apiKeyCipher"))
        assertFalse(out.contains("BASE64CIPHER"))
        assertFalse(out.contains("deep-should-go"))
        // 勾选"含 Key"时搜索 key 也得真的进包（写成 backend→明文 的独立字段，密文字段照旧移除）
        assertTrue(out.contains("\"searchApiKeys\":{\"zhipu\":\"sk-zhipu-plain\"}"))
        assertFalse(out.contains("SEARCHCIPHER"))
        // 解不出来的后端（key 池里没有）不写占位值
        assertFalse(out.contains("BOCHACIPHER"))
    }

    @Test
    fun unreadableConfigIsNotSilentlyDropped() {
        val broken = "{\"providers\":[ 这不是合法 JSON"
        assertEquals(broken, DataBackupManager.transformSecrets(broken, emptyMap()))
    }

    @Test
    fun backupDefaultsAreAllScopesNoKeysSnapshotsOn() {
        val d = AppSettings()
        assertTrue(d.backupScopes.isEmpty())     // 空 = 全部五个域
        assertFalse(d.backupIncludeKeys)
        assertTrue(d.backupSnapshots)
        assertEquals(0L, d.lastDataExportAt)
        // 搜索服务出厂即"不配也能用"：默认必须是内置免 key，且老配置文件（无这几个字段）
        // 靠这些默认值就能解析出来
        assertEquals("builtin", d.searchBackend)
        assertEquals(5, d.searchCount)
        assertTrue(d.searchFallback)
        assertTrue(d.searchApiKeyCiphers.isEmpty())
        assertTrue(d.searchOptions.isEmpty())
    }

    @Test
    fun searchCountCapMatchesTheToolSideCap() {
        // 设置页滑杆区间 3..8 必须与 WebSearchTool 的 coerceIn(1, 8) 对上：
        // 上限不一致时用户以为能设 10 条，实际每次还是被裁到 8
        assertTrue(AppSettings().searchCount in 3..8)
    }

    @Test
    fun pruneKeepsEightNewestAcrossReasons() {
        val dir = tmp.newFolder("backups")
        // compact 与 purge 交错、时间戳递增。旧命名（reason 在前）会让这条测试失败：
        // 字典序变成按字母排，"pre-compact-…" 永远排在 "pre-purge-…" 之前。
        val names = (0 until 10).map { i ->
            "pre-202609${10 + i}-01000$i-${if (i % 2 == 0) "compact" else "purge"}-sid$i.json"
        }
        names.forEach { File(dir, it).writeText("{}") }
        DataBackupManager.pruneSnapshots(dir)
        val left = dir.list()!!.sorted()
        assertEquals(names.drop(2), left)
    }

    @Test
    fun foreignAndMalformedFilesInBackupsSurvivePrune() {
        val dir = tmp.newFolder("backups")
        val mem = File(dir, "memories-2026-09-20-1010.md").apply { writeText("记忆快照") }
        val junk = File(dir, "pre-junk.json").apply { writeText("{}") }
        repeat(9) { i -> File(dir, "pre-2026091$i-010000-compact-s$i.json").writeText("{}") }
        DataBackupManager.pruneSnapshots(dir)
        assertTrue(mem.exists())
        assertTrue(junk.exists())
        assertEquals(8, dir.listFiles { f -> f.name.startsWith("pre-2026091") }!!.size)
    }
}
