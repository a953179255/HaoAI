package com.haoai.agent.ui

import com.haoai.agent.data.HaoJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * P4 子代理过程旁路存储（2026-10-21 用户实测需求：回合结束后点开历史链卡里的
 * 「并行子代理 N 路」要能回看每路的过程——原先 subagents 只活在 liveTools
 * 内存态里，startRun 一清历史回合就剩个空壳）。
 *
 * 为什么是旁路文件而不是塞进会话 JSON：[com.haoai.agent.data.StoredToolCall]
 * 是磁盘格式红线（SessionStore B15：字段名即格式，538 条持久化锁），且一次
 * spawn_agents 的全过程（每路 task/steps/结论）有几 KB~几十 KB，进主会话
 * JSON 会让每次 save 全量重写的代价陡增。旁路文件模式项目里已有先例
 * （todos、job log）。
 *
 * 落点：files/subagent_logs/<sid>/<callId>.json，内容 = List<SubagentLine>。
 * 读写复用 [HaoJson] 的原子写（tmp+rename+.bak 损坏回退）。
 */
class SubagentLogStore(private val dir: File) {

    private val serializer = ListSerializer(SubagentLine.serializer())

    init {
        runCatching { dir.mkdirs() }
    }

    private fun fileOf(sid: String, callId: String): File {
        val d = File(dir, sid).apply { runCatching { mkdirs() } }
        // callId 是引擎生成的 UUID（无路径分隔符）；防御性过滤防越界写
        val safe = callId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(d, "$safe.json")
    }

    /** 覆盖写该工具调用的子代理过程快照（同 callId 后写覆盖前写）。空列表跳过。 */
    fun save(sid: String, callId: String, lines: List<SubagentLine>) {
        if (lines.isEmpty()) return
        val target = fileOf(sid, callId)
        runCatching {
            HaoJson.writeAtomic(target, Json.encodeToString(serializer, lines))
        }
    }

    /** 读回；文件不存在/解析失败均返回 null（调用方按空过程处理）。 */
    fun load(sid: String, callId: String): List<SubagentLine>? {
        val f = fileOf(sid, callId)
        if (!f.exists()) return null
        return runCatching { HaoJson.readJsonSafe(f, serializer) }.getOrNull()
    }

    /** 该会话是否已有落盘的子代理过程（rebuildRows 缓存预热判定用）。 */
    fun has(sid: String, callId: String): Boolean = fileOf(sid, callId).exists()

    /**
     * 会话彻底删除时连过程一起清（deleteSessionForever 调用）。
     * 回收站软删（deleteSession）不调——7 天内恢复后过程还要能回看。
     */
    fun clearSession(sid: String) {
        runCatching { File(dir, sid).deleteRecursively() }
    }
}
