package com.haoai.pc

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 降级链（`fallback`）的判据。
 *
 * 这一批要补的是 PC 侧缺的那一半：手机端 `AppContainer.clientFor` 早就有 FallbackClient，
 * 而 PC 只有 3/8/20 秒三档退避，退完直接报错 —— 定时任务与任务链都以 auto 档在夜里跑，
 * 撞上一次 429 就是整条链停在那儿等一个不在电脑前的人。
 *
 * 三条刻意的边界，每条都有对应的测试：
 * ① 只吃**可重试**的错（429/5xx/超时）；400 是"请求本身不合法"，换模型也没用，换了只会把
 *    同一个错误重复三遍并让人以为"降级过"；
 * ② 换网关时**不把主网关的 key 带过去**（除非同一主机）—— 备用地址是用户自己填的，
 *    可能是本机 llama-server，也可能是陌生主机；
 * ③ 账本按**实际用的那一档**记，不按设置里那个：降级发生过就要在数据里看得见。
 */
class FallbackTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            val tmp = Files.createTempDirectory("haoai-fb-home").toFile()
            System.setProperty("haoai.home", File(tmp, "home").absolutePath)
            File(tmp, "home").mkdirs()
        }
    }

    private fun ws() = Files.createTempDirectory("haoai-fb-ws").toFile().apply { mkdirs() }

    /** 每个模型一个假客户端：`fail` 里写的那个模型永远抛（写 `*` = 全都抛）。 */
    private class Fake(val model: String, private val fail: ProviderError?, private val log: MutableList<String>) : ChatClient {
        override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
            log += model
            fail?.let { throw it }
            onText("来自 $model 的回答")
            return AssistantTurn("来自 $model 的回答", emptyList(), Usage(10, 5), "stop")
        }
    }

    private class AllowGate : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = options.firstOrNull() ?: "都行"
    }

    private val transient429 = ProviderError("网关限流 429", status = 429, transient = true)
    private val hard400 = ProviderError("请求不合法", status = 400, transient = false)

    private fun drive(
        failModel: String? = "big",
        err: ProviderError = transient429,
        fallback: String = "small",
        model: String = "big"
    ): Triple<Engine, List<String>, List<String>> {
        val seen = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val session = Session("f" + System.nanoTime(), ws())
        session.mode = "auto"
        val settings = PcSettings(model = model, fallback = fallback, permissionMode = "auto")
        val fails = { m: String -> failModel == "*" || m == failModel }
        val engine = Engine(
            session, settings, builtinTools(), AllowGate(),
            { ev -> if (ev is Ev.Notice) notices += ev.s },
            Fake(model, if (fails(model)) err else null, seen),
            clientFor = { s, slot ->
                val m = slot?.model ?: s.model
                Fake(m, if (fails(m)) err else null, seen)
            },
            backoffs = longArrayOf(1, 1, 1)     // 产品里是 3/8/20 秒，测试不该真等 31 秒
        )
        engine.submit("说一句")
        return Triple(engine, seen, notices)
    }

    @Test
    fun `a rate-limited model falls through to the next one and the turn still finishes`() {
        val (engine, seen, notices) = drive()
        assertEquals("先试主档再试备胎", listOf("big", "small"), seen.distinct())
        assertTrue("没说过降级到了谁：" + notices.joinToString(" | "),
            notices.any { it.contains("已降级") && it.contains("small") })
        assertTrue("要说清是从谁降下来的（界面上那半截话是前一个模型说的）：" + notices.joinToString(" | "),
            notices.any { it.contains("big") })
        assertEquals("实际用的那一档才是 modelNow", "small", engine.modelNow)
        val last = engine.messages().last()
        assertEquals("回合没跑完：" + last.content, "来自 small 的回答", last.content)
    }

    @Test
    fun `a non-retryable error does not switch models`() {
        val (_, seen, notices) = drive(err = hard400)
        assertEquals("400 换模型也没用，不该假装降级：" + seen.joinToString(","), listOf("big"), seen.distinct())
        assertFalse("不该出现降级提示：" + notices.joinToString(" | "),
            notices.any { it.contains("已降级") })
    }

    @Test
    fun `an exhausted chain reports the failure instead of looping`() {
        val (engine, seen, _) = drive(failModel = "*", fallback = "small,bigger")
        assertEquals("三档各试一次就该收口：" + seen.joinToString(","),
            listOf("big", "small", "bigger"), seen.distinct())
        assertTrue("链到头了要留下一句错误，不能静默结束", engine.lastError != null)
    }

    @Test
    fun `no fallback configured means one attempt and one clear error`() {
        val (engine, seen, _) = drive(fallback = "")
        assertEquals("没配链就不该多试一个模型：" + seen.joinToString(","), listOf("big"), seen.distinct())
        assertEquals("没降级成功时 modelNow 还是主档", "big", engine.modelNow)
    }

    @Test
    fun `the chain is rebuilt when the settings change`() {
        // 改了主模型但链上留着上一个，表现就是"我明明换成 X 了，报错还是说 Y 不可用"。
        val session = Session("f" + System.nanoTime(), ws())
        val seen = mutableListOf<String>()
        val engine = Engine(
            session, PcSettings(model = "big", fallback = "small"), builtinTools(), AllowGate(), {},
            Fake("big", null, seen),
            clientFor = { s, slot -> Fake(slot?.model ?: s.model, null, seen) }
        )
        engine.useSettings(PcSettings(model = "other", fallback = "third"))
        assertEquals("other", engine.modelNow)
        engine.submit("说一句")
        assertEquals("换设置之后第一档该是新的那个：" + seen.joinToString(","), listOf("other"), seen.distinct())
    }

    @Test
    fun `the chain string accepts three shapes and ignores blanks`() {
        val slots = parseFallback(" a , b@http://127.0.0.1:8080/v1 ;\n c@ ")
        assertEquals(listOf("a", "b", "c"), slots.map { it.model })
        assertEquals("", slots[0].baseUrl)
        assertEquals("http://127.0.0.1:8080/v1", slots[1].baseUrl)
        // `b@`（写了 @ 但没写地址）= 同网关换模型，不是"地址为空"
        assertEquals("", slots[2].baseUrl)
        assertTrue(parseFallback(" , , ").isEmpty())
    }

    @Test
    fun `same host means scheme host and port all match`() {
        assertTrue(sameHost("http://127.0.0.1:8080/v1", "http://127.0.0.1:8080/v1"))
        assertFalse("端口不同就不是同一台（8080 与 8081 上是两套凭据）",
            sameHost("http://127.0.0.1:8080/v1", "http://127.0.0.1:8081/v1"))
        assertFalse(sameHost("https://api.example.com/v1", "http://api.example.com/v1"))
        assertFalse("坏地址一律按不同主机处理（宁可不带 key）", sameHost("not a url", "http://a/v1"))
    }

    @Test
    fun `the api key never travels to a different host`() {
        val (primary, sawPrimary) = echo()
        val (backup, sawBackup) = echo()
        val baseA = "http://127.0.0.1:${(primary.address as InetSocketAddress).port}/v1"
        val baseB = "http://127.0.0.1:${(backup.address as InetSocketAddress).port}/v1"
        Env.apiKeyFile.writeText("sk-must-not-travel")
        val s = PcSettings(baseUrl = baseA, model = "big", fallback = "small@$baseB")
        val ask = { c: ChatClient -> runCatching { c.chat(emptyList(), emptyList()) {} } }
        ask(chatClient(s, ModelSlot("big", baseA)))
        ask(chatClient(s, ModelSlot("small", baseB)))
        assertTrue("主网关该拿到 key：" + sawPrimary.get(), sawPrimary.get().contains("sk-must-not-travel"))
        // 注意断的是"没有 key 内容"，不是"没有这个头"：Provider 本来就总会发一行
        // `Authorization: Bearer …`（没配 key 时是空的），这是既有行为，本批不改它。
        val tokenAtBackup = sawBackup.get().removePrefix("Bearer").trim()
        assertFalse("换网关不许把这把 key 带出去：" + sawBackup.get(),
            tokenAtBackup.contains("sk-must-not-travel"))
        assertEquals("备用档不该带上任何凭据：" + sawBackup.get(), "", tokenAtBackup)
        // 同主机（同端口）时还是要带 —— 本机 llama-server 那种"换个模型名"的写法最常见
        val same = ModelSlot("small", baseA)
        ask(chatClient(s, same))
        assertTrue("同主机换模型时 key 该照常带：" + sawPrimary.get(),
            sawPrimary.get().contains("sk-must-not-travel"))
        primary.stop(0); backup.stop(0)
    }

    /** 只回一行 `[DONE]` 的假网关，作用是把对方看到的 Authorization 头记下来。 */
    private fun echo(): Pair<HttpServer, AtomicReference<String>> {
        val seen = AtomicReference("")
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            seen.set(ex.requestHeaders.getFirst("Authorization") ?: "")
            runCatching { ex.requestBody.readBytes() }
            val body = "data: [DONE]\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.start()
        return s to seen
    }
}
