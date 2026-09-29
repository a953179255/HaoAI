package com.haoai.pc

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * 技能订阅源与"装之前先看见"的判据。
 *
 * 全走一个本机 http 夹具，不碰外网 —— 这一份功能的输入**本来就是**外部网址，
 * 用真网址测等于把测试的结论交给一个我控制不了的服务器。
 *
 * 最要紧的三条：
 * ① 清单地址只认 http/https（订阅源是用户从别处粘来的，不能成为读本地文件的口子）；
 * ② 拉不到正文的条目**照样列出来**，只是标上原因（静默消失会让人以为"没有这个技能"）；
 * ③ 危险工具那一批是从工具表推的，不是手抄的（手抄那张表迟早漏一把新工具）。
 */
class SkillFeedTest {

    companion object {
        private lateinit var home: File
        private lateinit var srv: HttpServer
        private var base = ""

        private const val GOOD_MD = """---
name: 剪片助手
description: 把 mp4 剪成竖屏
tools: [write, read]
---
先用 `read` 看一下时长，再用 `media` 裁切。
"""

        private const val RAW_MD = "# 没有 YAML 头\n\n需要 run_code 跑一段 python。\n"

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            home = Files.createTempDirectory("haoai-feeds-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            File(home, "home").mkdirs()
            // 端口 0 = 立刻绑定，start() 之前就能读到真实端口，条目 url 不必等第二轮注册
            srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            base = "http://127.0.0.1:" + srv.address.port
            val ok = { t: String ->
                com.sun.net.httpserver.HttpHandler { ex ->
                    val b = t.toByteArray(StandardCharsets.UTF_8)
                    ex.responseHeaders.add("Content-Type", "text/markdown; charset=utf-8")
                    ex.sendResponseHeaders(200, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
            }
            srv.createContext("/good.md", ok(GOOD_MD))
            srv.createContext("/raw.md", ok(RAW_MD))
            srv.createContext("/notjson", ok("<html>登录一下才能看</html>"))
            // skills 写成了对象而不是数组：这是最常见的"看着像清单"的错法
            srv.createContext("/badwrap.json", ok("""{"skills":{"name":"甲","url":"$base/good.md"}}"""))
            srv.createContext("/index.json", ok(
                """{"skills":[
                {"name":"甲","url":"$base/good.md","desc":"会剪"},
                {"name":"乙","url":"$base/missing.md"},
                {"url":""}]}"""
            ))
            srv.createContext("/bare.json", ok("""[{"name":"丙","url":"$base/raw.md"}]"""))
            srv.createContext("/many.json", ok(
                """{"skills":[""" + (1..60).joinToString(",") { """{"name":"s$it","url":"$base/good.md"}""" } + "]}"
            ))
            srv.start()
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() = srv.stop(0)
    }

    @Before
    fun reset() {
        SkillFeeds.load().forEach { SkillFeeds.remove(it.id) }
        SkillDocs.list().forEach { SkillDocs.remove(it.slug) }
    }

    @After
    fun clean() = reset()

    private fun feed(url: String = "$base/index.json", name: String = ""): SkillFeed {
        val (f, err) = SkillFeeds.add(name, url)
        return f ?: throw AssertionError("加订阅源不该失败：$err（$url）")
    }

    // ---- 订阅源本身 ----

    @Test
    fun `a feed survives a restart`() {
        val f = feed(name = "我的收藏")
        val again = SkillFeeds.load()
        assertEquals(1, again.size)
        assertEquals("我的收藏", again[0].name)
        assertEquals(f.url, again[0].url)
        assertTrue("落盘文件必须就在状态根里", SkillFeeds.file().exists())
    }

    @Test
    fun `blank name falls back to the host so the list is never a row of blanks`() {
        feed()
        assertEquals("没填名字也得有个能认出来的标题", "127.0.0.1", SkillFeeds.load()[0].name)
    }

    @Test
    fun `only http and https indexes get fetched`() {
        for (u in listOf("file:///C:/Windows/win.ini", "javascript:alert(1)", "ftp://x/y.json", "C:\\a\\b.json")) {
            val (_, err) = SkillFeeds.add("", u)
            assertTrue("该挡住：$u -> $err", err.isNotEmpty())
        }
        assertEquals("一条都不该落进去", 0, SkillFeeds.load().size)
    }

    @Test
    fun `duplicate feeds are refused, caps are enforced`() {
        feed()
        val (_, dup) = SkillFeeds.add("", "$base/index.json")
        assertTrue("重复订阅该拒：" + dup, dup.isNotEmpty())
        repeat(SkillFeeds.MAX_FEEDS - 1) { SkillFeeds.add("", "http://127.0.0.1:${srv.address.port}/i$it.json") }
        assertEquals(SkillFeeds.MAX_FEEDS, SkillFeeds.load().size)
        val (_, over) = SkillFeeds.add("x", "http://127.0.0.1:9/another.json")
        assertTrue("超过上限该说清楚：" + over, over.contains("12"))
    }

    @Test
    fun `remove takes the right one and leaves the rest`() {
        val a = feed()
        val b = SkillFeeds.add("b", "$base/bare.json").first!!
        assertTrue(SkillFeeds.remove(a.id))
        assertEquals(listOf(b.id), SkillFeeds.load().map { it.id })
        assertFalse("删掉之后再删一次不该报成功", SkillFeeds.remove(a.id))
    }

    // ---- 清单的形状与容错 ----

    @Test
    fun `both index shapes parse`() {
        val (wrapped, e1) = SkillFeeds.fetch(feed())
        assertEquals("wrapped 该两条：" + e1, 2, wrapped.size)
        assertEquals("甲", wrapped[0].name)
        val bare = SkillFeeds.fetch(SkillFeeds.add("b", "$base/bare.json").first!!)
        assertEquals(1, bare.first.size)
        assertEquals("丙", bare.first[0].name)
    }

    @Test
    fun `an entry whose document cannot be fetched is still listed`() {
        val (list, _) = SkillFeeds.fetch(feed())
        val missing = list.first { it.name == "乙" }
        assertTrue("拉不到正文要写明原因，不许静默丢掉：" + missing.scanError,
            missing.scanError.isNotEmpty())
        assertEquals("拉不到就不该编出一份工具清单", emptyList<String>(), missing.tools)
    }

    @Test
    fun `a non-json index says so instead of throwing`() {
        val (list, err) = SkillFeeds.fetch(SkillFeeds.add("x", "$base/notjson").first!!)
        assertTrue(list.isEmpty())
        assertTrue("要说清楚为什么不是清单：" + err, err.contains("JSON"))
    }

    @Test
    fun `a skills field that is not an array says so instead of listing nothing`() {
        val (list, err) = SkillFeeds.fetch(SkillFeeds.add("w", "$base/badwrap.json").first!!)
        assertTrue(list.isEmpty())
        assertTrue("该说清是形状不对，而不是「这个源没有技能」：" + err, err.contains("JSON"))
    }

    @Test
    fun `entries past the cap are dropped, not the whole feed`() {
        val (list, err) = SkillFeeds.fetch(SkillFeeds.add("m", "$base/many.json").first!!)
        assertEquals(SkillFeeds.MAX_ENTRIES, list.size)
        assertEquals("", err)
    }

    @Test
    fun `an entry without a url is skipped`() {
        assertEquals("没有 url 的条目不该占一个名字位", 2, SkillFeeds.fetch(feed()).first.size)
    }

    // ---- 装之前那份清单 ----

    @Test
    fun `declared tools are listed, read-only ones marked as such`() {
        val p = SkillFeeds.preview("$base/good.md")
        assertTrue(p.ok)
        assertTrue("声明的写工具该在：" + p.tools, p.tools.any { it.startsWith("write") && it.contains("作者声明") })
        assertTrue("只读那把也要照实说：" + p.tools, p.tools.any { it.startsWith("read") && it.contains("只读") })
        assertEquals("剪片助手", p.name)
    }

    @Test
    fun `tools named only in the body are marked as inferred`() {
        val p = SkillFeeds.preview("$base/raw.md")
        assertTrue("正文里写了 run_code 就该报出来：" + p.tools,
            p.tools.any { it.startsWith("run_code") && it.contains("正文里这么写") })
    }

    @Test
    fun `prose about reading files is not a permission`() {
        val out = SkillPerms.scan("---\n---\n先 read 一下再 grep，看看有没有 media 目录\n")
        assertTrue("只读工具不该被扫进权限：" + out, out.none { it.startsWith("read") || it.startsWith("grep") })
    }

    @Test
    fun `an unknown declared tool name is surfaced, not swallowed`() {
        val out = SkillPerms.scan("---\ntools: [totally_made_up]\n---\n正文\n")
        assertTrue("认不出的名字最该让人看到：" + out,
            out.any { it.contains("totally_made_up") && it.contains("没有这把工具") })
    }

    @Test
    fun `external mcp tools are called out by name`() {
        val out = SkillPerms.scan("---\ntools: [mcp__github__create_issue]\n---\n也用 mcp__slack__post 发消息\n")
        assertTrue("声明的 mcp 该列：" + out, out.any { it.startsWith("mcp__github__create_issue") })
        assertTrue("正文里出现的 mcp 该列：" + out, out.any { it.startsWith("mcp__*") })
    }

    @Test
    fun `the dangerous set comes from the tool table, not a hand-copied list`() {
        val exec = builtinTools().filter { it.kind != "read" }.map { it.name }
        assertTrue("工具表里至少该有 write 和 shell", exec.containsAll(listOf("write", "shell", "git")))
        // task 的 kind 是 read（它自己不落盘），但它派生的子任务能用上全部工具，所以要额外点名
        val expect = exec + "task"
        val body = "---\n---\n" + expect.joinToString(" ") { "`$it`" } + "\n"
        val out = SkillPerms.scan(body)
        val missing = expect.filter { t -> out.none { it.startsWith("$t（") } }
        assertEquals("每一把会动东西的工具都该被扫出来，缺：" + missing, emptyList<String>(), missing)
    }

    @Test
    fun `the disclaimer travels with the list`() {
        // 这句话是本功能的边界所在：删了它，"权限清单"就变成"沙箱承诺"
        assertTrue(SkillPerms.NOTE.contains("不是沙箱保证"))
        assertTrue(SkillPerms.NOTE.contains("不受这份清单限制"))
    }

    @Test
    fun `yaml list style tools declarations are read too`() {
        val out = SkillPerms.scan("---\nallowed-tools:\n  - shell\n  - read\ndescription: 别把这个当工具\n---\n正文\n")
        assertTrue("块状写法也要认：" + out, out.any { it.startsWith("shell（作者声明") })
        assertTrue("续行不该被当成工具名：" + out, out.none { it.contains("别把这个当工具") })
    }

    // ---- 来源：装了要知道从哪装的 ----

    @Test
    fun `a url install remembers where it came from and leaves the text alone`() {
        val r = SkillDocs.importUrl("$base/good.md")
        assertTrue("导入失败：" + r.error, r.ok)
        val d = SkillDocs.list().first()
        assertEquals("$base/good.md", d.origin)
        assertEquals("正文必须原样，不许被插进我的字段", GOOD_MD, d.file.readText(StandardCharsets.UTF_8))
        // 重启进程后还在（sidecar 真的落盘了）
        assertEquals(1, SkillDocs.list().count { it.origin == "$base/good.md" })
    }

    @Test
    fun `a pasted skill has no origin`() {
        SkillDocs.importText(RAW_MD)
        assertEquals("", SkillDocs.list().first().origin)
    }

    @Test
    fun `removing a skill takes its source with it`() {
        SkillDocs.importUrl("$base/good.md")
        val dir = SkillDocs.list().first().file.parentFile
        assertTrue(SkillDocs.remove(SkillDocs.list().first().slug))
        assertFalse("sidecar 残留会让下一次同名导入误报来源", dir.exists())
    }

    @Test
    fun `preview of an unreachable url says why instead of showing an empty card`() {
        val p = SkillFeeds.preview("$base/missing.md")
        assertFalse(p.ok)
        assertTrue(p.error.isNotEmpty())
        assertEquals(0, p.tools.size)
    }

    @Test
    fun `preview refuses a file url`() {
        val p = SkillFeeds.preview("file:///C:/a/SKILL.md")
        assertFalse(p.ok)
        assertTrue(p.error.contains("http"))
    }

    // ---- 线格式：界面直接吃这几串 ----

    @Test
    fun `json payloads escape quotes and stay parseable`() {
        val f = SkillFeeds.add("引号\"号", "$base/index.json").first!!
        val feeds = Json.parseToJsonElement(SkillFeeds.json()).jsonArray
        assertEquals("引号\"号", feeds[0].jsonObject["name"]!!.jsonPrimitive.content)
        val (entries, _) = SkillFeeds.fetch(f)
        val arr = Json.parseToJsonElement(SkillFeeds.entriesJson(entries)).jsonArray
        assertEquals(2, arr.size)
        assertEquals("甲", arr[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(arr[0].jsonObject["tools"]!!.jsonArray.isNotEmpty())
        val obj = Json.parseToJsonElement(SkillFeeds.previewJson(SkillFeeds.preview("$base/good.md")))
            .jsonObject
        assertEquals("$base/good.md", obj["url"]!!.jsonPrimitive.content)
        assertTrue(obj["head"]!!.jsonPrimitive.content.contains("media"))
    }

    @Test
    fun `refresh by unknown id fails loudly`() {
        val (list, err) = SkillFeeds.refresh("nope")
        assertTrue(list.isEmpty())
        assertTrue(err.contains("没有这条订阅源"))
    }
}
