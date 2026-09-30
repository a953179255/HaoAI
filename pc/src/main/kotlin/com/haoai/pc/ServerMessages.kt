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
     * `POST /api/edit` {sid,index,text} —— 改一句已经说过的话并重跑。
     *
     * 语义与移动端一致：**替换**那条用户消息，并把它之后的一切都丢掉。
     * 留着后半段不行：那些工具结果与新问题无关，模型会照着旧结论接着往下说。
     */
    internal fun WebServer.editMessage(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val index = b.str("index").toIntOrNull() ?: -1
        val text = b.str("text")
        if (text.isBlank() || sid.isBlank()) {
            send(ex, 200, """{"ok":false,"error":"缺 text 或 sid"}""",
                "application/json; charset=utf-8"); return
        }
        val (got, err) = startRun(sid, text, cutTo = index, trigger = "重跑")
        if (err != null) {
            send(ex, 409, """{"ok":false,"error":${quote(err)}}""", "application/json; charset=utf-8"); return
        }
        send(ex, 200, """{"ok":true,"sid":${quote(got)},"cut":$index}""", "application/json; charset=utf-8")
    }


    /** `POST /api/cut` {sid,index} —— 丢掉某句之后的所有内容（"就到这里，别往下接了"）。 */
    internal fun WebServer.cutMessage(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val index = b.str("index").toIntOrNull() ?: -1
        val m = sessions[sid]
        if (m == null) {
            send(ex, 404, """{"ok":false,"error":"没有这条会话"}""", "application/json; charset=utf-8"); return
        }
        if (m.running) {
            send(ex, 409, """{"ok":false,"error":"这条会话正在跑，先停止再改历史"}""",
                "application/json; charset=utf-8"); return
        }
        // keep=0 是 /clear 用法（连第一条一起丢）；默认留着这一句，那是"删到这里"的语义
        if (!m.engine.cutTo(index, keepAt = b.str("keep") != "0")) {
            send(ex, 200, """{"ok":false,"error":"只能删「你说过的那一句话」之后的内容"}""",
                "application/json; charset=utf-8"); return
        }
        send(ex, 200, """{"ok":true,"cut":$index}""", "application/json; charset=utf-8")
        publish("sessions", "{}", sid)
    }


    /**
     * `POST /api/rewindturn` {sid,index} —— 回到这一句之前：**话和文件一起回去**。
     *
     * 为什么光有 `/api/cut` 不够：那句"删到这里"只截对话，工作区里那几轮改的文件留在原地，
     * 于是"退回到问它之前"是假的 —— 人以为改动没了，下一轮模型读到的还是改过的内容。
     * 这里退的是"这一轮以及它之后的所有轮"（同一路径取这批记录里最早那份快照）。
     *
     * 顺序刻意：能校验的先校验完（会话在、没在跑、这一句确实属于某一轮），再截历史，
     * 最后才动文件 —— 动文件是这里唯一收不回的那一步，要让它出问题时
     * 用户至少看得见"话已经退到哪了"，而不是反过来。
     */
    internal fun WebServer.rewindTurn(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val index = b.str("index").toIntOrNull() ?: -1
        val json = "application/json; charset=utf-8"
        val m = sessions[sid]
        if (m == null) {
            send(ex, 404, """{"ok":false,"error":"没有这条会话"}""", json); return
        }
        if (m.running) {
            send(ex, 409, """{"ok":false,"error":"这条会话正在跑，先停止再回退"}""", json); return
        }
        val hit = Checkpoints.runCovering(sid, index)
        if (hit == null) {
            send(ex, 200, """{"ok":false,"error":"这一句对不上任何一轮（那次运行早于这个功能，或记录被清掉了）"}""", json)
            return
        }
        val at = hit.second
        if (!m.engine.cutTo(at, keepAt = false)) {
            send(ex, 200, """{"ok":false,"error":"截不动这一段历史，文件也没动"}""", json); return
        }
        val runs = Checkpoints.runsFrom(sid, at)
        val dropped = m.engine.dropTodosForRewind()
        val r = Checkpoints.rewindAll(m.engine.session.workspace, runs)
        fun arr(xs: List<String>) = xs.joinToString(",", "[", "]") { quote(it) }
        send(ex, 200,
            """{"ok":true,"cut":$at,"runs":${runs.size},"todos":$dropped,"note":${quote(r.note)},""" +
                """"restored":${arr(r.restored)},"deleted":${arr(r.deleted)},""" +
                """"missing":${arr(r.missing)}}""", json)
        publish("sessions", "{}", sid)
    }


    /** `POST /api/regenerate` {sid} —— 把最后一条用户消息重问一遍，换个回答。 */
    internal fun WebServer.regenerate(ex: HttpExchange) {
        val sid = pick(Body(ex).str("sid"))
        val e = sessions[sid]?.engine
        if (e == null) {
            send(ex, 404, """{"ok":false,"error":"没有这条会话"}""", "application/json; charset=utf-8"); return
        }
        val idx = e.lastUserIndex()
        val text = e.textAt(idx)
        if (idx < 0 || text.isNullOrBlank()) {
            send(ex, 200, """{"ok":false,"error":"这条会话里还没有可重问的一句话"}""",
                "application/json; charset=utf-8"); return
        }
        val (got, err) = startRun(sid, text, cutTo = idx, trigger = "改问重发")
        if (err != null) {
            send(ex, 409, """{"ok":false,"error":${quote(err)}}""", "application/json; charset=utf-8"); return
        }
        send(ex, 200, """{"ok":true,"sid":${quote(got)},"index":$idx}""", "application/json; charset=utf-8")
    }


    /**
     * `POST /api/rollback` {sid,path} —— 把一个文件退回最近一份快照。
     *
     * 是"回滚这一个文件"，不是"撤销整个任务"：agent 一次跑十几步，
     * 用户想撤的往往就是刚才那一个文件，那就给他一个精确、看得见时间的动作。
     */
    internal fun WebServer.rollback(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val rel = b.str("path")
        val ws = sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()
        if (rel.isBlank()) {
            send(ex, 200, """{"ok":false,"error":"没说要回滚哪个文件"}""",
                "application/json; charset=utf-8"); return
        }
        val at = Snapshots.restore(ws, rel)
        if (at == null) {
            send(ex, 404, """{"ok":false,"error":"没有这个文件的快照（写之前没留底，或快照开关当时关着）"}""",
                "application/json; charset=utf-8")
            return
        }
        send(ex, 200, """{"ok":true,"restored":${quote(at)}}""", "application/json; charset=utf-8")
        publish("notice", quote("已把 $rel 退回快照（$at）"), sid)
    }


    /**
     * `POST /api/attach` —— 把浏览器里选的文件落到工作区的 `.haoai-attach/`，回相对路径。
     *
     * 为什么不直接把内容塞进消息：模型要的是"一个能读的路径"。文本还好办，
     * 图片/二进制进文本历史只会变成乱码，而落到盘上之后 read、grep、
     * 视觉输入走的是同一条路。
     *
     * 文件名一律自己拼：清洗掉所有路径分隔符再加时间戳。**不能把用户给的名字交给
     * 文件系统** —— `..\..\x` 这种名字会把文件写到工作区外面去。
     */
    internal fun WebServer.attach(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val ws = sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()
        val name = b.str("name").replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5 ]"), "_").take(80)
        val data = b.str("data")
        if (name.isBlank() || data.isBlank()) {
            send(ex, 200, """{"ok":false,"error":"缺 name 或 data"}""",
                "application/json; charset=utf-8"); return
        }
        if (data.length > 12_000_000) {
            send(ex, 200, """{"ok":false,"error":"附件太大（上限约 9 MB）"}""",
                "application/json; charset=utf-8"); return
        }
        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull()
        if (bytes == null) {
            send(ex, 200, """{"ok":false,"error":"附件没读全（base64 解不开）"}""",
                "application/json; charset=utf-8"); return
        }
        val rel = ".haoai-attach/" + System.currentTimeMillis() + "-" + name
        val out = File(ws, rel)
        try {
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        } catch (e: Exception) {
            send(ex, 200, """{"ok":false,"error":${quote("存不下：" + (e.message ?: e.javaClass.simpleName))}}""",
                "application/json; charset=utf-8"); return
        }
        send(ex, 200, """{"ok":true,"path":${quote(rel)},"bytes":${bytes.size}}""",
            "application/json; charset=utf-8")
    }


    /** `GET /api/file?sid=&path=` —— 看一个文本文件的前 200 KB（二进制只报大小）。 */
    /**
     * `GET /api/workspaces` —— 最近用过的工作区，给左栏那个切换器。
     *
     * 不另存一份"最近列表"：会话索引里本来就写着每条会话的工作区，再存一份就是两个真源，
     * 迟早对不上（手机端踩过这个）。这里按目录归组，条数与最后活动时间都从会话算出来。
     */
    /**
     * `POST /api/compact` {sid} —— 手动把早期消息折进摘要（不等触发线）。
     *
     * 跑着的那条不许压：历史正在被回合改，这时候算出来的切点等于两条任务往一段历史上写。
     */
    internal fun WebServer.compactNow(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val m = sessions[sid]
        if (m == null) {
            send(ex, 200, """{"ok":false,"error":"没有这条会话"}""", "application/json; charset=utf-8"); return
        }
        if (m.running) {
            send(ex, 200, """{"ok":false,"error":${quote("这条正在跑，等它收尾再压（不然切点会算错）")}}""",
                "application/json; charset=utf-8"); return
        }
        val before = m.engine.contextChars()
        val cut = runCatching { m.engine.compactNow() }.getOrElse { e ->
            send(ex, 200,
                """{"ok":false,"error":${quote("压缩失败：" + (e.message ?: e.javaClass.simpleName))}}""",
                "application/json; charset=utf-8")
            return
        }
        send(ex, 200, """{"ok":true,"cut":$cut,"before":$before,"after":${m.engine.contextChars()}}""",
            "application/json; charset=utf-8")
        publish("sessions", "{}", sid)
    }


    /**
     * `POST /api/delmsg` {sid,index} —— 删掉某一句和它带出来的一切（见 [Engine.deleteAt]）。
     *
     * 跑着的时候不许删：引擎线程正在往同一段历史上写，删完它下一轮读到的就不是刚才那份。
     */
    internal fun WebServer.deleteMessage(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val i = b.str("index").toIntOrNull() ?: -1
        val m = sessions[sid]
        val (n, err) = when {
            m == null -> 0 to "没有这条会话"
            m.running -> 0 to "这条会话正在跑，先停止再删"
            else -> m.engine.deleteAt(i)
        }
        send(ex, 200, if (err.isEmpty()) """{"ok":true,"removed":$n}"""
        else """{"ok":false,"error":${quote(err)}}""", "application/json; charset=utf-8")
        if (err.isEmpty()) publish("sessions", "{}", sid)
    }


    /**
     * `GET /api/checkpoints?sid=` —— 这条会话最近几轮各动了几个文件。
     * `POST /api/rewind` {sid, run} —— 回到那一轮开始之前。
     *
     * 与 Git 面板同一立场：**人点的这颗钮就是审批**，所以不再过模型的权限闸。
     * 但它会覆盖与删除工作区里的文件，所以界面上必须两步确认（见 index.html 那颗按钮）。
     */
    internal fun WebServer.checkpointsList(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val ws = sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()
        send(ex, 200, Checkpoints.listJson(sid, ws.name), "application/json; charset=utf-8")
    }


    internal fun WebServer.rewindRun(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val run = b.str("run")
        if (run.isBlank()) {
            send(ex, 200, PreviewPanel.errJson("要指定回到哪一轮（run）"), "application/json; charset=utf-8")
            return
        }
        val ws = sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()
        val r = Checkpoints.rewind(ws, run)
        val miss = r.missing.joinToString(", ") { quote(it) }
        send(ex, 200, """{"ok":${r.ok},"note":${quote(r.note)},"restored":[${
            r.restored.joinToString(", ") { quote(it) }
        }],"deleted":[${r.deleted.joinToString(", ") { quote(it) }}],""" +
            """"missing":[$miss]}""", "application/json; charset=utf-8")
    }

    // ---- 手机联动（局域网）：这一组是**只回本机网页**的控制口，真正的对外面在 LanServer ----

