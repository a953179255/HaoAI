package com.haoai.pc

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具参数 schema 的形状判据（S8 定，B22 按两端同款放宽一轮）。
 *
 * 以前 14 处用帮助器、5 个文件各自手搭 `buildJsonObject` —— 产出逐字节相同，
 * 纯粹是两套写法；手搭那份还少了一道"形状走样"的防线。这条判据把 builtinTools()
 * 全量过一遍：type=object、属性键 ∈ {type, description, items}、required ⊆
 * properties、type ∈ 已知集合。
 *
 * **B22 放宽的理由**：`ask_user` 的 options 是 {label,description} 对象数组 ——
 * 与手机端 schema 逐字节同款才叫同一个工具（富度不一致 = 模型在两端拿到不同
 * 的契约）。所以属性允许 `description`，数组允许 `items`；`items` 只许是对象
 * 数组（属性同样只能 {type}、required ⊆ 属性）。纯 {type} 的老规矩对其余
 * builtin 仍然隐性成立 —— 谁要给标量加 description，先问一遍"两端都要不要"。
 *
 * MCP 工具不在此列：它们的 schema 来自外部服务器，嵌套与描述是人家的地盘。
 */
class ToolSchemaTest {

    private val KNOWN_TYPES = setOf("string", "integer", "number", "boolean", "array", "object")
    private val PROP_KEYS = setOf("type", "description", "items")

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
                assertTrue(
                    "${t.name}.$key 的属性键超范围：${o.keys}（只许 type/description/items）",
                    o.keys.all { it in PROP_KEYS }
                )
                val ty = o["type"]?.jsonPrimitive?.content
                assertTrue("${t.name}.$key 的 type 不认识：$ty", ty in KNOWN_TYPES)
                // items 只许是对象数组：label 必填、子属性同规则、required ⊆ 子属性
                o["items"]?.let { ito ->
                    val io = ito.jsonObject
                    assertEquals("${t.name}.$key.items 必须是 type=object", "object", io["type"]?.jsonPrimitive?.content)
                    val ip = io["properties"]?.jsonObject ?: error("${t.name}.$key.items 缺 properties")
                    for ((ik, iv) in ip) {
                        assertTrue(
                            "${t.name}.$key.items.$ik 的属性键超范围：${iv.jsonObject.keys}",
                            iv.jsonObject.keys.all { it in PROP_KEYS }
                        )
                        assertTrue(
                            "${t.name}.$key.items.$ik 的 type 不认识",
                            iv.jsonObject["type"]?.jsonPrimitive?.content in KNOWN_TYPES
                        )
                    }
                    io["required"]?.jsonArray?.forEach { r ->
                        val k = r.jsonPrimitive.content
                        assertTrue("${t.name}.$key.items 的 required 里有 properties 没有的键：$k", ip.keys.contains(k))
                    }
                }
            }
            p["required"]?.jsonArray?.forEach { r ->
                val k = r.jsonPrimitive.content
                assertTrue("${t.name} 的 required 里有 properties 没有的键：$k", props.keys.contains(k))
            }
        }
    }
}
