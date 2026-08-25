package com.haoai.agent.agent.tools

import com.haoai.agent.platform.PermissionCenter
import com.haoai.agent.platform.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class BashTool : Tool {

    override val name = "bash"
    override val description =
        "在工作空间目录内执行 POSIX shell 命令（Android /system/bin/sh）。超时上限 5 分钟。高危命令会被安全策略拦截。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") { put("type", "string") }
            putJsonObject("timeout_ms") { put("type", "integer") }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("command")) }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val dir = ctx.shellDir
                ?: return@withContext ToolResult("当前工作空间不支持 shell（SAF 目录）", true)
            val command = args.reqString("command")
            // 访问工作空间之外的共享存储时，先引导「文件管理」权限
            PermissionCenter.ensureStorageIfOutside(
                ctx.appContext, command, ctx.shellDir?.absolutePath
            )
            val timeout = (args.optInt("timeout_ms") ?: 30_000).coerceIn(1000, 300_000).toLong()
            val result = ShellRunner.exec(dir, command, timeout)
            ToolResult("exit=${result.exitCode}\n---\n${TextCap.middle(result.output, 12_000)}")
        }
}
