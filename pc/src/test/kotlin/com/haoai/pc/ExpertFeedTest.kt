package com.haoai.pc

import com.sun.net.httpserver.HttpServer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * 「专家市场」（对标 Octop 专家页第四个页签）。
 *
 * 三条主张：
 * 1. **默认没有远端**：`expertFeed` 空 = 直接拒并说清怎么开，不发任何请求；
 * 2. 只认 http/https、只收对象、有张数与字节上限 —— 一个坏源不该能把页面或内存搞挂；
 * 3. 清单里的卡**不自动装**：这里只交出原文，装的动作走 `/api/presets op:'import'`
 *    那条已经验过的路（换新 id、拒掉没人设的卡）。
 */
class ExpertFeedTest {

    companion object {
        private lateinit var server: HttpServer
        private var port = 0
        private val bodies = HashMap<String, String>()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { ex ->
                val code = if (ex.requestMethod == "GET" && bodies.containsKey(ex.requestURI.path)) 200 else 404
                val bytes = (bodies[ex.requestURI.path] ?: "not found").toByteArray(StandardCharsets.UTF_8)
                /* 关掉 keep-alive：JDK 的 HttpServer 默认派发线程一次只服务一个交换，
                   而 HttpURLConnection 会把连接留着复用 —— 于是第二个用例的请求排在一条
                   空闲的旧连接后面，症状是 Read timed out（第一个用例却好好的）。
                   这条不写清楚，下一个人会以为是产品里的 fetch 有超时 bug。 */
                ex.responseHeaders.add("Connection", "close")
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            port = server.address.port
            server.start()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            server.stop(0)
        }

        private fun url(name: String) = "http://127.0.0.1:$port/$name"
    }

    /**
     * 连着发两次都要成。这条是修出来的产物：老的 `URL.openConnection()` 在这台机器上
     * 对任何地址都拿不到响应（10 秒后 Read timed out），换成 java.net.http.HttpClient
     * 之后才通 —— 只发一次的测试（第一次就红）分不清"客户端选错"和"服务没起"。
     */
    @Test
    fun `two sequential fetches both answer`() {
        bodies["/probe.json"] = """[{"name":"探针","persona":"你是探针。"}]"""
        val a = ExpertFeed.fetch(url("probe.json"))
        val b = ExpertFeed.fetch(url("probe.json"))
        assertEquals("第一次：" + a.second, 1, a.first.size)
        assertEquals("第二次：" + b.second, 1, b.first.size)
    }

    @Test
    fun `an unset feed refuses without making any request`() {
        val (cards, err) = ExpertFeed.fetch("   ")
        assertTrue(cards.isEmpty())
        assertNotNull("空地址要明说怎么开", err)
        assertTrue(err!!.contains("还没配市场地址"))
    }

    @Test
    fun `only http and https are accepted`() {
        for (bad in listOf("file:///C:/x.json", "javascript:alert(1)", "//127.0.0.1/x.json")) {
            val (cards, err) = ExpertFeed.fetch(bad)
            assertTrue(cards.isEmpty())
            assertTrue("要拒掉「$bad」并说明只认 http/https：" + err, err!!.contains("只认 http/https"))
        }
    }

    @Test
    fun `a feed list carries name and desc and keeps the raw card for import`() {
        bodies["/ok.json"] = """{"version":1,"cards":[
            {"name":"市场甲","desc":"从远端来","persona":"你是市场甲。","mode":"ask"},
            {"name":"市场乙","persona":"你是市场乙。"}]}"""
        val (cards, note) = ExpertFeed.fetch(url("ok.json"))
        assertEquals("两张卡都该在：$note", 2, cards.size)
        assertEquals("市场甲", cards[0].name)
        assertEquals("从远端来", cards[0].desc)
        // 交回的是原文：装卡的活归 /api/presets 的 import，市场这里不写库
        assertTrue(cards[0].json.contains("你是市场甲"))
    }

    @Test
    fun `dirty items are skipped instead of failing the whole list`() {
        bodies["/dirty.json"] = """["只给个名字",{"name":"能用的","persona":"你是能用的。"},7]"""
        val (cards, note) = ExpertFeed.fetch(url("dirty.json"))
        assertEquals(1, cards.size)
        assertEquals("能用的", cards.first().name)
        assertNotNull("跳过了要说明跳过了几项：" + note, note)
        assertTrue(note!!.contains("2 项不是卡片"))
    }

    @Test
    fun `a list with nothing usable is refused in words`() {
        bodies["/empty.json"] = """{"cards":[]}"""
        val (cards, err) = ExpertFeed.fetch(url("empty.json"))
        assertTrue(cards.isEmpty())
        assertTrue("要说清为什么一张都没有：" + err, err!!.contains("没有任何一张"))

        bodies["/junk.json"] = "这不是 JSON"
        val (c2, e2) = ExpertFeed.fetch(url("junk.json"))
        assertTrue(c2.isEmpty())
        assertTrue("非法 JSON 要直说：" + e2, e2!!.contains("不是合法 JSON"))

        bodies["/noshape.json"] = """{"version":1}"""
        val (c3, e3) = ExpertFeed.fetch(url("noshape.json"))
        assertTrue(c3.isEmpty())
        assertTrue("没有 cards 字段要说清：" + e3, e3!!.contains("cards"))
    }

    @Test
    fun `a huge list is capped and a huge body is refused`() {
        val many = (1..60).joinToString(",") { """{"name":"卡$it","persona":"你是卡$it。"}""" }
        bodies["/many.json"] = """[$many]"""
        val (cards, _) = ExpertFeed.fetch(url("many.json"))
        assertEquals("一次最多交回 ${ExpertFeed.MAX_CARDS} 张", ExpertFeed.MAX_CARDS, cards.size)

        bodies["/big.json"] = """[{"name":"胖卡","persona":"${"字".repeat(300_000)}"}]"""
        val (c2, e2) = ExpertFeed.fetch(url("big.json"))
        assertTrue(c2.isEmpty())
        assertTrue("超过字节上限要拒：" + e2, e2!!.contains("上限"))
    }

    /**
     * 市场不自己定规矩：装卡走 `Presets.importJson`。读了一遍 `Presets.parse` 才拿到硬事实——
     * **它要求对象里有 `id`**（`o["id"] ?: return null`），缺 id 的卡会被拒成
     * "这不是一张能读的角色卡"，而这句话完全没提到真正缺的是 id。
     * 所以市场清单的形状必须与 `op:'export'` 的输出同形（带 id）；装进来时 importJson
     * 会换成一个新 id，源里那个 id 不带进本地库 —— 两条不同市场的卡不会撞车。
     */
    @Test
    fun `market cards go through the same import gate as file imports`() {
        bodies["/gate.json"] = """[
            {"id":"mkt-fixed-id","name":"市场卡","persona":"你是市场卡，回答要短。","mode":"ask"},
            {"name":"没有 id 的一张"}]"""
        val (cards, _) = ExpertFeed.fetch(url("gate.json"))
        assertEquals(2, cards.size)
        val (ok, errOk) = Presets.importJson(cards[0].json)
        assertNull("带 id 的导出形卡不该被拒：" + errOk, errOk)
        assertTrue("导入要换一个新 id，不复用市场里那个",
            ok!!.id.isNotEmpty() && ok.id != "mkt-fixed-id")
        val (bare, err) = Presets.importJson(cards[1].json)
        assertNull("缺 id 的该被拒：" + bare?.name, bare)
        assertTrue(err!!.contains("角色卡"))
    }
}
