package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.util.Base64
import com.haoai.pc.WebServer.Body

/**
 * WebServer 按域拆出来的一页（S5）：这些方法原来平铺在 Server.kt 的类体里。
 *
 * 形状是**同包扩展函数**而不是独立 handler 类：类体拆成独立类要给每个内部引用
 * 加 `ctx.` 前缀（改动面 ×10 且每处都可能改错），而扩展是纯搬移 —— route 分派表
 * 一字未动、方法名未变，行为由 522 条测试与像素剧本兜底。B15 真正要两壳共用的
 * 是 Engine（EngineFactory 那层），不是这个网页壳。
 *
 * 成员**保持原缩进**：多行字符串与续行模板里的缩进是内容的一部分，dedent 就是改行为。
 */


    /**
     * `POST /api/rule` {op:'add'|'del', text:'shell(git push*) deny', sid} —— 规则表在界面上可增删。
     *
     * 之前只能看不能改（加规则要回 CLI 跑 `haoai allow`），而"以后这类都允许"这个按钮
     * 就在审批卡上 —— 用户既然能一键写规则，就得能看见并收回来的地方。
     * 规则是**按工作区**存的，所以改的是这条会话自己的工作区，不是全局那个。
     */
    internal fun WebServer.ruleEdit(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val ws = sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()
        val text = b.str("text")
        val r = Rule.parse(text)
        if (r == null) {
            send(ex, 200, """{"ok":false,"error":"看不懂这条规则。写法如：shell(git push*) deny"}""",
                "application/json; charset=utf-8"); return
        }
        val ok = if (b.str("op") == "del") Policies.get().remove(ws, r.tool, r.pattern)
        else { Policies.get().add(ws, r); true }
        if (!ok) {
            send(ex, 200, """{"ok":false,"error":"没找到这条规则（可能已经被删了）"}""",
                "application/json; charset=utf-8"); return
        }
        send(ex, 200, """{"ok":true,"rule":${quote(r.render())}}""", "application/json; charset=utf-8")
        publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }


    /**
     * `GET/POST /api/memory` —— 项目说明文件（AGENTS.md 那一类）的读与写。
     *
     * 读写都只碰**这条会话自己的工作区**，路径由服务端算（[Memory.target]），
     * 前端不能指定写哪儿 —— 否则就成了"用一个接口往任意路径写文本"。
     */
    internal fun WebServer.memory(ex: HttpExchange) {
        // 请求体只能读一次：分两次 new Body(ex) 的话第二次拿到的是空对象
        val b = Body(ex)
        val get = ex.requestMethod == "GET"
        val sid = if (get) querySid(ex) else b.str("sid")
        val scope = if (get) queryOf(ex, "scope") else b.str("scope")
        val id = pick(sid)
        val ws = sessions[id]?.engine?.session?.workspace ?: settings.workspaceFile()
        // scope=global 是跨项目的个人偏好（HAOAI_HOME/MEMORY.md），默认是这条会话工作区里的说明
        val f = if (scope == "global") Env.memoryFile else Memory.target(ws)
        if (get) {
            val text = runCatching { if (f.isFile) f.readText() else "" }.getOrDefault("")
            send(ex, 200,
                """{"ok":true,"path":${quote(f.absolutePath)},"exists":${f.isFile},"text":${quote(text)}}""",
                "application/json; charset=utf-8")
            return
        }
        val text = b.str("text")
        val done = runCatching { f.parentFile?.mkdirs(); f.writeText(text); f }
        if (done.isFailure) {
            send(ex, 200,
                """{"ok":false,"error":${quote("写不下去：" + (done.exceptionOrNull()?.message ?: ""))}}""",
                "application/json; charset=utf-8")
            return
        }
        send(ex, 200, """{"ok":true,"path":${quote(f.absolutePath)},"chars":${text.length}}""",
            "application/json; charset=utf-8")
        // 发 settings 而不是往流里插一条提示：页面上的"已存 N 字"与 toast 已经说完了，
        // 而顶栏那圈占用必须重算 —— 项目说明是系统提示的一部分，改了它占用就变了。
        publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }


    /**
     * `GET/POST /api/memories` —— 条目化长期记忆（工作区 `MEMORY.md`，与手机端同一格式）。
     *
     * GET  `?sid&q=` → `{ok,path,exists,count,max,items}`；POST `{sid,op:add|edit|del,...}`。
     *
     * 两条边界：① 路径由服务端从**这条会话自己的工作区**算出来，前端不能指定写哪儿
     * （与 `/api/memory` 同一口径 —— 服务只绑 127.0.0.1，但同机任意页面都能打这个端口）；
     * ② 写它是人点按钮，即审批，不再过模型的权限闸（同 Git 面板那四个端点）。
     */
    internal fun WebServer.memories(ex: HttpExchange) {
        val b = Body(ex)
        val get = ex.requestMethod == "GET"
        val sid = if (get) querySid(ex) else b.str("sid")
        val ws = sessions[pick(sid)]?.engine?.session?.workspace ?: settings.workspaceFile()
        val f = Memories.fileFor(ws)
        val doc = Memories.load(f)
        val json = "application/json; charset=utf-8"
        if (get) {
            send(ex, 200,
                """{"ok":true,"path":${quote(f.absolutePath)},"exists":${f.isFile},""" +
                    """"count":${doc.items.count { it.live }},"max":${Memories.MAX_ITEMS},""" +
                    """"items":${Memories.json(doc, queryOf(ex, "q"))}}""", json)
            return
        }
        val op = b.str("op")
        if (op == "tidy") { memoriesTidy(ex, b, f, doc); return }
        val content = b.str("content").trim()
        val imp = b.str("imp").toIntOrNull() ?: 3
        val type = b.str("type").trim().ifBlank { Memories.TYPE_FACT }
        val tags = b.str("tags").split(',', '，').map { it.trim() } - ""
        var merged = false
        var kept = content.length
        val err: String? = when (op) {
            "del" -> if (Memories.forget(doc, b.str("id"))) null else "没找到这一条，没动"
            "edit" -> if (Memories.update(doc, b.str("id"), content, imp, type)) null else "没找到这一条，改不动"
            else -> when {
                content.isEmpty() -> "内容是空的，没记"
                doc.items.count { it.live } >= Memories.MAX_ITEMS ->
                    "已经有 ${doc.items.count { it.live }} 条，到上限 ${Memories.MAX_ITEMS} 条了：" +
                        "先删掉不再适用的那几条再记新的"
                else -> {
                    val (item, added) = Memories.add(doc, content, type, imp, tags, "manual")
                    merged = !added
                    kept = item.content.length
                    null
                }
            }
        }
        if (err != null) { send(ex, 200, """{"ok":false,"error":${quote(err)}}""", json); return }
        if (!Memories.save(f, doc)) {
            send(ex, 200, """{"ok":false,"error":${quote("写不下去：" + f.absolutePath)}}""", json); return
        }
        val note = if (kept < content.length)
            ""","note":${quote("这条超过 ${Memories.MAX_CHARS} 字，只留下前 ${Memories.MAX_CHARS} 字")}""" else ""
        send(ex, 200,
            """{"ok":true,"merged":$merged,"count":${doc.items.count { it.live }}$note,""" +
                """"items":${Memories.json(doc)}}""", json)
        // 记忆也是系统提示的一部分：改了它，顶栏那圈上下文占用要重算
        publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }


    /**
     * `POST /api/memories {op:"tidy", apply:"true"}` —— 整理一次。
     *
     * 先给"将要动谁"再动手（`apply` 不带就是预览）：这是自动判断在改用户的长期记忆，
     * 一次点错能把十几条一起合掉。预览不写文件，所以返回的三份计数才是可信的。
     * 手机端是每轮自动 tidy，这边刻意不自动 —— 两端共用一份文件时，两边都自动整理＝互相整理。
     */
    internal fun WebServer.memoriesTidy(ex: HttpExchange, b: Body, f: File, doc: Memories.Doc) {
        val json = "application/json; charset=utf-8"
        val apply = b.str("apply") == "true"
        val t = Memories.tidy(doc)
        if (apply && !Memories.save(f, doc)) {
            send(ex, 200, """{"ok":false,"error":${quote("写不下去：" + f.absolutePath)}}""", json)
            return
        }
        val rows = (t.degraded.map { it to "重要度低且 30 天没用" } +
            t.merged.map { it to "和另一条重复" }).take(8)
        val detail = rows.joinToString(",", "[", "]") { (it, why) ->
            """{"content":${quote(it.content.take(60))},"why":${quote(why)}}"""
        }
        send(ex, 200,
            """{"ok":true,"applied":$apply,"degraded":${t.degraded.size},"merged":${t.merged.size},""" +
                """"purged":${t.purged.size},"count":${doc.items.count { it.live }},"detail":$detail}""", json)
        if (apply && t.changed > 0) publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }


    /**
     * `GET/POST /api/skills` —— 自定义 `/命令`（技能）的读与增删。
     * `POST {op:'add'|'del', name, desc, text}`；同名算覆盖。
     */
    /** 手写的 `/命令` 与导入的 SKILL.md 拼成一份清单：界面上是同一个列表，不必分两处渲染。 */
    internal fun WebServer.skillItems(): String = "[" +
        (Skills.load().map {
            """{"name":${quote(it.name)},"desc":${quote(it.desc)},"text":${quote(it.text)},"doc":false}"""
        } + SkillDocs.jsonItems().let { if (it.isEmpty()) emptyList() else listOf(it) })
            .joinToString(",") + "]"


    internal fun WebServer.skillsJson(): String =
        """{"ok":true,"items":${skillItems()},""" +
            """"taken":${quote(BUILTIN_CMDS.joinToString(","))},""" +
            """"feeds":${SkillFeeds.json()},"permNote":${quote(SkillPerms.NOTE)},""" +
            """"dir":${quote(SkillDocs.dir().absolutePath)},"maxDocs":${SkillDocs.MAX_DOCS}}"""


    internal fun WebServer.arrOf(list: List<String>): String = list.joinToString(",", "[", "]") { quote(it) }


    /**
     * 订阅源那几条操作。返回 false 表示这条 op 归这里管了（已发送响应）。
     *
     * `feed-install` **不重新拉一遍正文**给人看 —— 它直接走 `SkillDocs.importUrl`，
     * 所以"看过清单再装"这件事必须由界面保证（装按钮只出现在预览展开之后），
     * 而不是服务端假装自己拦得住：这里没有任何"预览过才允许装"的状态，
     * 有状态就会变成"换了个入口就绕过"。要挡就得两边都挡，那已是签名校验的范围。
     */
    internal fun WebServer.skillFeeds(ex: HttpExchange, b: Body): Boolean {
        // 成功/失败都回同一套字段：ok + error + feeds + items，界面只用判 ok，不用记五种形状
        fun reply(err: String, msg: String = "") {
            send(ex, 200, """{"ok":${err.isEmpty()},"error":${quote(err)},"msg":${quote(msg)},""" +
                """"feeds":${SkillFeeds.json()},"permNote":${quote(SkillPerms.NOTE)},""" +
                """"items":${skillItems()}}""", "application/json; charset=utf-8")
        }
        when (b.str("op")) {
            "feed-add" -> {
                val (f, err) = SkillFeeds.add(b.str("name"), b.str("url"))
                reply(err, f?.name ?: "")
            }
            "feed-del" -> reply(if (SkillFeeds.remove(b.str("id"))) "" else "没有这条订阅源", "已删掉")
            "feed-refresh" -> {
                val (entries, err) = SkillFeeds.refresh(b.str("id"))
                if (entries.isEmpty()) reply(err)
                else send(ex, 200, """{"ok":true,"error":"","entries":${SkillFeeds.entriesJson(entries)},""" +
                    """"feeds":${SkillFeeds.json()},"permNote":${quote(SkillPerms.NOTE)}}""",
                    "application/json; charset=utf-8")
            }
            "feed-preview" -> send(ex, 200,
                """{"ok":true,"preview":${SkillFeeds.previewJson(SkillFeeds.preview(b.str("url")))},""" +
                    """"permNote":${quote(SkillPerms.NOTE)}}""", "application/json; charset=utf-8")
            "feed-install" -> {
                val r = SkillDocs.importUrl(b.str("url"))
                send(ex, 200, """{"ok":${r.ok},"added":${arrOf(r.added)},"skipped":${arrOf(r.skipped)},""" +
                    """"error":${quote(r.error)},"items":${skillItems()},""" +
                    """"feeds":${SkillFeeds.json()},"permNote":${quote(SkillPerms.NOTE)}}""",
                    "application/json; charset=utf-8")
            }
            else -> return false
        }
        return true
    }


    internal fun WebServer.skills(ex: HttpExchange) {
        val b = Body(ex)
        if (ex.requestMethod == "GET") {
            send(ex, 200, skillsJson(), "application/json; charset=utf-8")
            return
        }
        val op = b.str("op")
        /*
         * 订阅源五条操作先走 [skillFeeds]：它管的是"列表页"，
         * 装进去之后仍然由下面 `import-url` 那条路落盘（一个入口，一套 sanitize 与上限）。
         */
        if (op.startsWith("feed-")) {
            if (skillFeeds(ex, b)) return
            send(ex, 200, """{"ok":false,"error":"不认这个订阅源操作：$op"}""",
                "application/json; charset=utf-8")
            return
        }
        /*
         * 导入这条路只管"装进 HAOAI_HOME/skills/<slug>/"，
         * 越界与超限的判定都在 [SkillDocs] 里（zip 条目名是外部输入，防线只有一处）。
         */
        if (op in listOf("import-text", "import-url", "import-zip", "deldoc")) {
            val r = when (op) {
                "import-text" -> SkillDocs.importText(b.str("text"))
                "import-url" -> SkillDocs.importUrl(b.str("url"))
                "import-zip" -> {
                    val bytes = runCatching {
                        java.util.Base64.getDecoder().decode(b.str("data").trim())
                    }.getOrNull()
                    if (bytes == null) ImportReport(error = "没读懂这个包（base64 解不开）")
                    else SkillDocs.importZip(bytes)
                }
                else -> {
                    val gone = SkillDocs.remove(SkillDocs.sanitize(b.str("slug")))
                    if (gone) ImportReport(added = listOf("已删掉"))
                    else ImportReport(error = "没有这个技能，或者它是手写的 /命令（那种请在原处删）")
                }
            }
            send(ex, 200,
                """{"ok":${r.ok},"added":${arrOf(r.added)},"skipped":${arrOf(r.skipped)},""" +
                    """"error":${quote(r.error)},"items":${skillItems()}}""",
                "application/json; charset=utf-8")
            return
        }
        val name = b.str("name").trim().trimStart('/')
        if (name.isEmpty()) {
            send(ex, 200, """{"ok":false,"error":"命令名是空的"}""",
                "application/json; charset=utf-8"); return
        }
        val list = Skills.load().toMutableList()
        if (op == "del") {
            list.removeAll { it.name == name }
        } else {
            if (name.any { it.isWhitespace() }) {
                send(ex, 200, """{"ok":false,"error":"命令名不能含空格"}""",
                    "application/json; charset=utf-8"); return
            }
            if (name.lowercase() in BUILTIN_CMDS) {
                send(ex, 200, """{"ok":false,"error":"/$name 是内置命令，换一个名字"}""",
                    "application/json; charset=utf-8"); return
            }
            list.removeAll { it.name == name }
            list += Skill(name, b.str("desc").trim(), b.str("text"))
        }
        Skills.save(list)
        send(ex, 200, """{"ok":true,"count":${list.size}}""", "application/json; charset=utf-8")
    }


    /**
     * `GET/POST /api/mcp` —— 外部 MCP 服务器配置的读、改与重连。
     * `POST {op:'add'|'del', name, command, args}`（args 是空格分隔的一行）。
     *
     * 界面只能**改这份配置文件**：要跑哪个命令得由用户自己写下来。
     * 模型没有这条路 —— 它能调用已注册的外部工具，但拉不起一个新进程。
     */
    internal fun WebServer.mcp(ex: HttpExchange) {
        fun render(status: List<Map<String, String>>): String = status.joinToString(",", "[", "]") { row ->
            "{" + row.entries.joinToString(",") { (k, v) -> "\"$k\":${quote(v)}" } + "}"
        }
        if (ex.requestMethod == "GET") {
            send(ex, 200, """{"ok":true,"servers":${render(Mcp.status())}}""",
                "application/json; charset=utf-8")
            return
        }
        val b = Body(ex)
        val list = McpConfig.load().toMutableList()
        val name = b.str("name").trim()
        if (name.isEmpty()) {
            send(ex, 200, """{"ok":false,"error":"名字是空的"}""",
                "application/json; charset=utf-8"); return
        }
        if (b.str("op") == "del") list.removeAll { it.name == name }
        else {
            val cmd = b.str("command").trim()
            if (cmd.isEmpty()) {
                send(ex, 200, """{"ok":false,"error":"命令是空的"}""",
                    "application/json; charset=utf-8"); return
            }
            if (!name.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                send(ex, 200, """{"ok":false,"error":"名字只能用字母、数字、_ 与 -"}""",
                    "application/json; charset=utf-8"); return
            }
            list.removeAll { it.name == name }
            list += McpServer(name, cmd.substringBefore(' '), cmd.substringAfter(' ', "")
                .split(' ').filter { it.isNotBlank() })
        }
        McpConfig.save(list)
        Mcp.reload()
        send(ex, 200, """{"ok":true,"servers":${render(Mcp.status())}}""",
            "application/json; charset=utf-8")
        // 工具表变了 → 顶栏那圈占用（工具说明那一行）必须重算
        publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }


    /**
     * 凭据条目：列出（只给掩码）、改、撤。见 [Secrets]。
     *
     * `value` 留空 = 撤掉（删文件）。这里的响应**永远不含明文**，
     * 连测试都是从响应文本里搜不到那串 key 来判的 —— 泄密这件事一旦回退是静默的。
     */
    internal fun WebServer.secrets(ex: HttpExchange) {
        val b = Body(ex)
        if (ex.requestMethod != "GET") {
            val slot = Secrets.find(b.str("id"))
            if (slot == null) {
                send(ex, 200, """{"ok":false,"error":"没有这个凭据条目"}""",
                    "application/json; charset=utf-8"); return
            }
            val done = Secrets.set(slot, b.str("value"))
            if (done.isFailure) {
                send(ex, 200,
                    """{"ok":false,"error":${quote("密钥存不下：" + (done.exceptionOrNull()?.message ?: ""))}}""",
                    "application/json; charset=utf-8")
                return
            }
        }
        send(ex, 200, Secrets.json(), "application/json; charset=utf-8")
    }


    /**
     * 钩子条目：事件挂脚本。见 [Hooks]。
     *
     * 只给界面用 —— 引擎的工具表里**没有**这把工具，模型加不了自己的钩子。
     * 正因为命令只有用户能写，它跑的时候才不需要过审批闸口。
     */
    internal fun WebServer.hooks(ex: HttpExchange) {
        val b = Body(ex)
        if (ex.requestMethod != "GET") {
            when (b.str("op")) {
                "del" -> Hooks.remove(b.str("id"))
                "toggle" -> Hooks.find(b.str("id"))?.let { Hooks.update(it.copy(enabled = !it.enabled)) }
                else -> {
                    val command = b.str("command").trim()
                    if (command.isBlank()) {
                        send(ex, 200, """{"ok":false,"error":"要跑什么？命令是空的"}""",
                            "application/json; charset=utf-8"); return
                    }
                    val event = b.str("event").ifBlank { Hooks.RUN_END }
                    if (event !in Hooks.EVENTS) {
                        send(ex, 200, """{"ok":false,"error":${quote("还不认识事件「$event」，现在只有 " +
                            Hooks.EVENTS.joinToString("/"))}}""",
                            "application/json; charset=utf-8"); return
                    }
                    val id = b.str("id").ifBlank { "hk" + System.nanoTime().toString(16).take(8) }
                    val old = Hooks.find(id)
                    if (old == null && Hooks.load().size >= Hooks.MAX) {
                        send(ex, 200, """{"ok":false,"error":${quote("钩子最多 " + Hooks.MAX + " 条，先删一条")}}""",
                            "application/json; charset=utf-8"); return
                    }
                    Hooks.update(
                        Hook(
                            id = id,
                            name = b.str("name").trim().ifBlank { command.take(18) },
                            event = event, command = command,
                            shell = b.str("shell").ifBlank { "pwsh" },
                            timeoutSec = (b.str("timeoutSec").toIntOrNull()
                                ?: old?.timeoutSec ?: 30).coerceIn(1, 600),
                            enabled = old?.enabled ?: true,
                            last = old?.last ?: "", lastAt = old?.lastAt ?: 0L
                        )
                    )
                }
            }
        }
        send(ex, 200, Hooks.json(), "application/json; charset=utf-8")
    }

