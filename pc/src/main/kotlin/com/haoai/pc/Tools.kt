package com.haoai.pc

import com.haoai.core.takeSafe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 两端共用的截断口径（B15）：定义在 `:core`，这里原地 typealias ——
 * 同包同名，7 个引用文件一个 import 都不用加。
 * PC 旧版没有代理对保护、也没有 tail；core 版是手机端原版的超集（口径从此真的只有一份）。
 */
typealias TextCap = com.haoai.core.TextCap

/**
 * 工具结果（B15）：定义在 `:core`，字段顺序沿用 PC 旧版（位置传参不能动），
 * 移动端的 `imageDataUrl` 挂在末尾、PC 不用它。
 */
typealias ToolResult = com.haoai.core.ToolResult

/** ask_user 的一个选项：label 卡上直接显示，desc 是给用户看的一句含义说明（与手机端同款）。 */
data class AskOpt(val label: String, val desc: String = "")

/**
 * ask_user 的完整请求 —— 与手机端 `AskUserRequest` 同一组语义（B22 起两端一致）：
 * [allowFree] 摆不摆自由输入、[confirm] 点选后还要不要再按一下（防误触）、
 * [recommend] 第一个选项标不标「推荐」徽标。
 */
data class AskReq(
    val question: String,
    val options: List<AskOpt>,
    val allowFree: Boolean = true,
    val confirm: Boolean = true,
    val recommend: Boolean = true
)

/** 审批与提问的出口。CLI 与 Web 各实现一份，工具层不关心前面是谁。 */
interface Gate {
    fun approve(title: String, detail: String, kind: String): Boolean
    fun ask(question: String, options: List<String>): String

    /**
     * 带完整参数的提问。默认把标签喂给旧的 [ask] —— 只关心"问什么、选哪个"的
     * 壳（CLI、测试替身）零改动跟上；要携带 desc/徽标/确认步的壳（网页卡、
     * LAN 手机卡）覆盖它。与 approveRule 一族同一个套路：新能力默认退化。
     */
    fun ask(req: AskReq): String = ask(req.question, req.options.map { it.label })

    /**
     * 带"以后这类都允许"的审批。默认实现退化成普通 approve，
     * 这样测试与脚本化场景不用改；CLI 与网页各自覆盖它来落规则。
     */
    fun approveRule(
        title: String,
        detail: String,
        kind: String,
        tool: String,
        pattern: String
    ): Boolean = approve(title, detail, kind)

    /**
     * 带风险分级的版本。默认退化回 5 参那条，
     * 这样 CLI 与所有测试假闸口不用一起改；只有网页壳覆盖它把分级显示到卡上。
     *
     * 传的是 [RiskOf.Verdict] 而不是拼好的字符串：分级有三要素（等级、文案、为什么），
     * 拆成三个参数就会有人按文案匹配 —— 界面改一次措辞，高危红框就静默失效了。
     */
    fun approveRule(
        title: String,
        detail: String,
        kind: String,
        tool: String,
        pattern: String,
        risk: RiskOf.Verdict?
    ): Boolean = approveRule(title, detail, kind, tool, pattern)

    /**
     * 带**逐块选择**的版本：[plan] 非空说明这次改动切得开，卡片上该摆勾选框。
     *
     * 默认实现把 plan 扔掉、退化成整条批准 —— CLI、定时任务、手机点的都是这条路：
     * 前两者没有人可问，手机上只有三个整条按钮。所以"能不能逐块"这件事
     * 由界面能力决定，而不是由工具决定，工具只是把基线交给闸口。
     * 网页壳覆盖它，把人勾选的结果写回 [HunkPlan.keep] 再返回 true。
     */
    fun approveRule(
        title: String,
        detail: String,
        kind: String,
        tool: String,
        pattern: String,
        risk: RiskOf.Verdict?,
        plan: HunkPlan?
    ): Boolean = approveRule(title, detail, kind, tool, pattern, risk)
}

data class Todo(var text: String, var status: String = "pending")

