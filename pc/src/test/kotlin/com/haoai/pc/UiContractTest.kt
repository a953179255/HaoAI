package com.haoai.pc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面与服务端之间**靠手抄**的几份清单，抄漂了不许静默。
 *
 * 为什么值得钉：这两类漂移都不会让任何一边报错 —— 命令表漂了，技能能占用内置名、
 * 然后面板上永远点不到；事件名漂了，通知发出去没人听（#111 就是"SSE 发了 DOM 没有"）。
 * 判据全部读**产品源码本身**（`ApiDocTest` 的模式），不是 README 的转述。
 */
class UiContractTest {

    private val server = File("src/main/kotlin/com/haoai/pc/Server.kt")
    private val ui = File("src/main/resources/ui/index.html")

    private fun read(f: File): String {
        // 读不到就等于这条测试什么都没做 —— 宁可在这里红，别悄悄绿
        assertTrue("找不到 ${f.path}（测试要在 pc/ 目录下跑）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /** 服务端发出的全部 SSE 事件名：publish("x" 的字面量 + sse() 里那两声心跳。
     *  扫**整个服务端源码目录**而不是单个 Server.kt —— S5 按域拆分后 publish 散在
     *  Server*.kt 各页里，只盯一个文件会把"发了"的名单读少，制造假红。 */
    private fun sentEvents(): Set<String> {
        val dir = File("src/main/kotlin/com/haoai/pc")
        assertTrue("找不到服务端源码目录 " + dir.path, dir.isDirectory)
        val files = dir.listFiles { f -> f.isFile && f.extension == "kt" }
        assertTrue("服务端一个 .kt 都没有", files != null && files.isNotEmpty())
        val pub = mutableSetOf<String>()
        val beat = mutableSetOf<String>()
        for (f in files) {
            val src = f.readText(Charsets.UTF_8)
            pub += Regex("publish\\(\\s*\"([a-z]+)\"").findAll(src).map { it.groupValues[1] }
            beat += Regex("write\\(\\s*os,\\s*\"([a-z]+)\"").findAll(src).map { it.groupValues[1] }
        }
        assertTrue("服务端一个 publish(\"…\") 都没抓到 —— 正则或文件布局变了，先修这条测试", pub.isNotEmpty())
        return pub + beat
    }

    /** 前端订阅的全部事件名：on('x' 封装 + es.addEventListener('x' 直挂。 */
    private fun subscribed(): Set<String> {
        val src = read(ui)
        val via = Regex("on\\('([a-z]+)'").findAll(src).map { it.groupValues[1] }.toSet()
        // 只认 es.addEventListener（SSE 实例直挂）—— 裸抓会把 scroll/click/paste 这些
        // DOM 事件算成 SSE 订阅，第一次就是这么红的
        val raw = Regex("es\\.addEventListener\\('([a-z]+)'").findAll(src).map { it.groupValues[1] }.toSet()
        assertTrue("index.html 里一个 on('…') 都没抓到 —— 正则或文件布局变了，先修这条测试", via.isNotEmpty())
        return via + raw
    }

    @Test
    fun `the slash command table and the frontend CMDS stay in sync`() {
        // 前端清单：只截 CMDS=[…] 那一块再抓 a:'…'，别在全页上裸搜（会把将来的巧合算进来）
        val src = read(ui)
        assertTrue("index.html 里找不到 const CMDS=[ —— 命令面板的结构变了，先修这条测试", src.contains("const CMDS=["))
        val block = src.substringAfter("const CMDS=[").substringBefore("];")
        val front = Regex("a:'(\\w+)'").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(
            "内置命令两份清单漂了。服务端 BUILTIN_CMDS（技能重名闸 + taken 下发）与 " +
                "index.html 的 CMDS 表必须逐条一致 —— 谁多谁少都算漂（漂过的实锤：compact）",
            BUILTIN_CMDS, front
        )
    }

    @Test
    fun `every command the panel shows has a real handler`() {
        // 面板上摆出来却 switch 里没有 case 的命令 = 点了什么都不发生
        val src = read(ui)
        val block = src.substringAfter("switch(hit.a){").substringBefore("return true;")
        val cases = Regex("case '(\\w+)'").findAll(block).map { it.groupValues[1] }.toSet()
        assertTrue("没抓到 runCommand 的 switch —— 结构变了，先修这条测试", cases.isNotEmpty())
        val shown = src.substringAfter("const CMDS=[").substringBefore("];")
            .let { Regex("a:'(\\w+)'").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        assertEquals("CMDS 里列了但 switch 没有 case 的命令（点了没反应）", emptySet<String>(), shown - cases)
        assertEquals("switch 里有 case 但 CMDS 没列的命令（面板上找不到入口）", emptySet<String>(), cases - shown)
    }

    @Test
    fun `sse event names line up on both sides`() {
        val sent = sentEvents()
        val sub = subscribed()
        // hello/ping 是握手与保活：连接本身即语义，EventSource 不需要监听它们
        val heartbeat = setOf("hello", "ping")
        assertEquals(
            "前端订了但服务端从不发的事件（点了没人应的订阅，#111 同族病）",
            emptySet<String>(), sub - sent
        )
        assertEquals(
            "服务端发了但前端没订的事件（发了没人听；心跳 hello/ping 除外）",
            emptySet<String>(), (sent - heartbeat) - sub
        )
    }

    @Test
    fun `unread bumps only hang on subscribed events`() {
        // BUMP 里的名字要是没被 on('…') 订阅，那个事件的未读数永远不会涨
        val src = read(ui)
        val bump = src.substringAfter("const BUMP={").substringBefore("}")
        val keys = Regex("(\\w+):1").findAll(bump).map { it.groupValues[1] }.toSet()
        assertTrue("没抓到 BUMP 表 —— 结构变了，先修这条测试", keys.isNotEmpty())
        assertEquals(
            "BUMP 里挂了未读计数但前端没订阅的事件（那个事件永远不会让会话亮起来）",
            emptySet<String>(), keys - subscribed()
        )
    }
}
