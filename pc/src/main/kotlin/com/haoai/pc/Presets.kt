package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 预设 agent（角色卡）：一套"人设 + 模型 + 工作区 + 档位"存起来，新会话一键带上。
 *
 * 为什么要：这台机器上的活天生是几种不同的角色 —— 直播时口述要快（auto 档、便宜模型），
 * 剪视频要的是媒体工具与那个装素材的目录，写安卓代码要的是 HaoAI 仓库加"问一句再动手"。
 * 每次开会话都重挑一遍模型、重换一次工作区、重设一遍档位，三件事里总会漏一件，
 * 而漏的那一件通常要到跑错了才发现（在错的目录里写文件最贵）。
 *
 * 刻意**不**存"关掉的工具"：会话级工具开关（v0.40）已经在那条会话上了，
 * 角色卡再叠一层白名单，出问题时得同时看两处才知道是谁关的 —— 收益不值这个账。
 *
 * 专家卡字段（desc/icon/color/mbti/quick）是角色卡的展示层升级：原来列表里只有名字，
 * 人设全文塞 title 悬停才看得见 —— "专家"要的是一张能扫一眼就知道谁擅长什么的卡片。
 * 全部可缺省：老 presets.json 原样读入，一个字段都不补。
 */
/**
 * 快捷提问（对标 Octop 的 quick_prompts：`{title, description, prompt}`）。
 *
 * 为什么不是一个字符串就够：卡上那颗按钮要写"能短到一眼扫完"的名字，
 * 而发出去的那句要写全（带上下文与要求）。以前两者是同一句话，于是按钮上
 * 挂着一整段话，要么按钮撑爆、要么提示词被削短到模型看不懂。
 * `desc` 是悬停/副标题，用来解释"这条会产出什么"。
 */
data class QuickPrompt(
    val title: String,
    val desc: String = "",
    /** 留空 = 就发 title 这句（老卡只有文字，迁移时正是这样）。 */
    val prompt: String = ""
) {
    /** 真正发出去的那句。 */
    fun text(): String = prompt.ifBlank { title }
}

data class Preset(
    val id: String,
    val name: String,
    /** 塞进系统提示的一段话：这个角色是谁、要产出什么、有什么规矩。 */
    val persona: String,
    /** 留空 = 跟全局默认模型。 */
    val model: String,
    /** 留空 = 跟全局工作区。 */
    val workspace: String,
    /** plan / ask / auto；留空或非法 = 跟全局默认档位。 */
    val mode: String,
    /** 一句专长描述（卡片第二行）。 */
    val desc: String = "",
    /** 头像字符：emoji 或单字；留空取名字首字。 */
    val icon: String = "",
    /** 头像底色 #RRGGBB；留空用哈希色。 */
    val color: String = "",
    /** 四字母 MBTI，纯展示徽标。 */
    val mbti: String = "",
    /** 快捷提问：专家卡上点一下就带着这套配置发出去。 */
    val quick: List<QuickPrompt> = emptyList(),
    /**
     * 绑定的知识库 id（对标 Octop 的 `agents.knowledge_base_ids`）。
     *
     * 为什么绑在卡上而不是全局：知识库的价值是"这个角色说话有依据"。
     * 全局挂十个库，每一轮都给模型看一份目录，是把窗口当仓库用；
     * 绑在卡上，剪辑专家只看剪辑规范，法务卡只看制度文件。
     * 空 = 只带"每回合自动带上"的那些库（[Knowledge.defaultOpenIds]）。
     */
    val kbs: List<String> = emptyList(),
    /**
     * 关着的卡不出现在选择列表里，也不能被点名开会话 / 被 agent 派工。
     *
     * 为什么要有"关"这个态而不是直接删：卡是会在手里改的（人设、绑定、参数），
     * 改到一半不想被人用，但删了那些现场就没了。
     */
    val enabled: Boolean = true,
    /** 新会话空场时显示的那句话（对标 Octop 的 welcome_message）+ 下面挂快捷提问。 */
    val welcome: String = "",
    /**
     * 每卡运行参数：-1 = 不覆盖全局设置（哨兵值见 [NO_OVERRIDE]）。
     * 默认值这里写的是字面量 —— Kotlin 的主构造函数默认值取不到伴生对象的简单名，
     * 而 `PresetFieldsTest` 有一条把"默认值 == 哨兵"钉住，改一边就会红。
     */
    val temperature: Double = -1.0,
    val maxTokens: Int = -1,
    val maxTurns: Int = -1,
    val created: Long = System.currentTimeMillis()
) {
    /** 头像字符的统一出口：没设 icon 就拿名字第一个字。 */
    fun avatarChar(): String = icon.ifBlank { name.take(1) }

    companion object {
        /**
         * "这一项不覆盖"的哨兵值。
         *
         * 为什么不用 0：0 对 temperature 是合法值（确定性输出）、对 maxTokens 是
         * "不发这个字段"，用 0 当"没设"就永远表达不了"我要把温度设成 0"。
         */
        const val NO_OVERRIDE = -1
    }
}