class ToolCtx(
    val workspace: File,
    val settings: PcSettings,
    var mode: String,
    val gate: Gate,
    val todos: MutableList<Todo> = mutableListOf(),
    /** 这一轮的名字，由引擎在建 ctx 时填。空 = 不在任何一轮里（CLI 单发、测试）。 */
    var runId: String = "",
    var sid: String = ""
) {
    /**
     * 派子任务的能力，由引擎在建 ctx 时接上。
     *
     * 为什么是个可空函数而不是让工具自己去 new 一个引擎：引擎才握着设置、权限闸、
     * 事件出口和深度。工具层保持"不知道上面是谁"，CLI/网页/测试才能共用同一套工具。
     */
    var spawn: ((label: String, prompt: String, opts: SubOpts) -> Pair<String, String>)? = null

    fun resolve(p: String): File {
        val clean = p.trim().replace('\\', '/')
        val f = if (File(clean).isAbsolute) File(clean) else File(workspace, clean)
        return runCatching { f.canonicalFile }.getOrElse { f.absoluteFile }
    }

    fun rel(f: File): String = runCatching {
        val ws = workspace.canonicalPath
        val abs = f.canonicalPath
        if (abs == ws) "." else if (abs.startsWith(ws + File.separator))
            abs.substring(ws.length + 1).replace('\\', '/') else abs
    }.getOrElse { f.toString() }

    fun outside(f: File): Boolean = !f.canonicalPath.startsWith(workspace.canonicalPath + File.separator)

    /**
     * 统一闸口：先查规则表（S2），再落到档位。
     *
     * 顺序很要紧 —— **规则先于档位**，否则 `auto` 会把用户专门写下的"这条要问我"给跳过。
     * 反过来 `plan` 是最高优先级：计划模式就是只读，规则表也放行不了写。
     *
     * @param tool 工具名（shell / write / edit …）
     * @param subject 这次动作的对象：shell 传整条命令，写类传相对路径
     * @param subjectIsPath 对象是不是一个文件路径。browser/screen 传的是 URL、坐标、控件名，
     *   必须给 false —— 否则下面那句"在工作区之外"会把一个 URL 当路径判出来，
     *   在审批卡上写出一句驴唇不对马嘴的话（实测过，第一次就踩了）。
     */
    fun guard(
        tool: String,
        subject: String,
        title: String,
        detail: String,
        subjectIsPath: Boolean = true
    ): String? = guardCore(tool, subject, title, { detail }, subjectIsPath, null)

    /**
     * 惰性版本：detail 只在**真的要问人**时才算。
     *
     * 为什么需要：screen 工具的 detail 要查"此刻哪个窗口在前台"，那是一次 PowerShell 调用
     * （约 1 秒）。放在规则/档位的判定之前算，就等于每次被拒绝的动作都白付一次钱，
     * 计划模式下尤其离谱 —— 只读模式根本不会弹框。
     *
     * [plan] 非空时审批卡会摆逐块勾选，人挑完的结果写在 plan 上（见 [HunkPlan]）。
     * 只有 `write`/`edit` 会给：它们才是"一次调用里可能有好几处不相关的改动"的那种动作。
     */
    fun guard(
        tool: String,
        subject: String,
        title: String,
        detail: () -> String,
        subjectIsPath: Boolean = true,
        plan: HunkPlan? = null
    ): String? = guardCore(tool, subject, title, detail, subjectIsPath, plan)

    /**
     * 逐块切分的基线 = 这个文件**现在**的内容。拿不到（不该问人、太大、读不动）就返回 null，
     * 意思是"这次不摆勾选框，按整条审批"。
     *
     * 为什么要在弹卡**之前**读：勾掉的那几块要落回原样，就得先有原样可比。
     * `plan` 档先挡掉：只读档根本不会弹卡，白读一遍大文件（这条与 detail 惰性化是同一个理由）。
     */
    fun diffBase(target: File): String? {
        if (mode == "plan") return null
        if (!target.isFile) return ""
        if (target.length() > Hunks.MAX_BYTES) return null
        return runCatching { target.readText() }.getOrNull()
    }

    /** 切块这件事本身不许把工具搞崩：切不出来就按整条审批，工具照常干活。 */
    fun hunksOf(base: String, target: String): HunkPlan? =
        runCatching { Hunks.plan(base, target) }.getOrNull()

    /**
     * 逐块合并的基线已经过期时要回给模型的那句话。
     *
     * 为什么要有这一句：卡片挂着等人看的这几分钟里，文件完全可能被人改了 —— 用户自己在编辑器里
     * 动了一行、别的会话的 agent 写了同一个文件、甚至格式化工具跑了一遍。这时候按**旧基线**算出来的
     * 块往哪里落都是猜，猜错的代价是盖掉别人的改动。所以一个字都不写，并让模型重读再重提。
     * 整条覆盖不走这条路（它本来就是"以模型给的为准"，与本功能出现之前一致）。
     */
    fun staleBaseline(rel: String): String =
        "没有写入 $rel：这张审批卡挂着的几分钟里，文件内容变了。" +
            "逐块合并是按弹卡时的内容算的，基线一变落点就会错，宁可不写。" +
            "请重新 read 一次 $rel，再按现状重新提这条改动。"

    private fun guardCore(
        tool: String,
        subject: String,
        title: String,
        detail: () -> String,
        subjectIsPath: Boolean,
        plan: HunkPlan?
    ): String? {
        val kind = if (tool == "shell") "exec" else "write"
        val verdict = Policies.get().decide(workspace, tool, subject)

        if (verdict?.decision == Decision.DENY) {
            return "规则拒绝：${verdict.why}。这条被显式禁掉了，别再重试，换方案或用 ask_user 问用户。"
        }
        if (mode == "plan") {
            return "计划模式（只读）下拒绝执行「$title」。要动手请先切到 ask/auto 档位。"
        }
        if (verdict?.decision == Decision.ALLOW) return null
        val out = subjectIsPath && kind == "write" && outside(resolve(subject))
        val exists = subjectIsPath && kind == "write" &&
            runCatching { resolve(subject).isFile }.getOrDefault(false)
        val risk = RiskOf.of(tool, subject, detail(), out, exists)
        // 用户显式开了"允许写到工作区外"之后，"在外面"这一项不再计入风险；
        // 但**只摘掉这一项**：强推、删文件、覆盖已有文件那些照样拦。
        // 不这么做的话这个开关就成了"关掉整个审批"的别名，而它的名字只承诺了"允许写到外面"。
        val outAllowed = out && HaoFlag.enabled(HaoFlag.OUTSIDE_WRITE, settings.flags)
        val effective = if (outAllowed) RiskOf.of(tool, subject, detail(), false, exists) else risk
        /*
         * 沙箱第一层：**auto 档不许往工作区外写**（要往外卖得自己在设置里开）。
         *
         * 原来这里已经会弹卡（高危不跳审批），但那等于"夜里白等五分钟再自动拒"：
         * 定时任务与任务链都以 auto 档跑，没人看卡，模型最后收到的那句是
         * "超时未答，按拒绝处理" —— 看不出是边界问题还是网关问题，于是它会换个写法再试一次。
         * 当场拒 + 说清为什么 + 说清怎么显式允许，才是这一档该有的样子。
         * ask 档**故意不改**：人在电脑前，弹卡让他点是对的，凭什么替他决定。
         *
         * 只管 `write`/`edit` 这两个"按模型给的路径落文件"的工具，**不管 media/record**：
         * 后者是"把产物导到用户指定的输出路径"，素材库在 D:\ 是常态，
         * 那条路自己已经有判据（工作区外的产物不进页面、正文里写明"在工作区外面"，
         * 见 MediaTest `files outside the workspace are not offered to the page`）。
         * 第一版没想清这一层，把 media 导出也一起挡了 —— 是整套测试跑出来才看见的，
         * 不是想出来的。**边界要挡的是"改坏别人的文件"，不是"生成一个大文件"。**
         */
        val boundaryTool = tool == "write" || tool == "edit"
        if (out && boundaryTool && mode == "auto" && !outAllowed) {
            /*
             * 顺序是量出来的：工具结果那一行在界面上只看得见前九十个字符
             * （截图里第一版把绝对路径写在第二句，结果"怎么办"整段被截没 ——
             * 一条说不清该怎么办的拒绝，等于让人或模型自己猜）。
             * 所以：**结论 → 怎么办 → 才是要动哪个文件**。
             * 路径用模型自己写的那个（多半是相对的），别摆绝对路径占字数。
             * 也不用 `**加粗**`：这一行按纯文本渲染，星号会原样显示出来。
             */
            return "已拒绝：这条要写到工作区之外。要允许就开 haoai flags on outside_write；" +
                "或把路径改到工作区里。别原样重试，也别改用 shell 绕（那层这规则管不到，但风险闸照样拦）。" +
                "被拒的路径：$subject"
        }
        /*
         * auto 档跳过审批，但**高危不跳**。
         *
         * 理由很具体：定时任务与任务链都以 auto 档跑，而链里完全可能出现
         * `git push --force` 或"删工作区外的文件"。以前 auto 就是"全放行"，
         * 等于把不可逆的那一下也交给了模型自己决定。现在低/中危照旧自动过，
         * 高危仍然弹卡 —— 而卡可以在手机上点（v0.55 那条路），不会真把人堵在工位外。
         */
        if (mode == "auto" && effective.level != Risk.HIGH && verdict?.decision != Decision.ASK) return null

        val extra = if (out) "（在工作区之外）" else ""
        val why = if (verdict != null) "\n为什么还要问：${verdict.why}" else ""
        val pattern = if (tool == "shell") PolicyStore.commandPrefix(subject) else subject
        val ok = gate.approveRule(title, detail() + extra + why, kind, tool, pattern, effective, plan)
        return if (ok) null else "用户拒绝了这次「$title」。不要原样重试，换个方案或用 ask_user 问清楚。"
    }

    /**
     * 改之前留一份，并**回给检查点账本**。
     *
     * 返回快照文件（没快照就返回 null）不是为了好看：`/rewind` 要区分
     * "这轮改坏了它"（有快照 ⇒ 还原）与"这轮新建了它"（没快照 ⇒ 删掉）。
     * 开关关着时不留快照，也就没法回滚 —— 这时候如实返回 null，
     * 界面上那句"这一轮没登记改动"才是真话。
     */
    fun snapshotBefore(target: File): File? {
        if (!target.isFile) {
            Checkpoints.note(this, target, null)
            return null
        }
        if (!HaoFlag.enabled(HaoFlag.SNAPSHOT_BEFORE_WRITE, settings.flags)) {
            Checkpoints.note(this, target, null)
            return null
        }
        return runCatching {
            val dir = File(workspace, ".haoai-snap").apply { mkdirs() }
            Env.excludeFromGit(workspace, ".haoai-snap")
            // 同一毫秒里改同一个文件两次是真会发生的（一轮里连续两个 edit）。
            // 名字撞车会把**最早**那份盖掉，而回滚要的恰好就是最早那份。
            val key = "${System.currentTimeMillis()}-${Snapshots.keyOf(workspace, target)}"
            var snap = File(dir, key)
            var n = 1
            while (snap.exists()) { snap = File(dir, "$key-${n++}") }
            target.copyTo(snap, overwrite = false)
            Checkpoints.note(this, target, snap)
            snap
        }.getOrNull()
    }
}

