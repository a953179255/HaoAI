package com.haoai.agent.platform.sandbox

import java.io.File

/**
 * Linux 沙箱运行环境解析（3.3）：BashTool 的 linux 后端与 3.5 stdio MCP 共用。
 * 以「文件系统现状」为准（安装即就绪、删除即失效），不落额外状态。
 */
object SandboxEnv {

    data class Sandbox(
        val install: Proot.Install,
        val rootfs: File,
        val distroId: String,
        /** 宿主工作区（绑定进沙箱 /workspace）；SAF 场景为 shell-home 兜底目录。 */
        val workspaceHostDir: File,
        val guestWorkspace: String = "/workspace"
    ) {
        /**
         * 组装 proot argv：工作区 + /dev /proc /sys 基础绑定，命令经 haoai-env
         * 包装（guest 内 PATH/TZ/HOME 才正确）。extraBinds 供 3.5 stdio 注入。
         * v8：ProotBackend 每条命令起一次性 proot 进程用此方法（对齐 proot-distro run）；
         * 旧的常驻 buildSessionArgs（曾致 haoai-env 双包 + 死会话吞命令）已删除。
         */
        /** fakeRoot=false 供实验对照（-0 与 app uid 下 fork 的交互排查）。 */
        var fakeRoot: Boolean = true

        fun buildArgs(cmd: String, extraBinds: List<Pair<String, String>> = emptyList()): List<String> {
            val binds = buildList {
                add(workspaceHostDir.absolutePath to guestWorkspace)
                add("/dev" to "/dev")
                add("/proc" to "/proc")
                add("/sys" to "/sys")
                addAll(extraBinds)
            }
            return Proot.buildCommand(
                install, rootfs, cmd,
                binds = binds,
                workdir = guestWorkspace,
                envWrapper = true,
                fakeRoot = fakeRoot
            )
        }
    }

    /** 解析当前可用的沙箱（取最近安装的就绪发行版）；不可用返回 null（BashTool 据此回落 toybox）。 */
    fun resolve(appFilesDir: File, nativeLibraryDir: String?, workspaceHostDir: File?): Sandbox? {
        if (workspaceHostDir == null) return null
        val install = Proot.ensureReady(appFilesDir, nativeLibraryDir) ?: return null
        val distrosDir = File(appFilesDir, "proot/distros")
        val candidates = distrosDir.listFiles()
            ?.filter { it.isDirectory && Proot.shAvailable(File(it, "rootfs")) }
            ?: return null
        val chosen = candidates.maxByOrNull { dir ->
            runCatching {
                val meta = File(dir, "meta.json").takeIf { it.exists() }?.readText() ?: "{}"
                org.json.JSONObject(meta).optLong("installedAt")
            }.getOrDefault(0L)
        } ?: return null
        return Sandbox(install, File(chosen, "rootfs"), chosen.name, workspaceHostDir)
    }

    /** SystemPrompt 的沙箱说明（未就绪返回空串，不注入）。 */
    fun describe(sandbox: Sandbox?, probeSummary: String = ""): String {
        sandbox ?: return ""
        val probe = if (probeSummary.isNotBlank()) "沙箱内可用：$probeSummary。" else ""
        return "Linux 沙箱（${sandbox.distroId}）已就绪：bash 默认在沙箱内执行，宿主工作区映射为沙箱内 /workspace，$probe" +
            "可 apk add / apt install 按需安装软件（写入类操作走既有审批）。长任务（构建/下载/服务）用 bash 的 background=true 投递后台，" +
            "立即返回后用 job_output 查看输出；也可用 tmux 会话管理交互式任务。"
    }
}
