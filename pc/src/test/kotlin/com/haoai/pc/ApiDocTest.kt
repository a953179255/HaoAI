package com.haoai.pc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接口文档与路由表不许各走各的。
 *
 * 为什么值得钉一条测试：文档一旦漂了，外部脚本会照着已经不存在的形状调用，
 * 症状出现在别人身上（"你说的那个 API 不好使"），而这边一行代码都不会红。
 * 所以这里**两边都查**：代码里有的必须写在文档上，文档上写的必须真在代码里。
 *
 * 读的是源码里的路由字面量，不是 README 的转述 —— 结论要带出处，出处要能被机器核对。
 */
class ApiDocTest {

    private val doc = File("docs/api.md")
    private val server = File("src/main/kotlin/com/haoai/pc/Server.kt")
    private val lan = File("src/main/kotlin/com/haoai/pc/Lan.kt")
    private val client = File("tools/haoai-client.py")

    /** 代码里的路由：`"/api/x" ->` 与 `path == "/lan/x"` 两种写法。 */
    private fun routesIn(text: String): Set<String> {
        val arrow = Regex("\"(/[A-Za-z0-9._/-]*)\"\\s*->")
        val eq = Regex("path\\s*==\\s*\"(/[A-Za-z0-9._/-]*)\"")
        return (arrow.findAll(text).map { it.groupValues[1] } + eq.findAll(text).map { it.groupValues[1] })
            .filter { it.startsWith("/") }.toSet()
    }

    /**
     * 文档里的路由：反引号里的 `/api/…`、`/lan/…`、`/phone`，以及那几个页面路径。
     *
     * 先剥掉围栏代码块：``` 之间的内容整段会被"反引号包一行"的规则误当成一个 span，
     * 于是 curl 例子也算文档 —— 例子会旧，表不会。要钉的是表。
     * 以 `/` 结尾的一律不算：那是人写的通配（文档里写的是「lan 斜杠星」那种），不是真实路径。
     * 顺带一条 Kotlin 的坑：块注释是**会嵌套**的，注释里出现「斜杠加星号」的任意组合
     * 都会开一层新注释，然后整个文件被吞掉 —— 这条就是那样红过一次。
     */
    private fun documented(): Set<String> {
        val text = doc.readText().replace(Regex("(?s)```.*?```"), " ")
        val out = LinkedHashSet<String>()
        val pages = setOf("/", "/index.html", "/md.js", "/shared.js", "/phone")
        Regex("`([^`]+)`").findAll(text).forEach { m ->
            // 按"不是路径里能出现的字符"切开，再逐个整段判前缀 ——
            // 直接拿正则去抓会吃掉 `bash pc/tools/api-smoke.sh` 里的 "/api-smoke.sh"
            // 和 `HAOAI_HOME/apikey` 里的 "/apikey"（第一次就被这两处判成"文档编了路径"）。
            m.groupValues[1].split(Regex("[^A-Za-z0-9._/-]")).forEach { tok ->
                val ok = tok == "/" || tok in pages ||
                    (tok.startsWith("/api/") || tok.startsWith("/lan/")) && !tok.endsWith("/")
                if (ok) out += tok
            }
        }
        return out
    }

    @Test
    fun `the doc and the sources it describes are both on disk`() {
        // 读不到文件就等于这条测试什么都没做 —— 宁可在这里红，别悄悄绿
        for (f in listOf(doc, server, lan, client)) {
            assertTrue("测试得跑在 pc/ 目录下才读得到 $f（现在是 " + File(".").absolutePath + "）", f.isFile)
        }
    }

    @Test
    fun `every route the server answers is documented`() {
        val code = routesIn(server.readText()) + routesIn(lan.readText())
        val missing = (code - documented()).sorted()
        assertEquals(
            "这些端点存在但没写进 docs/api.md（新加的端点要顺手补文档，否则外部脚本永远不知道它有了）",
            emptyList<String>(), missing
        )
        assertTrue("路由表不该是空的（空 = 抓取正则坏了，这条测试会一直假绿）：" + code.size, code.size > 40)
    }

    @Test
    fun `the doc does not invent a route`() {
        val code = routesIn(server.readText()) + routesIn(lan.readText())
        val invented = (documented() - code).sorted()
        assertEquals(
            "文档里写了代码里没有的路径（要么改名了没同步，要么当初就写错 —— 外部脚本照着调只会拿到 404）",
            emptyList<String>(), invented
        )
    }

    @Test
    fun `the shipped client only calls routes that exist`() {
        val code = routesIn(server.readText()) + routesIn(lan.readText())
        val used = Regex("\"(/[a-z][A-Za-z0-9._/-]*)\"").findAll(client.readText()).map { it.groupValues[1] }
            .filter { it.startsWith("/api/") || it.startsWith("/lan/") }.toSet()
        assertTrue("客户端一个路径都没抓到（说明抓取或客户端本身坏了）：" + used, used.isNotEmpty())
        assertEquals("客户端在调不存在的路径", emptyList<String>(), (used - code).toList().sorted())
    }

    @Test
    fun `the stable surface is a small subset of everything`() {
        // 文档第 1 节是"对外承诺稳定"的那一小撮。它必须真的存在、真的少 ——
        // 全都在承诺稳定，等于没承诺：以后每次改界面都会撞上自己的文档。
        val text = doc.readText()
        val section = text.substringAfter("## 1. ").substringBefore("## 2. ")
        val promised = Regex("(?:GET|POST) (/api/[A-Za-z0-9._/-]*)").findAll(section)
            .map { it.groupValues[1] }.toSet()
        assertTrue("第 1 节该列的是 GET/POST 形式的端点，抓到 0 个说明格式被改了：" + promised.size,
            promised.isNotEmpty())
        assertTrue("承诺稳定的面要小（现在 ${promised.size} 条）：" + promised, promised.size <= 16)
        for (must in listOf("/api/task", "/api/events", "/api/state", "/api/decide", "/api/stop"))
            assertTrue("这几个是外部程序真正需要的，必须在第 1 节：" + promised, promised.contains(must))
    }
}
