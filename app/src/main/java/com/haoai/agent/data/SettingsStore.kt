package com.haoai.agent.data

import android.content.Context
import com.haoai.agent.agent.policy.PermissionMode
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 供应商下的单个模型条目（「供应商→模型」两级结构）。
 * 能力三态：null=未知/自动（不做门控），true=支持，false=不支持（请求侧据此裁剪参数）。
 *
 * 模态字段：inputModalities/outputModalities 存裸名
 * （text/image/audio/video/pdf），null=未检测（CapabilityResolver 回退目录/启发式/旧 vision）。
 * capsSource 记录能力来源（models.dev / guess / manual），manual 时不再被自动检测覆盖，
 * 可在能力编辑页「恢复自动检测」清掉。
 */
@Serializable
data class ModelEntry(
    val id: String,
    val displayName: String = "",
    val vision: Boolean? = null,
    val tools: Boolean? = null,
    val reasoning: Boolean? = null,
    /** 0=沿用供应商配置/按模型名推测 */
    val contextLength: Int = 0,
    /** 0=沿用供应商配置 */
    val maxTokens: Int = 0,
    /** 输入模态裸名列表；null=未检测 */
    val inputModalities: List<String>? = null,
    /** 输出模态裸名列表；null=未检测（视为 text） */
    val outputModalities: List<String>? = null,
    /** 能力来源：null=自动/未设置 | "models.dev" | "guess" | "manual" */
    val capsSource: String? = null,
    /** 目录声明的思考等级白名单（如 low/medium/high）；null=未知 */
    val effortValues: List<String>? = null,
    /** 本模型思考等级覆盖：空=跟随全局设置（"off"/low/medium/high/…）；按模型控制 */
    val reasoningEffortOverride: String = ""
) {
    /** 是否含某输入模态（vision 旧字段兼容：image 查询回退 vision）。 */
    fun hasInput(mod: String): Boolean? {
        inputModalities?.let { return it.contains(mod) }
        if (mod == "image" || mod == "video") return vision
        return null
    }
}

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKeyCipher: String = "",
    /** 协议：openai_compat（默认，旧配置零迁移）| anthropic（原生 Messages API）。 */
    val protocol: String = "openai_compat",
    /** 上下文窗口（tokens）。0 = 按模型名自动推测。 */
    val contextLength: Int = 0,
    /** 单次回复上限（max_tokens）。0 = 供应商默认（部分服务仅 4K，易截断）。 */
    val maxTokens: Int = 0,
    /** 同供应商下的可选模型列表（不含当前 model；聊天与设置均可直接切换，无需新建条目）。 */
    val models: List<ModelEntry> = emptyList(),
    // ── 采样参数「是否发送」开关：默认全关=与旧行为一致，请求体不带该字段 ──
    val sendTemperature: Boolean = false,
    val temperature: Float = 1.0f,
    val sendTopP: Boolean = false,
    val topP: Float = 1.0f,
    val sendPresencePenalty: Boolean = false,
    val presencePenalty: Float = 0f,
    val sendFrequencyPenalty: Boolean = false,
    val frequencyPenalty: Float = 0f,
    // ── API Key 池：主 Key 之外追加备用密文，按策略轮换分摊限流 ──
    val apiKeyPoolCiphers: List<String> = emptyList(),
    /** ROUND_ROBIN=逐请求轮询 | RANDOM=随机 */
    val keyRotation: String = "ROUND_ROBIN",
    // ── 余额查询：GET baseUrl+path，按点分 JSON 路径取值展示 ──
    val balanceEnabled: Boolean = false,
    val balanceApiPath: String = "/credits",
    val balanceJsonPath: String = "data.total_usage"
) {
    /** 当前 model 对应的条目（无则 null=能力未知不门控）。 */
    fun modelEntry(): ModelEntry? = models.firstOrNull { it.id == model }

    /** 实际生效的上下文窗口：模型条目覆盖 > 供应商配置 > 按模型名推测。 */
    fun effectiveContextLength(): Int =
        modelEntry()?.contextLength?.takeIf { it > 0 }
            ?: contextLength.takeIf { it > 0 }
            ?: guessContextLength(model)

    /** 实际生效的单次回复上限：模型条目覆盖 > 供应商配置 > 本地 4096/云端默认。 */
    fun effectiveMaxTokens(): Int =
        modelEntry()?.maxTokens?.takeIf { it > 0 }
            ?: maxTokens.takeIf { it > 0 }
            ?: if (baseUrl.contains("127.0.0.1") || baseUrl.startsWith("local")) 4096 else 0

    /** 全部可选模型 ID（当前 model 排最前，去重）。 */
    fun modelIds(): List<String> = (listOf(model) + models.map { it.id }).distinct()

    /** 当前模型统一能力视图（手动覆盖 > 检测写回 > 旧 vision > 名称启发式 > 默认）。 */
    fun caps(): CapabilityResolver.Caps = CapabilityResolver.resolve(modelEntry(), model)

    /**
     * 实际生效的思考等级：模型条目覆盖 > 全局设置。
     * "off" 显式关闭（不发 reasoning_effort）；空=全局；条目未配置时回落全局。
     */
    fun effectiveReasoningEffort(globalEffort: String): String {
        val ov = modelEntry()?.reasoningEffortOverride.orEmpty()
        return if (ov.isNotBlank()) ov else globalEffort
    }

    companion object {
        /** 按模型名推测上下文窗口（常见模型速查，未命中给保守默认）。 */
        fun guessContextLength(model: String): Int {
            val m = model.lowercase()
            return when {
                "1m" in m || "1-million" in m -> 1_000_000
                "gemini-2.5-pro" in m || "gemini-2.0" in m -> 1_048_576
                "gemini" in m -> 1_048_576
                "gpt-4.1" in m || "gpt-5" in m -> 400_000
                "claude" in m -> 200_000
                "o1" in m || "o3" in m || "o4" in m -> 200_000
                "kimi" in m || "moonshot" in m -> 256_000
                "deepseek" in m -> 131_072
                "qwen" in m -> 131_072
                "glm-4" in m || "glm4" in m -> 131_072
                "grok" in m -> 131_072
                "llama-3" in m || "llama3" in m -> 131_072
                "gpt-4o" in m || "gpt-4-turbo" in m -> 128_000
                "gpt-4" in m -> 8_192
                "gpt-3.5" in m -> 16_385
                else -> 32_768
            }
        }
    }
}

