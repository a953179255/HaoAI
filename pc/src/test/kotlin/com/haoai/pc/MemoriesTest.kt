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

    /* ---- 使用反馈与整理（v0.69.0）---- */

    private val DAY = 86_400_000L

    /** 指纹表按"文件路径 + 条目 id"分桶，所以每份测试自己造一个不重复的 key。 */
    private fun freshDoc(name: String) = Memories.Doc(key = "/tmp/haoai-$name")

    @Test
    fun `metadata we do not understand survives our rewrite`() {
        // 手机端会写 rc:1（被注入过，防它把召回来的老事实再提取一遍）。
        // 这边不认识这个键 —— 但共用一个文件的人没有"读不懂就可以扔"的权力。
        val text = PHONE_SAMPLE.replace(
            "uses:7 uq:3 src:model", "uses:7 uq:3 rc:1 src:model zz:future"
        )
        val out = Memories.render(Memories.parse(text))
        assertTrue("未知的元数据键要原样带回去：$out", out.contains("rc:1") && out.contains("zz:future"))
        assertEquals("带着未知键也要幂等", out, Memories.render(Memories.parse(out)))
    }

    @Test
    fun `use feedback records each item at most once an hour`() {
        val d = freshDoc("hour")
        val (it, _) = Memories.add(d, "构建用 gradle 9.6", importance = 4)
        val t0 = System.currentTimeMillis()
        assertTrue(Memories.markUsed(d, listOf(it.id), "gradle 怎么构建", t0))
        assertEquals(1, it.useCount)
        assertTrue("同一条的 rc 标记要打上（手机端读它）", it.rawMeta.any { p -> p.first == "rc" })
        assertFalse("一小时内再注入一次不该再计数",
            Memories.markUsed(d, listOf(it.id), "gradle 怎么构建", t0 + 60_000))
        assertEquals(1, it.useCount)
        assertTrue(Memories.markUsed(d, listOf(it.id), "gradle 怎么构建", t0 + 2 * 3_600_000L))
        assertEquals(2, it.useCount)
        assertEquals("一小时限流管的是次数，不是最后使用时间", t0 + 2 * 3_600_000L, it.lastUsedAt)
    }

    @Test
    fun `the same question asked again does not inflate the unique query count`() {
        val d = freshDoc("uq")
        val (it, _) = Memories.add(d, "回答先给结论", importance = 4)
        val t0 = System.currentTimeMillis()
        Memories.markUsed(d, listOf(it.id), "结论呢", t0)
        Memories.markUsed(d, listOf(it.id), "结论呢", t0 + 2 * 3_600_000L)
        assertEquals("同一句话问两遍算 1 个查询", 1, it.uniqQueries)
        Memories.markUsed(d, listOf(it.id), "先把结论说出来好吗", t0 + 3 * 3_600_000L)
        assertEquals("换个说法才算新的", 2, it.uniqQueries)
        val before = it.uniqQueries
        Memories.markUsed(d, listOf(it.id), "   ", t0 + 9 * 3_600_000L)
        assertEquals("空查询没有指纹可言，不该加次数", before, it.uniqQueries)
    }

    @Test
    fun `writing usage back reads the file again instead of overwriting the other end`() {
        val f = tmp()
        val d = Memories.Doc(key = f.absolutePath)
        val (mine, _) = Memories.add(d, "这个项目用 Temurin 25", importance = 4)
        assertTrue(Memories.save(f, d))
        // 与此同时手机端往同一份文件里加了一条（PC 内存里那份是旧的）
        val phoneLine = "- [abcd1234 · 重要度5] 手机端新记的一条 <!-- id:abcd1234 imp:5 type:fact " +
            "created:1758000000000 lastUsed:0 uses:0 -->"
        f.writeText(f.readText().trimEnd() + "\n" + phoneLine + "\n")
        assertTrue(Memories.bumpUsage(f, listOf(mine.id), "JDK 用哪个"))
        val after = Memories.load(f)
        assertEquals("回写不能把手机端那条抹掉", 2, after.items.size)
        assertNotNull(after.items.firstOrNull { it.id == "abcd1234" })
        assertEquals(1, after.items.first { it.id == mine.id }.useCount)
    }

    @Test
    fun `tidy degrades stale low-importance items but keeps them readable`() {
        val d = freshDoc("degrade")
        val now = System.currentTimeMillis()
        val (old, _) = Memories.add(d, "很久以前顺手记下的一条", importance = 2)
        old.createdAt = now - 40 * DAY
        val (hot, _) = Memories.add(d, "常用的一条", importance = 4)
        hot.createdAt = now - 40 * DAY
        val t = Memories.tidy(d, now)
        assertEquals(listOf(old.id), t.degraded.map { it.id })
        assertEquals(Memories.DORMANT, old.supersededBy)
        assertTrue("降级不是删除：条目还在文件里", d.items.any { it.id == old.id })
        assertTrue("重要度高的不动", hot.live)
        assertFalse("降级的不再进提示", Memories.inject(d, "一条", k = 8).contains("很久以前"))
    }

    @Test
    fun `tidy merges near duplicates by pointing at the keeper`() {
        val now = System.currentTimeMillis()
        fun line(id: String, imp: Int, text: String, created: Long) =
            "- [$id · 重要度$imp] $text <!-- id:$id imp:$imp type:fact created:$created lastUsed:0 uses:0 -->"
        // 从文件里读，而不是用 add() 造：add() 自己就会把归一化相同的两条合成一条，
        // 而真实场景是"手机端早先记下过一条措辞略不同的"，那才是 tidy 要收拾的现场。
        val d = Memories.parse(listOf(
            "# 长期记忆", "", "## 事实（4）",
            line("aaaa1111", 5, "这个仓库用 gradle 9.6 构建", now),
            line("bbbb2222", 2, "这个仓库，用 gradle 9.6 构建。", now - DAY),
            line("cccc3333", 4, "run the gradle build task with jdk", now - 2 * DAY),
            line("dddd4444", 1, "run the gradle build task with jdk now", now - 3 * DAY)
        ).joinToString("\n"), "/tmp/haoai-merge")
        val t = Memories.tidy(d, now)
        assertEquals("留重要度高的那条", setOf("bbbb2222", "dddd4444"), t.merged.map { it.id }.toSet())
        assertEquals("aaaa1111", d.items.first { it.id == "bbbb2222" }.supersededBy)
        assertEquals("cccc3333", d.items.first { it.id == "dddd4444" }.supersededBy)
        assertTrue("四条都还在文件里：误合了能改回来", d.items.size == 4)
        assertEquals(2, d.active.size)
    }

    @Test
    fun `tidy purges only what has been dead for a month`() {
        val d = freshDoc("purge")
        val now = System.currentTimeMillis()
        val (gone, _) = Memories.add(d, "早就忘掉的一条", importance = 3)
        Memories.forget(d, gone.id)
        d.items.first { it.id == gone.id }.updatedAt = now - 31 * DAY
        val (recent, _) = Memories.add(d, "上礼拜刚忘掉的一条", importance = 3)
        Memories.forget(d, recent.id)
        d.items.first { it.id == recent.id }.updatedAt = now - 10 * DAY
        val t = Memories.tidy(d, now)
        assertEquals(listOf(gone.id), t.purged.map { it.id })
        assertTrue("不满 30 天的还留着供回看", d.items.any { it.id == recent.id })
    }

    @Test
    fun `tidy on a healthy library promises to do nothing`() {
        // 界面第一次点击只是预览，"0 条"是它要能显示出来的一句话，不是空响应
        val d = freshDoc("clean")
        val now = System.currentTimeMillis()
        Memories.add(d, "一条正常在用的记忆", importance = 4).first.createdAt = now
        assertEquals(0, Memories.tidy(d, now).changed)
    }
}
