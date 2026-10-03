package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 随包内置专家库（`src/main/resources/expert-library.json`）的判据。
 *
 * 为什么值得单独钉一份：这个文件是"开箱就有八张能用的卡"的唯一来源，而它坏了的表现
 * 特别隐蔽 —— 前端读 `/api/experts/library`，解析不出来就 `items:[]`，
 * 页面上显示的是"（没有匹配的内置专家）"，看起来像这个库本来就该是空的。
 * 在这批之前，JVM 侧没有一条判据真正打开过这个文件。
 *
 * 三段主张：
 * - **每张卡都得有欢迎语与富快捷提问**：内置卡是用户第一次接触"专家"这件事的样子，
 *   启用一张却没有欢迎语 = 补齐的那批字段只在自建卡上生效，看着像没做。
 * - **按钮上的字与发出去的那句必须分开**：`title` 短到能放进一颗按钮，
 *   `prompt` 才是模型看到的那句（两者一样长，就是把一整段话挂在按钮上）。
 * - **库的形状要能被同一份解析器原样吃回来**：`/api/experts/library` 是把这份文件
 *   整块回给前端的，前端「启用」再把表单字段 POST 回 /api/presets（op=save）。
 *   这里走 `Presets.importJson`（与 save 同一套字段读法）过一遍，库里的字段名一旦
 *   和解析器对不上就会红；真点「启用」那条路归像素剧本 ui-xpcards 管。
 */
class ExpertLibraryTest {

    private val root: JsonObject = Json.parseToJsonElement(
        File("src/main/resources/expert-library.json").readText()
    ).jsonObject
    private val items: List<JsonObject> = root["items"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `the library parses and every card carries the display fields`() {
        assertTrue("内置库不该少于 8 张：" + items.size, items.size >= 8)
        val keys = items.map { it["key"]!!.jsonPrimitive.content }
        assertEquals("key 不许重复：" + keys, keys.size, keys.toSet().size)
        for (c in items) {
            val k = c["key"]!!.jsonPrimitive.content
            for (f in listOf("name", "icon", "color", "desc", "persona"))
                assertTrue("$k 缺 $f", (c[f]?.jsonPrimitive?.content ?: "").isNotBlank())
            assertTrue("$k 的颜色要能直接当背景用：" + c["color"],
                Regex("^#[0-9a-fA-F]{6}$").matches(c["color"]!!.jsonPrimitive.content))
            assertTrue("$k 的人设短到不像一段规矩（${c["persona"]!!.jsonPrimitive.content.length} 字）",
                c["persona"]!!.jsonPrimitive.content.length >= 60)
            val mode = c["mode"]?.jsonPrimitive?.content ?: ""
            assertTrue("$k 的档位只能是空或三档之一：" + mode, mode.isEmpty() || mode in Presets.MODES)
        }
    }

    @Test
    fun `every built-in card has a welcome line that fits the form`() {
        for (c in items) {
            val k = c["key"]!!.jsonPrimitive.content
            val w = c["welcome"]?.jsonPrimitive?.content ?: ""
            assertTrue("$k 没有欢迎语：启用之后新会话第一屏是空白的", w.isNotBlank())
            // 表单那格是 maxlength=120：库里写超了，启用后一编辑就会被截，
            // 而截掉的那半句用户看不见
            assertTrue("$k 的欢迎语 ${w.length} 字，超了表单的 120", w.length <= 120)
        }
    }

    @Test
    fun `quick prompts separate the button label from the sentence sent`() {
        for (c in items) {
            val k = c["key"]!!.jsonPrimitive.content
            val qs = c["quick"]!!.jsonArray
            assertTrue("$k 的快捷提问该有 2~3 条：" + qs.size, qs.size in 2..3)
            for (el in qs) {
                val q = el.jsonObject
                val title = q["title"]?.jsonPrimitive?.content ?: ""
                val prompt = q["prompt"]?.jsonPrimitive?.content ?: ""
                val desc = q["desc"]?.jsonPrimitive?.content ?: ""
                assertTrue("$k 有条快捷提问没标题", title.isNotBlank())
                assertTrue("$k 的按钮标题 ${title.length} 字，太长（上限 40）：$title", title.length <= 40)
                assertTrue("$k 的「$title」没有要发出去的那句", prompt.isNotBlank())
                assertTrue("$k 的「$title」正文 ${prompt.length} 字，超了服务端的 600", prompt.length <= 600)
                assertTrue("$k 的「$title」标题与正文一模一样 —— 那还分两栏干什么", title != prompt)
                assertTrue("$k 的「$title」说明 ${desc.length} 字，超了 80", desc.length <= 80)
            }
        }
    }

    @Test
    fun `card temperatures are legal or explicitly left to global`() {
        var followsGlobal = 0
        for (c in items) {
            val k = c["key"]!!.jsonPrimitive.content
            val t = c["temperature"]?.jsonPrimitive?.content?.toDoubleOrNull()
                ?: Preset.NO_OVERRIDE.toDouble()
            if (t == Preset.NO_OVERRIDE.toDouble()) { followsGlobal++; continue }
            assertTrue("$k 的温度 $t 不在 0..2（-1 才是不覆盖）", t in 0.0..2.0)
        }
        // 至少一张卡不管温度：否则"跟全局"这条路在内置库里从没被走过，
        // 哨兵值 -1 是不是被正确读出来就没人验过了
        assertTrue("内置库该留一张不设温度的卡来证明 -1 这条路走得通", followsGlobal >= 1)
    }

    @Test
    fun `a built-in card survives the enable round trip through the presets store`() {
        val home = java.nio.file.Files.createTempDirectory("haoai-lib").toFile()
        try {
            System.setProperty("haoai.home", File(home, "h").absolutePath)
            for (c in items) {
                val key = c["key"]!!.jsonPrimitive.content
                // 库里的 key 不是卡 id：启用/导入都会换一个新 id（同一张内置卡
                // 启用两次不该互相覆盖）。这里把 key 挪成 id 只为让 parse 认这张卡。
                val asCard = JsonObject(c.toMutableMap().apply {
                    remove("key"); put("id", JsonPrimitive(key))
                })
                val (p, err) = Presets.importJson(asCard.toString())
                assertNull("$key 导入失败：" + err, err)
                val card = p!!
                // 启用时 key 变成卡上的 id，名字是给人看的那个（不是 key）。
                // 这两条一开始写成 assertEquals(key, card.name)，红在测试自己身上。
                assertTrue("$key 启用后没拿到新 id：" + card.id, card.id.isNotBlank() && card.id != key)
                assertEquals("$key 的名字没原样带过来", c["name"]!!.jsonPrimitive.content, card.name)
                assertEquals(c["welcome"]!!.jsonPrimitive.content, card.welcome)
                assertEquals(c["persona"]!!.jsonPrimitive.content, card.persona)
                assertEquals((c["quick"]!!.jsonArray).size, card.quick.size)
                assertEquals(c["quick"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content,
                    card.quick[0].title)
                assertEquals(c["quick"]!!.jsonArray[0].jsonObject["prompt"]!!.jsonPrimitive.content,
                    card.quick[0].text())
                val t = c["temperature"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: Preset.NO_OVERRIDE.toDouble()
                assertEquals(t, card.temperature, 1e-9)
                assertTrue(card.enabled)
            }
        } finally {
            home.deleteRecursively()
        }
    }
}