/**
 * 写改前的快照。
 *
 * 文件名以前只用 `时间戳-裸文件名`，于是 `src/A.md` 与 `docs/A.md` 会写进同一份快照，
 * 回滚时把错目录的内容盖回来 —— 而"回滚"恰恰是用户最信任的一步。
 * 现在键里带上**相对路径**（分隔符换成 __），回滚才有唯一的对应关系。
 */
object Snapshots {
    fun dir(workspace: File): File = File(workspace, ".haoai-snap")

    fun keyOf(workspace: File, target: File): String {
        val rel = runCatching {
            val ws = workspace.canonicalPath
            val t = target.canonicalPath
            if (t.startsWith(ws)) t.substring(ws.length).trimStart('\\', '/') else target.name
        }.getOrDefault(target.name)
        val flat = rel.replace('\\', '/').replace("/", "__")
        return if (flat.isBlank()) target.name else flat
    }

    /** 某个文件的历史快照，新的在前。 */
    fun forPath(workspace: File, rel: String): List<File> {
        val key = keyOf(workspace, workspace.resolveRel(rel))
        val dir = dir(workspace)
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles { f -> f.name.endsWith("-$key") }?.toList() ?: emptyList())
            .sortedByDescending { it.name.substringBefore('-').toLongOrNull() ?: 0L }
    }

    /** 回滚到最近一份快照。返回用了哪份快照（界面上要说清"回到了几点几分的样子"）。 */
    fun restore(workspace: File, rel: String): String? {
        val target = workspace.resolveRel(rel)
        val snap = forPath(workspace, rel).firstOrNull() ?: return null
        return runCatching {
            val ts = snap.name.substringBefore('-').toLongOrNull()
            snap.copyTo(target, overwrite = true)
            if (ts != null) java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime().toString().replace('T', ' ') else snap.name
        }.getOrNull()
    }
}

/** 相对/绝对都接受，且拒绝跑出工作区的 `..`。 */
internal fun File.resolveRel(p: String): File {
    val f = if (File(p).isAbsolute) File(p) else File(this, p)
    return runCatching { f.canonicalFile }.getOrElse { f.absoluteFile }
}

abstract class Tool(
    name: String,
    desc: String,
    params: JsonObject,
    val kind: String = "read",
    /**
     * 挂在这个工具上的实验特性。不为 null 且开关关着时，工具**不进 schema** ——
     * 模型看不见它，也就不会去调、不会调通了再收到一句"没权限"。
     * 这就是 S6 开关注册表存在的意义：新能力可以"先上代码，再按需给可见性"。
     * （`flag` 是 PC 端私有字段，不在 [AgentTool] 接口里 —— 手机端没有这套开关。）
     */
    val flag: HaoFlag? = null
) : com.haoai.core.AgentTool<ToolCtx> {
    override val name: String = name
    override val desc: String = desc
    override val params: JsonObject = params

    fun visibleWhen(settings: PcSettings): Boolean =
        flag == null || HaoFlag.enabled(flag, settings.flags)

    /**
     * suspend 是两端共用契约（B15）：手机端工具真在协程里跑，PC 这边函数体照旧是
     * 普通阻塞代码 —— 调用点（引擎的工具执行处）用 `runBlocking` 包一层，
     * 引擎本来就活在自己的线程上，包一层不改变调度语义。
     */
    abstract override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult

    fun req(args: JsonObject, k: String): String? = args[k]?.jsonPrimitive?.content
    fun int(args: JsonObject, k: String, dflt: Int): Int = args[k]?.jsonPrimitive?.content?.toIntOrNull() ?: dflt
    fun bool(args: JsonObject, k: String): Boolean = args[k]?.jsonPrimitive?.content == "true"

    protected fun fail(msg: String) = ToolResult(msg, error = true)
}

class ReadTool : Tool(
    "read", "读取文件内容，返回带行号。大文件用 offset/limit 分段读。",
    schema("path" to "string", "offset" to "integer", "limit" to "integer", required = arrayOf("path"))
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val path = req(args, "path") ?: return fail("read 缺少 path")
        val f = ctx.resolve(path)
        if (!f.isFile) return fail("文件不存在：${ctx.rel(f)}")
        if (f.length() > 4_000_000) return fail("文件过大（${f.length()} 字节），先用 grep 定位再按行读")
        return try {
            val lines = f.readLines()
            val offset = (req(args, "offset")?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val limit = (req(args, "limit")?.toIntOrNull() ?: 400).coerceIn(1, 2000)
            if (offset > lines.size) return fail("offset 超出总行数 ${lines.size}")
            val end = minOf(lines.size, offset + limit - 1)
            val sb = StringBuilder("[${ctx.rel(f)}] 共 ${lines.size} 行，显示 $offset-$end\n")
            for (i in offset..end) sb.append(String.format("%5d: %s%n", i, TextCap.head(lines[i - 1], 500)))
            ToolResult(sb.toString().trimEnd())
        } catch (e: Exception) {
            fail("读取失败：${e.message}")
        }
    }
}

class WriteTool : Tool(
    "write", "新建或整篇覆盖一个文件。改已有文件请优先用 edit，整篇覆盖会丢掉你没看到的行。",
    schema("path" to "string", "content" to "string", required = arrayOf("path", "content")),
    kind = "write"
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val path = req(args, "path") ?: return fail("write 缺少 path")
        val content = args["content"]?.jsonPrimitive?.content ?: return fail("write 缺少 content")
        val f = ctx.resolve(path)
        /*
         * 基线要在**弹卡之前**拿到，不然"勾掉的那几块保持原样"就无从算起。
         * 代价是自动档也白读一次文件（多一遍 readText + 一次切块，几十毫秒级）：
         * 换回来的正是"夜里那条高危卡也能逐块挑"，比省一遍读值钱。
         */
        val base = ctx.diffBase(f)
        val plan = base?.let { ctx.hunksOf(it, content) }
        val why = ctx.guard(
            "write", ctx.rel(f), "写入文件 ${ctx.rel(f)}",
            { "新建或覆盖，共 ${content.length} 字符" + (plan?.offer ?: "") },
            plan = plan
        )
        if (why != null) return fail(why)
        return try {
            if (plan != null && ctx.diffBase(f) != base) return fail(ctx.staleBaseline(ctx.rel(f)))
            val before = base ?: if (f.isFile) f.readText() else ""
            val toWrite = plan?.content() ?: content
            ctx.snapshotBefore(f)
            f.parentFile?.mkdirs()
            f.writeText(toWrite)
            ToolResult(
                if (plan?.partial == true) plan.outcome(ctx.rel(f), toWrite)
                else "已写入 ${ctx.rel(f)}（${toWrite.length} 字符 / ${toWrite.lines().size} 行）",
                card = "diff", diff = Diff.unified(ctx.rel(f), before, toWrite)
            )
        } catch (e: Exception) {
            fail("写入失败：${e.message}")
        }
    }
}

