package com.haoai.agent.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `MEMORY.md` 的**行格式回归锁**。
 *
 * 为什么手机端也要有一份：这份文件是**两个端共用的**（电脑端 `pc/.../Memories.kt` 读写同一份、
 * 同一套行格式），而它一直是"只有口头约定、没有测试钉住"的状态。
 * 电脑端那边早就有"照手机端真实写出的样子读"的测试，但那份夹具是**手写**的样本 ——
 * 也就是说"手机真的写出来的是不是这个形状"这件事，此前两边都没测。
 * 这里干两件事：**用真实的 [MemoryBank] 写盘，再按电脑端解析器用的同一批正则去量**；
 * 以及钉住 `tidy()` 的形状 —— 两端可能指同一份文件，"整理"过谁、把谁合到了谁身上，
 * 必须两边都读得懂、都改得回来（详见 `MemoryBank.tidy()` 的注释）。
 *
 * 判据一律写成"期望的形状"而不是"非空"：这类共享契约上，"有个东西在"等于没测。
 */
class MemoryBankFormatTest {

    private fun bank(): MemoryBank {
        val dir = Files.createTempDirectory("haoai-memfmt").toFile()
        return MemoryBank(dir)
    }

    private fun File.lines(): List<String> = readLines().map { it.trimEnd() }

    /** 与电脑端 `Memories.LINE` 同一条正则（含那个 U+00B7 的间隔号）。 */
    private val lineRe = Regex("""^-\s*\[([0-9a-fA-F]{1,8})\s*·\s*重要度(\d)]\s*(.*)$""")

    /** 与电脑端 `Memories.META` 同一条：注释里的 `key:value`。 */
    private val metaRe = Regex("""([A-Za-z]+):(\S+)""")

    /** 一行条目 → 元数据表（键按电脑端一样小写；`lastUsed` 与 `lastused` 才是同一个东西）。 */
    private fun metaOf(line: String): Map<String, String> =
        metaRe.findAll(line.substringAfter("<!--")).associate {
            it.groupValues[1].lowercase() to it.groupValues[2]
        }

    @Test
    fun `a written line matches the shape the PC end parses`() {
        val b = bank()
        val m = b.remember("回答要先给结论再给依据", type = "preference", importance = 4, source = "model")
        assertNotNull("remember 没写进去", m)
        val raw = b.storageFile().lines()
        val line = raw.firstOrNull { it.contains("回答要先给结论再给依据") }
        assertNotNull("文件里找不到刚记的那条：\n" + raw.joinToString("\n"), line)
        val hit = lineRe.find(line!!)
        assertTrue("电脑端的行正则匹配不上手机写出的这一行：[$line]", hit != null)
        val meta = metaOf(line)
        for (k in listOf("id", "imp", "type", "created", "lastused", "uses"))
            assertTrue("元数据缺 $k：$meta", meta.containsKey(k))
        assertEquals("4", meta["imp"])
        assertEquals("preference", meta["type"])
        assertEquals("model", meta["src"])
        assertEquals("注释里的 id 必须与可见的那段一致（电脑端以注释为准）", m!!.id, meta["id"])
    }

    @Test
    fun `section headers are the four the PC end maps back to types`() {
        val b = bank()
        b.remember("偏好：先结论后依据", type = "preference")
        b.remember("决定：这轮不改引擎", type = "decision")
        b.remember("事件：09-28 装了新版", type = "event")
        b.remember("事实：仓库用 gradle 9.6", type = "fact")
        val heads = b.storageFile().lines().filter { it.startsWith("## ") }
        // 电脑端认的是"标签（条数）"这种形状，标签文字必须一字不差
        for (label in listOf("偏好", "决定", "事件", "事实"))
            assertTrue("缺分节标题『$label』：$heads", heads.any { it.startsWith("## $label（") })
    }

