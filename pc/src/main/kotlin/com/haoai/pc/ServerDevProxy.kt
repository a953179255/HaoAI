package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import java.io.File
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
     * `POST /api/substop` {sid,label} —— 只停某一条子任务，父任务继续。
     *
     * 和"停止"那颗总闸的区别：总闸会把这一轮整个中止（包括正在写的正文），
     * 而这里只让那条跑偏的调研收尾 —— 父引擎拿到一句"子任务被中断"，接着干别的。
     */
    // ---- 终端页签：人与 agent 共用同一份常驻进程表（ProcRegistry）----
    /*
     * 为什么给人也开一份：agent 跑 `gradle` 跑到一半弹确认，人看得见却插不上手，
     * 只能等它超时；而人在终端里敲的东西 agent 也看不见。两边其实是**同一个进程**，
     * 现在按序号各取各的游标（见 ProcRegistry.Live.since），互不偷输出。
     *
     * 边界：起进程只认 shell 名字（bash/pwsh/cmd，由 ShellLauncher 在本机找），
     * 不接受任意可执行文件；工作目录必须落在**这条会话的工作区里**。
     * 理由与 /api/img 同源 —— 这个服务只绑 127.0.0.1，但同机任意页面都能打这个端口，
     * "能起任意进程 + 任意目录"是不能给的。
     */
    internal fun WebServer.shellsJson(): String =
        """{"ok":true,"shells":[""" + ProcRegistry.list().joinToString(",") { l ->
            """{"id":${quote(l.id)},"label":${quote(l.label)},"display":${quote(l.display)},""" +
                """"cwd":${quote(Env.abs(l.cwd))},"alive":${l.alive()},""" +
                """"idle":${System.currentTimeMillis() - l.lastUsed},""" +
                """"exit":${if (l.alive()) -1 else (l.exitCode() ?: -1)}}"""
        } + "]}"


    internal fun WebServer.shellsList(ex: HttpExchange) {
        send(ex, 200, shellsJson(), "application/json; charset=utf-8")
    }


    /** 面板轮询：只给序号大于 after 的行。游标在浏览器里，所以刷新/换标签不影响模型那一路。 */
    internal fun WebServer.shellTail(ex: HttpExchange) {
        val id = queryOf(ex, "id")
        val l = ProcRegistry.get(id) ?: return send(
            ex, 200, """{"ok":false,"error":"没有这个进程（可能已被回收）","lines":[]}""",
            "application/json; charset=utf-8"
        )
        val after = runCatching { queryOf(ex, "after").toLong() }.getOrDefault(0L)
        val (next, lines) = l.since(after, 400)
        send(ex, 200, """{"ok":true,"next":$next,"alive":${l.alive()},"lines":[""" +
            lines.joinToString(",") { quote(it) } + "]}", "application/json; charset=utf-8")
    }


    internal fun WebServer.shellWorkspaceSid(sid: String): File =
        (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile())


    internal fun WebServer.shellOpen(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val ws = shellWorkspaceSid(sid).canonicalFile
        val want = b.str("cwd").ifBlank { ws.path }
        val cwd = runCatching { File(want).canonicalFile }.getOrDefault(ws)
        if (!cwd.path.startsWith(ws.path) || !cwd.isDirectory) {
            val why = "终端只能开在这条会话的工作区里：" + Env.abs(ws) + " 之下的目录（不接受任意目录）"
            send(ex, 200, """{"ok":false,"error":${quote(why)}}""", "application/json; charset=utf-8")
            return
        }
        ProcRegistry.open(b.str("label"), b.str("shell").ifBlank { "bash" }, cwd, "")
            .fold(
                onSuccess = { send(ex, 200, """{"ok":true,"id":${quote(it.id)},"display":${quote(it.display)}}""",
                    "application/json; charset=utf-8") },
                onFailure = { e -> send(ex, 200, """{"ok":false,"error":${quote(e.message ?: "起不来")}}""",
                    "application/json; charset=utf-8") }
            )
    }


    internal fun WebServer.shellSend(ex: HttpExchange) {
        val b = Body(ex)
        val id = b.str("id")
        if (id.isBlank()) { send(ex, 200, """{"ok":false,"error":"缺少 id"}""", "application/json; charset=utf-8"); return }
        val r = ProcRegistry.send(id, b.str("text"), b.str("enter") != "0")
        send(ex, 200, if (r.isSuccess) """{"ok":true}"""
        else """{"ok":false,"error":${quote(r.exceptionOrNull()?.message ?: "写不进去")}}""",
            "application/json; charset=utf-8")
    }

    // ---- 浏览器预览面板：把 CDP 画面搬进网页，人点的地方送回页面 ----
    //
    // 与 Git 面板同一个立场：**人亲手点这一下就是审批**，所以这里不再过模型的权限闸
    // （模型那条路照旧逐条问）。但两件事不放：开关没开时这套端点一律不动浏览器；
    // 网址只收 http/https —— 这条通道能把任何 URL 渲染成图，`file://` 就等于开了个读盘口。


    internal fun WebServer.previewOn(): Boolean = HaoFlag.enabled(HaoFlag.BROWSER_CONTROL, settings.flags)


    /**
     * 只读的那一半：**光是打开页签、看画面、看状态，绝不起新进程**。
     *
     * 上一版这里统一走 getOrCreate()，于是"关掉这台浏览器"被自己的轮询顶掉了 ——
     * 关闭之后下一次取帧又把 Edge 拉起来，面板上那句"已关掉"当场变成假话。
     */
    internal fun WebServer.previewPeek(): BrowserSession? =
        if (!previewOn()) null else BrowserSession.existing()


    /** 会做事的那一半：只有人明确按了"打开 / 送入 / 点一下 / 切标签"才允许起。 */
    internal fun WebServer.previewSession(): BrowserSession? =
        if (!previewOn()) null else runCatching { BrowserSession.getOrCreate() }.getOrNull()


    internal fun WebServer.previewOff(ex: HttpExchange) {
        send(ex, 200, PreviewPanel.errJson(PreviewPanel.OFF_NOTE), "application/json; charset=utf-8")
    }



    internal fun WebServer.previewState(ex: HttpExchange) {
        if (!previewOn()) {
            send(ex, 200, PreviewPanel.stateJson(false, PreviewPanel.OFF_NOTE, "", "", 0, 0, emptyList()),
                "application/json; charset=utf-8")
            return
        }
        val ses = previewPeek()
        if (ses == null) {
            send(ex, 200, PreviewPanel.stateJson(true,
                "还没有在跑的浏览器。输个网址按「打开」就会起一台（独立配置目录，不碰你自己的 Edge）。" +
                    "起不来时看 `haoai doctor`。",
                "", "", 0, 0, emptyList()), "application/json; charset=utf-8")
            return
        }
        val (vw, vh) = ses.viewport()
        send(ex, 200, PreviewPanel.stateJson(true, "", ses.pageUrlNow(), ses.pageTitle(),
            vw, vh, ses.pageList()), "application/json; charset=utf-8")
    }


    /** 一帧 JPEG。回 404/503 是故意的：面板靠 <img> 的 error 事件知道"这帧没拿到"。 */
    internal fun WebServer.previewFrame(ex: HttpExchange) {
        val ses = previewPeek()
        if (ses == null) {
            send(ex, 404, if (previewOn()) """{"ok":false,"error":"浏览器没起来"}"""
            else PreviewPanel.errJson(PreviewPanel.OFF_NOTE), "application/json; charset=utf-8")
            return
        }
        val bytes = runCatching { ses.jpegFrame() }.getOrDefault(ByteArray(0))
        if (bytes.isEmpty()) {
            send(ex, 503, """{"ok":false,"error":"这一帧没截到（页面可能正在导航）"}""",
                "application/json; charset=utf-8")
            return
        }
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("Content-Type", "image/jpeg")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }


    internal fun WebServer.previewOpen(ex: HttpExchange) {
        val b = Body(ex)
        val url = PreviewPanel.safeUrl(b.str("url"))
        if (url == null) {
            send(ex, 200, PreviewPanel.errJson("只收 http/https 网址；本地文件请用「产出」页签打开"),
                "application/json; charset=utf-8")
            return
        }
        val ses = previewSession()
        if (ses == null) { previewOff(ex); return }
        val note = runCatching { ses.navigate(url) }.getOrElse { e -> "打不开：${e.message ?: "未知原因"}" }
        send(ex, 200, PreviewPanel.okJson(note), "application/json; charset=utf-8")
    }


    internal fun WebServer.previewInput(ex: HttpExchange) {
        val b = Body(ex)
        val ses = previewSession()
        if (ses == null) { previewOff(ex); return }
        val num = fun(k: String): Double {
            val v = b.str(k).toDoubleOrNull() ?: 0.0
            return if (v.isNaN() || v.isInfinite()) 0.0 else v
        }
        val out = when (b.str("kind")) {
            "click" -> {
                val (vw, vh) = ses.viewport()
                val pt = PreviewPanel.mapClick(num("x"), num("y"), num("iw").toInt(), num("ih").toInt(), vw, vh)
                if (vw <= 0 || vh <= 0) "页面还没量出尺寸，稍等一下再点" else ses.clickAt(pt.first, pt.second)
            }
            "wheel" -> {
                val (vw, vh) = ses.viewport()
                ses.scrollAt(num("x").toInt().coerceIn(0, maxOf(vw - 1, 0)),
                    num("y").toInt().coerceIn(0, maxOf(vh - 1, 0)), num("dy").toInt())
            }
            "text" -> {
                val t = b.str("text")
                if (t.isEmpty()) "没东西可送" else ses.insertText(t.take(2000))
            }
            "key" -> ses.pressKey(b.str("key"))
            else -> "不认的动作：「${b.str("kind")}」（能用的：click / wheel / text / key）"
        }
        send(ex, 200, PreviewPanel.okJson(out), "application/json; charset=utf-8")
    }


    /**
     * 用完就关。面板会自己起一台带独立配置目录的浏览器 ——
     * 不留这颗按钮的话，一晚十几轮验收就是一堆没人要的 Edge 窗口（这台机器还是几个 agent 共用的）。
     */
    internal fun WebServer.previewClose(ex: HttpExchange) {
        val ses = BrowserSession.existing()
        if (ses == null) {
            send(ex, 200, PreviewPanel.okJson("没有正在跑的浏览器实例"), "application/json; charset=utf-8")
            return
        }
        val note = runCatching { ses.shutdown() }.getOrElse { e -> "关的时候出错了：${e.message}" }
        send(ex, 200, PreviewPanel.okJson(note + "（独立配置目录，不碰你自己的 Edge）"),
            "application/json; charset=utf-8")
    }


    internal fun WebServer.previewPick(ex: HttpExchange) {
        val b = Body(ex)
        val ses = previewSession()
        if (ses == null) { previewOff(ex); return }
        val note = ses.focusPage(b.str("id")) ?: "找不到那个标签页（可能已经被关掉了）"
        send(ex, 200, PreviewPanel.okJson(note), "application/json; charset=utf-8")
    }


    internal fun WebServer.shellClose(ex: HttpExchange) {
        val b = Body(ex)
        val id = b.str("id")
        val msg = if (id.isBlank()) "缺少 id" else ProcRegistry.close(id).getOrElse { "关不掉：${it.message}" }
        send(ex, 200, """{"ok":true,"message":${quote(msg)}}""", "application/json; charset=utf-8")
    }