class EditTool : Tool(
    "edit", "文件内精确替换。old_string 必须在文件中唯一，除非 all=true。",
    schema(
        "path" to "string", "old_string" to "string", "new_string" to "string", "all" to "boolean",
        required = arrayOf("path", "old_string", "new_string")
    ),
    kind = "write"
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val path = req(args, "path") ?: return fail("edit 缺少 path")
        val old = args["old_string"]?.jsonPrimitive?.content ?: return fail("edit 缺少 old_string")
        val new = args["new_string"]?.jsonPrimitive?.content ?: return fail("edit 缺少 new_string")
        val all = bool(args, "all")
        val f = ctx.resolve(path)
        if (!f.isFile) return fail("文件不存在：${ctx.rel(f)}")
        /*
         * 匹配检查挪到审批**之前**了。
         *
         * 原来是人批完五分钟、写到一半才发现 old_string 根本没找到 —— 那张卡白等了，
         * 而"逐块"还需要先有替换后的内容才能切。这条改动的对错与放不放行无关，
         * 先问"能不能做"再问"让不让做"才是省人的顺序。
         */
        val text = runCatching { f.readText() }.getOrNull()
            ?: return fail("读不到 ${ctx.rel(f)}，没改任何东西")
        val hits = text.split(old).size - 1
        if (hits == 0) return fail("没找到要替换的内容。先用 read 看清当前文本（注意缩进与换行是否一致）。")
        if (hits > 1 && !all) return fail("匹配到 $hits 处，不唯一。扩大 old_string 的上下文，或传 all=true。")
        val updated = if (hits == 1) text.replaceFirst(old, new) else text.replace(old, new)
        // all=true 的替换常常散在好几处 —— 那正是"整条批太粗"的场景：一次调用里第 1 处对、第 3 处不对
        val plan = ctx.hunksOf(text, updated)
        val why = ctx.guard(
            "write", ctx.rel(f), "编辑 ${ctx.rel(f)}",
            { "${old.length} → ${new.length} 字符（匹配 $hits 处）" + (plan?.offer ?: "") },
            plan = plan
        )
        if (why != null) return fail(why)
        return try {
            if (plan != null && ctx.diffBase(f) != text) return fail(ctx.staleBaseline(ctx.rel(f)))
            val toWrite = plan?.content() ?: updated
            ctx.snapshotBefore(f)
            f.writeText(toWrite)
            ToolResult(
                if (plan?.partial == true) plan.outcome(ctx.rel(f), toWrite)
                else "已编辑 ${ctx.rel(f)}（替换 ${if (all) hits else 1} 处）",
                card = "diff", diff = Diff.unified(ctx.rel(f), text, toWrite)
            )
        } catch (e: Exception) {
            fail("编辑失败：${e.message}")
        }
    }
}

/**
 * 派子任务时能交代的两件事：用哪个模型、只给哪几把工具（还有档位）。
 *
 * 为什么允许"按子任务"换：一条大任务里既有"把这三份日志读一遍 summarize"（脏活，
 * 本地小模型就够）又有"据此定方案"（要强的），全用同一个模型就是拿贵的干便宜的活。
 * 但**权限不能靠换子任务绕过**：档位只能更严、工具只能是父会话已有的子集，
 * 见 [Engine.spawn]。
 */
data class SubOpts(
    val model: String = "",
    val tools: String = "",
    val mode: String = ""
)

class TaskTool : Tool(
    "task",
    "派一个子任务去做一件独立的事：它有自己的上下文，做完只把最终结论带回来。" +
        "适合两类活：会刷出一大堆中间结果的调研、能并行做的几块。一次调用只交代一件事，" +
        "要并行就同一回合里多调几次。" +
        "可选 model（这条子任务用哪个模型，例如脏活交给本地小模型）、" +
        "tools（只给它哪几把工具，逗号分隔；不写就是父会话现在能用的那些）、" +
        "mode（plan/ask/auto；**只能比父会话更严，不能更松**）。",
    schema(
        "prompt" to "string", "label" to "string",
        "model" to "string", "tools" to "string", "mode" to "string",
        required = arrayOf("prompt")
    ),
    kind = "read"
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val prompt = req(args, "prompt")?.trim() ?: return fail("task 缺少 prompt")
        val label = (req(args, "label")?.trim() ?: "").ifBlank { prompt.take(24) }
        val spawn = ctx.spawn ?: return fail("当前环境没接引擎，派不了子任务")
        val opts = SubOpts(
            model = (req(args, "model") ?: "").trim(),
            tools = (req(args, "tools") ?: "").trim(),
            mode = (req(args, "mode") ?: "").trim().lowercase()
        )
        if (opts.mode.isNotEmpty() && opts.mode !in MODES)
            return fail("mode 只认 ${MODES.joinToString("/")}，现在是「${opts.mode}」")
        val (out, log) = spawn(label, prompt, opts)
        return ToolResult("【子任务「$label」的结论】\n$out", sub = log)
    }

    companion object { val MODES = setOf("plan", "ask", "auto") }
}

class GlobTool : Tool(
    "glob", "按通配找文件，如 **/*.kt。返回相对工作区的路径列表。",
    schema("pattern" to "string", "path" to "string", required = arrayOf("pattern"))
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val pattern = req(args, "pattern") ?: return fail("glob 缺少 pattern")
        val root = req(args, "path")?.let { ctx.resolve(it) } ?: ctx.workspace
        if (!root.isDirectory) return fail("目录不存在：${ctx.rel(root)}")
        val rx = globToRegex(pattern)
        val out = mutableListOf<String>()
        for (f in walkFiles(root)) {
            if (rx.matches(ctx.rel(f))) out += ctx.rel(f)
            if (out.size >= 400) break
        }
        return ToolResult(if (out.isEmpty()) "(无匹配) $pattern" else out.sorted().joinToString("\n") + "\n— 共 ${out.size} 个")
    }
}

