package com.haoai.agent.platform

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与电脑那份连接的协议回归 —— **不碰设备、不碰真网络**：
 * 测试里就地起一个假 PC（JVM 自带的 `com.sun.net.httpserver`），按电脑端
 * `pc/src/main/kotlin/com/haoai/pc/Lan.kt` 真实回的形状应答。
 *
 * 为什么这么测：这条链路将来要接通知服务与设置页，而它最容易坏在三处 ——
 * 人手工填的地址、token 的传递、以及"电脑回了一句人话但客户端把它当异常抛出去"。
 * 这三处都不需要真机就能钉死。
 */
/** 电脑端 `pendingJson` 真实回的形状：中文、嵌套 payload、风险三档里的高危。 */
private const val PENDING_FIXTURE = """{"ok":true,"items":[{"id":"a1","kind":"approval","sid":"pc1",""" +
    """"payload":{"title":"要执行 rm -rf build/","detail":"命令：rm -rf build/",""" +
    """"risk":"high","riskLabel":"高危","riskWhy":"整目录删除"}}]}"""

class PcLinkTest {

    /** 记录最后一个请求看到的东西，判"带没带 token / 发的什么体"就用它。 */    private class FakePc(
        var tokenOk: Boolean = true,
        var expectToken: String = "tk-77",
        var pendingBody: String = "",
        var decideEcho: String = "",
        var raw: String? = null
    ) : AutoCloseable {
        var lastToken = ""
        var lastPath = ""
        var lastBody = ""
        val srv: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { s ->
            s.createContext("/") { ex ->
                lastToken = ex.requestHeaders.getFirst("X-HaoAI-Token") ?: ""
                lastPath = ex.requestURI.path
                lastBody = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                val path = ex.requestURI.path
                // 假 PC 也照协议办事：除了 health 与 pair，别的口没有效 token 就是 401。
                // 不这样"宽容"的话，客户端忘了带头上也能测过 —— 那是量具在放水。
                val needs = !path.startsWith("/lan/pair") && path != "/lan/health"
                val code = when {
                    needs && !tokenOk -> 401
                    needs && lastToken != expectToken -> 401
                    else -> 200
                }
                val text = raw ?: when (path) {
                    "/lan/health" -> """{"ok":true,"service":"haoai-pc"}"""
                    "/lan/pair" -> """{"ok":true,"token":"tk-77","device":"魅族 20 Pro","hint":"带在头上"}"""
                    "/lan/sessions" -> """{"ok":true,"items":[{"id":"pc1","title":"把 README 改一遍",""" +
                        """"mode":"ask","running":false,"msgs":12,"ws":"工作台","updated":1790000000000}]}"""
                    "/lan/pending" -> pendingBody.ifBlank { PENDING_FIXTURE }
                    "/lan/decide" -> "{\"ok\":true,\"note\":\"" +
                        decideEcho.ifBlank { "已允许一次" } + "\"}"
                    "/lan/digest" -> """{"ok":true,"items":[{"at":"09-29 07:00","title":"早间巡检",""" +
                        """"verdict":"跑完了","text":"三个仓库都干净"}]}"""
                    "/lan/send" -> """{"ok":true,"note":"已经排进那条会话","sid":"pc1"}"""
                    "/lan/unpair" -> """{"ok":true,"note":"已解除这台设备的配对"}"""
                    "/lan/session" -> """{"ok":true,"sid":"pc1","title":"把 README 改一遍","running":false,""" +
                        """"items":[{"role":"user","name":"","text":"把 README 改一遍"}]}"""
                    else -> """{"ok":false,"error":"没有这个接口"}"""
                }
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            s.start()
        }

        val base get() = "http://127.0.0.1:${srv.address.port}/"
        override fun close() { srv.stop(0) }
    }

    @Test
    fun `hand-typed addresses all land on the same shape`() {
        // 这四种写法全都常见，而少写协议头的表象是"配对失败"、错误还是电脑端回的
        for (raw in listOf("192.168.1.5", "192.168.1.5:8720", "http://192.168.1.5:8720",
                           "http://192.168.1.5:8720/", "  192.168.1.5:8720  "))
            assertEquals("[$raw] 规范化结果不对", "http://192.168.1.5:8720/", pcNormalizeBase(raw))
        assertEquals("只填 IP 要补上默认端口", "http://10.0.2.2:$PC_LAN_DEFAULT_PORT/", pcNormalizeBase("10.0.2.2"))
        for (bad in listOf("", "   ", "http://", "a b:8720", "http://x:8720/prefix", "主机名:8720"))
            assertNull("[$bad] 该判成看不懂", pcNormalizeBase(bad))
    }

    @Test
    fun `health needs no token and says whether the other side is HaoAI`() {
        FakePc().use { pc ->
            val out = runBlocking { PcLink(pc.base).health() }
            assertTrue("health 该过：${(out as? PcOut.Fail)?.message}", out is PcOut.Ok<PcHealth>)
            // 端口上挂着别的程序时不能报"连上了"
            FakePc(raw = """{"ok":true,"service":"something-else"}""").use { other ->
                val bad = runBlocking { PcLink(other.base).health() }
                assertTrue("对面不是 HaoAI 时不能说成功", bad is PcOut.Fail)
                assertTrue("要说清是什么：${(bad as PcOut.Fail).message}", bad.message.contains("不是 HaoAI"))
            }
        }
    }

    @Test
    fun `pair keeps the token and every later request carries it`() {
        FakePc().use { pc ->
            val link = PcLink(pc.base)
            val out = runBlocking { link.pair("481205", "魅族 20 Pro") }
            assertTrue("配对该成功：${(out as? PcOut.Fail)?.message}", out is PcOut.Ok<PcPair>)
            assertEquals("tk-77", link.token())
            runBlocking { link.pending() }
            assertEquals("配对之后每个请求都要带回头上", "tk-77", pc.lastToken)
            assertEquals("/lan/pending", pc.lastPath)
        }
    }

    @Test
    fun `pending parses the chinese payload the PC actually sends`() {
        FakePc().use { pc ->
            val link = PcLink(pc.base, "tk-77")
            val out = runBlocking { link.pending() }
            assertTrue("该解出来：${(out as? PcOut.Fail)?.message}", out is PcOut.Ok<List<PcApproval>>)
            val a = (out as PcOut.Ok).value.single()
            assertEquals("a1", a.id)
            assertEquals("pc1", a.sid)
            assertEquals("要执行 rm -rf build/", a.payload.title)
            assertEquals("高危", a.payload.riskLabel)
            assertEquals("整目录删除", a.payload.riskWhy)
            assertEquals("high", a.payload.risk)
        }
    }

    @Test
    fun `decide posts the id and the decision and returns the PC's sentence`() {
        FakePc(decideEcho = "已记在本会话的规则里").use { pc ->
            val link = PcLink(pc.base, "tk-77")
            val out = runBlocking { link.decide("a1", "allow_session") }
            assertEquals("已记在本会话的规则里", (out as PcOut.Ok).value)
            assertTrue("发的体里要同时有 id 与 decision：${pc.lastBody}",
                pc.lastBody.contains("\"id\":\"a1\"") && pc.lastBody.contains("\"decision\":\"allow_session\""))
            // 不在那三个值里的决定不该发出去
            val bad = runBlocking { link.decide("a1", "yes_please") }
            assertTrue("乱填的决定要挡在本地", bad is PcOut.Fail)
        }
    }

    @Test
    fun `a revoked pairing comes back as unauthorized, not as a generic error`() {
        FakePc(tokenOk = false).use { pc ->
            val out = runBlocking { PcLink(pc.base, "tk-old").sessions() }
            assertTrue("该失败", out is PcOut.Fail)
            assertTrue("要单独标出'没配对'，界面才知道该跳回配对页", (out as PcOut.Fail).unauthorized)
            assertTrue("话要说人话：${out.message}", out.message.contains("配对"))
        }
    }

    @Test
    fun `unreachable pc answers a sentence, not an exception`() {
        // 端口上没人监听：127.0.0.1:1 一定连不上，且不会等满超时
        val out = runBlocking { PcLink("http://127.0.0.1:1").health() }
        assertTrue("连不上要回 Fail", out is PcOut.Fail)
        val msg = (out as PcOut.Fail).message
        assertTrue("要说清下一步查什么：$msg", msg.contains("连不上") && msg.contains("手机联动"))
        assertFalse("别把栈顶异常抛给调用方", msg.contains("Exception"))
    }

    @Test
    fun `non-json answer is refused instead of crashing the caller`() {
        FakePc(raw = "<html>打印机设置页</html>").use { pc ->
            val out = runBlocking { PcLink(pc.base, "tk-77").sessions() }
            assertTrue("该失败：$out", out is PcOut.Fail)
            assertTrue("要说'不是 JSON'并提示地址：${(out as PcOut.Fail).message}",
                out.message.contains("不是 JSON"))
        }
    }

    @Test
    fun `sessions and digest and send all round-trip`() {
        FakePc().use { pc ->
            val link = PcLink(pc.base, "tk-77")
            val s = runBlocking { link.sessions() }
            assertEquals("把 README 改一遍", (s as PcOut.Ok).value.single().title)
            assertEquals(12, s.value.single().msgs)
            val d = runBlocking { link.digest() }
            assertEquals("三个仓库都干净", (d as PcOut.Ok).value.single().text)
            val sent = runBlocking { link.send("pc1", "把 README 的验收段补一句") }
            assertEquals("pc1", (sent as PcOut.Ok).value)
            val one = runBlocking { link.session("pc1") }
            assertEquals("把 README 改一遍", (one as PcOut.Ok).value.items.first().text)
            val gone = runBlocking { link.unpair() }
            assertTrue((gone as PcOut.Ok).value.contains("解除"))
            assertEquals("解除之后本地 token 要清掉", "", link.token())
        }
    }
}