@Serializable
data class AppSettings(
    val providers: List<ProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
    val permissionMode: PermissionMode = PermissionMode.ASK_WRITES,
    val keepAlive: Boolean = true,
    /** 任务悬浮窗（Agent 后台运行时显示进度胶囊；权限缺失自动失效）。 */
    val runOverlay: Boolean = true,
    val customPrompt: String = "",
    val memoryEnabled: Boolean = true,
    val deepDream: Boolean = false,
    val autoLearn: Boolean = true,
    val dreamProviderId: String = "local",
    val dreamIdleMinutes: Int = 15,
    /** 记忆整理专用端侧模型（绝对路径）；null = 跟随端侧聊天模型。 */
    val dreamLocalModelFile: String? = null,
    val themeMode: String = "light",
    /** 动态取色：优先从聊天壁纸取主色，无壁纸时 Android 12+ 按系统壁纸生成色板。 */
    val dynamicColor: Boolean = false,
    /** 主题色种子索引（THEME_SEEDS 下标，0=默认液态玻璃绿）；动态色关闭时生效。 */
    val themeSeed: Int = 0,
    /** 自定义主题色槽位（固定 3 个，HEX 如 "#FF7A45"；空串=未保存）。 */
    val customSeedColors: List<String> = listOf("", "", ""),
    /** 当前应用中的自定义主题色（HEX）；空 = 使用 themeSeed 预设。 */
    val customSeedActive: String = "",
    /** AMOLED 纯黑模式（仅深色主题下生效：背景/表面换纯黑）。 */
    val amoledMode: Boolean = false,
    /** 聊天气泡不透明度（0.3-1.0，100% 时气泡几乎不透明）。 */
    val bubbleOpacity: Float = 0.7f,
    /** 聊天壁纸作用范围：false=仅聊天界面（默认），true=应用为全局壁纸。 */
    val wallpaperGlobal: Boolean = false,
    val reasoningEffort: String = "",
    val localModelFile: String? = null,
    val agentName: String = "",
    val soul: String = "",
    /** 档案头像：emoji 字符（空则回退名字首字）。 */
    val avatarEmoji: String = "",
    /** 档案头像底色：渐变索引（0-5）。 */
    val avatarGradient: Int = 0,
    /** 档案头像：相册图片路径（应用私有目录）。非空时优先于 emoji。 */
    val avatarImagePath: String? = null,
    /** 档案签名：一句话介绍，侧边栏头像旁展示。 */
    val bio: String = "",
    // ── 用户档案（聊天社交化 2026-10-07）：消息区右侧「我的名片」，与 Agent 档案对称。
    //    全部留空走默认（昵称「我」+ 蓝色渐变），引导第 3 步与设置→通用→我的档案可改 ──
    /** 我的昵称（空=「我」）。 */
    val myName: String = "",
    /** 我的头像 emoji（空则回退昵称首字）。 */
    val myEmoji: String = "",
    /** 我的头像底色渐变索引（0-5，默认 1=蓝，与 Agent 默认绿区分）。 */
    val myGradient: Int = 1,
    /** 我的头像图片路径（非空时优先于 emoji）。 */
    val myAvatarPath: String? = null,
    /** 我的签名（预留，抽屉副标题等处可展示）。 */
    val myBio: String = "",
    val onboarded: Boolean = false,
    val tokenInTotal: Long = 0,
    val tokenOutTotal: Long = 0,
    /** 今日用量（跨天自动归零）。tokenDay 为 yyyy-MM-dd。 */
    val tokenDay: String = "",
    val tokenInToday: Long = 0,
    val tokenOutToday: Long = 0,
    /** 端侧推理上下文窗口（llama.cpp -c 参数）。系统提示+工具约 5K token，32K 与引擎本地压缩窗口对齐；KV 内存随窗口线性增长。 */
    val localContextLength: Int = 32768,
    /** 上次记忆本地自动备份时间（毫秒）。 */
    val lastMemoryBackupAt: Long = 0,
    /** 上次记忆导出时间（毫秒）。 */
    val lastMemoryExportAt: Long = 0,
    /**
     * 数据备份：勾选的范围（[com.haoai.agent.platform.BackupScope] 名字）。
     * 空 = 全五个域（默认全备，让用户少一次"我是不是漏勾了"的担心）。
     */
    val backupScopes: List<String> = emptyList(),
    /** 备份包里是否带明文 API Key。默认关：zip 一旦外泄等于账号外泄。 */
    val backupIncludeKeys: Boolean = false,
    /** 破坏性操作（压缩/删除会话）前自动留本机快照。 */
    val backupSnapshots: Boolean = true,
    /** 上次导出到设备外的时间（毫秒）。0 = 从未导出。 */
    val lastDataExportAt: Long = 0,
    /**
     * 联网搜索主后端（同一时间只生效一家）：见 `SearchProviders.CATALOG`。
     * 默认 builtin —— 装上就能联网，配 key 只是把结果质量换上来。
     */
    val searchBackend: String = "builtin",
    /** 搜索结果条数（各家都按这个数截断，进上下文的量由此控制）。 */
    val searchCount: Int = 5,
    /** 主后端不可用（配额尽/超时/空结果）时退回内置免 key 链，不让任务直接失败。 */
    val searchFallback: Boolean = true,
    /** 各家 API Key：backend id → Keystore 密文。明文不落盘，备份默认剔除该字段。 */
    val searchApiKeyCiphers: Map<String, String> = emptyMap(),
    /** 非密钥配置，按 `后端.字段` 存（searxng.url / doubao.mode / custom.template …）。 */
    val searchOptions: Map<String, String> = emptyMap(),
    /**
     * 各家「测试搜索」的最近一次结果：backend → `时间戳|耗时ms|条数`（失败记 `时间戳|0|-1`）。
     * 「正在使用」那张卡要说"上次测试 4.9 秒 / 5 条"，就得跨页面留着——
     * 只放内存里的话返回设置页再进来就空了，那张卡会退化成一句没有证据的自我表扬。
     */
    val searchTestResults: Map<String, String> = emptyMap(),
    /**
     * S6 特性开关的用户覆盖：flagKey → 是否开启。
     * 只存"与默认值不同"的项（见 HaoFlag.compactOverrides），未出现的 key 走枚举默认。
     */
    val enabledFlags: Map<String, Boolean> = emptyMap(),
    /** 上次记忆固化时间与结果（健康度仪表盘展示）。 */
    val lastConsolidationAt: Long = 0,
    val lastConsolidationReport: String = "",
    /** 4.3 虚拟屏后台自动化总开关（默认关；开启后 vscreen_* 工具才注册，且要求 API 30+）。 */
    val vscreenEnabled: Boolean = false,
    /** 4.3 虚拟屏画面码率档位（kbps，可选项 1500/3000/5000/10000/20000）；映射截图分辨率与 JPEG 质量。 */
    val vscreenBitrateKbps: Int = 3000,
    /** 4.3 运行时任务视图隐藏：Agent 运行期间把本应用任务从最近任务中隐藏，避免被误滑关闭。 */
    val vscreenHideTask: Boolean = false,
    /** 4.3 无障碍适配模式：有 TalkBack 等无障碍服务时禁用手势注入（会被接管），操作走节点语义并逐步朗读。 */
    val a11yAdaptiveMode: Boolean = false,
    /** 5.1 Token 每日预算（输入+输出合计，千 token 为单位避免输入大数）；0=不限。 */
    val dailyTokenBudgetK: Int = 0,
    /** 5.2 Provider 降级链（有序 providerId 备用列表）；空=不降级。 */
    val fallbackChain: List<String> = emptyList(),
    /** 5.3 内部任务模型路由：记忆提取（空=主模型；"local"=端侧）。 */
    val memoryExtractProviderId: String = "",
    /** 5.3 会话标题生成模型。 */
    val titleProviderId: String = "",
    /** 5.3 上下文压缩摘要模型。 */
    val summarizeProviderId: String = "",
    /**
     * 能力委派：主模型缺某模态时由委派模型代看/代听。
     * 值为 "providerId" 或 "providerId|modelId"；空=不启用委派（仅靠 shell 绕行）。
     * vision=图像代看（模型需支持 image-in）；asr=音频转写（模型需支持 audio-in）。
     */
    val visionProviderId: String = "",
    val asrProviderId: String = "",
    /** 5.4 聊天会话模型：格式 "providerId" 或 "providerId|modelId"（同供应商多模型时精确到模型）。空=跟随供应商默认。 */
    val chatPurposeId: String = "",
    /** 用途模型备用链：主目标请求失败时按序降级。 */
    val memoryExtractFallbackIds: List<String> = emptyList(),
    val titleFallbackIds: List<String> = emptyList(),
    val summarizeFallbackIds: List<String> = emptyList(),
    /** 聊天模型备用链（同 purpose 结构，元素可为 "providerId" 或 "providerId|modelId"）。 */
    val chatFallbackIds: List<String> = emptyList(),
    /** E5b 成本熔断总开关：关闭后交互聊天与无人值守（定时/工作流）均不再自动收尾（失控任务会持续消耗 token）。 */
    val costBreakerEnabled: Boolean = true,
    /** E5 单轮 LLM token 累计上限（prompt+completion，交互聊天口径）；0=不限。无人值守（定时/工作流）固定 15 万硬限，不受此项影响。 */
    val turnTokenCap: Int = 250_000,
    /** E5b 圈数熔断：单轮工具调用累计上限；0=不限。 */
    val toolCallCap: Int = 80,
    /** E5b 软提醒：达单轮 token 上限 70% 时注入一次精简收尾提醒。 */
    val softBudgetWarn: Boolean = true,
    /** 设置结构版本（迁移用）：1 = 熔断分级改造；2 = 模型条目模态字段（vision 物化为 inputModalities）。 */
    val settingsVersion: Int = 0,
    /** E5 连续工具失败熔断阈值（复用 E3 计数）；0=仅 token 熔断。 */
    val consecutiveToolFailCap: Int = 8,
    /** B3 智能审批：命中需审批的操作先用辅助模型评审（APPROVE/DENY/ESCALATE）；失败一律升级人工。 */
    val smartApproval: Boolean = false,
    /** B6 会话工具组预设：null=按会话 activeGroups；"minimal"/"coding"/"full"。 */
    val toolProfile: String = "",
    /** 玻璃质感参数（主题外观设置页调，全 App 共享一套；默认=实验室定稿 16×1+blur4）。 */
    val glass: GlassParams = GlassParams()
)

