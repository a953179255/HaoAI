package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
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


    internal fun WebServer.schedules(): Scheduler? = sched


    /**
     * `GET/POST /api/schedules` —— 定时任务的读与增删改。
     * `POST {op:'add'|'del'|'toggle'|'run', id?, name?, prompt?, kind?, every?, at?}`
     */
    internal fun WebServer.schedules(ex: HttpExchange) {
        val b = Body(ex)
        if (ex.requestMethod != "GET") {
            val list = Schedules.load()
            val id = b.str("id").ifBlank { "sc" + System.nanoTime().toString(16).take(8) }
            when (b.str("op")) {
                "del" -> Schedules.remove(id)
                "toggle" -> list.firstOrNull { it.id == id }?.let {
                    it.enabled = !it.enabled; Schedules.update(it)
                }
                "run" -> list.firstOrNull { it.id == id }?.let { runSchedule(it) }
                else -> {
                    val prompt = b.str("prompt").trim()
                    if (prompt.isEmpty()) {
                        send(ex, 200, """{"ok":false,"error":"要跑的那句话是空的"}""",
                            "application/json; charset=utf-8"); return
                    }
                    // 一句话排期优先：解析不出来就明确拒绝，**不退回默认时间** ——
                    // 猜错的时间会在人睡着的时候起一条真任务并花 token。
                    val whenText = b.str("when").trim()
                    val parsed = if (whenText.isEmpty()) null else SchedulePlan.parse(whenText, System.currentTimeMillis())
                    // 只有"写了却没看懂"才拒绝；没写就走下面那套手填字段（老客户端与 CLI 都不带 when）
                    if (whenText.isNotEmpty() && parsed?.first == null) {
                        send(ex, 200, """{"ok":false,"error":${quote(parsed?.second ?: "没看懂那句排期")}}""",
                            "application/json; charset=utf-8"); return
                    }
                    val at = parsed?.first
                    val old = list.firstOrNull { it.id == id }
                    Schedules.update(
                        Schedule(
                            id = id,
                            name = b.str("name").trim().ifBlank { prompt.take(18) },
                            prompt = prompt,
                            kind = at?.kind ?: if (b.str("kind") == "daily") "daily" else "interval",
                            every = at?.every ?: (b.str("every").toIntOrNull()?.coerceIn(1, 7 * 24 * 60) ?: 60),
                            at = (at?.at ?: b.str("at")).ifBlank { "09:00" },
                            days = at?.days ?: b.str("days"),
                            runAt = at?.runAt ?: 0L,
                            flow = b.str("flow"),
                            created = old?.created ?: System.currentTimeMillis(),
                            enabled = old?.enabled ?: true,
                            lastRun = old?.lastRun ?: 0L,
                            lastSid = old?.lastSid ?: "",
                            lastError = old?.lastError ?: ""
                        )
                    )
                }
            }
        }
        send(ex, 200, """{"ok":true,"items":${schedulesJson()}}""", "application/json; charset=utf-8")
        publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }


    internal fun WebServer.schedulesJson(): String = Schedules.load().joinToString(",", "[", "]") { s ->
        """{"id":${quote(s.id)},"name":${quote(s.name)},"prompt":${quote(s.prompt)},""" +
            """"kind":${quote(s.kind)},"every":${s.every},"at":${quote(s.at)},""" +
            """"days":${quote(s.days)},"runAt":${s.runAt},"flow":${quote(s.flow)},""" +
            """"when":${quote(SchedulePlan.describe(s.kind, s.every, s.at, s.days, s.runAt))},""" +
            """"enabled":${s.enabled},"lastRun":${s.lastRun},"lastSid":${quote(s.lastSid)},""" +
            """"lastError":${quote(s.lastError)},"nextDue":${Schedule.nextDue(s, System.currentTimeMillis())}}"""
    }


    /** 界面边打边预览："每周一三五 8 点" → 复述 + 下一次时间。 */
    internal fun WebServer.schedParse(ex: HttpExchange) {
        // 界面走 GET 的查询串，CLI/测试可能把 when 放在 JSON 里：两边都认，
        // 但只读一次请求体（这条链路上每个 handler 只能读一次 body，读两遍会拿空）
        val raw = Regex("""(?:^|&)when=([^&]*)""").find(ex.requestURI.rawQuery ?: "")?.groupValues?.get(1)
        val text = raw?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault("") }
            ?: Body(ex).str("when")
        val (at, err) = SchedulePlan.parse(text, System.currentTimeMillis())
        if (at == null) {
            send(ex, 200, """{"ok":false,"error":${quote(err)}}""", "application/json; charset=utf-8")
            return
        }
        val next = Schedule.nextDue(
            Schedule("p", "p", "p", kind = at.kind, every = at.every, at = at.at,
                days = at.days, runAt = at.runAt),
            System.currentTimeMillis()
        )
        send(ex, 200, """{"ok":true,"echo":${quote(at.echo)},"kind":${quote(at.kind)},""" +
            """"every":${at.every},"at":${quote(at.at)},"days":${quote(at.days)},"runAt":${at.runAt},""" +
            """"next":$next,"nextText":${quote(if (next > 0L) schedStamp(next) else "不会跑（检查写法）")}}""",
            "application/json; charset=utf-8")
    }


    internal fun WebServer.schedStamp(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val w = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        return "%02d-%02d %02d:%02d %s".format(
            c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), w
        )
    }


    internal fun WebServer.workflows(ex: HttpExchange) {
        val b = Body(ex)
        if (ex.requestMethod != "GET") {
            when (b.str("op")) {
                "del" -> Workflows.remove(b.str("id"))
                "run" -> {
                    val (ok, note) = runWorkflow(b.str("id"))
                    if (!ok) {
                        send(ex, 200, """{"ok":false,"error":${quote(note)}}""",
                            "application/json; charset=utf-8"); return
                    }
                }
                else -> {
                    val steps = Workflows.parseSteps(b.str("steps"))
                    if (steps.isEmpty()) {
                        send(ex, 200, """{"ok":false,"error":"一行一步，至少写一行"}""",
                            "application/json; charset=utf-8"); return
                    }
                    val id = b.str("id").ifBlank { "wf" + System.nanoTime().toString(16).take(8) }
                    val old = Workflows.find(id)
                    Workflows.update(
                        Workflow(id, b.str("name").trim().ifBlank { steps.first().take(18) }, steps,
                            created = old?.created ?: System.currentTimeMillis())
                    )
                }
            }
        }
        send(ex, 200, Workflows.json(), "application/json; charset=utf-8")
    }


    /**
     * 跑一条任务链：第一步正常起一轮，剩下的**塞进那条会话已有的排队队列**。
     *
     * 不另造一套执行机制是这里的关键决定：队列由每轮结束时的接力自动往下发，
     * 于是每一步都看得见上一步的结果（同一段历史），按停止也会像平时一样清掉队列，
     * 而"任务链跑到一半会话被删了"这类边角情况不用重新想一遍。
     */
    internal fun WebServer.runWorkflow(id: String): Pair<Boolean, String> {
        val wf = Workflows.find(id) ?: return false to "没有这条任务链（可能刚被删掉）"
        if (wf.steps.isEmpty()) return false to "这条任务链是空的"
        val (sid, err) = startRun("", wf.steps.first(), named = wf.name, fresh = true, trigger = "任务链")
        if (err != null) return false to err
        val rest = wf.steps.drop(1)
        if (rest.isNotEmpty()) sessions[sid]?.let { m ->
            synchronized(m.queue) { rest.forEach { m.queue.add(it to emptyList()) } }
            publish("queue", queueJson(sid, synchronized(m.queue) { m.queue.toList() }), sid)
        }
        publish("sessions", "{}")
        return true to "已开跑：${wf.name}（共 ${wf.steps.size} 步）"
    }


    /**
     * 到点（或用户点了"立刻跑一次"）：起一条**新会话**去跑这句话。
     *
     * 用新会话而不是塞进当前会话：定时任务是"另一件事"，混进用户正在聊的那条，
     * 除了把上下文搅乱之外没有别的好处；而参考实现（openclaw 那类双机部署）也是
     * 一次触发一条独立会话。标题定成任务名，否则列表里全是"每天早上…"这种半截句子。
     */
    internal fun WebServer.runSchedule(s: Schedule) {
        // 配了任务链就跑链：链里每一步都看得见上一步的结果，比"排一句话"顶用
        if (s.flow.isNotBlank()) {
            // 先记账再跑：不写 lastRun 的话调度每 5 秒就会再触发一次，链会被反复起头
            s.lastRun = System.currentTimeMillis()
            s.lastError = ""
            Schedules.update(s)
            val (ok, note) = runWorkflow(s.flow)
            if (!ok) {
                s.lastError = note
                Schedules.update(s)
                Env.log("sched", "跑链失败：$note")
            }
            return
        }
        val item = s.copy()
        item.lastRun = System.currentTimeMillis()
        item.lastError = ""
        Schedules.update(item)
        val sid = try {
            val (newId, err) = startRun("", item.prompt, named = item.name, fresh = true, trigger = "定时")
            if (err != null) throw IllegalStateException(err)
            publish("title", """{"title":${quote(item.name)}}""", newId)
            // 侧栏要立刻刷出来：定时任务跑那几分钟里用户得看得见它在动，而不是"到点没反应"
            publish("sessions", "{}")
            newId
        } catch (e: Exception) {
            item.lastError = e.message ?: e.javaClass.simpleName
            Schedules.update(item)
            return
        }
        item.lastSid = sid
        Schedules.update(item)
    }


    internal fun WebServer.digestExport(ex: HttpExchange) {
        val (rel, note) = Digest.export(settings.workspaceFile())
        val ok = rel.isNotEmpty()
        send(ex, 200, """{"ok":$ok,"note":${quote(note)},"path":${quote(rel)}}""",
            "application/json; charset=utf-8")
    }

