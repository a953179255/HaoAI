package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import java.io.File

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
     * `GET /api/files?sid=&path=` —— 工作区文件列表，给右栏「产出」页签下的浏览区。
     * `GET /api/files?sid=&q=` —— 递归按片段搜，给输入框的 @ 提及用（返回相对路径）。
     *
     * 只许在工作区里面走：算完 canonical 之后不在工作区内的，一律退回根目录。
     * 这个服务只绑 127.0.0.1，但浏览器里**任何**页面都能对本机端口发请求，
     * 所以"路径不能逃出工作区"必须在服务端守住，不能指望前端不传 `..`。
     */
    internal fun WebServer.files(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val ws = (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile
        val dir = runCatching { File(ws, queryOf(ex, "path")).canonicalFile }.getOrDefault(ws)
        val root = if (dir.path.startsWith(ws.path) && dir.isDirectory) dir else ws
        val q = queryOf(ex, "q").trim()
        if (q.isNotEmpty()) {
            /*
             * `?q=` 是输入框里 @ 提及文件的递归搜索。三条边界都是必须的：
             * 只在 canonical 之后的工作区内走（这个服务绑 127.0.0.1，但浏览器里任何页面
             * 都能对本机端口发请求）；扫满 4000 个条目就收，免得在一份内核 checkout 里
             * 按一个字母就把盘扫穿；跳过 .git / node_modules / build 这类没人要的目录。
             */
            val skip = setOf(".git", ".gradle", ".idea", "build", "dist", "target", ".haoai-output", ".trash")
            val hits = mutableListOf<Pair<String, Boolean>>()
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(ws to 0)
            var seen = 0
            val low = q.lowercase()
            while (queue.isNotEmpty() && hits.size < 40 && seen < 4000) {
                val (dir, depth) = queue.removeFirst()
                for (f in dir.listFiles()?.toList() ?: emptyList()) {
                    if (++seen > 4000) break
                    if (f.name in skip || f.name.startsWith("node_modules")) continue
                    val rel = f.relativeToOrSelf(ws).path.replace('\\', '/')
                    if (rel.lowercase().contains(low)) hits += rel to f.isDirectory
                    if (hits.size >= 40) break
                    if (f.isDirectory && depth < 6) queue.add(f to depth + 1)
                }
            }
            // 文件名开头就命中的排在前面，再按名字短的在前：打 "serv" 想看的第一个是 Server.kt，
            // 不是 xxx-service.txt，也不是某个深层同名文件
            val ranked = hits.sortedWith(
                compareByDescending<Pair<String, Boolean>> {
                    it.first.substringAfterLast('/').lowercase().startsWith(low)
                }.thenBy { it.first.substringAfterLast('/').length }.thenBy {
                    it.first.count { c -> c == '/' }
                }.thenBy { it.first.length }
            )
            send(ex, 200, ranked.joinToString(",", """{"path":"","entries":[""", "]}") { (rel, dir) ->
                """{"n":${quote(rel)},"d":$dir,"s":0}"""
            }, "application/json; charset=utf-8")
            return
        }
        val list = root.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.filter { it.name != ".git" && !it.name.startsWith("node_modules") }?.take(300)
            ?: emptyList()
        val shown = if (root == ws) "" else root.relativeToOrSelf(ws).path.replace('\\', '/')
        val sb = StringBuilder("""{"path":${quote(shown)},"entries":[""")
        list.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("""{"n":${quote(f.name)},"d":${f.isDirectory},"s":${if (f.isDirectory) 0 else f.length()}}""")
        }
        sb.append("]}")
        send(ex, 200, sb.toString(), "application/json; charset=utf-8")
    }


    internal fun WebServer.workspaces(ex: HttpExchange) {
        val cur = pick(querySid(ex))
        val curWs = runCatching {
            (sessions[cur]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile.absolutePath
        }.getOrDefault("")
        val metas = SessionIndex.list(400)
        val grouped = LinkedHashMap<String, Pair<Int, Long>>()
        metas.forEach { m ->
            val key = runCatching { File(m.workspace).canonicalFile.absolutePath }.getOrDefault(m.workspace)
            if (key.isBlank()) return@forEach
            val (n, t) = grouped[key] ?: (0 to 0L)
            grouped[key] = (n + 1) to maxOf(t, m.updated)
        }
        // 全局默认那个即使一条会话都没有也要在列表里（第一次用的人只有它）
        val def = runCatching { settings.workspaceFile().canonicalFile.absolutePath }.getOrDefault("")
        if (def.isNotEmpty()) grouped.getOrPut(def) { 0 to 0L }
        val items = grouped.entries.sortedByDescending { it.value.second }
        send(ex, 200, items.joinToString(",", """{"current":${quote(curWs)},"items":[""", "]}") { (p, v) ->
            """{"p":${quote(p)},"n":${v.first},"t":${v.second},""" +
                """"cur":${p == curWs},"def":${p == def}}"""
        }, "application/json; charset=utf-8")
    }


    /**
     * `GET /api/img?sid=&path=` —— 把会话工作区里的图片原样发给浏览器，好让流里画得出缩略图。
     *
     * 历史里存的是路径，界面要显示就得有个取字节的口子。三条边界与 /api/files 同一套理由：
     * 这个服务只绑 127.0.0.1，但同机任意页面都能对这个端口发请求，所以路径必须 canonical
     * 之后仍在**这条会话自己的工作区**里，而且只放认得出的那四种图 ——
     * 否则它就成了一个"读任意本地文件并回显"的接口。
     */
    internal fun WebServer.imageFile(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val ws = runCatching {
            (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile
        }.getOrNull()
        val given = queryOf(ex, "path")
        val f = if (ws == null) null else runCatching {
            // 工具产出的截图存的是绝对路径，用户附件是工作区相对路径：两种都要能取回
            (if (File(given).isAbsolute) File(given) else File(ws, given)).canonicalFile
        }.getOrNull()
        val inside = f != null && ws != null &&
            (f.path == ws.path || f.path.startsWith(ws.path + File.separator))
        val mime = if (inside && f != null && f.isFile) Images.mime(f) else null
        if (mime == null || f == null || f.length() > Images.MAX_BYTES) {
            send(ex, 404, "没有这张图（或它在工作区外面 / 太大 / 类型不认）",
                "text/plain; charset=utf-8"); return
        }
        val bytes = f.readBytes()
        ex.responseHeaders.add("Content-Type", mime)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }


    /**
     * `GET /api/media?sid=&path=` —— 把这条会话工作区里的音视频发给浏览器，支持 Range。
     *
     * 三条边界与 /api/img 是同一套理由（这个服务只绑 127.0.0.1，但同机任意页面都能
     * 对这个端口发请求）：canonical 之后必须仍在这条会话自己的工作区内、类型必须由**魔数**
     * 认得出（只看后缀就等于让一个改名成 .mp4 的文本文件混出去）、单文件有上限。
     */
    internal fun WebServer.mediaFile(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val ws = runCatching {
            (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile
        }.getOrNull()
        val given = queryOf(ex, "path")
        val f = if (ws == null) null else runCatching {
            (if (File(given).isAbsolute) File(given) else File(ws, given)).canonicalFile
        }.getOrNull()
        val inside = f != null && ws != null &&
            (f.path == ws.path || f.path.startsWith(ws.path + File.separator))
        val mime = if (inside && f != null && f.isFile && f.length() <= MediaMime.MAX) MediaMime.sniff(f) else null
        if (mime == null || f == null) {
            send(ex, 404, "没有这个媒体文件（或它在工作区外面 / 太大 / 类型不认）",
                "text/plain; charset=utf-8"); return
        }
        val len = f.length()
        val (from, to) = Range.parse(ex.requestHeaders.getFirst("Range"), len)
        val size = to - from + 1
        ex.responseHeaders.add("Content-Type", mime)
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        ex.responseHeaders.add("Cache-Control", "no-store")
        val partial = from > 0 || to < len - 1
        if (partial) ex.responseHeaders.add("Content-Range", "bytes $from-$to/$len")
        ex.sendResponseHeaders(if (partial) 206 else 200, size)
        runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val out = ex.responseBody
                val buf = ByteArray(64 * 1024)
                var left = size
                raf.seek(from)
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                out.close()
            }
        }
    }


    internal fun WebServer.fileOne(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val ws = (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile
        val rel = queryOf(ex, "path")
        val f = runCatching { File(ws, rel).canonicalFile }.getOrNull()
        if (f == null || !f.path.startsWith(ws.path) || !f.isFile) {
            send(ex, 404, """{"ok":false,"error":"没有这个文件（或它在工作区外面）"}""",
                "application/json; charset=utf-8"); return
        }
        if (f.length() > 4_000_000) {
            send(ex, 200, """{"ok":false,"error":"文件太大（${f.length() / 1024} KB），只给前 200 KB 的预览"}""",
                "application/json; charset=utf-8"); return
        }
        /*
         * "是不是文本"不能靠 readText() 抛不抛异常 —— 它遇到坏字节是替换成 U+FFFD 而不是抛，
         * 于是一张 PNG 会以一屏乱码的形式被当成文本发回界面（实测就是这么露出来的）。
         * 改成自己判：图片魔数直接算二进制（界面走 /api/img 画出来），
         * 其余看前 8 KB 里有没有 NUL 字节（真正的文本文件不会有）。
         */
        val head = runCatching {
            val b = ByteArray(minOf(8192, f.length().toInt()))
            f.inputStream().use { it.read(b) }; b
        }.getOrDefault(ByteArray(0))
        val isImage = Images.mime(f) != null
        val isBinary = isImage || head.contains(0.toByte())
        if (isBinary) {
            send(ex, 200, """{"ok":true,"binary":true,"img":$isImage,"bytes":${f.length()}}""",
                "application/json; charset=utf-8"); return
        }
        val text = runCatching { f.readText() }.getOrNull()
        if (text == null) {
            send(ex, 200, """{"ok":true,"binary":true,"img":false,"bytes":${f.length()}}""",
                "application/json; charset=utf-8"); return
        }
        val cut = text.take(200_000)
        send(ex, 200, """{"ok":true,"text":${quote(cut)},"truncated":${text.length > cut.length}}""",
            "application/json; charset=utf-8")
    }