object Presets {
    /**
     * 上限从 12 提到 24：内置专家库上线后"启用一个"是一句话的事，
     * 12 张很容易撞顶，而撞顶的报错出现在用户点了"启用"之后，最扫兴。
     */
    const val MAX = 24

    val MODES = listOf("plan", "ask", "auto")

    fun file(): File = File(Env.home, "presets.json")

    fun newId(): String = "pr" + System.nanoTime().toString(16).take(8)

    /**
     * 一条卡的全部字段 —— `save` / `json` / `exportJson` 三处都从这里出。
     *
     * 为什么值得收成一处：以前这三处各抄一遍字段清单，加一个字段要记得改三处，
     * 漏掉的那处的表现是"存进去再打开就没了"（`Schedules` 那次丢的就是 `created`）。
     * 现在加字段只改 [fields] 与 [parse] 两处，而 `PresetFieldsTest` 会拿
     * "每个字段都过一遍存与读"钉住它们对得上。
     */
    private fun fields(p: Preset): String =
        """"id":${js(p.id)},"name":${js(p.name)},"persona":${js(p.persona)},""" +
            """"model":${js(p.model)},"workspace":${js(p.workspace)},"mode":${js(p.mode)},""" +
            """"desc":${js(p.desc)},"icon":${js(p.icon)},"color":${js(p.color)},"mbti":${js(p.mbti)},""" +
            """"quick":[${p.quick.joinToString(",") { q ->
                """{"title":${js(q.title)},"desc":${js(q.desc)},"prompt":${js(q.prompt)}}"""
            }}],"kbs":${p.kbs.joinToString(",", "[", "]") { js(it) }},""" +
            """"enabled":${p.enabled},"welcome":${js(p.welcome)},""" +
            """"temperature":${p.temperature},"maxTokens":${p.maxTokens},"maxTurns":${p.maxTurns},""" +
            """"created":${p.created}"""

