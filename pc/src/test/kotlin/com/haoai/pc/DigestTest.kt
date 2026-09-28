package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 定时结果汇总的判据。
 *
 * 这一层的价值全在"只收定时那一类"和"不另存一份真相"上：
 * 数据从运行账本里挑，所以挑错触发条件（把用户手动的也收进来）就是界面上看着多、看着少。
 */
class DigestTest {
    private lateinit var home: File
    private lateinit var ws: File

    @Before
    fun setUp() {
        home = Files.createTempDirectory("haoai-digest-home").toFile()
        System.setProperty("haoai.home", home.absolutePath)
        ws = Files.createTempDirectory("haoai-digest-ws").toFile()
    }

    private fun add(trigger: String, title: String, turns: Int, out: String, stopped: Boolean = false) =
        RunLedger.add(sid = "pc-$title", title = title, goal = "g", trigger = trigger,
            turns = turns, ms = 1234L, stopped = stopped, out = out)

    @Test
    fun `only scheduled runs make the digest`() {
        add("手动", "随手一问", 2, "手动的答案")
        add("定时", "早间巡检", 3, "今天没有报错")
        add("排队", "排队的", 1, "x")
        add("定时", "睡前收工", 2, "清单列好了")
        val es = Digest.entries()
        assertEquals("只该看到两条定时的：" + es.map { it.title }, listOf("睡前收工", "早间巡检"), es.map { it.title })
        assertEquals("跑了 3 轮 · 1.2s", es.first { it.title == "早间巡检" }.verdict)
    }

    @Test
    fun `a stopped run says so instead of looking like a result`() {
        add("定时", "剪片子", 1, "跑到一半被停了", stopped = true)
        val e = Digest.entries().first()
        assertTrue("被停止要说清：" + e.verdict, e.verdict.contains("被停止"))
    }

    @Test
    fun `the json is well formed even when the answer has quotes`() {
        add("定时", "带引号", 2, "他说「好」并写了 \"ok\"\n第二行")
        val root = Json.parseToJsonElement(Digest.json()).jsonObject
        val item = root["items"]!!.jsonArray[0].jsonObject
        assertEquals("带引号", item["title"]!!.jsonPrimitive.content)
        assertTrue("换行不许把 JSON 打断：" + item["text"]!!.jsonPrimitive.content,
            item["text"]!!.jsonPrimitive.content.contains("第二行"))
        assertEquals(1, root["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `the markdown export lands inside the workspace and says how many`() {
        add("定时", "早间巡检", 2, "一切正常")
        val empty = Digest.markdown()
        assertTrue(empty.contains("早间巡检"))
        val (rel, note) = Digest.export(ws)
        assertEquals(".haoai-output/schedule-log.md", rel)
        assertTrue("回执要说清写了几条：" + note, note.contains("1 条"))
        val f = File(ws, ".haoai-output/schedule-log.md")
        assertTrue("文件该真的在盘上", f.isFile)
        assertTrue(f.readText(Charsets.UTF_8).contains("一切正常"))
        assertFalse("不该把整个工作区翻一遍", File(ws, "schedule-log.md").exists())
    }

    @Test
    fun `an empty digest still renders something a human can read`() {
        assertEquals("还没有跑完过任何定时任务。", Digest.markdown().trim().lines().last().trim())
        assertTrue(Digest.entries().isEmpty())
        val root = Json.parseToJsonElement(Digest.json()).jsonObject
        assertEquals(0, root["items"]!!.jsonArray.size)
        assertEquals("0", root["count"]!!.jsonPrimitive.content)
    }
}
