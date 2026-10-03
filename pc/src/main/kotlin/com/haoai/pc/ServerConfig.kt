package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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


    /** `GET /api/backups` —— 已有的备份包（含恢复之前自动留的那张"后悔药"）。 */
    internal fun WebServer.backupsJson(): String {
        val items = Backup.list()
        return "{\"ok\":true,\"items\":[" + items.joinToString(",") { b ->
            "{\"name\":" + quote(b.name) + ",\"at\":" + quote(b.at) + ",\"bytes\":" + b.bytes +
                ",\"entries\":" + b.entries + ",\"keysIncluded\":" + b.keysIncluded +
                ",\"reason\":" + quote(b.reason) + ",\"scopes\":[" +
                b.scopes.joinToString(",") { s -> quote(s) } + "]}"
        } + "]}"
    }


    /**
     * `POST /api/backup` {action:export|restore|delete, scopes, keys, name}
     *
     * 导出与恢复都动状态根：引擎每轮 `persist()`、恢复还会覆盖文件，
     * 所以**正在跑的会话一律挡掉**（让用户先停止）。这不是保守 ——
     * 边写边读出来的包"看着完整其实缺半轮"，而半轮恢复比失败更难查。
     *
     * 恢复成功之后要把内存里的引擎与设置丢掉：它们握着的是恢复前的文件，
     * 不丢就等于"恢复成功了，但界面还在用旧数据"。
     */
    internal fun WebServer.backupOp(ex: HttpExchange) {
        val b = Body(ex)
        val action = b.str("action")
        val busy = liveCount()
        if (action == "export" || action == "restore") {
            if (busy > 0) {
                val what = if (action == "export") "备份" else "恢复"
                send(ex, 200, "{\"ok\":false,\"error\":\"有 " + busy + " 条会话正在跑，先停止它们再" + what + "\"}",
                    "application/json; charset=utf-8")
                return
            }
        }
        when (action) {
            "export" -> {
                val r = runCatching { Backup.export(b.list("scopes"), b.str("keys") == "true") }
                if (r.isSuccess) {
                    val s = r.getOrThrow()
                    send(ex, 200, "{\"ok\":true,\"name\":" + quote(s.name) +
                        ",\"entries\":" + s.entries + ",\"bytes\":" + s.bytes + "}",
                        "application/json; charset=utf-8")
                } else {
                    send(ex, 200, "{\"ok\":false,\"error\":" + quote(r.exceptionOrNull()?.message ?: "导出失败") + "}",
                        "application/json; charset=utf-8")
                }
            }
            "restore" -> {
                val msg = Backup.restore(b.str("name"))
                if (msg.startsWith("ok")) {
                    synchronized(order) { order.clear() }
                    sessions.clear()
                    settings = PcSettings.load()
                    publish("sessions", "{}")
                    send(ex, 200, "{\"ok\":true,\"msg\":" + quote(msg) + "}",
                        "application/json; charset=utf-8")
                } else {
                    send(ex, 200, "{\"ok\":false,\"error\":" + quote(msg) + "}",
                        "application/json; charset=utf-8")
                }
            }
            "delete" -> {
                val msg = Backup.delete(b.str("name"))
                send(ex, 200, if (msg == "ok") "{\"ok\":true}"
                else "{\"ok\":false,\"error\":" + quote(msg) + "}", "application/json; charset=utf-8")
            }
            else -> send(ex, 200, "{\"ok\":false,\"error\":\"不认识的动作\"}",
                "application/json; charset=utf-8")
        }
    }


    internal fun WebServer.settingsJson(): String {
        val st = settings
        val flags = HaoFlag.entries.joinToString(",", "[", "]") { f ->
            """{"key":${quote(f.key)},"title":${quote(f.title)},"what":${quote(f.what)},""" +
                """"on":${HaoFlag.enabled(f, st.flags)}}"""
        }
        val rules = Policies.get().rules(st.workspaceFile()).joinToString(",", "[", "]") { r ->
            """{"text":${quote(r.render())}}"""
        }
        val key = System.getenv("HAOAI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: runCatching { Env.apiKeyFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()

        return """{"provider":${quote(st.providerName)},"baseUrl":${quote(st.baseUrl)},""" +
            """"model":${quote(st.model)},"mode":${quote(st.permissionMode)},"maxTokens":${st.maxTokens},""" +
            """"reasoningEffort":${quote(st.reasoningEffort)},""" +
            """"searchProvider":${quote(st.searchProvider)},""" +
            """"fallback":${quote(st.fallback)},""" +
            /*
             * 市场地址要回得来：前端与像素剧本判"这个功能开了没"读的就是这份对象。
             * 第一次踩的时候 GET /api/settings 里没这个键，于是剧本里
             * `settings.expertFeed` 永远是 undefined —— 就算 CLI 真的写进去了，
             * 判据也还是红的，而症状看着像"市场整个坏了"。
             */
            """"expertFeed":${quote(st.expertFeed)},""" +
            // 只报"有没有 key"，不报 key 本身：这份对象会整体发给浏览器
            """"hasSearchKey":${Search.key().isNotBlank()},""" +
            // 上下文窗口必须回得去：抽屉里那一格原来永远是空的，用户以为没配，
            // 而保存时 num() 把空串读成 0 —— 于是"打开设置再保存"就把窗口清零了。
            """"contextChars":${st.contextChars},""" +
            """"workspace":${quote(st.workspaceFile().absolutePath)},""" +
            """"hasKey":${key != null},""" +
            """"flags":$flags,"rules":$rules}"""
    }


    internal fun WebServer.saveSettings(ex: HttpExchange) {
        val body = runCatching { Json.parseToJsonElement(bodyOf(ex)).jsonObject }.getOrNull()
        if (body == null) {
            send(ex, 400, """{"ok":false}""", "application/json; charset=utf-8"); return
        }
        var n = settings
        body["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { n = n.copy(model = it) }
        body["baseUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { n = n.copy(baseUrl = it) }
        body["maxTokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { n = n.copy(maxTokens = maxOf(0, it)) }
        // 窗口只认正数：读成 0 会让"那圈占用"永远显示 0%，比留空更骗人
        body["searchProvider"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            n = n.copy(searchProvider = it.trim().lowercase())
        }
        // 密钥不在这里写：一把 key 只留 `/api/secrets` 一个入口（见 [Secrets]）。
        // 两处能写同一把 key，"改了没生效"就要查两条路才知道哪条没走。
        body["contextChars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let {
            if (it > 0) n = n.copy(contextChars = it)
        }
        // 允许清空（"" = 不发 reasoning_effort），所以这里不判空
        body["reasoningEffort"]?.jsonPrimitive?.contentOrNull?.let {
            n = n.copy(reasoningEffort = it.trim().lowercase())
        }
        // 降级链同理：留空就是"不要降级"，得让人能清掉
        body["fallback"]?.jsonPrimitive?.contentOrNull?.let { n = n.copy(fallback = it.trim()) }
        /*
         * 专家市场清单地址（#136）：**空串是合法值**（空 = 这个功能等于不存在），
         * 所以这里也不判空 —— 界面上"清空地址"必须真能清掉，否则那个页签永远卡在旧源上。
         * 只存地址，不存任何密钥：这份设置会被 GET /api/settings 整体发回前端。
         */
        body["expertFeed"]?.jsonPrimitive?.contentOrNull?.let { n = n.copy(expertFeed = it.trim()) }
        body["mode"]?.jsonPrimitive?.contentOrNull?.takeIf { it in listOf("plan", "ask", "auto") }?.let {
            n = n.copy(permissionMode = it)
            // 权限模式是全局设置，但要立刻反映到**每一个**活着的会话引擎上，
            // 否则切回后台那个会话时它会继续用旧模式跑。（这里刻意不用 `it`，
            // 外层 `let` 已经把模式字符串占掉了，嵌套 `forEach { it -> }` 会把外层值遮蔽掉。）
            sessions.values.forEach { m -> m.engine.session.mode = it }
        }
        body["workspace"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { n = n.copy(workspace = it) }
        body["flags"]?.jsonObject?.let { fo ->
            val merged = n.flags.toMutableMap()
            fo.forEach { (k, v) -> merged[k] = (v.jsonPrimitive.content == "true") }
            n = n.copy(flags = HaoFlag.compactOverrides(merged))
        }
        /*
         * 密钥单独一条路（`/api/secrets`）：它**不进 PcSettings**（那份会被 GET /api/settings
         * 整体发回前端），只落 HAOAI_HOME/apikey。界面上给一个改与撤的入口是必要的 ——
         * 之前只能回 CLI，而"网页里能改模型、改网关，唯独改不了 key"会让人以为哪儿配错了。
         */
        PcSettings.save(n)
        settings = n
        // 全局设置变了，**每条活着的会话都要跟上**：引擎各自握着构造时那份设置
        // （模型、baseUrl、压缩阈值都在里面），不换的话界面上显示新模型、发请求用旧的。
        sessions.values.forEach { m ->
            m.engine.useSettings(n)
            // 跑着的那条不改档位：它可能刚被"本任务都允许"提到 auto，
            // 中途被一次设置保存打回去，正等确认的工具会当场变成"被拒绝"。
            if (!m.running) m.engine.session.mode = n.permissionMode
        }
        publish("settings", """{"mode":${quote(n.permissionMode)}}""")
        send(ex, 200, """{"ok":true}""", "application/json; charset=utf-8")
    }

