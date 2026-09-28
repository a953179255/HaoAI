package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 条目记忆这一层的回归。
 *
 * 为什么单独有这份：这个文件的**对端在手机里**（`app/.../memory/MemoryBank.kt`），
 * 而手机端没有单测钉住格式 —— 也就是说"PC 写出去的文件手机读不读得回来"这件事，
 * 只有这边的测试能守。所以这里既测"我们自己的 round-trip"，也测"照手机端的形状读"。
 */
class MemoriesTest {

    private fun tmp(name: String = "MEMORY.md"): File =
        Files.createTempDirectory("haoai-mem").resolve(name).toFile()

    /** 手机端 MemoryBank 真实写出来的样子（含注释元数据、分节标题、一条手写的无注释行）。 */
    private val PHONE_SAMPLE = """
        # 长期记忆

        ## 偏好（2）
        - [c4a9e667 · 重要度4] 回答要先给结论再给依据 <!-- id:c4a9e667 imp:4 type:preference created:1758000000000 lastUsed:1758100000000 uses:7 uq:3 src:model -->
        - [1a2b3c4d · 重要度3] 提交信息写"为什么"而不是"改了什么" [git,提交]

        ## 事实（1）
        - [eeee1111 · 重要度5] 这个仓库用 gradle 9.6 构建，JDK 是 Temurin 25 <!-- id:eeee1111 imp:5 type:fact created:1757000000000 lastUsed:0 uses:0 -->

        ## 已归档（1）
        - [deadbeef · 重要度2] 旧的构建命令是 gradlew <!-- id:deadbeef imp:2 type:fact created:1700000000000 lastUsed:0 uses:0 sup:archived -->

        ## 其他
        - 这条是用户手写的、没有 id 的说明
    """.trimIndent()

    @Test
    fun `reads the shape the phone writes`() {
        val d = Memories.parse(PHONE_SAMPLE)
        assertEquals("四条带 id 的 + 一条手写的", 4, d.items.size)
        val pref = d.items.first { it.id == "c4a9e667" }
        assertEquals(4, pref.importance)
        assertEquals(Memories.TYPE_PREF, pref.type)
        assertEquals(1758000000000L, pref.createdAt)
        assertEquals(7, pref.useCount)
        assertEquals(3, pref.uniqQueries)
        assertEquals("model", pref.source)
        // 没有注释的那一行：type 跟着分节标题走，可见的 [标签] 当标签用
        val hand = d.items.first { it.id == "1a2b3c4d" }
        assertEquals(Memories.TYPE_PREF, hand.type)
        assertEquals(listOf("git", "提交"), hand.tags)
        // 已归档的那条不算活的
        assertFalse("sup:archived 的条目不能再进提示", d.items.first { it.id == "deadbeef" }.live)
    }

    @Test
    fun `round trip is stable and keeps every field`() {
        val d = Memories.parse(PHONE_SAMPLE)
        val again = Memories.parse(Memories.render(d))
        assertEquals("重画一遍条数不能变", d.items.size, again.items.size)
        for (it in d.items) {
            val b = again.items.first { x -> x.content == it.content }
            assertEquals(it.importance, b.importance)
            assertEquals(it.type, b.type)
            assertEquals(it.createdAt, b.createdAt)
            assertEquals(it.useCount, b.useCount)
            assertEquals(it.source, b.source)
            assertEquals(it.supersededBy, b.supersededBy)
            assertEquals(it.tags, b.tags)
        }
        // 再走一遍必须逐字节相同（幂等）：否则每次打开界面都在改用户的文件
        assertEquals(Memories.render(d), Memories.render(again))
    }

    @Test
    fun `lines we cannot parse are kept, not dropped`() {
        val d = Memories.parse(PHONE_SAMPLE)
        assertTrue("解析不了的行要留在 extras", d.extras.any { it.contains("没有 id 的说明") })
        assertTrue(Memories.render(d).contains("没有 id 的说明"))
    }

