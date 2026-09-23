package com.haoai.agent.agent.mcp

import com.haoai.agent.data.HaoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * 单台 MCP 服务器的用户配置。toolCache 缓存最近一次 tools/list 结果，
 * 用于 server 尚未连上（冷启动/断网）时也能把工具注册给模型——调用时报"不可用"而非"未知工具"。
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    /** 传输类型：http = Streamable HTTP 远程（默认）；stdio = 沙箱内拉起本地服务器（3.5）。 */
    val kind: String = "http",
    /** stdio 类型的沙箱内启动命令（如 node /workspace/mcp/server.js 或 uvx mcp-server-fetch）。 */
    val command: String = "",
    /** 自定义请求头（如 Authorization）。含敏感值，存 app 私有目录，不进工作区。 */
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** 工具审批级别：write=每次询问（默认）；read=免审批（仅建议只读 server）。 */
    val approvalLevel: String = "write",
    /** 用户显式允许该 server 走明文 http（NetGuard 白名单，默认关）。 */
    val allowPlaintext: Boolean = false,
    val toolCache: List<McpToolInfoData> = emptyList()
)

@Serializable
data class McpToolInfoData(
    val name: String,
    val description: String = "",
    /** 原始 inputSchema JSON 字符串（空 = 防御性默认 object）。 */
    val schemaJson: String = ""
)

/** 服务器配置持久化（filesDir/mcp/servers.json，HaoJson 原子写 + .bak 兜底）。
 *  headers 值经 Android Keystore 加密落盘（enc:<base64>，密钥名不敏感保留明文）：
 *  旧版明文值加载后原样识别，首次 save 自动升级；Keystore 加密失败拒绝写入（fail-closed，
 *  绝不把 Authorization 明文落盘）。 */
object McpServerStore {

    private lateinit var file: File

    private const val ENC_PREFIX = "enc:"
    private val cipher = com.haoai.agent.data.KeystoreCipher()

    fun init(filesDir: File) {
        file = File(filesDir, "mcp/servers.json")
    }

    fun load(): List<McpServerConfig> {
        if (!::file.isInitialized) return emptyList()
        val text = HaoJson.readTextSafe(file) ?: return emptyList()
        val parsed = runCatching {
            HaoJson.json.decodeFromString(ListSerializer(McpServerConfig.serializer()), text)
        }.getOrDefault(emptyList())
        // 解密（enc: 前缀）+ 识别旧版明文；发现明文立即回写完成迁移
        val decrypted = parsed.map { s ->
            if (s.headers.isEmpty()) s
            else s.copy(headers = s.headers.mapValues { (_, v) -> decryptValue(v) })
        }
        val hasLegacy = parsed.any { s ->
            s.headers.values.any { it.isNotBlank() && !it.startsWith(ENC_PREFIX) }
        }
        if (hasLegacy) runCatching { save(decrypted) }
        return decrypted
    }

    fun save(servers: List<McpServerConfig>) {
        if (!::file.isInitialized) return
        val secured = servers.map { s ->
            if (s.headers.isEmpty()) s
            else s.copy(headers = s.headers.mapValues { (_, v) -> encryptValue(v) })
        }
        val text = HaoJson.json.encodeToString(
            ListSerializer(McpServerConfig.serializer()),
            secured
        )
        HaoJson.writeAtomic(file, text)
    }

    private fun encryptValue(v: String): String =
        if (v.isBlank() || v.startsWith(ENC_PREFIX)) v
        else cipher.encrypt(v)?.let { ENC_PREFIX + it }
            ?: error("MCP header 加密失败（Keystore 不可用），拒绝明文落盘")

    private fun decryptValue(v: String): String =
        if (!v.startsWith(ENC_PREFIX)) v
        else {
            val plain = cipher.decrypt(v.removePrefix(ENC_PREFIX))
            if (plain.isEmpty()) {
                android.util.Log.w("HaoMcp", "MCP header 解密失败（Keystore 不可用/换机恢复），值置空")
            }
            plain
        }
}
