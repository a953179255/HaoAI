package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import com.sun.net.httpserver.HttpServer

/**
 * Codebase 语义索引（#6）的回归。
 *
 * 判据盯四件事：
 * 1. 纯函数（分块/选文件/编解码/余弦/端点形状解析）**不碰网络也钉死**；
 * 2. **词不相同但意思相近能搜到**（这是整个功能存在的理由——假端点按"含配置"给向量）；
 * 3. **增量**：文件没动不重新向量化、改了才嵌（用假端点的请求计数证明）；
 * 4. **不配端点时 grep 逐字不变、端点挂了也不吞文本结果**（语义是增强不是依赖）。
 */
class SemanticIndexTest {

    // ---- 纯函数 ----

    @Test
    fun `分块按行攒，超长单行硬切，块数与长度有界`() {
        val short = SemanticIndex.chunkText("第一行\n第二行\n")
        assertEquals(1, short.size)

        val long = (1..400).joinToString("\n") { "第 $it 行，这行有点长，用来把块撑过目标长度" }
        val chunks = SemanticIndex.chunkText(long)
        assertTrue("400 行该切成多块：" + chunks.size, chunks.size >= 3)
        assertTrue(chunks.all { it.isNotBlank() })
        assertTrue(chunks.joinToString("").contains("第 400 行"))

        val oneLine = "x".repeat(2500)
        val cut = SemanticIndex.chunkText(oneLine)
        assertTrue("单行 2500 字符必须切开：" + cut.size, cut.size >= 3)
    }

    @Test
    fun `选文件：按扩展名与目录黑名单，顺序确定`() {
        val ws = Files.createTempDirectory("haoai-sem-f").toFile().apply { mkdirs() }
        File(ws, "a.kt").writeText("fun a() = 1\n")
        File(ws, "b.bin").writeText("whatever")
        File(ws, "build").mkdirs()
        File(ws, "build/c.kt").writeText("should be skipped")
        File(ws, "sub").mkdirs()
        File(ws, "sub/d.md").writeText("# hi\n")
        val got = SemanticIndex.filesToIndex(ws).map { it.relativeTo(ws).path.replace('\\', '/') }
        assertEquals(listOf("a.kt", "sub/d.md"), got)   // 排序确定 + build/ 被跳过 + 非文本扩展名不要
    }

    @Test
    fun `向量编解码 roundtrip`() {
        val vecs = listOf(listOf(1.5f, -2.25f, 3f), listOf(0f, 0f, 7.5f))
        val b64 = SemanticIndex.encodeVecs(vecs)
        val back = SemanticIndex.decodeVecs(b64, 3, 2)
        assertEquals(vecs, back)
        assertNull("维度对不上必须拒绝（防缓存错位）", SemanticIndex.decodeVecs(b64, 4, 2))
        assertNull(SemanticIndex.decodeVecs("!!!not-base64!!!", 3, 2))
    }

    @Test
    fun `端点返回的两种形状都认，数量对不上拒绝`() {
        // 本机 llama-server 实测形状：裸数组 + embedding 多一层 batch 维
        val legacy = """[{"index":0,"embedding":[[0.1,0.2]]},{"index":1,"embedding":[[0.3,0.4]]}]"""
        val v = Embed.parse(legacy, 2)
        assertEquals(listOf(0.1, 0.2), v!![0])
        assertEquals(listOf(0.3, 0.4), v[1])

        val openai = """{"data":[{"embedding":[0.5,0.6]},{"embedding":[0.7,0.8]}]}"""
        val v2 = Embed.parse(openai, 2)
        assertEquals(listOf(0.5, 0.6), v2!![0])

        assertNull("数量对不上=端点吞了输入", Embed.parse(legacy, 3))
        assertNull("形状不认识要拒绝而不是猜", Embed.parse("""{"foo":1}""", 1))
    }

    @Test
    fun `余弦：同向 1、正交 0`() {
        assertEquals(1.0, Embed.cosine(listOf(1.0, 0.0), listOf(1.0, 0.0)), 1e-9)
        assertEquals(0.0, Embed.cosine(listOf(1.0, 0.0), listOf(0.0, 1.0)), 1e-9)
        assertEquals(0.0, Embed.cosine(listOf(0.0, 0.0), listOf(1.0, 1.0)), 1e-9)
    }

    // ---- e2e：本地假端点（2 维，含"配置"给 [1,0] 否则 [0,1]）----

    /** 假端点：记请求次数（供增量断言），按"文本里有没有配置俩字"给向量。 */
    private fun withFakeEmbed(block: (url: String, hits: () -> Int) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = java.util.concurrent.atomic.AtomicInteger(0)
        server.createContext("/embeddings") { ex ->
            hits.incrementAndGet()
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val inputs = Json.parseToJsonElement(body).jsonObject["input"]!!.jsonArray
                .map { it.jsonPrimitive.content }
            val arr = inputs.mapIndexed { i, t ->
                val v = if (t.contains("配置")) "1,0" else "0,1"
                """{"index":$i,"embedding":[[$v]]}"""
            }.joinToString(",")
            val out = "[$arr]".toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/embeddings") { hits.get() }
        } finally {
            server.stop(0)
        }
    }

