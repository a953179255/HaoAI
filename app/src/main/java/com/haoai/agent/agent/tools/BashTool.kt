package com.haoai.agent.agent.tools

import com.haoai.agent.agent.tools.shell.ProotBackend
import com.haoai.agent.agent.tools.shell.ShellBackend
import com.haoai.agent.agent.tools.shell.SshBackend
import com.haoai.agent.agent.tools.shell.ToyboxBackend
import com.haoai.agent.platform.PermissionCenter
import com.haoai.agent.platform.sandbox.SandboxEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class BashTool : Tool {

    override val name = "bash"
    override val description =
        "在工作空间目录内执行 shell 命令。默认 backend=auto：Linux 沙箱（proot 发行版）就绪时在沙箱内执行（glibc 环境，宿主工作区=沙箱内 /workspace），否则回落 Android toybox（/system/bin/sh）。可显式 backend=\"toybox\"|\"linux\"|\"ssh\"。timeout_ms 默认 30 秒、上限 600 秒（长构建）。高危命令会被安全策略拦截（三个后端一致）。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") {
                put("type", "string")
                put("description", "要执行的 POSIX shell 命令")
            }
            putJsonObject("timeout_ms") { put("type", "integer") }
            putJsonObject("backend") {
                put("type", "string")
                putJsonArray("enum") {
                    add(JsonPrimitive("auto")); add(JsonPrimitive("toybox")); add(JsonPrimitive("linux")); add(JsonPrimitive("ssh"))
                }
                put("description", "执行后端：auto（默认，沙箱就绪用 linux 否则 toybox）/ toybox / linux / ssh")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("command")) }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val command = args.reqString("command")
            // 引擎层 invokeTool 按 bash 的 timeout_ms+20s 动态包裹（3.3 放宽至 600s，长构建可用）
            val timeout = (args.optInt("timeout_ms") ?: 30_000).coerceIn(1000, 600_000).toLong()
            val backendArg = args.optString("backend").ifBlank { "auto" }
            val nativeDir = ctx.appContext?.applicationInfo?.nativeLibraryDir

            val backend: ShellBackend = when (backendArg) {
                "toybox" -> ToyboxBackend(ctx.shellDir)
                "linux" -> {
                    val sb = SandboxEnv.resolve(ctx.appFilesDir, nativeDir, ctx.shellDir)
                        ?: return@withContext ToolResult(
                            "linux 后端不可用：尚未安装 Linux 沙箱发行版（请在 设置 → Linux 环境 安装），或当前设备不满足 proot 运行条件",
                            true
                        )
                    ProotBackend.forSandbox(sb)
                }
                "ssh" -> {
                    val t = SshBackend.loadTargets(ctx.appFilesDir).firstOrNull()
                        ?: return@withContext ToolResult(
                            "SSH 后端不可用：未配置 SSH 目标（应用 files/ssh/targets.json）。远程信息可改用 web_fetch 或远程 HTTP MCP 服务器",
                            true
                        )
                    SshBackend(t.host, t.port, t.user, SshBackend.findSshBinary())
                }
                else -> { // auto：沙箱就绪且 fork 能力正常 → linux；否则 toybox（行为与升级前一致）
                    val sb = SandboxEnv.resolve(ctx.appFilesDir, nativeDir, ctx.shellDir)
                    if (sb != null && sandboxForkOk(sb)) ProotBackend.forSandbox(sb) else ToyboxBackend(ctx.shellDir)
                }
            }

            // 访问工作空间之外的共享存储时，先引导「文件管理」权限（沙箱场景操作 /workspace 无此需求，检查幂等无害）
            PermissionCenter.ensureStorageIfOutside(
                ctx.appContext, command, ctx.shellDir?.absolutePath
            )

            val result = try {
                backend.exec(command, timeout)
            } catch (ie: kotlinx.coroutines.CancellationException) {
                throw ie
            } catch (e: Exception) {
                return@withContext ToolResult("命令执行失败：${e.message}", true)
            }
            val note = when (backend.id) {
                "linux" -> "（linux 沙箱）"
                "ssh" -> "（ssh）"
                else -> ""
            }
            ToolResult("exit=${result.exitCode}$note\n---\n${TextCap.middle(result.output, 12_000)}")
        }

    /**
     * 沙箱 fork 能力探测（进程内缓存一次）：部分设备的 app 进程 seccomp 栈
     * （如 API 36 模拟器）会拒绝 proot guest 内的 fork/clone（ENOSYS），表现为
     * "can't fork: Function not implemented"（真机正常，参见 上游 issue #1047
     * 同类问题）。auto 路由据此回落 toybox；显式 backend="linux" 不拦，让模型
     * 能看到原始报错。探测命令本身要 fork 子 shell，失败即视为受限。
     */
    private var forkProbeResult: Boolean? = null

    private suspend fun sandboxForkOk(sb: SandboxEnv.Sandbox): Boolean {
        forkProbeResult?.let { return it }
        val r = runCatching {
            ProotBackend.forSandbox(sb).exec("( echo probe-ok )", 20_000)
        }.getOrNull()
        android.util.Log.w("BashTool", "sandbox fork probe: r=$r")
        val ok = r != null && r.exitCode == 0 && r.output.contains("probe-ok")
        forkProbeResult = ok
        return ok
    }
}