    @Test
    fun `adding the same thing twice raises importance instead of duplicating`() {
        val d = Memories.parse(PHONE_SAMPLE)
        val before = d.items.size
        val (item, added) = Memories.add(
            d, "回答要先给结论，再给依据。", importance = 5,
            type = Memories.TYPE_PREF
        )
        assertFalse("归一化后一样的内容不该再记一条", added)
        assertEquals(before, d.items.size)
        assertEquals("只把重要度抬上去", 5, item.importance)
    }

    @Test
    fun `content is flattened to one line and capped`() {
        val d = Memories.Doc()
        val (it, _) = Memories.add(d, "第一行\n第二行\t带  空格", type = Memories.TYPE_FACT)
        assertEquals("换行会把一条拆成两条，必须压平", "第一行 第二行 带 空格", it.content)
        val long = "字".repeat(600)
        val (it2, _) = Memories.add(d, long)
        assertEquals(Memories.MAX_CHARS, it2.content.length)
        // 写出去再读回来，仍然是一条
        val f = tmp()
        assertTrue(Memories.save(f, d))
        assertEquals(2, Memories.load(f).items.size)
    }

    @Test
    fun `forget is soft and keeps the record`() {
        val d = Memories.parse(PHONE_SAMPLE)
        val n = d.items.count { it.live }
        assertTrue(Memories.forget(d, "eeee1111"))
        assertEquals(n - 1, d.items.count { it.live })
        assertEquals("条目还在，只是被标记取代", 4, d.items.size)
        assertFalse(Memories.forget(d, "没有这个id"))
    }

    @Test
    fun `search ranks by overlap and importance, in chinese too`() {
        val d = Memories.parse(PHONE_SAMPLE)
        val hit = Memories.search(d, "这个仓库怎么构建", k = 3)
        assertTrue("中文要能按二元组匹配上：${hit.map { it.content }}",
            hit.firstOrNull()?.content?.contains("gradle") == true)
        assertEquals("搜不到就该是空：搜索框不是注入，不许拿无关条目糊上去",
            0, Memories.search(d, "完全无关的火星话", k = 3).size)
    }

    @Test
    fun `injection respects the k and character budgets`() {
        val d = Memories.Doc()
        repeat(30) { i ->
            Memories.add(d, "第 ${i} 条记忆：这一条要写得足够长才能考验注入预算，" +
                "所以后面再补一段话把它撑满。".repeat(3), importance = (i % 5) + 1)
        }
        val text = Memories.inject(d, "记忆", k = 8)
        val lines = text.lines().filter { it.startsWith("- ") }
        assertTrue("最多 8 条，实际 ${lines.size}", lines.size in 1..8)
        assertTrue("总字数要守住 1200，实际 ${text.length}", text.length <= Memories.INJECT_CAP + 200)
        assertTrue("单条要截到 180 字以内",
            lines.all { it.length <= Memories.PER_ITEM_CAP + 40 })
        assertTrue("空库什么都不注入", Memories.inject(Memories.Doc(), "随便").isEmpty())
    }

    @Test
    fun `archived and untrusted items never reach the prompt`() {
        val d = Memories.parse(PHONE_SAMPLE)
        val text = Memories.inject(d, "gradle 构建 命令", k = 8)
        assertFalse("已归档的那条不能出现：$text", text.contains("旧的构建命令"))
        d.items.first { it.id == "eeee1111" }.origin = "untrusted"
        assertFalse("来源不可信的不进提示",
            Memories.inject(d, "gradle 构建 命令", k = 8).contains("gradle 9.6"))
    }

    @Test
    fun `cap of 200 items is enforced by the caller reading count`() {
        val d = Memories.Doc()
        repeat(200) { i -> Memories.add(d, "第 $i 条各不相同的记忆内容", importance = 3) }
        assertEquals(200, d.items.count { it.live })
        assertNotNull(d.items.firstOrNull())
        // 上限的判定在接口层（要给用户回话），这里只保证数得对
        assertEquals(Memories.MAX_ITEMS, 200)
    }
}