    private fun tempHome(): String {
        val dir = Files.createTempDirectory("haoai-sem-home").toFile()
        val old = System.getProperty("haoai.home")
        System.setProperty("haoai.home", dir.absolutePath)
        return old ?: ""
    }

    private fun restoreHome(old: String) {
        if (old.isEmpty()) System.clearProperty("haoai.home") else System.setProperty("haoai.home", old)
    }

    @Test
    fun `词不相同意思相近能搜到，且只有改动的文件重新向量化`() {
        val ws = Files.createTempDirectory("haoai-sem-ws").toFile().apply { mkdirs() }
        File(ws, "config.kt").writeText("// 这里负责加载配置并完成初始化\nfun load() {}\n")
        File(ws, "paint.kt").writeText("// 纯绘制，和界面颜色打交道\nfun draw() {}\n")
        val oldHome = tempHome()
        try {
            withFakeEmbed { url, hits ->
                // 1) 首查：建索引（假端点按"含配置"给 [1,0]）→ 查询"配置怎么加载"命中 config.kt
                val r1 = SemanticIndex.query(ws, "配置怎么加载", url)
                assertNull("首查不该有 note：" + r1.note, r1.note)
                assertTrue("该命中 config.kt：" + r1.hits, r1.hits.isNotEmpty())
                assertEquals("config.kt", r1.hits.first().path)
                assertTrue("得分该是 1.0（同向）：" + r1.hits.first().score, r1.hits.first().score > 0.99)
                val afterFirst = hits()

                // 2) 再查一次：文件没动 → 只嵌查询那一句，不重建
                SemanticIndex.query(ws, "配置在哪里", url)
                assertEquals("增量：文件没动只该多 1 次请求", afterFirst + 1, hits())

                // 3) 改一个文件 → 那个文件重嵌（仍只嵌动过的 + 查询）
                File(ws, "paint.kt").writeText("// 改了：这里也碰配置缓存\nfun draw() {}\n")
                val r3 = SemanticIndex.query(ws, "配置怎么加载", url)
                assertTrue(r3.hits.isNotEmpty())
                assertTrue("改过的文件要进索引", r3.hits.any { it.path == "paint.kt" })
                assertTrue("重嵌请求要多于 1（动过的块 + 查询）", hits() >= afterFirst + 2)
            }
        } finally {
            restoreHome(oldHome)
        }
    }

    @Test
    fun `端点挂了给原因，不抛；没配端点说清楚`() {
        val ws = Files.createTempDirectory("haoai-sem-ws2").toFile().apply { mkdirs() }
        File(ws, "a.kt").writeText("fun a() = 1\n")
        val oldHome = tempHome()
        try {
            val off = SemanticIndex.query(ws, "随便问", "")
            assertTrue("没配端点不该有结果", off.hits.isEmpty())
            assertTrue(off.note!!.contains("embedUrl"))

            val dead = SemanticIndex.query(ws, "随便问", "http://127.0.0.1:1/embeddings")
            assertTrue(dead.hits.isEmpty())
            assertTrue("端点挂了要给原因：" + dead.note, dead.note != null)
        } finally {
            restoreHome(oldHome)
        }
    }

    @Test
    fun `grep：没配端点逐字不变，配了端点 0 命中附语义近邻`() {
        val ws = Files.createTempDirectory("haoai-sem-grep").toFile().apply { mkdirs() }
        File(ws, "config.kt").writeText("// 这里负责加载配置并完成初始化\nfun load() {}\n")
        val c = ToolCtx(ws, PcSettings(), "ask", object : Gate {
            override fun approve(title: String, detail: String, kind: String) = true
            override fun ask(question: String, options: List<String>) = ""
        })
        // 没配端点：字面搜不到就是搜不到，输出不许多一个字
        val plain = GrepTool().run(
            Json.parseToJsonElement("""{"pattern":"根本不存在的词组"}""").jsonObject, c
        )
        assertFalse(plain.error)
        assertEquals("(无匹配) 根本不存在的词组", plain.content)

        val oldHome = tempHome()
        try {
            withFakeEmbed { url, _ ->
                val c2 = ToolCtx(ws, PcSettings(embedUrl = url), "ask", object : Gate {
                    override fun approve(title: String, detail: String, kind: String) = true
                    override fun ask(question: String, options: List<String>) = ""
                })
                val sem = GrepTool().run(
                    Json.parseToJsonElement("""{"pattern":"配置怎么加载"}""").jsonObject, c2
                )
                assertFalse(sem.error)
                assertTrue("要有语义段：" + sem.content, sem.content.contains("语义近邻"))
                assertTrue("要指到 config.kt：" + sem.content, sem.content.contains("config.kt"))
                assertTrue("文本结果的那句还要在：" + sem.content, sem.content.contains("(无匹配)"))

                // 字面有命中时不掺和语义（不改变既有输出）
                val lit = GrepTool().run(
                    Json.parseToJsonElement("""{"pattern":"fun load"}""").jsonObject, c2
                )
                assertFalse(lit.error)
                assertTrue(lit.content.contains("config.kt"))
                assertFalse("有字面命中就不该夹语义段：" + lit.content, lit.content.contains("语义近邻"))
            }
        } finally {
            restoreHome(oldHome)
        }
    }
}
