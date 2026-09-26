package com.haoai.agent.agent.engine

import com.haoai.agent.agent.tools.TextCap
import com.haoai.agent.platform.FileBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * S4 工具结果溢出：超过落库上限时，全文写进工作区，会话里只留"头尾摘要 + 路径"。
 *
 * 现状与动机：引擎在落库前用 `TextCap.middle(content, STORED_CAP=16000)` 截断，
 * 也就是说**第 1.6 万零 1 个字符之后永久消失** —— 模型后来想查"那次构建到底哪几行报错"
 * 只能重跑一遍工具（重跑可能还有副作用）。上游四家都是同一套做法：
 * ZCode `contracts/src/tools/contract.ts:117-135`（每工具 maxInline/maxModel + 头尾预览）、
 * deepseek-harness 的 `packages/spill` 系列、claude-code（输出流式落文件 + 内联约 30k 后给"路径 + 预览"）、
 * codex（`tool_output_token_limit`）。
 *
 * 默认关（见 `HaoFlag.TOOL_RESULT_SPILL`）：它拿"目录里多出文件 + 回查要再花 token"
 * 换"信息不再永久丢失"，这个取舍该用户定，不该我们替他定。
 */

/** 溢出目录（工作区内相对路径）。 */
const val TOOL_OUTPUT_DIR = ".haoai-output"

// 为什么放工作区而不是 app 私有目录：`read` 工具走 FileBackend，只能看见工作区内的相对路径
// （agent/tools/FileTools.kt:32 无 backend 就报"未绑定工作空间"）。落到 filesDir 等于模型
// 再也读不回来，溢出就变成纯浪费。口径与 BashTool 的 .haoai-jobs/ 一致。

/** 目录里最多留多少个溢出文件，超了删最旧的（手机端不能无界堆）。 */
const val SPILL_KEEP_FILES = 40

/** 单条溢出的字符硬顶：比这更大就不写文件只留摘要，免得一条日志吃掉几十 MB。 */
const val SPILL_MAX_CHARS = 2_000_000

/**
 * 头尾摘要 + 回查指引。**纯函数**，不碰 IO，便于单测。
 *
 * 摘要正文直接复用 [TextCap.middle]，不另写一份切分逻辑 —— 这样"关掉开关时落库内容
 * 与改造前逐字相同"是结构性成立，而不是靠两边手抄同一个 65%/25% 系数（那种抄法一改就散）。
 */
fun spillPreview(content: String, cap: Int, relPath: String?): String {
    val body = TextCap.middle(content, cap) // 未超限 → 原样返回
    if (content.length <= cap || relPath == null) return body
    return StringBuilder(body.length + 320).apply {
        append(body)
        append("\n\n［完整输出共 ").append(content.length)
        append(" 字符，已存进工作区文件 `").append(relPath).append("`。")
        append("上面只是头尾摘要 —— 要看中间部分就用 read(path=\"").append(relPath)
        append("\", offset=起始行, limit=行数) 分段读，不要凭摘要猜内容。］")
    }.toString()
}

/**
 * 落库前的统一出口。三条不溢出的退路都返回"和今天一样的纯截断"：
 * 开关关着、没绑工作区、单条超硬顶。写文件失败同样退回纯截断 ——
 * 溢出是增益，不能因为它把工具结果落库这件事搞失败。
 */
internal suspend fun capForStore(
    content: String,
    cap: Int,
    backend: FileBackend?,
    callId: String,
    spillOn: Boolean
): String {
    if (content.length <= cap) return content
    if (!spillOn || backend == null || content.length > SPILL_MAX_CHARS) return spillPreview(content, cap, null)
    // 文件名带毫秒前缀：FileBackend.listDir 只给名字，靠"字典序 == 时间序"才能做自清理
    val rel = "$TOOL_OUTPUT_DIR/${System.currentTimeMillis()}-$callId.txt"
    val written = runCatching {
        withContext(Dispatchers.IO) {
            backend.writeText(rel, content)
            pruneSpillDir(backend)
        }
    }.isSuccess
    return spillPreview(content, cap, if (written) rel else null)
}

/** 只留最近 [SPILL_KEEP_FILES] 个；删除失败不报错（溢出本身已经完成，清理是尽力而为）。 */
private suspend fun pruneSpillDir(backend: FileBackend) {
    val names = backend.listDir(TOOL_OUTPUT_DIR)
        .map { it.trimEnd('/').substringAfterLast('/') }
        .filter { it.endsWith(".txt") }
    if (names.size <= SPILL_KEEP_FILES) return
    names.sorted()
        .take(names.size - SPILL_KEEP_FILES)
        .forEach { name -> runCatching { backend.delete("$TOOL_OUTPUT_DIR/$name") } }
}
