package com.haoai.agent.agent.engine

import com.haoai.agent.agent.tools.TextCap
import com.haoai.agent.platform.FileBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S4 工具结果溢出的回归锁。核心是第一条：**关掉开关时落库内容必须与改造前逐字相同** ——
 * "新行为可完全回退"不能只是口号，要有一条等式钉住它。
 */
class ToolOutputSpillTest {

    private fun longText(n: Int, marker: String = "x"): String =
        buildString(n) { repeat(n / marker.length) { append(marker) } }

    @Test
    fun `no path preview is byte-identical to the old TextCap middle truncation`() {
        val cap = 16_000
        listOf(16_001, 20_000, 100_000).forEach { len ->
            val content = longText(len)
            assertEquals(
                "长度 $len 时溢出摘要与 TextCap.middle 不一致，回退口径被破坏",
                TextCap.middle(content, cap),
                spillPreview(content, cap, null)
            )
        }
    }

    @Test
    fun `short content passes through untouched`() {
        val content = "exit=0\nall good"
        assertEquals(content, spillPreview(content, 16_000, null))
        assertEquals(content, spillPreview(content, 16_000, ".haoai-output/1-call.txt"))
    }

    @Test
    fun `with path the preview tells the model where and how to read it back`() {
        val rel = ".haoai-output/1700000000000-call9.txt"
        val out = spillPreview(longText(20_000), 16_000, rel)
        assertTrue("摘要里必须带完整路径", out.contains(rel))
        assertTrue("必须给出可执行的 read 调用", out.contains("read(path=\"$rel\""))
        assertTrue("必须说明 offset/limit 分段读", out.contains("offset=") && out.contains("limit="))
        assertTrue("必须报全文规模，模型才知道差多少", out.contains("共 20000 字符"))
        assertTrue("必须留省略量", out.contains("中间省略"))
    }

    @Test
    fun `truncation never leaves a lone surrogate, even with the pointer appended`() {
        // 把 emoji 代理对正好卡在切分点上（head = 65% * cap）
        val cap = 1_001
        val content = "a".repeat(649) + "😀🎉".repeat(200) + "b".repeat(cap)
        listOf(null, ".haoai-output/x.txt").forEach { rel ->
            val chars = spillPreview(content, cap, rel).toCharArray()
            for (i in chars.indices) {
                if (chars[i].isHighSurrogate()) {
                    assertTrue("出现孤儿高代理", i + 1 < chars.size && chars[i + 1].isLowSurrogate())
                } else if (chars[i].isLowSurrogate()) {
                    assertTrue("出现孤儿低代理", i > 0 && chars[i - 1].isHighSurrogate())
                }
            }
        }
    }

    // ── capForStore：开关、无工作区、超硬顶、自清理 ─────────────────────────────

    private open class FakeBackend : FileBackend {
        override val displayName = "fake"
        val files = LinkedHashMap<String, String>()
        override suspend fun readText(rel: String, maxBytes: Int): String = files[rel] ?: ""
        override suspend fun writeText(rel: String, content: String) { files[rel] = content }
        override suspend fun delete(rel: String) { files.remove(rel) }
        override suspend fun rename(fromRel: String, newName: String) {}
        override suspend fun listDir(rel: String): List<String> =
            files.keys.filter { it.startsWith("$rel/") }.toList()
        override suspend fun walk(maxEntries: Int): List<com.haoai.agent.platform.FileEntry> = emptyList()
        override fun shellWorkdir(): File? = null
    }

    @Test
    fun `flag off keeps the old truncate-only behaviour`() = runBlocking {
        val be = FakeBackend()
        val out = capForStore(longText(20_000), 16_000, be, "call1", spillOn = false)
        assertEquals(TextCap.middle(longText(20_000), 16_000), out)
        assertTrue("开关关着就不该往用户工作区写文件", be.files.isEmpty())
    }

    @Test
    fun `flag on spills the full text and points at it`() = runBlocking {
        val be = FakeBackend()
        val full = longText(30_000, "ab")
        val out = capForStore(full, 16_000, be, "call7", spillOn = true)
        assertEquals("溢出文件必须存全文，一字不裁", full, be.files.values.single())
        val rel = be.files.keys.single()
        assertTrue("路径必须落在 .haoai-output/ 下", rel.startsWith("$TOOL_OUTPUT_DIR/"))
        assertTrue("文件名要带 callId 便于对应", rel.endsWith("-call7.txt"))
        assertTrue("摘要必须指向那个文件", out.contains(rel))
    }

    @Test
    fun `no backend falls back without throwing`() = runBlocking {
        val out = capForStore(longText(20_000), 16_000, null, "call1", spillOn = true)
        assertEquals(TextCap.middle(longText(20_000), 16_000), out)
        assertFalse(out.contains(".haoai-output"))
    }

    @Test
    fun `oversized results are not spilled`() = runBlocking {
        val be = FakeBackend()
        val huge = longText(SPILL_MAX_CHARS + 5, "ab")
        val out = capForStore(huge, 16_000, be, "call1", spillOn = true)
        assertTrue("超硬顶就不该写盘", be.files.isEmpty())
        assertFalse(out.contains(".haoai-output"))
    }

    @Test
    fun `spill dir self-prunes to the keep limit`() = runBlocking {
        val be = FakeBackend()
        repeat(SPILL_KEEP_FILES + 5) { i ->
            be.writeText("$TOOL_OUTPUT_DIR/${1_000_000_000L + i}-old$i.txt", "stale")
        }
        val before = be.files.size
        capForStore(longText(20_000), 16_000, be, "new", spillOn = true)
        val after = be.files.keys.filter { it.startsWith("$TOOL_OUTPUT_DIR/") }.size
        assertEquals("清理前应当超预算", SPILL_KEEP_FILES + 5, before)
        assertTrue("清理后必须回到预算内，实际 $after", after <= SPILL_KEEP_FILES)
        // 删的必须是最旧的：老名字里编号最小的那个不该还在
        assertFalse(be.files.containsKey("$TOOL_OUTPUT_DIR/1000000000-old0.txt"))
        // 新写的那条不能被删掉
        assertTrue(be.files.keys.any { it.endsWith("-new.txt") })
    }

    @Test
    fun `write failure degrades to plain truncation instead of losing the result`() = runBlocking {
        val boom = object : FakeBackend() {
            override suspend fun writeText(rel: String, content: String) {
                throw java.io.IOException("disk full")
            }
        }
        val out = capForStore(longText(20_000), 16_000, boom, "call1", spillOn = true)
        assertEquals(TextCap.middle(longText(20_000), 16_000), out)
        assertNull(boom.files["whatever"])
    }
}
