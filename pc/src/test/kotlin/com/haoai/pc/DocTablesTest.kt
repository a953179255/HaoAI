package com.haoai.pc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文档里那些表格**能不能真的渲染成表**，只有数格子才知道。
 *
 * 为什么值得钉：ROADMAP 与 README 的表是"这批做到哪了"的唯一台账，而 markdown 表格少一格
 * 不会报任何错 —— 渲染出来那一格是空的，或者两格并成一格，读的人根本不知道自己看到的少了什么。
 * 这类毛病在这份文件里已经手工修过两次（B22 行拿全角 `｜` 当分隔符，"依赖"那格是空的；
 * 5.1 第 1 行少一个 `|`，"欠账"和"现状"并成了一格），两次都是**看的时候发现不了、数的时候才发现**。
 *
 * 判据读的是文件本身，不是某个渲染器：转义过的竖线（`\|`）不算分隔符（正文里写 `plan\|build` 是合法的）。
 */
class DocTablesTest {

    private val files = listOf("ROADMAP.md", "README.md", "../README.md")

    /** 一行的分隔符数量：`\|` 是正文里的竖线，不算。 */
    private fun bars(line: String): Int = line.replace("\\|", "").count { it == '|' }

    private fun tables(path: String): List<Pair<Int, List<String>>> {
        val f = File(path)
        assertTrue("找不到 $path（测试要在 pc/ 目录下跑）", f.isFile)
        val lines = f.readText(Charsets.UTF_8).split('\n')
        val out = mutableListOf<Pair<Int, List<String>>>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim().startsWith("|")) {
                var j = i
                while (j < lines.size && lines[j].trim().startsWith("|")) j += 1
                out += (i + 1) to lines.subList(i, j)
                i = j
            } else i += 1
        }
        return out
    }

    @Test
    fun `每张表的每一行格数都和表头一致`() {
        var seen = 0
        val bad = mutableListOf<String>()
        for (path in files) {
            for ((start, block) in tables(path)) {
                seen += 1
                val header = bars(block.first())
                // 表头后面必须紧跟分隔行，否则整块根本不成表（渲染器就是按这条判的）
                if (block.size < 2 || !Regex("""^\|[\s:|-]+\|$""").matches(block[1].trim())) {
                    bad += "$path:$start 表头后面没有 `|---|` 分隔行，这块不会渲染成表"
                    continue
                }
                if (bars(block[1]) != header) {
                    bad += "$path:$start 分隔行 ${bars(block[1])} 格，表头 ${header} 格"
                }
                block.drop(2).forEachIndexed { k, line ->
                    if (bars(line) != header) {
                        val why = if (line.contains('｜') && bars(line) < header)
                            "（这行里有全角 `｜`，多半是它被当成了分隔符）" else ""
                        bad += "$path:${start + k + 2} 这行 ${bars(line)} 格，表头 ${header} 格$why：" +
                            line.take(36)
                    }
                }
            }
        }
        // 量具自己会坏：一张表都没找到＝这条判据什么都没做
        assertTrue("三份文档里一张表都没扫到，八成是路径或扫描规则漂了", seen >= 5)
        assertTrue("表格格数不一致（少一格不会报错，只会让人看到空格外）：\n" + bad.joinToString("\n"),
            bad.isEmpty())
    }

    /** 转义竖线不许被误当成格子边界（第 10 行那种 `plan\|build\|edit\|yolo` 是合法正文）。 */
    @Test
    fun `转义过的竖线算正文不算分隔符`() {
        val row = """| 一行 | `plan\|build\|edit\|yolo` |"""
        assertTrue("把转义竖线数成了分隔符（会制造假红）：${bars(row)}", bars(row) == 3)
    }
}