class GrepTool : Tool(
    "grep",
    "在文件内容里搜正则，返回 文件:行号:内容。找符号、定位问题用它，别整篇读。" +
        "文本 0 命中且配置了 settings.embedUrl 时，会附带「语义近邻」（把 pattern 的字面当自然语句去问向量端点）——" +
        "适合\"意思相近但用词不同\"的找法；正则写法对语义这段无效。",
    schema(
        "pattern" to "string", "path" to "string", "glob" to "string", "ignore_case" to "boolean",
        required = arrayOf("pattern"))
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val pattern = req(args, "pattern") ?: return fail("grep 缺少 pattern")
        val root = req(args, "path")?.let { ctx.resolve(it) } ?: ctx.workspace
        val fileRx = req(args, "glob")?.let { globToRegex(it) }
        val rx = runCatching {
            if (bool(args, "ignore_case")) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        }.getOrElse { return fail("正则不合法：${it.message}") }
        val targets = if (root.isFile) listOf(root) else walkFiles(root)
        val sb = StringBuilder()
        var n = 0
        for (f in targets) {
            if (f.length() > 2_000_000) continue
            val rel = ctx.rel(f)
            if (fileRx != null && !fileRx.matches(rel)) continue
            var no = 0
            try {
                f.useLines { seq ->
                    seq.forEach { line ->
                        no++
                        if (rx.containsMatchIn(line)) {
                            n++
                            sb.append(rel).append(':').append(no).append(':')
                                .append(TextCap.head(line.trim(), 200)).append('\n')
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (n >= 500) break
        }
        var out = if (n == 0) "(无匹配) $pattern" else sb.toString().trimEnd() + "\n— 命中 $n 处"
        /*
         * #6 语义回退：文本 0 命中才问向量端点 —— 有字面命中就不掺和（不改变 grep 的既有输出）。
         * 端点没配/失败只追加一行原因，文本结果永远在前面（语义是增强，不能拖死搜索）。
         */
        val emb = ctx.settings.embedUrl.trim()
        if (n == 0 && emb.isNotBlank()) {
            val r = SemanticIndex.query(ctx.workspace, pattern, emb)
            when {
                r.hits.isNotEmpty() -> {
                    out += "\n语义近邻（embedding 相似度，按 pattern 字面查询，正则写法对这段无效）："
                    for (h in r.hits) {
                        out += "\n  %.2f  %s  — %s".format(h.score, h.path, h.snippet.replace('\n', ' '))
                    }
                }
                r.note != null -> out += "\n（语义检索没跑：${r.note}）"
                else -> out += "\n（语义近邻无结果）"
            }
        }
        return ToolResult(out)
    }
}

class ShellTool : Tool(
    "shell",
    "执行命令并返回 exit code + 合并的 stdout/stderr。shell=pwsh（默认）或 bash（Git Bash，" +
        "需要管道、heredoc、\$() 时用它）。超时用 timeout 秒控制。",
    schema("command" to "string", "shell" to "string", "timeout" to "integer", "cwd" to "string",
        required = arrayOf("command")),
    kind = "exec"
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val command = req(args, "command") ?: return fail("shell 缺少 command")
        val shell = (req(args, "shell") ?: "pwsh").lowercase()
        val timeoutSec = int(args, "timeout", 180).coerceIn(1, 1800)
        val cwd = req(args, "cwd")?.let { ctx.resolve(it) } ?: ctx.workspace
        val launcher = launcherFor(shell) ?: return fail("这台机器上找不到 $shell，换一种 shell 或给绝对路径")

        val why = ctx.guard("shell", command, "执行命令（$shell）", command)
        if (why != null) return fail(why)

        /**
         * Windows 上**不能**把命令直接当 argv 塞给 bash/pwsh：
         * ProcessBuilder 会把参数拼成一条命令行字符串交给 CreateProcess，MSYS 的 bash 再按自己的
         * 规则二次解析 —— 结果就是命令里的引号被吃掉（实测 `python -c "print('x'*400)"` 到了 bash
         * 变成 `python -c print(x*400)`，syntax error）。凡是带引号的命令都会踩。
         *
         * 解法：命令写进临时脚本文件，让 shell 去**读文件**（`bash -l file.sh` / `pwsh -File file.ps1`）。
         * 脚本放系统临时目录，不污染用户仓库；跑完删掉。
         */
        val kind = when {
            launcher.first.endsWith("pwsh.exe") || launcher.first.endsWith("powershell.exe") -> "pwsh"
            launcher.first.endsWith("cmd.exe") -> "cmd"
            else -> "sh"
        }
        val script = File.createTempFile(
            "haoai-cmd-",
            if (kind == "pwsh") ".ps1" else if (kind == "cmd") ".bat" else ".sh",
            File(System.getProperty("java.io.tmpdir"))
        )
        val body = if (kind == "pwsh") "$PWSH_UTF8_PREFIX $command" else command
        // 同 PsRunner：Windows PowerShell 5.1 没有 BOM 就按 GBK 读脚本，
        // 命令里只要出现中文就会被解坏（表现是"命令跑通了但参数是乱码"）。
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        if (kind == "pwsh") script.writeBytes(bom + body.toByteArray(Charsets.UTF_8))
        else script.writeText(body)

        return try {
            val pb = ProcessBuilder(listOf(launcher.first) + launcher.second + script.absolutePath)
                .directory(if (cwd.isDirectory) cwd else ctx.workspace)
                .redirectErrorStream(true)
            pb.environment()["PYTHONUTF8"] = "1"
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            val p = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { br ->
                    while (true) {
                        val l = br.readLine() ?: break
                        out.append(l).append('\n')
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            if (!p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) {
                p.destroyForcibly()
                reader.join(1000)
                return ToolResult("超时 ${timeoutSec}s 已终止。已输出：\n${TextCap.middle(out.toString(), 4000)}", true)
            }
            reader.join(3000)
            ToolResult("exit=${p.exitValue()}\n---\n$out", card = "terminal")
        } catch (e: Exception) {
            fail("执行失败：${e.message}")
        } finally {
            runCatching { script.delete() }
        }
    }

    private fun launcherFor(shell: String): Pair<String, List<String>>? =
        // 与常驻进程共用同一套"在这台 Windows 上找到 shell"的逻辑（Pty.kt 的 ShellLauncher）：
        // 两处各写一份 bash 候选路径，将来一定有一份改了另一份没改。
        ShellLauncher.forName(shell)

    companion object {
        /** 不切代码页的话，中文输出在 PowerShell 下必乱码（本机实测）。 */
        private const val PWSH_UTF8_PREFIX = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;"
    }
}

class TodoTool : Tool(
    "todo", "维护本次任务的步骤清单（整体替换，每次传完整列表）。status ∈ pending|doing|done|cancelled",
    schema("items" to "array", required = arrayOf("items"))
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val items = args["items"]?.jsonArray ?: return fail("todo 缺少 items")
        ctx.todos.clear()
        items.forEach { el ->
            val o = el.jsonObject
            val text = o["text"]?.jsonPrimitive?.content ?: o["title"]?.jsonPrimitive?.content ?: "(未命名)"
            val st = o["status"]?.jsonPrimitive?.content ?: "pending"
            ctx.todos += Todo(text, st)
        }
        val mark = mapOf("done" to "[x]", "doing" to "[>]", "cancelled" to "[-]")
        return ToolResult(
            if (ctx.todos.isEmpty()) "(空清单)"
            else ctx.todos.mapIndexed { i, t -> "${i + 1}. ${mark[t.status] ?: "[ ]"} ${t.text}" }.joinToString("\n")
        )
    }
}

/**
 * ask_user：暂停等用户拍板。参数面、结果前缀与手机端逐条对齐（B22）——
 * 同名工具在两端必须是同一个契约，模型拿到的 desc、用户看到的交互才不漂。
 * 结果文本（「用户选择了：/用户回答：」前缀）是界面回显已答卡的数据源。
 */
class AskUserTool : Tool(
    "ask_user",
    "向用户提出带选项的问题并暂停等待回答（运行挂起，用户点选后继续）。" +
        "遇到分叉、歧义或要替用户做假设时，先调用本工具问清再动手，不要自己猜：" +
        "例——用户说「设个 9 点的闹钟」没说早上还是晚上 → 问；" +
        "「让回答更有创意」可以调温度也可以改提示词 → 问；" +
        "两个方案都可行、删除/覆盖等不可逆操作前 → 问。" +
        "不要用于纯闲聊，也不要连续高频调用。把推荐项放在第一个；用户总可以看到自由输入出口。" +
        "低风险、选错也无代价的问题加 confirm=false 让用户点选即回答，交互更省事；" +
        "问卷/测试等无优劣之分的选择加 recommend=false 隐藏「推荐」徽标。",
    askUserParams()
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val question = req(args, "question") ?: return fail("ask_user 缺少 question")
        val opts = args["options"]?.jsonArray?.mapNotNull { o ->
            (o as? JsonObject)?.let {
                val label = it["label"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@let null
                if (label.isEmpty()) return@let null
                AskOpt(label, it["description"]?.jsonPrimitive?.contentOrNull?.trim() ?: "")
            }
        }.orEmpty()
        // 与手机端同一把校验：options 是对象数组、恰好 2~4 个；字符串载荷按 0 个算
        // （老夹具/旧模型习惯会当场收到这句纠偏，手机端同款）。
        if (opts.size < 2 || opts.size > 4) {
            return fail("options 需要 2~4 个互斥选项（收到 ${opts.size} 个），请修正后重试")
        }
        // 三个开关默认 true：字段缺省 = 老实盘（手机端 optBool 的语义）
        val allowFree = args["allow_free_text"]?.jsonPrimitive?.contentOrNull != "false"
        val confirm = args["confirm"]?.jsonPrimitive?.contentOrNull != "false"
        val recommend = args["recommend"]?.jsonPrimitive?.contentOrNull != "false"
        val ans = ctx.gate.ask(AskReq(question, opts, allowFree, confirm, recommend))
        val labels = opts.map { it.label }
        // 前缀即契约：界面按「用户选择了：」回显已答卡，改动前缀要同步两端渲染
        return ToolResult(
            when {
                ans.isBlank() -> "用户未给出有效回答；请按默认假设继续并在回复中说明"
                ans in labels -> "用户选择了：$ans"
                else -> "用户回答：${ans.takeSafe(2000)}"
            }
        )
    }
}

class WebFetchTool : Tool(
    "web_fetch", "抓一个网页转成纯文本（max_chars 默认 8000，最多 30000）。",
    schema("url" to "string", "max_chars" to "integer", required = arrayOf("url")),
    kind = "net"
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL).build()

    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val url = req(args, "url") ?: return fail("web_fetch 缺少 url")
        if (!url.startsWith("http")) return fail("url 必须以 http/https 开头")
        val max = int(args, "max_chars", 8000).coerceIn(200, 30_000)
        return try {
            val r = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) HaoAI/0.1").GET().build()
            val resp = client.send(r, HttpResponse.BodyHandlers.ofString())
            val raw = resp.body()
            val text = if (raw.contains("<html", true) || raw.contains("<body", true)) htmlToText(raw) else raw
            ToolResult("${url}（HTTP ${resp.statusCode()}）\n\n${TextCap.middle(text, max)}")
        } catch (e: Exception) {
            fail("抓取失败：${e.message}")
        }
    }
}

