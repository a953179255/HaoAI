package com.haoai.agent.platform

import com.haoai.agent.data.HaoJson
import com.haoai.agent.data.KeystoreCipher
import java.io.File
import kotlinx.serialization.Serializable

/**
 * 配过对的那台电脑记在哪 + "电脑上有人在等"该怎么判。
 *
 * 两件都是纯逻辑，刻意不碰 Android 组件：这样它们能在 JVM 单测里跑，
 * 而"通知到底该不该再弹一次"这种东西，靠真机一遍遍试是试不出来的。
 */

/** 加密盒子的一层薄壳：真机走 Keystore，测试里给个可逆的假货。 */
interface PcCipher {
    fun encrypt(plain: String): String?
    fun decrypt(text: String): String
}

/** 默认实现：手机端那份 `KeystoreCipher`（与 MCP header、SSH 私钥同一把钥匙的用法）。 */
class KeystorePcCipher(private val inner: KeystoreCipher = KeystoreCipher()) : PcCipher {
    override fun encrypt(plain: String): String? = inner.encrypt(plain)
    override fun decrypt(text: String): String = inner.decrypt(text)
}

@Serializable
data class PcEndpoint(
    val base: String = "",
    val device: String = "",
    val token: String = "",
    /** 配对成功的时间，只给设置页显示"配于…"。 */
    val pairedAt: Long = 0L
)

/**
 * 配对结果落盘：`filesDir/pc-link.json`。
 *
 * 三条硬规矩（都是这个仓库里已经付过学费的地方）：
 * ① **token 绝不明文落盘**。加密失败就**拒绝保存**并说清原因 ——
 *    明文存盘等于把"能替你点审批"的凭据留给任何读到这个文件的程序；
 *    同一份策略见 `agent/mcp/McpServerStore.kt` 的 `encryptValue`。
 * ② 解密失败（换机恢复 / 清除 Keystore）时**不假装还在**：返回 null，让界面说"重新配一次"，
 *    而不是留着一条点什么都 401 的假配对。
 * ③ 地址在存之前先过 [pcNormalizeBase]：把打错的地址存下来，等于把一次性的挫败变成常驻的困惑。
 */
class PcStore(
    private val cipher: PcCipher = KeystorePcCipher(),
    private val file: File? = null
) {
    private val encPrefix = "enc:v1:"

    fun storageFile(filesDir: File): File = file ?: File(filesDir, "pc-link.json")

    /** @return 成功回 Ok(说明)，失败回那句人话（调用方直接显示，不用翻译）。 */
    fun save(target: File, base: String, token: String, device: String): PcOut<String> {
        val normalized = pcNormalizeBase(base)
            ?: return PcOut.Fail("那个地址看不懂：要的是 IP 或主机名（可以带端口），例如 192.168.1.5:8720")
        if (token.isBlank()) return PcOut.Fail("没有 token：先在电脑上生成配对码，配一次才有")
        val sealed = enc(token) ?: return PcOut.Fail(
            "这台机器的 Keystore 用不了，配对没存。把 token 明文写在盘上，" +
                "等于把「能替你点审批」的凭据留给任何读到这个文件的程序 —— 宁可不做。"
        )
        val json = HaoJson.json.encodeToString(
            PcEndpoint.serializer(), PcEndpoint(base = normalized, device = device, token = sealed, pairedAt = System.currentTimeMillis())
        )
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(json)
            PcOut.Ok("已记住那台电脑")
        }.getOrElse { PcOut.Fail("写盘失败：${it.message ?: it.javaClass.simpleName}") }
    }

    fun load(target: File): PcEndpoint? {
        if (!target.isFile) return null
        val ep = runCatching {
            HaoJson.json.decodeFromString(PcEndpoint.serializer(), target.readText())
        }.getOrNull() ?: return null
        val plain = dec(ep.token)
        if (ep.token.isNotEmpty() && plain.isEmpty()) {
            android.util.Log.w("HaoPcLink", "配对 token 解不开（换机/清除 Keystore），当作没配对")
            return null
        }
        return ep.copy(token = plain)
    }

    fun clear(target: File) {
        runCatching { if (target.exists()) target.delete() }
    }

    /** 给设置页看的：地址 + 设备名 + token 的掩码（永远不给整串）。 */
    fun masked(ep: PcEndpoint): String {
        val head = ep.token.take(4)
        return if (head.isEmpty()) "没有 token" else "$head…（共 ${ep.token.length} 位，已加密存储）"
    }

    private fun enc(v: String): String? =
        if (v.startsWith(encPrefix)) v else cipher.encrypt(v)?.let { encPrefix + it }

    private fun dec(v: String): String =
        if (!v.startsWith(encPrefix)) v else cipher.decrypt(v.removePrefix(encPrefix))
}

/** 该对通知做什么。刻意不叫"发通知"——决定权在调用方（它才知道渠道与权限状态）。 */
sealed class PcWatchAction {
    /** 第一次看到 / 又多了新的：要弹一条"有 N 条在等你批"。 */
    data class Notify(val total: Int, val fresh: Int) : PcWatchAction()
    /** 从"有"变成"没有"：要收掉那条通知（不收回就是永远挂着一条假待办）。 */
    object Clear : PcWatchAction()
    /** 和上次一样：什么都不做。轮询是 4 秒一次，这里松一次就会天天震。 */
    object Same : PcWatchAction()
}

/**
 * 待批集合的差集判定。
 *
 * 为什么单独拿出来：手机网页端是"页面开着才刷新"，而这一半是**后台每几秒问一次**。
 * 后台版最容易坏在两处 —— 同一批待批反复弹（人直接关不掉通知），
 * 以及批完之后通知还挂着（人以为电脑仍在等他）。这两处都能用纯逻辑钉死。
 */
class PcWatch {
    private var seen: Set<String> = emptySet()

    fun onPending(ids: Collection<String>): PcWatchAction {
        val now = ids.filter { it.isNotBlank() }.toSet()
        if (now.isEmpty()) {
            val had = seen.isNotEmpty()
            seen = emptySet()
            return if (had) PcWatchAction.Clear else PcWatchAction.Same
        }
        val fresh = (now - seen).size
        seen = now
        // 一条都没多就不该再响：只有"确实没见过"才值得打断人
        return if (fresh > 0) PcWatchAction.Notify(now.size, fresh) else PcWatchAction.Same
    }

    /**
     * 忘掉"已经提醒过谁"。
     * 断线、解除配对、以及"通知被我们主动收掉"之后都要调：不然重连时那几条老待办
     * 会被当成"已经弹过了"，屏幕上就再也没有提醒了 —— 而这正是最需要它响的时候。
     */
    fun reset() { seen = emptySet() }
}

/**
 * 测试用的可逆假货（JVM 里没有 AndroidKeyStore）。
 * 用 `java.util.Base64` 而不是 `android.util.Base64`：后者在单测里被 mock 成"返回默认值"，
 * 于是加密永远得到 null，测试会在一个假故障上打转（`isReturnDefaultValues = true` 的坑）。
 */
class FakePcCipher(private val works: Boolean = true) : PcCipher {
    override fun encrypt(plain: String): String? =
        if (!works) null else java.util.Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))

    override fun decrypt(text: String): String =
        runCatching { String(java.util.Base64.getDecoder().decode(text), Charsets.UTF_8) }.getOrDefault("")
}
