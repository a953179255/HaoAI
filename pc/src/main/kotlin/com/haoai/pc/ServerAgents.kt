package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import com.haoai.pc.WebServer.Body

/**
 * 多 Agent 的**壳侧**：把 [Agents] 那个纯状态机接到真的引擎上，并开出 `/api/agents`。
 *
 * 为什么要分开：`Agents` 只管"谁在跑、消息排在谁那里、同一个专家不许并发"，
 * 它可以脱开模型与网页被测（见 AgentsTest）。"去把那句话跑出来"是壳的事 ——
 * 网页壳、CLI 壳各有各的会话表，状态机不该知道这些。这与 `Gate`、`Scheduler.fire`
 * 是同一个套路：**调度与执行分开，两边才都能单独测**。
 */

/**
 * 接上执行口。启动时调一次。
 *
 * 三件事必须一起做：读回上次的实例状态、注入 runner/followup、起收件箱线程。
 * 少起线程的后果最隐蔽 —— 后台投递会永远停在 queued，界面上一句"已投进收件箱"
 * 就成了假话，而这条链路上没有任何一处会报错。
 */
internal fun WebServer.wireAgents() {
    Agents.restore()
    Agents.runner = { presetId, text ->
        val p = Presets.find(presetId)
        if (p == null) "" to "没有这张专家卡：$presetId"
        else askPeer(p, text)
    }
    // 回信送回**发信人那条会话**：只把答案留在收件箱里没人读，等于没回
    Agents.followup = { sid, text ->
        if (sid.isNotBlank() && sid != "user") {
            val (_, err) = startRun(sid, text, trigger = "专家回信")
            if (err != null) Env.log("agents", "回信送不进去：$err")
        }
    }
    Agents.notify = { publish("agents", Agents.json()) }
    Agents.ensureWorker()
}

/**
 * 在**对方自己那条常驻会话**里跑一句话。
 *
 * 复用而不是每次新建，是"同事"与"子任务"的实质差别：新建一条会话等于对方什么都不记得，
 * 那 ask_agent 就退化成另一个 task 工具。会话已经不在了（被删了/重启没恢复）才新建一条，
 * 并把新 id 记回实例上。
 */
private fun WebServer.askPeer(p: Preset, text: String): Pair<String, String?> {
    val known = Agents.state(p.id)?.sid.orEmpty()
    val sid = known.takeIf { it.isNotBlank() && sessions.containsKey(it) }
        ?: newSessionId(Presets.workspaceOf(p), p).also { Agents.attach(p.id, it) }
    val m = sessions[sid] ?: return "" to "对方的会话没起来（$sid）"
    if (m.running) return "" to "「${p.name}」正在跑另一件事，等它完事再问，或者改 background"
    publish("title", """{"title":${quote("问 " + p.name)}}""", sid)
    return try {
        m.engine.submit(text) to null
    } catch (e: Exception) {
        "" to (e.message ?: e.javaClass.simpleName)
    }
}

/**
 * `GET /api/agents` —— 实例与收件箱；
 * `POST {op:'start'|'stop'|'ask', preset?, text?, mode?}`。
 *
 * `ask` 是给界面上"直接问这个专家一句"用的（同步那条路会占住这个 HTTP 请求，
 * 所以界面默认走 background —— 让人等一个不知道几分钟的回答是错的设计）。
 */
internal fun WebServer.agents(ex: HttpExchange) {
    val b = Body(ex)
    if (ex.requestMethod != "GET") {
        val preset = b.str("preset").trim()
        when (b.str("op")) {
            "start" -> {
                val (_, err) = Agents.start(preset)
                if (err != null) {
                    send(ex, 200, """{"ok":false,"error":${quote(err)}}""",
                        "application/json; charset=utf-8"); return
                }
            }
            "stop" -> if (!Agents.stop(preset)) {
                send(ex, 200, """{"ok":false,"error":${quote("这个实例还没起过，没什么可停")}}""",
                    "application/json; charset=utf-8"); return
            }
            "ask" -> {
                val (out, err) = Agents.ask(preset, "user", b.str("text"), b.str("mode").ifBlank { "background" })
                if (err != null) {
                    send(ex, 200, """{"ok":false,"error":${quote(err)}}""",
                        "application/json; charset=utf-8"); return
                }
                send(ex, 200, """{"ok":true,"reply":${quote(out)}}""",
                    "application/json; charset=utf-8")
                return
            }
        }
        publish("agents", Agents.json())
    }
    send(ex, 200, Agents.json(), "application/json; charset=utf-8")
}
