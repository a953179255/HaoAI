package com.haoai.pc

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 模型侧的定时任务三件套（B3）：把"帮我每天八点…"从"用户去界面填表"变成
 * 一句话落地。排期解析与界面**共用 [SchedulePlan]** —— 两处各算一次"下一次"
 * 迟早算出两个答案（日程页那条批注的同款病根）。
 *
 * 刻意没有 `cron_run`：立刻跑需要 WebServer 那侧的 runSchedule（起会话、发事件），
 * 工具层拿不到 emit 通路；界面「定时」页签的"立即跑"按钮覆盖这个需求，
 * 硬塞一条跨层钩子不值当。
 */
private fun fmtNext(ms: Long): String =
    if (ms <= 0) "—"
    else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

private fun describe(s: Schedule): String =
    SchedulePlan.describe(s.kind, s.every, s.at, s.days, s.runAt)

class CronListTool : Tool(
    "cron_list",
    "列出定时任务：id、名字、人话排期、下次运行、启用状态。要停用/改动某条，先用它拿 id。",
    schema(),
    kind = "read"
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val list = Schedules.load()
        if (list.isEmpty()) return ToolResult("(还没有定时任务。排期用 cron_create)")
        val now = System.currentTimeMillis()
        return ToolResult(list.joinToString("\n") { s ->
            val next = if (s.enabled) Schedule.nextDue(s, now) else 0L
            "${s.id} · ${s.name} · ${describe(s)} · ${if (s.enabled) "启用" else "停用"}" +
                if (s.enabled) " · 下次 ${fmtNext(next)}" else ""
        })
    }
}

class CronCreateTool : Tool(
    "cron_create",
    "新建定时任务。phrase=什么时候（一句话排期，如「每天早上八点半」「每周一三五 17:00」「每隔 30 分钟」）；" +
        "prompt=到点跑什么（会起一条新会话去跑，不搅乱当前对话）；name=任务名（可选）。" +
        "排期看不懂会被拒绝并给例子——照它说的改。改已有任务：cron_list 拿 id，删了重建（或去界面「定时」页签）。",
    schema(
        "phrase" to "string", "prompt" to "string", "name" to "string",
        required = arrayOf("phrase", "prompt")
    ),
    kind = "write"
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val phrase = req(args, "phrase")?.trim() ?: return fail("cron_create 缺 phrase")
        val prompt = req(args, "prompt")?.trim() ?: return fail("cron_create 缺 prompt")
        if (prompt.isEmpty()) return fail("prompt 是空的——到点要跑的那句话还没写")
        val (at, err) = SchedulePlan.parse(phrase, System.currentTimeMillis())
        if (at == null) return fail("排期没看懂：$err")
        val name = (req(args, "name") ?: "").trim().ifBlank { prompt.take(18) }
        val id = "sc" + System.nanoTime().toString(16).take(8)
        val s = Schedule(
            id, name, prompt, kind = at.kind, every = at.every,
            at = at.at, days = at.days, runAt = at.runAt
        )
        Schedules.update(s)
        val next = Schedule.nextDue(s, System.currentTimeMillis())
        return ToolResult(
            "已建定时任务 $id「$name」：${at.echo}，下次 ${fmtNext(next)}。\n" +
                "到点会起一条新会话跑那句话；停用 cron_toggle，删了重建可改内容。"
        )
    }
}

class CronToggleTool : Tool(
    "cron_toggle",
    "启用/停用一条定时任务（id 见 cron_list）。",
    schema("id" to "string", "enabled" to "boolean", required = arrayOf("id", "enabled")),
    kind = "write"
) {
    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val id = req(args, "id")?.trim() ?: return fail("cron_toggle 缺 id")
        val enabled = req(args, "enabled")?.trim() != "false"
        val s = Schedules.load().firstOrNull { it.id == id }
            ?: return fail("没有这个任务 $id，cron_list 看看现在有哪些")
        s.enabled = enabled
        Schedules.update(s)
        return ToolResult(
            if (enabled) "已启用「${s.name}」，下次 ${fmtNext(Schedule.nextDue(s, System.currentTimeMillis()))}"
            else "已停用「${s.name}」"
        )
    }
}