    /** 读一条卡：字段名与 [fields] 一一对应；缺省一律走 data class 的默认值。 */
    private fun parse(el: JsonElement): Preset? {
        val o = runCatching { el.jsonObject }.getOrNull() ?: return null
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return Preset(
            id = id,
            name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
            persona = o["persona"]?.jsonPrimitive?.contentOrNull ?: "",
            model = o["model"]?.jsonPrimitive?.contentOrNull ?: "",
            workspace = o["workspace"]?.jsonPrimitive?.contentOrNull ?: "",
            mode = (o["mode"]?.jsonPrimitive?.contentOrNull ?: "").takeIf { it in MODES } ?: "",
            desc = o["desc"]?.jsonPrimitive?.contentOrNull ?: "",
            icon = o["icon"]?.jsonPrimitive?.contentOrNull ?: "",
            color = o["color"]?.jsonPrimitive?.contentOrNull ?: "",
            mbti = (o["mbti"]?.jsonPrimitive?.contentOrNull ?: "").uppercase(),
            // 老卡是 ["一句话", …]，新卡是 [{title,desc,prompt}, …]：两种都读，写出去只有一种
            quick = (o["quick"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { q ->
                // runCatching 是必须的：老卡的 quick 是 ["一句话"]，对字符串取 jsonObject 会抛，
                // 而外面 load() 那层 runCatching 会把整份文件吞成"没有卡" —— 表现是升级之后
                // 所有专家卡一起消失。PresetFieldsTest 里那条 legacy 用例抓的就是这个。
                val oq = runCatching { q.jsonObject }.getOrNull()
                val t = oq?.get("title")?.jsonPrimitive?.contentOrNull
                if (t != null) QuickPrompt(t,
                    oq?.get("desc")?.jsonPrimitive?.contentOrNull ?: "",
                    oq?.get("prompt")?.jsonPrimitive?.contentOrNull ?: "")
                else q.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { QuickPrompt(it) }
            },
            kbs = o["kbs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            welcome = o["welcome"]?.jsonPrimitive?.contentOrNull ?: "",
            temperature = o["temperature"]?.jsonPrimitive?.doubleOrNull
                ?: Preset.NO_OVERRIDE.toDouble(),
            maxTokens = o["maxTokens"]?.jsonPrimitive?.intOrNull ?: Preset.NO_OVERRIDE,
            maxTurns = o["maxTurns"]?.jsonPrimitive?.intOrNull ?: Preset.NO_OVERRIDE,
            created = o["created"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        )
    }

    fun load(): MutableList<Preset> = runCatching {
        val root = Json.parseToJsonElement(file().readText())
        val arr = if (root is JsonArray) root else root.jsonObject["items"]?.jsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { parse(it) }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(list: List<Preset>) {
        val body = list.joinToString(",", "[", "]") { "{${fields(it)}}" }
        runCatching {
            file().parentFile?.mkdirs()
            file().writeText(body)
        }
    }

    fun update(item: Preset): MutableList<Preset> {
        val list = load()
        val i = list.indexOfFirst { it.id == item.id }
        if (i >= 0) list[i] = item else { if (list.size >= MAX) return list; list += item }
        save(list)
        return list
    }

    fun remove(id: String): Boolean {
        val list = load()
        val kept = list.filterNot { it.id == id }
        if (kept.size == list.size) return false
        save(kept)
        return true
    }

    fun find(id: String): Preset? = load().firstOrNull { it.id == id }

    /**
     * 这张卡现在能不能拿来开会话。
     *
     * 返回 `null` = 能用；否则是给界面看的那句话。**关着的卡不是删掉的卡**：
     * 它还在列表里（灰着），但点名它必须被明确拒绝并说明原因 ——
     * 静默改用别的卡、或者开一条没有角色的会话，都是把"我没生效"藏起来。
     */
    fun usable(id: String): String? {
        if (id.isBlank()) return null
        val p = find(id) ?: return "没有这个角色卡，可能已经被删掉了（$id）"
        if (!p.enabled) return "「${p.name}」已经关掉，去「专家」页打开再用"
        return null
    }

    /** 预设里那个目录现在还在不在。不在就宁可报错，也不悄悄把人放进他没选的仓库。 */
    fun workspaceOf(p: Preset): File? =
        p.workspace.takeIf { it.isNotBlank() }?.let { runCatching { File(it).canonicalFile }.getOrNull() }
            ?.takeIf { it.isDirectory }

    /** 档位只认这三个值，别的（手改过的 json）一律回落到全局默认。 */
    fun modeOf(p: Preset, fallback: String): String = p.mode.takeIf { it in MODES } ?: fallback

    /**
     * 卡上的运行参数盖到设置上（返回副本，设置本身不可变）。
     *
     * 只覆盖真设过的那些（!= [Preset.NO_OVERRIDE]）：口述用的卡要温度高、
     * 改代码的卡要 maxTokens 大，而"这张卡没写"的那几项必须继续跟全局走 ——
     * 全部写死等于每张卡都偷偷改了别人的默认值。
     */
    fun applyTo(p: Preset, s: PcSettings): PcSettings {
        var n = s
        if (p.temperature != Preset.NO_OVERRIDE.toDouble()) n = n.copy(temperature = p.temperature)
        if (p.maxTokens != Preset.NO_OVERRIDE) n = n.copy(maxTokens = p.maxTokens.coerceAtLeast(0))
        if (p.maxTurns != Preset.NO_OVERRIDE) n = n.copy(maxTurns = p.maxTurns.coerceAtLeast(1))
        return n
    }

    /**
     * 复制一张卡：新 id、名字加"副本"，其余原样（含绑定与参数）。
     * 唯一例外是 `enabled` —— 复制的用途是"照这个样子再改一张"，
     * 复制出一张关着的卡等于让用户先打开它才能编辑，白绕一步。
     */
    fun duplicate(p: Preset): Preset = p.copy(
        id = newId(), name = (p.name + " 副本").take(24), enabled = true,
        created = System.currentTimeMillis()
    )

    /** 单卡导出（给用户备份 / 换机器 / 发给同事）。 */
    fun exportJson(p: Preset): String = "{${fields(p)}}"

    /**
     * 单卡导入：校验 + **换 id**。
     *
     * 为什么一定要换 id：带着原 id 进来会和现有卡撞名，`update` 的语义是"覆盖"，
     * 于是"导入一张卡"变成"删掉我原来那张"。撞车这种事不该靠用户先删。
     */
    fun importJson(text: String): Pair<Preset?, String?> {
        val p = runCatching {
            val root = Json.parseToJsonElement(text)
            // 三种都收：裸卡片、卡片数组、以及 /api/presets 的 export 响应 {ok,card}
            //（用户会把下载下来的那份原样再传回来，那时候它带的是外壳）。
            val el = if (root is JsonArray) root.firstOrNull() ?: return@runCatching null
            else root.jsonObject["card"] ?: root
            parse(el)
        }.getOrNull() ?: return null to "这不是一张能读的角色卡（要的是导出的那份 JSON）"
        if (p.name.isBlank() && p.persona.isBlank()) return null to "这张卡既没有名字也没有人设"
        return p.copy(id = newId(), created = System.currentTimeMillis()) to null
    }

    fun json(): String {
        val items = load().joinToString(",") { "{${fields(it)}}" }
        return """{"ok":true,"items":[$items],"max":$MAX,"modes":${MODES.joinToString(",", "[", "]") { js(it) }},""" +
            """"noOverride":${Preset.NO_OVERRIDE}}"""
    }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}
