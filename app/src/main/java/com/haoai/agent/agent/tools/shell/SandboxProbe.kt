package com.haoai.agent.agent.tools.shell

import com.haoai.agent.platform.sandbox.SandboxEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 沙箱能力探测（3.3）：后台跑一次 `which …`，把可用命令摘要缓存进 SystemPrompt
 * （"沙箱内可用：git, python3 …"，未装的包模型会自行 apk add / apt install）。
 * 发行版安装/删除时调用 invalidate() 让下一轮重新探测。
 */
object SandboxProbe {

    @Volatile private var cached: String? = null
    private val started = AtomicBoolean(false)

    /** 当前缓存的摘要（未探测完返回 null，SystemPrompt 此时省略该段）。 */
    fun summary(): String? = cached

    /**
     * 确保探测已启动（幂等；失败不重试直到 invalidate）。
     * 探测本身也是一次 proot 执行（≤20s），只跑一次。
     */
    fun ensureStarted(sandbox: SandboxEnv.Sandbox, scope: CoroutineScope) {
        if (cached != null) return
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val cmd = "for b in git python3 node npm pip3 curl wget gcc make; do command -v \$b >/dev/null 2>&1 && printf '%s ' \$b; done; echo"
            val r = runCatching {
                ProotBackend.forSandbox(sandbox).exec(cmd, 20_000)
            }.getOrNull()
            // base 镜像里这些工具通常一个都没有：循环不打印任何名字，末位 echo 只回一个空行，
            // ProotBackend 会把「仅空白」的输出回退成 "(无输出)"（人读友好）。此处必须把它
            // 当空处理，否则系统提示会出现「沙箱内可用：（无输出）」误导模型与调试者
            //（用户反馈 2026-09-16）。
            val raw = r?.takeIf { it.exitCode == 0 }?.output?.trim().orEmpty()
            val cleaned = raw.removePrefix("(无输出)").trim()
            val names = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
            cached = if (names.isEmpty()) "（暂无常见开发工具，可 apk add / apt install 安装）" else names.joinToString(", ")
        }
    }

    fun invalidate() {
        cached = null
        started.set(false)
    }
}
