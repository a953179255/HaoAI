package com.haoai.pc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Run/Verify 闭环（#17）：把「检测项目类型 → 构建 → 启动 → 验证 → 停止」一条链接成**一个**工具。
 *
 * 为什么一个工具而不是让模型自己用 shell 拼：这条链的每一步都容易半途而废 ——
 * 构建完不起服务、起了不停（端口占着到下次冲突）、停用硬杀（长驻进程没机会收尾）。
 * 一个工具 + 逐段报告，模型和人都能看见"死在哪一段"。
 *
 * 停止用 v0.80.0 的 TTY：先送 Ctrl+C（shell_send 的 text="\u0003"、enter=false）礼貌中断，
 * 给进程收尾机会，5 秒没收敛再 shell_close 兜底；TTY 建不起来（非 Windows 等）直接管道 + 关闭
 * —— 两条路都保证不留僵尸。
 *
 * 构建命令只认**确定性默认表**（见 [defaultBuild]），拿不准就让调用方传 `build` 覆盖 ——
 * 猜错的构建命令比不构建更糟（在别人仓库里跑错命令是不可逆的）。
 */
class RunVerifyTool : Tool(
    "run_verify",
    "把「检测项目类型→构建→启动→验证→停止」一条链接着跑完，逐段报告结果。" +
        "type 可手动指定 gradle|npm|maven|cargo|python（不传就按工作区文件猜）；" +
        "build 传构建命令覆盖默认表，传 \"-\" 跳过构建；start 传要起的长驻命令（给了才会启动并在最后停掉，" +
        "停止先 Ctrl+C=shell_send text=\"\\u0003\" enter=false 再兜底关闭）；" +
        "verify_url / verify_port / verify_file 至少给一个当验证判据（可多个，全过才算过；" +
        "url 状态码 <500 即算活，404 也是有服务在应答）；timeout_ms 是验证轮询上限（默认 30000）。",
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("type", buildJsonObject { put("type", "string") })
            put("build", buildJsonObject { put("type", "string") })
            put("start", buildJsonObject { put("type", "string") })
            put("verify_url", buildJsonObject { put("type", "string") })
            put("verify_port", buildJsonObject { put("type", "integer") })
            put("verify_file", buildJsonObject { put("type", "string") })
            put("timeout_ms", buildJsonObject { put("type", "integer") })
        })
    },
    kind = "exec"
) {

    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val typeHint = req(args, "type")?.trim().orEmpty()
        val buildOverride = req(args, "build")?.trim()
        val startCmd = req(args, "start")?.trim().orEmpty()
        val url = req(args, "verify_url")?.trim().orEmpty()
        val port = int(args, "verify_port", 0)
        val file = req(args, "verify_file")?.trim().orEmpty()
        val timeoutMs = int(args, "timeout_ms", 30_000).coerceIn(1_000, 300_000)
        val hasVerify = url.isNotEmpty() || port > 0 || file.isNotEmpty()
        if (buildOverride == null && startCmd.isEmpty() && !hasVerify) {
            return fail("总得做点什么：至少给 build / start / verify_* 之一（全空=这条链没内容）")
        }

        // 一把闸：这条链要跑的东西全写进审批标题（子命令都是本工具内部执行，不再各问一次）
        val plan = buildString {
            append("检测项目类型")
            if (buildOverride != null) append(" + 构建「${buildOverride.take(80)}」")
            if (startCmd.isNotEmpty()) append(" + 启动「${startCmd.take(80)}」")
            if (url.isNotEmpty()) append(" + 验证 $url")
            if (port > 0) append(" + 端口 $port")
            if (file.isNotEmpty()) append(" + 产物 $file")
        }
        val why = ctx.guard("shell", plan, "Run/Verify 闭环", "构建/启动/验证一条龙（含长驻进程的启动与停止）")
        if (why != null) return fail(why)

        val ws = ctx.workspace
        val log = StringBuilder()

        // ---- 1) 检测 ----
        val type = if (typeHint.isNotBlank()) typeHint else detectProject(ws).orEmpty()
        log.append("检测：")
            .append(if (type.isBlank()) "没认出项目类型（按文件猜的，可用 type= 手动指）" else describeType(ws, type))
            .append('\n')

        // ---- 2) 构建 ----
        if (buildOverride == "-") {
            log.append("构建：跳过（build=\"-\"）\n")
        } else if (buildOverride != null || type.isNotBlank()) {
            val cmd = buildOverride ?: defaultBuild(type, ws)
            if (cmd == null) {
                log.append("构建：跳过（").append(type.ifBlank { "未知类型" }).append(" 没有可靠的默认构建命令）\n")
            } else {
                log.append("构建：`").append(cmd).append("` …")
                val t0 = System.currentTimeMillis()
                val (code, out, timedOut) = runShell(ws, cmd, timeoutMs.coerceAtLeast(60_000))
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                if (timedOut) {
                    log.append("超时（${timeoutMs / 1000}s）\n输出尾：\n").append(out.takeLast(600)).append('\n')
                    return fail(log.toString() + "\n结论：构建超时，后面不跑了。")
                }
                if (code != 0) {
                    log.append("失败（退出码 $code，${secs}s）\n输出尾：\n").append(out.takeLast(600)).append('\n')
                    return fail(log.toString() + "\n结论：构建没过，不启动也不验证。")
                }
                log.append("ok（退出 0，").append(secs).append("s）\n")
            }
        } else {
            log.append("构建：跳过（没给 build 也没认出类型）\n")
        }

        // ---- 3) 启动（给了才做；优先 TTY，失败退回管道）----
        var shellId: String? = null
        var usedTty = false
        if (startCmd.isNotEmpty()) {
            val ttyRec = ProcRegistry.open("run_verify", "bash", ws, startCmd, tty = true)
                .getOrNull()
            val chosen = ttyRec ?: ProcRegistry.open("run_verify", "bash", ws, startCmd, tty = false)
                .getOrElse { e ->
                    log.append("启动：失败（").append(e.message ?: "未知").append("）\n")
                    return fail(log.toString() + "\n结论：服务没起来，验证无从谈起。")
                }
            shellId = chosen.id
            usedTty = chosen.tty != null
            log.append("启动：已起 ").append(chosen.id)
                .append(if (usedTty) "（tty=true）" else "（管道；这台建不了 TTY）")
                .append('\n')
            Thread.sleep(600)   // 给进程一个起手的机会：起手就崩的要在这里被验出来
        }

        // ---- 4) 验证 ----
        if (hasVerify) {
            val t0 = System.currentTimeMillis()
            var lastWhy = "（还没轮询过）"
            var ok = false
            while (System.currentTimeMillis() - t0 < timeoutMs) {
                val miss = verifyMiss(ws, url, port, file)
                if (miss == null) { ok = true; break }
                lastWhy = miss
                Thread.sleep(300)
            }
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            if (!ok) {
                log.append("验证：失败（").append(lastWhy).append("，等了 ").append(secs).append("s）\n")
                stopStart(shellId, usedTty)
                return fail(log.toString() + "\n结论：验证没过，已把起过的进程停掉。")
            }
            log.append("验证：ok（").append(describeVerify(url, port, file)).append("，用时 ").append(secs).append("s）\n")
        } else {
            log.append("验证：没给 verify_*，只报到启动为止\n")
        }

        // ---- 5) 停止 ----
        val stoppedNote = stopStart(shellId, usedTty)
        if (stoppedNote != null) log.append("停止：").append(stoppedNote).append('\n')

        log.append("结论：整条链跑通。")
        return ToolResult(log.toString())
    }

    /** 没过返回原因字符串；全过返回 null。 */
    private fun verifyMiss(ws: File, url: String, port: Int, file: String): String? {
        if (file.isNotEmpty()) {
            val f = File(ws, file)
            if (!f.exists()) return "产物不存在：$file"
        }
        if (port > 0) {
            val reachable = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); true }
            } catch (e: Exception) { false }
            if (!reachable) return "127.0.0.1:$port 连不上"
        }
        if (url.isNotEmpty()) {
            val code = try {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                    .GET().build()
                client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
            } catch (e: Exception) { -1 }
            // <500 都算"服务活着"：404/401 说明端口上真有个应用在应答，那是"起来了"不是"没起来"
            if (code < 0 || code >= 500) return "$url 不可用（HTTP ${if (code < 0) "连不上" else code}）"
        }
        return null
    }

    private fun describeVerify(url: String, port: Int, file: String): String = buildList {
        if (url.isNotEmpty()) add("$url 有应答")
        if (port > 0) add("端口 $port 通")
        if (file.isNotEmpty()) add("产物 $file 在")
    }.joinToString("、")

    /**
     * 停掉启动过的长驻进程：先 TTY Ctrl+C（礼貌，给收尾机会），5 秒没收敛再 shell_close 兜底。
     * 返回停止说明（null = 这轮没启动过）。
     */
    private fun stopStart(id: String?, usedTty: Boolean): String? {
        if (id == null) return null
        val rec = ProcRegistry.get(id) ?: return "进程已不在（自己退了也算停）"
        if (usedTty) {
            runCatching { ProcRegistry.send(id, "\u0003", enter = false) }
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && rec.alive()) Thread.sleep(200)
            if (!rec.alive()) return "Ctrl+C 后自行退出（礼貌停止）"
        }
        ProcRegistry.close(id)
        return if (usedTty) "Ctrl+C 没收住，已关闭兜底" else "已关闭"
    }

    // ---- 检测与默认构建表（确定性的，不猜）----

    companion object {
        /** 按工作区文件认项目类型。返回 null = 没认出来。 */
        fun detectProject(dir: File): String? = when {
            File(dir, "gradlew.bat").isFile || File(dir, "gradlew").isFile -> "gradle"
            File(dir, "build.gradle.kts").isFile || File(dir, "build.gradle").isFile -> "gradle"
            File(dir, "pom.xml").isFile -> "maven"
            File(dir, "package.json").isFile -> "npm"
            File(dir, "Cargo.toml").isFile -> "cargo"
            File(dir, "pyproject.toml").isFile || File(dir, "setup.py").isFile ||
                File(dir, "requirements.txt").isFile -> "python"
            else -> null
        }

        private fun describeType(dir: File, type: String): String = when (type) {
            "gradle" -> if (File(dir, "gradlew.bat").isFile || File(dir, "gradlew").isFile)
                "gradle（工作区自带 wrapper）" else "gradle"
            "npm" -> "npm（package.json）"
            else -> type
        }

        /**
         * 默认构建命令。**没有可靠默认的返回 null（跳过而不是猜）** ——
         * python 没有统一构建；npm 没有 scripts.build 也只是跳过。
         */
        fun defaultBuild(type: String, dir: File): String? = when (type) {
            "gradle" -> when {
                File(dir, "gradlew.bat").isFile -> "gradlew.bat build"
                File(dir, "gradlew").isFile -> "./gradlew build"
                else -> "gradle build"
            }
            "maven" -> "mvn -q -DskipTests package"
            "cargo" -> "cargo build"
            "npm" -> {
                val pkg = runCatching { File(dir, "package.json").readText() }.getOrDefault("")
                if (!Regex(""""build"\s*:""").containsMatchIn(pkg)) null
                else if (File(dir, "pnpm-lock.yaml").isFile) "pnpm run build"
                else if (File(dir, "yarn.lock").isFile) "yarn build"
                else "npm run build"
            }
            else -> null
        }

        /**
         * 跑一条 shell 命令（构建用）：bash -l -c / pwsh -Command / cmd /c —— 与 ShellLauncher
         * 的一次性执行同一套"这台机器上哪个 shell 可用"的判断。回 (退出码, 输出尾, 是否超时)。
         */
        fun runShell(cwd: File, cmd: String, timeoutMs: Int): Triple<Int, String, Boolean> {
            val launcher = ShellLauncher.forName("bash")
                ?: ShellLauncher.forName("pwsh")
                ?: ShellLauncher.forName("cmd")
                ?: return Triple(-1, "这台机器上找不到任何 shell（bash/pwsh/cmd）", false)
            return try {
                // forName 给的一次性参数：bash 是 ["-l"]（后面要跟脚本或 -c）、pwsh 已带 -Command、
                // cmd 已带 /c —— 只有 bash 需要补 "-c" 才能把命令当字符串执行。
                val base = launcher.second
                val argv = listOf(launcher.first) +
                    (if (base.lastOrNull() == "-l") base + listOf("-c", cmd) else base + cmd)
                val p = ProcessBuilder(argv)
                    .directory(if (cwd.isDirectory) cwd else File(System.getProperty("user.dir")))
                    .redirectErrorStream(true)
                    .start()
                val out = StringBuilder()
                val t = Thread {
                    p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { out.append(it).append('\n') }
                }
                t.isDaemon = true
                t.start()
                val done = p.waitFor(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!done) {
                    p.destroyForcibly()
                    Triple(-1, out.toString(), true)
                } else {
                    Triple(p.exitValue(), out.toString(), false)
                }.let { Triple(it.first, it.second.takeLast(4000), it.third) }
            } catch (e: Exception) {
                Triple(-1, "起 shell 失败：${e.message}", false)
            }
        }
    }
}
