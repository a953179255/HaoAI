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


    /**
     * `POST /api/model` {sid, model, scope} —— 换模型。
     *
     * `scope` 缺省是 `session`：只改这一条引擎的设置。以前全局只有一个模型，
     * 于是"这条会话拿本地小模型试个简单问题、别影响另外三条"做不到，
     * 而 codex / opencode 这类参考实现都是按会话选的。
     * `scope=all` 才是原来的全局路径：存盘 + 所有活着的会话一起跟上。
     */
    internal fun WebServer.modelSet(ex: HttpExchange) {
        val b = Body(ex)
        val m = b.str("model").trim()
        if (m.isEmpty()) {
            send(ex, 200, """{"ok":false,"error":"模型名是空的"}""",
                "application/json; charset=utf-8"); return
        }
        val sid = pick(b.str("sid"))
        val all = b.str("scope") == "all"
        if (all) {
            val n = settings.copy(model = m)
            PcSettings.save(n)
            settings = n
            sessions.values.forEach { it.engine.useSettings(n) }
        } else {
            val e = sessions[sid]?.engine
            if (e == null) {
                send(ex, 200, """{"ok":false,"error":"没有这条会话，改不了模型"}""",
                    "application/json; charset=utf-8"); return
            }
            e.useSettings(e.settings.copy(model = m))
        }
        send(ex, 200, """{"ok":true,"model":${quote(m)},"sid":${quote(sid)},"scope":"${if (all) "all" else "session"}"}""",
            "application/json; charset=utf-8")
        publish("model", """{"model":${quote(m)},"scope":"${if (all) "all" else "session"}"}""", sid)
        if (all) publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }


    /** `GET /api/models?sid=` —— 列网关上的模型，给顶栏的模型切换器用。 */
    internal fun WebServer.models(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val list = runCatching { sessions[sid]?.engine?.models() ?: emptyList() }.getOrDefault(emptyList())
        // "使用中"要按**这条会话**的模型标：按会话换模型之后，全局那个可能已经不是它在用的了，
        // 照全局标出来的结果是 chip 显示"本地 7B"、列表里打勾的却是另一行。
        val cur = sessions[sid]?.engine?.settings?.model ?: settings.model
        send(ex, 200, list.joinToString(",", "[", "]") { m ->
            """{"id":${quote(m)},"current":${m == cur}}"""
        }, "application/json; charset=utf-8")
    }


    /** `GET /api/export?sid=` —— 整条会话导成 markdown（贴进文档、发给别人时用）。 */
    internal fun WebServer.exportSession(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val e = sessions[sid]?.engine
        if (e == null) {
            send(ex, 404, "没有这条会话", "text/plain; charset=utf-8"); return
        }
        val sb = StringBuilder("# ").append(e.session.title.get()).append("\n\n")
        e.messages().forEach { m ->
            when {
                m.role == "user" -> sb.append("## 你\n\n").append(m.content ?: "").append("\n\n")
                m.role == "assistant" && !m.content.isNullOrBlank() ->
                    sb.append("### 助手\n\n").append(m.content).append("\n\n")
                m.role == "tool" -> sb.append("> `").append(m.name).append("` ")
                    .append((m.content ?: "").lineSequence().first().take(160)).append("\n\n")
            }
        }
        ex.responseHeaders.add("Content-Disposition", """attachment; filename="haoai-$sid.md"""")
        send(ex, 200, sb.toString(), "text/markdown; charset=utf-8")
    }


    /**
     * `POST /api/share` 出一份只读快照；`GET /api/share?name=` 把它读回来。
     *
     * 快照落在 `HAOAI_HOME/share/`，**不**写进用户的工作区 ——
     * 那是个 git 仓库，导出的东西不该混进用户的 diff 里。
     */
    internal fun WebServer.share(ex: HttpExchange) {
        val want = queryOf(ex, "name")
        if (ex.requestMethod == "GET" && want.isNotBlank()) {
            val f = Share.read(want)
            if (f == null) {
                send(ex, 404, "没有这个快照（名字不合法或已经被清掉）", "text/plain; charset=utf-8"); return
            }
            send(ex, 200, runCatching { f.readText(Charsets.UTF_8) }.getOrDefault(""),
                "text/html; charset=utf-8")
            return
        }
        val sid = pick(Body(ex).str("sid"))
        val e = sid.takeIf { it.isNotBlank() }?.let { sessions[it]?.engine }
        if (e == null) {
            send(ex, 200, """{"ok":false,"error":"没有这条会话"}""",
                "application/json; charset=utf-8"); return
        }
        val msgs = e.messages().map { Triple(it.role, it.name ?: "", it.content ?: "") }
        val name = Share.nameFor(sid)
        val file = Share.write(
            name, Share.render(
                e.session.title.get(), e.session.workspace.absolutePath,
                e.settings.model, e.session.mode, System.currentTimeMillis(), msgs
            )
        )
        if (file == null) {
            send(ex, 200, """{"ok":false,"error":"快照没写进去（磁盘或权限）"}""",
                "application/json; charset=utf-8"); return
        }
        send(ex, 200,
            """{"ok":true,"name":${quote(name)},"path":${quote(file.absolutePath)},""" +
                """"url":${quote("/api/share?name=$name")},"blocks":${msgs.size}}""",
            "application/json; charset=utf-8")
    }

