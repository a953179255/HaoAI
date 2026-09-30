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
     * `GET /api/gitstatus?sid=` —— Git 面板要的那份状态。
     *
     * 不在仓库里也回 200 + `isRepo:false`：面板要能说一句"这里不是仓库"，
     * 而不是摆一个空列表让人以为"没有改动"（这两件事差很远）。
     */
    internal fun WebServer.gitStatus(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val ws = (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile())
        val repo = GitPanel.repoFor(ws)
        if (repo == null) {
            send(ex, 200, """{"ok":true,"isRepo":false,"clean":true,"count":0,"stagedCount":0,""" +
                """"entries":[],"note":"这里不是 git 仓库（工作区往上也没有 .git）"}""",
                "application/json; charset=utf-8")
            return
        }
        send(ex, 200, GitPanel.statusJson(repo), "application/json; charset=utf-8")
    }


    /** `GET /api/gitdiff?sid=&path=&staged=` —— 单个文件的差异，只读。 */
    internal fun WebServer.gitDiff(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val repo = GitPanel.repoFor(
            (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile())
        ) ?: return send(ex, 200, """{"ok":false,"error":"这里不是 git 仓库"}""",
            "application/json; charset=utf-8")
        send(ex, 200, GitPanel.diffJson(repo, queryOf(ex, "path"), queryOf(ex, "staged") == "1"),
            "application/json; charset=utf-8")
    }


    /**
     * `POST /api/gitstage` {sid, paths[], unstage} —— 勾选暂存 / 取消暂存。
     *
     * 这四条面板动作**不过模型的权限闸**：点按钮的人就是用户本人，
     * 再弹一次"要不要提交你刚按下的提交"是把审批做成噪声。
     * 边界改由 GitPanel 守住：只认这条会话工作区里的仓库、路径过 safePath、没有 push 这类动词。
     */
    internal fun WebServer.gitStage(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val repo = GitPanel.repoFor(
            (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile())
        ) ?: return send(ex, 200, """{"ok":false,"error":"这里不是 git 仓库"}""",
            "application/json; charset=utf-8")
        send(ex, 200, GitPanel.stageJson(repo, b.list("paths"), b.str("unstage") == "1"),
            "application/json; charset=utf-8")
    }


    /** `POST /api/gitcommit` {sid, message, paths[]} —— 提交（勾选非空则先暂存）。 */
    internal fun WebServer.gitCommit(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val repo = GitPanel.repoFor(
            (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile())
        ) ?: return send(ex, 200, """{"ok":false,"error":"这里不是 git 仓库"}""",
            "application/json; charset=utf-8")
        send(ex, 200, GitPanel.commitJson(repo, b.str("message"), b.list("paths")),
            "application/json; charset=utf-8")
    }