    @Test
    fun `the file explains itself before the first section`() {
        // 这两行说明是给**人**看的（也是电脑端必须原样带回去的东西）：
        // 共用文件的一方如果只按"- 条目"重建整份文件，这些散文会凭空消失。
        val b = bank()
        b.remember("一条事实", type = "fact")
        val raw = b.storageFile().readText()
        assertTrue("标题行没了：\n${raw.take(120)}", raw.lineSequence().any { it.startsWith("# ") })
        assertTrue("表头说明（> 开头）没了：\n${raw.take(240)}", raw.lineSequence().any { it.startsWith("> ") })
    }

    @Test
    fun `injection writes the counters and the recall flag the PC end reads`() {
        val b = bank()
        val m = b.remember("构建用 gradle 9.6 加 Temurin 25", type = "fact", importance = 5)
        b.markInjected(listOf(m!!.id), "怎么构建")
        val line = b.storageFile().lines().first { it.contains("gradle 9.6") }
        val meta = metaOf(line)
        assertEquals("注入过一次就该记一次", "1", meta["uses"])
        assertNotNull("`uq` 没写出来（电脑端读它算新查询数）", meta["uq"])
        assertEquals("防召回环的标记要落在文件里", "1", meta["rc"])
    }

    @Test
    fun `a superseded item stays readable and moves to the archived section`() {
        val b = bank()
        val old = b.remember("旧的构建命令是 gradlew", type = "fact", importance = 2)
        val neu = b.remember("现在用 gradle 9.6", type = "fact", importance = 4)
        assertTrue(b.supersede(old!!.id, neu!!.id))
        val raw = b.storageFile().lines()
        assertTrue("该有已归档分节：${raw.filter { it.startsWith("## ") }}",
            raw.any { it.startsWith("## 已归档") })
        val line = raw.first { it.contains("旧的构建命令") }
        assertEquals("失效不删除：留痕要指向新的那条", neu.id,
            metaOf(line)["sup"])
    }

    @Test
    fun `writing one item never rewrites the bytes of the others`() {
        val b = bank()
        b.remember("第一条不动的内容", type = "preference", importance = 4)
        b.remember("第二条不动的内容", type = "event")
        val before = b.storageFile().lines().filter { lineRe.matches(it) }.toSet()
        assertEquals(2, before.size)
        b.remember("第三条是新加的", type = "fact")
        val after = b.storageFile().lines().filter { lineRe.matches(it) }
        assertEquals("条数只该多一条", 3, after.size)
        for (line in before)
            assertTrue("已有那条被改写了（元数据漂移会互相覆盖对方的计数）：\n$line\n$after",
                after.contains(line))
    }

    // ---- tidy()：两端共写一份文件时，"整理"该留痕而不是删掉 ----

    private fun newDir(): File = Files.createTempDirectory("haoai-memtidy").toFile()

    /** 把某一条的 created/lastUsed 改成 n 天前（模拟"很久没用"），返回改后的行文本。 */
    private fun ageOut(file: File, id: String, days: Int): String {
        val old = System.currentTimeMillis() - days * 86_400_000L
        val lines = file.lines().map {
            if (it.contains("id:$id "))
                it.replace(Regex("created:\\d+"), "created:$old")
                    .replace(Regex("lastUsed:\\d+"), "lastUsed:$old")
            else it
        }
        file.writeText(lines.joinToString("\n") + "\n")
        return lines.first { it.contains("id:$id ") }
    }

    private fun sectionOf(file: File, id: String): String {
        var head = "（文件开头）"
        for (l in file.lines()) {
            if (l.startsWith("## ")) head = l
            else if (l.contains("id:$id ")) return head
        }
        return "（找不到 $id 那一行）"
    }