/** 粗暴够用的 HTML→文本：去 script/style，块级标签换行，压空行，解常见实体。 */
internal fun htmlToText(html: String): String {
    var s = html.replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
    s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
    s = s.replace(Regex("(?is)</(p|div|li|tr|h[1-6]|section|article)>"), "\n")
    s = s.replace(Regex("(?is)<[^>]+>"), " ")
    s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")
    s = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    return s.replace(Regex("\n{3,}"), "\n\n")
}

/** 跳过的目录名 —— 不跳的话 grep 一次 .git 就能烧掉几十万行。 */
private val SKIP_DIRS = setOf(
    ".git", ".gradle", "build", "out", "node_modules", ".idea", ".kotlin",
    ".haoai-snap", Env.TOOL_OUTPUT_DIR, "__pycache__", ".venv", "dist"
)

/** 手写递归而不是 walkTopDown().onEnter：FileTreeWalk 在序列操作上的类型推断在 Kotlin 2.x 里不稳。 */
internal fun walkFiles(root: File, cap: Int = 20_000): List<File> {
    val out = ArrayList<File>()
    fun rec(d: File, depth: Int) {
        if (out.size >= cap || depth > 12) return
        val kids = d.listFiles() ?: return
        for (k in kids) {
            if (out.size >= cap) return
            if (k.isDirectory) {
                if (k.name in SKIP_DIRS || k.name.startsWith(".")) continue
                rec(k, depth + 1)
            } else if (k.isFile) {
                out += k
            }
        }
    }
    if (root.isFile) out += root else rec(root, 0)
    return out
}

private val REGEX_META = charArrayOf('.', '+', '(', ')', '|', '^', '$', '{', '}', '[', ']', '\\')

// 通配转正则：`**` 跨目录，`*` 不跨斜杠，`?` 单字符。
// （这里刻意不用 KDoc：注释里写 glob 样例会出现 `*/`，Kotlin 的块注释会就地被关掉。）
internal fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                sb.append(".*"); i += 2
                if (i < glob.length && glob[i] == '/') i++
                continue
            }
            c == '*' -> sb.append("[^/]*")
            c == '?' -> sb.append("[^/]")
            c in REGEX_META -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    return Regex(sb.append('$').toString(), RegexOption.IGNORE_CASE)
}

internal fun schema(vararg props: Pair<String, String>, required: Array<String> = emptyArray()): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            props.forEach { (k, t) -> put(k, buildJsonObject { put("type", t) }) }
        })
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
    }

/**
 * ask_user 的参数面与手机端**逐字节同款**（options 是 {label,description} 对象数组、
 * 三个开关语义一致），所以不走 `schema(...)` 的极简形状 —— 富度对齐才叫同一个工具。
 * `ToolSchemaTest` 钉的形状规则为此放宽到「type/description/items」三键集
 * （items 只许是对象数组）；其余 builtin 仍是纯 {type}。
 */
internal fun askUserParams(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("question") {
            put("type", "string")
            put("description", "完整提问：一句话说清背景与要决定的事，以问号结尾")
        }
        putJsonObject("options") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("label") {
                        put("type", "string")
                        put("description", "选项短标签（≤12 字），卡片上直接显示")
                    }
                    putJsonObject("description") {
                        put("type", "string")
                        put("description", "该选项的含义/代价/后果，一行话；可省略")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("label")) })
            }
            put("description", "2~4 个互斥选项；推荐项放第一个")
        }
        putJsonObject("allow_free_text") {
            put("type", "boolean")
            put("description", "是否允许用户自由输入其他回答，默认 true")
        }
        putJsonObject("confirm") {
            put("type", "boolean")
            put(
                "description",
                "是否需要用户点选后再按确认按钮（防误触）。默认 true。" +
                    "低风险、选错也无代价的事实/偏好选择（如早上还是晚上）设 false：" +
                    "用户点选项即回答、任务立刻继续；" +
                    "删除/覆盖/花钱等不可逆或高代价的分叉必须保持 true"
            )
        }
        putJsonObject("recommend") {
            put("type", "boolean")
            put(
                "description",
                "是否给第一个选项标「推荐」徽标，默认 true。" +
                    "仅当你确实倾向该选项时才标；" +
                    "各选项无优劣之分的问题（测试问卷、量表打分、抽签类）设 false，不要标推荐"
            )
        }
    }
    put("required", buildJsonArray {
        add(JsonPrimitive("question"))
        add(JsonPrimitive("options"))
    })
}

/** 溢出落文件：与手机端 `EngineToolSpill` 同构。 */
const val SPILL_KEEP_FILES = 40
const val SPILL_MAX_CHARS = 2_000_000

fun spillPreview(content: String, cap: Int, path: String?): String {
    val body = TextCap.middle(content, cap)
    if (content.length <= cap || path == null) return body
    return body + "\n\n［完整输出共 " + content.length + " 字符，已存进工作区文件 `" + path +
        "`。上面只是头尾摘要 —— 要看中间部分就用 read(path=\"" + path +
        "\", offset=起始行, limit=行数) 分段读，不要凭摘要猜内容。］"
}

