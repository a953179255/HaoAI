package com.haoai.pc

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具参数 schema 只许有一种形状（S8：统一走 Tools.kt 的 `schema(...)` 帮助器）。
 *
 * 以前 14 处用帮助器、5 个文件（desktop/browser/git/shell_* /run_verify）各自手搭
 * `buildJsonObject` —— 产出逐字节相同，纯粹是两套写法；手搭那份还少了一道"形状走样"
 * 的防线。这条判据把 builtinTools() 全量过一遍：type=object、每个属性恰好
 * `{type}`、required ⊆ properties、type ∈ 已知集合 —— 谁哪天又开始手搭，
 * 或者把 description/enum 悄悄塞进来（要么都加、要么别加），这里当场红。
 *
 * MCP 工具不在此列：它们的 schema 来自外部服务器，嵌套与描述是人家的地盘。
 */
class ToolSchemaTest {

    private val KNOWN_TYPES = setOf("string", "integer", "number", "boolean", "array", "object")

    @Test
    fun `every builtin tool speaks the one schema shape`() {
        val tools = builtinTools()
        assertTrue("一把 builtin 工具都没抓到 —— 正则/名字变了先修这条", tools.size >= 20)
        for (t in tools) {
            val p = t.params
            assertEquals("${t.name} 的 params 顶层必须是 type=object", "object", p["type"]?.jsonPrimitive?.content)
            val props = p["properties"]?.jsonObject
                ?: error("${t.name} 缺 properties —— 不是 schema(...) 帮助器的产物")
            for ((key, v) in props) {
                val o = v.jsonObject
                assertEquals(
                    "${t.name}.$key 的属性恰好只有 type 一个键（要 description/enum 就给帮助器加上、全员一起加）",
                    setOf("type"), o.keys
                )
                val ty = o["type"]?.jsonPrimitive?.content
                assertTrue("${t.name}.$key 的 type 不认识：$ty", ty in KNOWN_TYPES)
            }
            p["required"]?.jsonArray?.forEach { r ->
                val k = r.jsonPrimitive.content
                assertTrue("${t.name} 的 required 里有 properties 没有的键：$k", props.keys.contains(k))
            }
        }
    }
}