    @Test
    fun `tidy merges by leaving a trace instead of dropping the line`() {
        val dir = newDir()
        val b = MemoryBank(dir)
        val older = b.remember("run the gradle build task with jdk", type = "fact", importance = 3)!!
        val keeper = b.remember("run the gradle build task with jdk now", type = "fact", importance = 5)!!
        assertEquals("整理该动一条", 1, b.tidy())
        val f = b.storageFile()
        val raw = f.lines()
        val goneLine = raw.firstOrNull { it.contains("id:${older.id} ") }
        assertTrue("被合掉的那条整行没了（留痕才是可回滚的整理）：\n${raw.joinToString("\n")}",
            goneLine != null)
        assertEquals("库里活着的应该只剩一条", 1, b.activeCount())
        assertEquals("留痕要指向保留那条的 id", keeper.id, metaOf(goneLine!!)["sup"])
        assertTrue("被合掉那条该归进已归档节：${sectionOf(f, older.id)}",
            sectionOf(f, older.id).startsWith("## 已归档"))
        assertTrue("保留那条不该被动到：${metaOf(raw.first { it.contains("id:${keeper.id} ") })}",
            metaOf(raw.first { it.contains("id:${keeper.id} ") })["sup"] == null)
    }

    /**
     * 改这条的真实理由：旧实现没有"已失效的不参与合重"这一步，于是
     * **重要度更高的那条 dormant 会当上保留者，把还活着的那条物理删掉** ——
     * 一次整理吃掉一条活跃记忆，而它自己早就在归档里了。
     */
    @Test
    fun `a degraded item never eats a live one`() {
        val dir = newDir()
        var b = MemoryBank(dir)
        val stale = b.remember("run the gradle build task with jdk", type = "fact", importance = 2)!!
        b.remember("run the gradle build task with jdk now", type = "fact", importance = 1)
        ageOut(b.storageFile(), stale.id, 40)
        b = MemoryBank(dir)                       // 重新读盘，按改过的时间戳算
        b.tidy()
        val f = b.storageFile()
        val staleLine = f.lines().first { it.contains("id:${stale.id} ") }
        val freshLine = f.lines().first { it.contains("jdk now") }
        assertEquals("久没用的那条应该降级成 dormant", "dormant", metaOf(staleLine)["sup"])
        assertTrue("活着的这条被整理吃掉了（旧实现就是这么丢数据）：\n$freshLine",
            metaOf(freshLine)["sup"] == null)
        assertEquals("只剩活的那条", 1, b.activeCount())
    }

    @Test
    fun `tidy converges - the second pass leaves the file alone`() {
        val dir = newDir()
        val b = MemoryBank(dir)
        b.remember("run the gradle build task with jdk", type = "fact", importance = 3)
        b.remember("run the gradle build task with jdk now", type = "fact", importance = 5)
        b.tidy()
        val once = b.storageFile().readText()
        assertEquals("第二次整理不该再动东西（合并没有收敛就会天天改盘）", 0, b.tidy())
        assertEquals("第二次写盘改了字节（幂等性破了）", once, b.storageFile().readText())
    }

    @Test
    fun `the trace the phone writes is readable by the PC end`() {
        val dir = newDir()
        val b = MemoryBank(dir)
        val gone = b.remember("run the gradle build task with jdk", type = "fact", importance = 3)!!
        val keeper = b.remember("run the gradle build task with jdk now", type = "fact", importance = 5)!!
        b.tidy()
        val line = b.storageFile().lines().first { it.contains("id:${gone.id} ") }
        assertTrue("电脑端的行正则匹配不上：[$line]", lineRe.matches(line))
        val meta = metaOf(line)
        assertEquals("sup 要指向保留那条", keeper.id, meta["sup"])
        assertTrue("id 形状两边要一致（电脑端按 1~8 位十六进制解析）：${meta["id"]}",
            Regex("^[0-9a-fA-F]{1,8}$").matches(meta["id"] ?: ""))
        assertNotNull("没写 upd 的话，电脑端清理时会拿 created 当基准，刚合掉的那条会被当场删掉",
            meta["upd"])
        assertTrue("upd 必须是合并时刻（不是出生时刻）",
            (meta["upd"]!!.toLong()) >= (meta["created"]!!.toLong()))
    }
}