fun capForStore(content: String, cap: Int, workspace: File, callId: String, spillOn: Boolean): String {
    if (content.length <= cap) return content
    if (!spillOn || content.length > SPILL_MAX_CHARS) return spillPreview(content, cap, null)
    val rel = "${Env.TOOL_OUTPUT_DIR}/${System.currentTimeMillis()}-$callId.txt"
    val written = runCatching {
        val f = File(workspace, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        Env.excludeFromGit(workspace, Env.TOOL_OUTPUT_DIR)
        pruneSpillDir(File(workspace, Env.TOOL_OUTPUT_DIR))
    }.isSuccess
    return spillPreview(content, cap, if (written) rel else null)
}

private fun pruneSpillDir(dir: File) {
    if (!dir.isDirectory) return
    val files = dir.listFiles()?.filter { it.isFile } ?: return
    if (files.size <= SPILL_KEEP_FILES) return
    files.sortedBy { it.lastModified() }.take(files.size - SPILL_KEEP_FILES).forEach { runCatching { it.delete() } }
}

object Diff {
    /**
     * 统一风格的行级 diff，给"审阅这次到底改了什么"用。
     *
     * 为什么不是 git diff：工作区不一定是仓库（新建的文件 git 根本不认），
     * 而且这一份要在工具结果里回给模型，必须自带边界（最多 80 行）不能无限长。
     * 公共前后缀之外的整段算一个替换块 —— 够用、线性时间、不会在长文件上炸。
     */
    fun unified(path: String, old: String, new: String, maxLines: Int = 80): String {
        /*
         * 改在几处就按几块渲染，块与块之间不再糊成一坨。
         *
         * 原来"公共前后缀之外整段算一块"，于是一篇文章里第 2 行和第 16 行各改一行，卡上写的是
         * **−15 行 / +15 行**，而中间那 13 行一个字节都没动。逐块上线之后这个数更扎眼：
         * 人刚退回一块，统计却说改了 15 行 —— 那会让人怀疑自己刚才到底点了什么。
         * 切得出块就照块报数（与 git 的 hunk 口径一致：并块后按整段替换区计），
         * 切不出（文件太大、超过 LCS 预算）才退回原来那份线性近似。
         */
        val hs = runCatching { Hunks.of(old, new) }.getOrDefault(emptyList())
        if (hs.size >= 2) {
            val rm = hs.sumOf { it.removed.size }
            val ad = hs.sumOf { it.added.size }
            return "−$rm 行 / +$ad 行   $path（${hs.size} 处）\n" +
                hs.joinToString("\n") { it.text(maxLines) } + "\n"
        }
        val a = old.lines()
        val b = new.lines()
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val removed = a.subList(p, a.size - s)
        val added = b.subList(p, b.size - s)
        if (removed.isEmpty() && added.isEmpty()) return "−0 行 / +0 行（内容没变）"
        val ctxBefore = a.drop(maxOf(0, p - 3)).take(minOf(p, 3))
        // 尾部上下文就是公共后缀的最后几行（前后两份内容这段是一样的，取哪边都行）
        val ctxAfter = a.takeLast(minOf(3, s))
        val body = StringBuilder()
        ctxBefore.forEach { body.append(" ").append(it).append('\n') }
        removed.take(maxLines).forEach { body.append("-").append(it).append('\n') }
        if (removed.size > maxLines) body.append("-…（另有 ").append(removed.size - maxLines).append(" 行未显示）\n")
        added.take(maxLines).forEach { body.append("+").append(it).append('\n') }
        if (added.size > maxLines) body.append("+…（另有 ").append(added.size - maxLines).append(" 行未显示）\n")
        ctxAfter.forEach { body.append(" ").append(it).append('\n') }
        return "−${removed.size} 行 / +${added.size} 行   $path\n" + body
    }

    /** 旧接口：只给模型看的那一行摘要（工具结果会进历史，越短越好）。 */
    fun stat(old: String, new: String): String {
        val a = old.lines()
        val b = new.lines()
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        return "−${a.size - p - s} 行 / +${b.size - p - s} 行"
    }
}

/**
 * 一处连续的改动 —— 审批卡上"逐块接受 / 拒绝"的那一块。
 *
 * 为什么不并进 [Diff]：`Diff.unified` 用的是"公共前后缀之外整段算一坨"的线性近似，
 * 那份够给模型看一句摘要，但一篇文章改三处它只给**一个**块 —— 而这次要拆的恰好就是"一坨"。
 * 切块要真做行级 LCS，两套算法不一样，塞进同一个对象里会让人以为 `unified` 也能返回多块。
 */
data class Hunk(
    /** 第几块（从 1 起）。界面、卡片、回给模型的那句话都按这个数说话。 */
    val no: Int,
    /** 旧文件里的起始**下标**（0 基）。给人看的行号是 `oldAt + 1`，界面与文案都别忘了 +1。 */
    val oldAt: Int,
    /** 这一块替掉的旧行数（0 = 纯插入）。 */
    val oldCount: Int,
    val removed: List<String>,
    val added: List<String>,
    val before: List<String>,
    val after: List<String>,
) {
    val stat: String get() = "−${removed.size} / +${added.size}"

    /**
     * 卡片上这一块的正文（`@@` + 上下文 + −/+ 行）。
     *
     * 上限只管**显示**：`added` 一直是全的，所以人被截断的长块批准之后写进去的内容仍然完整 ——
     * 否则"看了一半就放行"会变成"写了一半进文件"，那是数据损坏而不是显示问题。
     */
    fun text(maxLines: Int = 60): String {
        val sb = StringBuilder()
        sb.append("@@ 第 ").append(oldAt + 1).append(" 行起 @@  ").append(stat).append('\n')
        // 显示时把 CRLF 文件行尾那个 \r 抹掉：它是行尾的一部分，不是要给人看的内容
        fun emit(m: Char, s: String) { sb.append(m).append(s.trimEnd('\r')).append('\n') }
        before.forEach { emit(' ', it) }
        removed.take(maxLines).forEach { emit('-', it) }
        if (removed.size > maxLines)
            sb.append("-…（另有 ").append(removed.size - maxLines).append(" 行没显示）\n")
        added.take(maxLines).forEach { emit('+', it) }
        if (added.size > maxLines)
            sb.append("+…（另有 ").append(added.size - maxLines).append(" 行没显示）\n")
        after.forEach { emit(' ', it) }
        return sb.toString().trimEnd('\n')
    }
}

/** 把一份文件改动切成**互相分得开**的块，并决定这次要不要摆逐块勾选。 */
object Hunks {
    const val CONTEXT = 3
    const val MIN = 2
    const val MAX = 12

    /** LCS 动态规划的格数上限：再大就退回"整段一块"（那块只有一块，逐块勾选自然消失）。 */
    const val CELLS = 1_000_000L

    /** 基线或目标超过这个长度就不切块：卡片放不下，而切块是 quadratic 的。 */
    const val MAX_BYTES = 400_000

    /**
     * 按行拆，**行尾算在行里**。
     *
     * 为什么不用 `String.lines()`：它把结尾那个换行拆成了一个空尾项，而那是**行尾**不是**空行** ——
     * 留着它，逐块合回去就凭空多出一行空行（这条是被测试抓出来的，不是想出来的）。
     * 自己拆的第二份收益更大：`\r` 留在行里，于是 CRLF 的文件被逐块合并之后仍然是 CRLF，
     * 不需要"猜一个 eol 再 join"那种会把整篇行尾改掉的做法。
     */
    private fun lines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val nl = s.indexOf('\n', i)
            if (nl < 0) { out.add(s.substring(i)); break }
            out.add(s.substring(i, nl))
            i = nl + 1
        }
        return out
    }

    /** 有几行（结尾有没有换行都不多数）。 */
    fun rows(s: String): Int = lines(s).size

    fun of(old: String, new: String, context: Int = CONTEXT): List<Hunk> {
        val a = lines(old)
        val b = lines(new)
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val lo = p
        val hiA = a.size - s
        val hiB = b.size - s
        // [旧起, 旧止, 新起, 新止]，都是文件级下标、区间右开
        val cs = if ((hiA - lo).toLong() * (hiB - lo) > CELLS) listOf(intArrayOf(lo, hiA, lo, hiB))
        else changes(a.subList(lo, hiA), b.subList(lo, hiB), lo)
        val out = ArrayList<Hunk>()
        var i = 0
        while (i < cs.size) {
            var j = i
            // 间隔小于一份上下文的两个改动并成一块：中间那几行反正两边都会被显示成上下文，
            // 拆成两块只会让人以为是两件不同的事。
            while (j + 1 < cs.size && cs[j + 1][0] - cs[j][1] < context * 2) j++
            val oldAt = cs[i][0]
            val oldEnd = cs[j][1]
            out.add(
                Hunk(
                    no = out.size + 1,
                    oldAt = oldAt,
                    oldCount = oldEnd - oldAt,
                    removed = a.subList(oldAt, oldEnd).toList(),
                    // 并块之后新内容跨了两段改动，直接取整段：中间那些行与旧文件逐字相同，
                    // 从新的一份抄过来不会改变结果。
                    added = b.subList(cs[i][2], cs[j][3]).toList(),
                    before = a.subList(maxOf(0, oldAt - context), oldAt).toList(),
                    after = a.subList(oldEnd, minOf(a.size, oldEnd + context)).toList()
                )
            )
            i = j + 1
        }
        return out
    }

    /**
     * 这次改动值不值得摆逐块勾选。返回 null = 按整条审批（与本功能出现之前逐字节一致）。
     *
     * 三个"不给"各有各的理由：
     * - **只有一块**：勾选框只会让人多点一下，而它能说的话"拒绝"按钮已经说了；
     * - **块太多**：卡片变成一屏滚屏，逐块反而比整条更难读，也更难看出漏勾了哪块；
     * - **文件太大**：切块是 quadratic 的，把人卡在审批界面上等十几秒不值 ——
     *   而 `of()` 在超预算时自己会退成一整块，那条路也是 null。
     */
    fun plan(old: String, new: String): HunkPlan? {
        if (old.length > MAX_BYTES || new.length > MAX_BYTES) return null
        val hs = runCatching { of(old, new) }.getOrDefault(emptyList())
        if (hs.size < MIN || hs.size > MAX) return null
        return HunkPlan(hs, old, new)
    }

    /**
     * 按勾选把块合回文件。
     *
     * 被退的块**不动**：那几行由"下一块之前把旧行照抄"这一步带出来，所以不需要单独处理。
     * 结尾换行跟着最后落笔的那一侧（全接受时不走这里，见 [HunkPlan.content]）。
     */
    fun merge(base: String, hunks: List<Hunk>, keep: List<Boolean>, wanted: String): String {
        val a = lines(base)
        val out = ArrayList<String>()
        var cursor = 0
        var tail = base.endsWith("\n")
        hunks.forEachIndexed { i, h ->
            if (!keep.getOrElse(i) { true }) return@forEachIndexed
            if (h.oldAt > cursor) out.addAll(a.subList(cursor, h.oldAt))
            out.addAll(h.added)
            cursor = h.oldAt + h.oldCount
            if (cursor >= a.size) tail = wanted.endsWith("\n")
        }
        if (a.size > cursor) out.addAll(a.subList(cursor, a.size))
        if (out.isEmpty()) return ""
        return out.joinToString("\n") + (if (tail) "\n" else "")
    }

    private fun changes(a: List<String>, b: List<String>, off: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        var j = 0
        for (pr in lcs(a, b)) {
            if (pr[0] > i || pr[1] > j) out.add(intArrayOf(i + off, pr[0] + off, j + off, pr[1] + off))
            i = pr[0] + 1
            j = pr[1] + 1
        }
        if (i < a.size || j < b.size) out.add(intArrayOf(i + off, a.size + off, j + off, b.size + off))
        return out
    }

    /** 行级 LCS 的匹配对（中段内部下标，两侧都递增）。纯行比较，不做词内 diff —— 审批要的是"哪几行动了"。 */
    private fun lcs(a: List<String>, b: List<String>): List<IntArray> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = ArrayList<IntArray>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(intArrayOf(i, j)); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }
}

