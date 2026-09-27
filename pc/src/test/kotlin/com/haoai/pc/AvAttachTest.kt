package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 用户拖进来的**音视频附件**：只给路径与播放器，不给像素。
 *
 * 为什么值得单独测：图片那条路是"编成 data URL 塞进请求"，
 * 一段 mp4 走同一条路会把会话文件撑爆、把请求体撑炸。
 * 两边分开的边界（工作区内 + 魔数认得出）也必须守住 —— 那是"网页能读任意本地文件"的门口。
 */
class AvAttachTest {

    private class AllowGate : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = ""
    }

    /** 一问一答就收口的假模型：这条要验的是附件怎么进历史，不是回合循环。 */
    private class Echo : ChatClient {
        override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
            onText("收到")
            return AssistantTurn("收到", emptyList(), Usage(), "stop")
        }
    }

    /** 一个魔数是真的 mp4（ftyp）但内容极小的文件：够 MediaMime 认出来。 */
    private fun mp4(dir: File, name: String): File {
        val f = File(dir, name)
        val head = ByteArray(64)
        "ftypisom".toByteArray(Charsets.ISO_8859_1).copyInto(head, 4)
        f.writeBytes(head)
        return f
    }

    /**
     * 每条用例一套自己的状态根 + 自己的会话 id。
     *
     * 不这么做会互相串：Engine 构造时就从 `HAOAI_HOME/sessions/pc-<id>.json` 读历史，
     * 两条用例共用 "av1" 的话，后一条的"第一条用户消息"其实是上一条留下的。
     */
    private fun engineWith(): Pair<Engine, File> {
        val root = Files.createTempDirectory("haoai-av").toFile().apply { mkdirs() }
        System.setProperty("haoai.home", File(root, "home").absolutePath)
        val ws = File(root, "ws").apply { mkdirs() }
        val e = Engine(Session("av" + System.nanoTime(), ws), PcSettings(), builtinTools(), AllowGate(), {}, Echo())
        return e to ws
    }

    @Test
    fun `an audio-video attachment rides on the user message as a path`() {
        val (e, ws) = engineWith()
        val clip = mp4(ws, "我的素材.mp4")
        e.submit("把这段剪 3 秒", media = listOf(clip.absolutePath))
        val msgs = e.messages()
        val user = msgs.first { it.role == "user" }
        assertEquals(1, user.media.size)
        assertTrue("存的是绝对路径（界面按它取字节）：" + user.media,
            File(user.media[0]).isAbsolute && File(user.media[0]).isFile)
        // 关键：它**不能**混进 images —— 那会被编成 data URL 发给模型
        assertTrue("音视频不该走图片那条路：" + user.images, user.images.isEmpty())
    }

    @Test
    fun `a path that is not a real file is dropped instead of poisoning history`() {
        val (e, ws) = engineWith()
        val real = mp4(ws, "a.mp4")
        e.submit("两句都试试", media = listOf(real.absolutePath, ws.absolutePath + "/不存在.mp4"))
        val user = e.messages().first { it.role == "user" }
        assertEquals("只留真的在盘上的那个：" + user.media, 1, user.media.size)
        assertTrue("实际是：" + user.media, user.media[0].endsWith("a.mp4"))
    }

    @Test
    fun `the media list survives the session file round trip`() {
        val (e, ws) = engineWith()
        val clip = mp4(ws, "b.mp4")
        e.submit("带素材的一句话", media = listOf(clip.absolutePath))
        e.persistNow()
        val raw = Json.parseToJsonElement(e.session.file.readText()).jsonObject
        val msgs = raw["messages"]!!.jsonArray
        assertTrue("落盘时该带上 media 字段：" + msgs,
            msgs.any { (it.jsonObject["media"]?.jsonArray?.size ?: 0) > 0 })
        // 重开：新引擎从同一个会话文件读回来
        val back = Engine(e.session, PcSettings(), builtinTools(), AllowGate(), {}, Echo())
        val user = back.messages().first { it.role == "user" && it.media.isNotEmpty() }
        assertEquals("重开之后播放器还在：" + user.media, 1, user.media.size)
        assertTrue(user.media[0].endsWith("b.mp4"))
    }

}
