package com.haoai.agent.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份兜底脱敏的正则守卫（M1 修复时新增）。
 *
 * ## 这条路径为什么要有测试
 *
 * `DataBackupManager.regexStripSecrets` 只在**配置 JSON 解析失败**时才走 ——
 * 也就是说，正常用户永远走不到，但一旦走到就是"明文密钥直接进备份包"。
 * 两条路都很难靠人工发现：
 * ① 解析失败本身是低频事件，触发时用户只会看到"备份出来的包恢复不了"；
 * ② 正则写错一个字符，测试很难覆盖 —— 它对**没被测到的形态**天然沉默。
 *
 * 所以这里用"喂真实配置形态 + 数残留密钥"的方式锁死：
 * 判据不是"解析成功"，也不是"输出长得对"，而是**产物里还有没有明文密钥**。
 */
class BackupSecretStripTest {

    private val KEYLIKE = Regex("""(sk-|tv-|enc:)[A-Za-z0-9_\-]{4,}""")

    private fun assertNoLeak(name: String, out: String) {
        val leaked = KEYLIKE.findAll(out).map { it.value }.toList()
        assertTrue("$name：产物里仍有疑似凭据 $leaked\n实际输出：$out", leaked.isEmpty())
    }

    /**
     * 反射拿到 private 的 regexStripSecrets（测的是真源，不是抄副本）。
     *
     * 注意接收者：Kotlin `object` 的成员编译成**实例方法**（挂在 INSTANCE 上），
     * 不是静态方法 —— 用 `invoke(null, ...)` 会 NPE，必须传 INSTANCE。
     */
    private fun strip(text: String): String {
        val m = DataBackupManager::class.java
            .getDeclaredMethod("regexStripSecrets", String::class.java)
        m.isAccessible = true
        val instance = DataBackupManager::class.java.getField("INSTANCE").get(null)
        return m.invoke(instance, text) as String
    }

    // ── M1 的真泄露点：MCP header 里的明文凭据 ──────────────────────

    @Test
    fun `plain mcp header credentials are masked`() {
        // 用户自己填的 Authorization，不带 enc: 前缀 —— 原实现完全漏掉这条。
        val json = """{"mcpServers":[{"name":"n","headers":{"Authorization":"Bearer sk-AUTHLEAK","X-Api-Key":"sk-XLEAK"}}]}"""
        val out = strip(json)
        assertNoLeak("MCP 明文 header", out)
    }

    @Test
    fun `header keys survive so user can still identify the service`() {
        // 整块打成 "***" 会让用户 restore 后不知道该填什么 —— 键名必须留。
        val out = strip("""{"headers":{"Authorization":"Bearer sk-A","X-Api-Key":"sk-B"}}""")
        assertTrue("键名 Authorization 必须保留：$out", out.contains("\"Authorization\""))
        assertTrue("键名 X-Api-Key 必须保留：$out", out.contains("\"X-Api-Key\""))
    }

    // ── 已有三条规则不能被新规则误伤 ─────────────────────────────────

    @Test
    fun `provider api keys are still masked`() {
        val out = strip("""{"providers":[{"id":"openai","apiKey":"sk-REAL","baseUrl":"https://api.openai.com"}]}""")
        assertNoLeak("provider apiKey", out)
        assertTrue("baseUrl 是非敏感字段，不能被误删：$out", out.contains("baseUrl"))
        assertTrue("providers 结构必须保持可解析：$out", out.contains("\"providers\""))
    }

    @Test
    fun `enc ciphertext headers are still masked`() {
        val out = strip("""{"headers":{"X-Token":"enc:AbCdEf0123456789"}}""")
        assertFalse("enc: 密文不能出现在产物里：$out", out.contains("enc:AbCdEf0123456789"))
    }

    @Test
    fun `search api key map is still masked`() {
        val out = strip("""{"searchApiKeys":{"tavily":"tv-KEY123"}}""")
        assertNoLeak("搜索后端 key", out)
    }

    // ── 边界形态 ────────────────────────────────────────────────────

    @Test
    fun `empty headers object is untouched`() {
        val out = strip("""{"headers":{}}""")
        assertEquals("""{"headers":{}}""", out)
    }

    @Test
    fun `header value containing comma is fully masked`() {
        // 值里的逗号不能把一个凭据切成两半、只打码一半。
        val out = strip("""{"headers":{"X-List":"a,sk-SECRET,b"}}""")
        assertNoLeak("含逗号的 header 值", out)
    }

    @Test
    fun `pc link token ciphertext is masked`() {
        val out = strip("""{"token":"enc:v1:Qq7x9KLMNP","name":"host"}""")
        assertFalse("PC 配对 token 不能出包：$out", out.contains("enc:v1:Qq7x9KLMNP"))
    }
}