package com.haoai.pc

import kotlinx.serialization.json.JsonObject

/**
 * suspend 契约（B15）落到 JUnit4 上的桥：测试是普通 fun，进不了挂起上下文，
 * 而工具接口为了两端共用成了 suspend —— 真工具就在这一层 runBlocking 跑一遍。
 *
 * 命名 `runB` 而不是重载 `run`：调用点只做 `Tool().runB(` → `Tool().runB(` 的
 * 文本替换，**括号结构一行不动**（`MediaTest` 里那 3 处 `Ffmpeg.run` 是普通函数，
 * 逐字 `Tool().runB(` 匹配天然绕开它们）。
 */
fun Tool.runB(args: JsonObject, ctx: ToolCtx): ToolResult =
    kotlinx.coroutines.runBlocking { this@runB.run(args, ctx) }
