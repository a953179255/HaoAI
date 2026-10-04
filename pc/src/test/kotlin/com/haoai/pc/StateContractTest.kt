package com.haoai.pc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * /api/state 的字段对齐：**前端 applyState 读的每个字段，stateJson 都必须真的发**。
 *
 * 这是一条"一处改动必须碰两处"的横切缝，撞过的实锤：`preset` 在数据层一直有、
 * 接口忘了报，前端拿到"带角色的空会话"却不知道角色是谁，欢迎语永远画不出来
 * （字段在、接口不报，等于界面上没有）。以前靠人肉对齐，现在静态钉住。
 *
 * 只钉"读 ⊆ 发"这一个方向：新字段先发后读是正常的演进顺序（服务端多发的字段
 * 前端暂时不读不算错）。正则做的是**近似**：stateJson 是 StringBuilder 拼的，
 * 键形如 `\"todos\":[`（数组值）或 `\"mode\":\"`（字符串值），两种收法都要认。
 */
class StateContractTest {

    private val server = File("src/main/kotlin/com/haoai/pc/Server.kt")
    private val ui = File("src/main/resources/ui/index.html")

    private fun read(f: File): String {
        assertTrue("找不到 ${f.path}（测试要在 pc/ 目录下跑）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `applyState reads only fields stateJson actually emits`() {
        val src = read(server)
        val from = src.indexOf("internal fun stateJson")
        assertTrue("Server.kt 里找不到 stateJson（函数挪了名字就先修这条测试）", from >= 0)
        val to = src.indexOf("internal fun bodyOf", from)
        assertTrue("stateJson 的收尾锚点（bodyOf）找不到了——函数边界变了，先修这条测试", to > from)
        val body = src.substring(from, to)

        // 键后面跟冒号（对象值）或方括号（数组值）；\"mode\": 这种转义闭引号也要认
        val emitted = Regex("\"([a-zA-Z_]+)\\\\?\"\\s*[:\\[]").findAll(body).map { it.groupValues[1] }.toSet()
        assertTrue("stateJson 里一个键都没抓到（拼接方式变了，正则要跟着重写）", emitted.size >= 15)

        val html = read(ui)
        val aFrom = html.indexOf("function applyState")
        assertTrue("index.html 里找不到 applyState", aFrom >= 0)
        val aTo = html.indexOf("/* ==== [S6 面板对象] Cfg", aFrom)
        assertTrue("applyState 的收尾锚点（Cfg 面板头）找不到了——函数边界变了，先修这条测试", aTo > aFrom)
        val reads = Regex("\\bs\\.([a-zA-Z_]+)").findAll(html.substring(aFrom, aTo))
            .map { it.groupValues[1] }.toSet()

        // 这几个是数组方法撞上的（(s.messages||[]).forEach 之类），不是字段读取
        val methods = setOf("forEach", "map", "splice", "findIndex", "filter", "split", "trim", "indexOf", "length")
        val unknown = (reads - emitted - methods).sorted()
        assertTrue(
            "applyState 读了 stateJson 不发的字段：$unknown —— 要么服务端补发，要么前端别读（读 undefined = 界面停在旧值）",
            unknown.isEmpty())
    }
}