/**
 * 审批卡上的逐块选择去向。
 *
 * 为什么是"闸口往里写 [keep]、工具在外面读"，而不是把 [Gate.approveRule] 的 Boolean 换成三态：
 * Gate 有 CLI、网页和六份测试假闸口，而逐块只有网页壳做得到 —— 换返回类型要所有实现一起改，
 * 换回来的收益是零（其余几份的正确答案本来就还是 true/false）。
 * 没人写 keep ⇒ 整条应用；手机上点的那几下也走这条路（那边只发整条决定）。
 */
class HunkPlan(val hunks: List<Hunk>, val base: String, val wanted: String) {
    var keep: List<Boolean>? = null

    /** 挑过**而且**至少退了一块才算"部分接受"：全勾就是整条放行，不该让人误以为被挑过。 */
    val partial: Boolean get() = keep?.any { !it } == true

    val accepted: Int get() = keep?.count { it } ?: hunks.size

    /** 卡片 detail 末尾补的那一句 —— 只在真会给勾选框的时候说，整条审批那次不该多占一行。 */
    val offer: String get() = "\n这次改动分成 ${hunks.size} 处，可以一块一块挑（勾掉的保持原样）"

    private val rejected: List<Hunk> get() =
        hunks.filterIndexed { i, _ -> keep?.getOrNull(i) == false }

    /** 要写进文件的那一份。全接受时直接返回模型给的内容，逐字节相同（测试钉着这条）。 */
    fun content(): String {
        val k = keep ?: return wanted
        if (k.all { it }) return wanted
        return Hunks.merge(base, hunks, k, wanted)
    }

    /**
     * 部分接受时回给模型的那句话。
     *
     * 顺序仍是"结论 → 哪几块没进 → 接下来该怎么办"（工具结果那一行界面上只看得见前约九十个字，
     * 见沙箱那批的注释）。这句必须存在：模型以为整条都过了，下一轮就会基于一个不存在的世界继续改。
     */
    fun outcome(rel: String, written: String): String {
        val rs = rejected
        val where = rs.joinToString("、") { "第 ${it.no} 块（旧第 ${it.oldAt + 1} 行起，${it.stat}）" }
        val rows = Hunks.rows(written)
        return "已部分写入 $rel：${hunks.size} 块里退了 ${rs.size} 块，$where 保持原样。" +
            "现在文件 $rows 行。这次不是整条通过，别按你原本设想的内容继续改 —— " +
            "要动被退的那几处，先 read 看清现状再重新提。"
    }
}

fun builtinTools(): List<Tool> = listOf(
    ReadTool(), WriteTool(), EditTool(), GlobTool(), GrepTool(),
    ShellTool(), ShellOpenTool(), ShellSendTool(), ShellReadTool(), ShellCloseTool(), ShellListTool(),
    GitTool(), TodoTool(), AskUserTool(), WebFetchTool(), WebSearchTool(),
    BrowserTool(), ScreenTool(), TaskTool(), MediaTool(), RunCodeTool(), RecordTool(), RunVerifyTool()
)

/**
 * 引擎实际拿到的工具表 = 内置 + 外部 MCP。
 *
 * MCP 那半边整体包在 runCatching 里：一个配错的 server（命令不存在、握手超时）
 * 不该让整条会话起不来，最多是"这次没有外部工具"。
 */
fun allTools(): List<Tool> =
    builtinTools() + runCatching { Mcp.tools() }.getOrDefault(emptyList())