/**
 * 玻璃质感持久化参数（2026-10-08）：真源在这里，运行态镜像是
 * [com.haoai.agent.ui.theme.GlassTuning]（单例 State，玻璃组件逐帧读）。
 * 字段与默认值和 GlassTuning 的 DEFAULT_* 一一对应。
 */
@Serializable
data class GlassParams(
    /** 背景模糊 dp（全 App 玻璃统一） */
    val blur: Float = 4f,
    /** 折射环带宽度 dp */
    val lensHeight: Float = 16f,
    /** 折射强度倍数（位移=高度×倍数） */
    val lensAmountMul: Float = 1f,
    /** 卡片白雾（表面不透明度） */
    val veil: Float = 0.48f,
    /** 卡片圆角 dp */
    val corner: Float = 16f,
    /** 整面折射 */
    val lensFull: Boolean = false,
    /** 色差 */
    val ca: Boolean = true,
    /** 顶栏背景模糊 dp（顶栏有标题文字，磨砂比卡片厚一档） */
    val barBlur: Float = 15f,
    /** 顶栏白雾 */
    val barVeil: Float = 0.55f,
    /** 输入框背景模糊 dp */
    val inputBlur: Float = 4f,
    /** 输入框白雾（2026-10-08 独立于卡片：输入框常年压在正文上，用户要单独调） */
    val inputVeil: Float = 0.48f,
    /** 页面玻璃卡磨砂 dp（设置/管理/抽屉页卡片独立档，2026-10-09 方案A拆分；
     *  blur 继续管聊天卡片/弹层/任务面板） */
    val pageBlur: Float = 4f,
    /** 页面玻璃卡白雾（独立档；取代旧"壁纸深浅≠主题深浅时写死 0.50"分支——
     *  滑杆在任何壁纸/主题组合下恒生效） */
    val pageVeil: Float = 0.48f
)

