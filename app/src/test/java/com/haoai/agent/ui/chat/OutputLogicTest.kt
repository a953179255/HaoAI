package com.haoai.agent.ui.chat

import com.haoai.agent.agent.engine.ToolRunState
import com.haoai.agent.ui.ChainStep
import com.haoai.agent.ui.ChatRow
import com.haoai.agent.ui.UiDiff
import com.haoai.agent.ui.UiFilePath
import com.haoai.agent.ui.UiTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批3b：产出汇总纯逻辑钉死测试。
 * 口径：只认成功的 write/edit（带 fileRef+diff）；read 不进；ERROR 写不进（虚账）；
 * 同文件多次写合并取末次；顺序新→旧。
 */
class OutputLogicTest {

    private fun uiTool(
        callId: String,
        name: String,
        rel: String,
        state: ToolRunState = ToolRunState.DONE,
        added: Int = 10,
        removed: Int = 2,
        isNew: Boolean = false
    ) = UiTool(
        callId = callId, name = name, brief = rel, state = state,
        fileRef = UiFilePath(rel, "/ws/$rel"),
        diff = if (name == "write" || name == "edit")
            UiDiff(path = rel, isNewFile = isNew, added = added, removed = removed, lines = emptyList())
        else null
    )

    private fun row(vararg tools: UiTool) = ChatRow(
        key = tools.firstOrNull()?.callId ?: "k",
        role = "assistant", text = "",
        tools = tools.toList(),
        chainSteps = tools.map { ChainStep.Tool(it) }
    )

    @Test
    fun `write 与 edit 产物入列且按时间新→旧`() {
        val entries = buildOutputEntries(
            listOf(row(uiTool("c1", "write", "site/index.html")), row(uiTool("c2", "edit", "app.py")))
        )
        assertEquals(listOf("app.py", "site/index.html"), entries.map { it.relPath })
        assertEquals("edit", entries[0].toolName)
        assertEquals("c2", entries[0].callId)
    }

    @Test
    fun `read 不进产出`() {
        val entries = buildOutputEntries(listOf(row(uiTool("c1", "read", "app.py"))))
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `失败的写不进产出`() {
        val entries = buildOutputEntries(
            listOf(row(uiTool("c1", "write", "app.py", state = ToolRunState.ERROR)))
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `同文件多次写合并取末次`() {
        val entries = buildOutputEntries(
            listOf(
                row(uiTool("c1", "write", "app.py", added = 100, removed = 0, isNew = true)),
                row(uiTool("c2", "edit", "app.py", added = 3, removed = 1))
            )
        )
        assertEquals(1, entries.size)
        assertEquals("c2", entries[0].callId)
        assertEquals(3, entries[0].added)
        assertEquals(false, entries[0].isNewFile)
    }

    @Test
    fun `路径归一后进列`() {
        val entries = buildOutputEntries(listOf(row(uiTool("c1", "write", "/src//main/App.kt"))))
        assertEquals(listOf("src/main/App.kt"), entries.map { it.relPath })
    }

    @Test
    fun `链上混排多工具各取所需`() {
        val entries = buildOutputEntries(
            listOf(row(
                uiTool("c1", "read", "a.py"),
                uiTool("c2", "write", "b.py"),
                uiTool("c3", "bash", "c.sh").let {
                    // bash 无 fileRef/diff —— 天然不进
                    it.copy(name = "bash", fileRef = null, diff = null)
                }
            ))
        )
        assertEquals(listOf("b.py"), entries.map { it.relPath })
    }

    @Test
    fun `空会话空清单`() {
        assertTrue(buildOutputEntries(emptyList()).isEmpty())
    }

    @Test
    fun `SAF 无 fileRef 回落快照 path`() {
        val t = uiTool("c1", "write", "site/index.html").copy(fileRef = null)
        val entries = buildOutputEntries(listOf(row(t)))
        assertEquals(listOf("site/index.html"), entries.map { it.relPath })
        assertEquals("c1", entries[0].callId)
    }
}