class SettingsStore(context: Context) {

    private val file: File = File(context.filesDir, "settings/settings.json")

    // 设置写入频率低但序列化+磁盘 IO 不该卡主线程；单线程串行保证顺序
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Serializable
    private data class Wrapped(val settings: AppSettings = AppSettings())

    fun load(): AppSettings {
        val loaded = HaoJson.readJsonSafe(file, Wrapped.serializer())?.settings ?: AppSettings()
        var s = loaded
        if (s.settingsVersion < 1) {
            // 一次性迁移：settingsVersion 引入前，用户没显式改过（仍是旧默认 150k）的交互上限放宽到 250k。
            // 显式设过其他值（含 0=不限）的尊重原值；迁移后版本号置 1，下次保存落盘。
            if (s.turnTokenCap == 150_000) s = s.copy(turnTokenCap = 250_000)
            s = s.copy(settingsVersion = 1)
            save(s)
        }
        if (s.settingsVersion < 2) {
            // 一次性迁移：旧 vision 三态物化为 inputModalities（image 维度），UI 徽章与
            // CapabilityResolver 从此有确定数据；vision 字段保留不删（兼容回滚与旧读取点）。
            s = s.copy(
                providers = s.providers.map { p ->
                    p.copy(models = p.models.map { e ->
                        if (e.inputModalities == null && e.vision != null) {
                            e.copy(
                                inputModalities = if (e.vision == true) listOf("text", "image") else listOf("text"),
                                outputModalities = e.outputModalities ?: listOf("text")
                            )
                        } else e
                    })
                },
                settingsVersion = 2
            )
            save(s)
        }
        return s
    }

    fun save(settings: AppSettings) {
        io.execute {
            runCatching {
                HaoJson.writeAtomic(file, HaoJson.json.encodeToString(Wrapped.serializer(), Wrapped(settings)))
            }.onFailure {
                android.util.Log.w("HaoSettings", "设置保存失败（检查文件属主/权限）: ${it.message}")
            }
        }
    }
}
