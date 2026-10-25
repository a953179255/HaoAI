package com.haoai.agent.platform

import android.util.Log
import com.haoai.agent.agent.engine.SubagentHandle
import com.haoai.agent.data.AppContainer
import com.haoai.agent.data.StoredSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * P4 后台子代理完成协调器（进程级，AppContainer 成员）。
 *
 * 契约（对齐 ZCode agent-background-subagent-join 的现行方案与 OpenMinis 的 submit funnel）：
 * spawn_agent(background=true) 的子代理在主回合结束后继续运行；终态（DONE/ERROR）时引擎
 * 经 EngineFactory 注入的 onBackgroundSubagentDone 回调到这里，协调器把结果作为一条
 * [task-notification] 消息注入原会话并起一轮"唤醒回合"，让主代理补充回复。
 *
 * 两条投递通路：
 *  - 前台 VM 通路（优先）：有存活的 ChatViewModel 时把通知交给它——忙则进该会话队列
 *    （本轮结束自动接上），闲则直接起跑。全程 UI 可见，且不产生"无头引擎写盘 vs
 *    前台 VM 旧副本"的副本分叉（P2-1 的根因就在副本分叉）。
 *  - 无头通路（兜底）：没有任何存活 VM 时，加载会话最新副本 → EngineFactory 造
 *    无人值守引擎 → runTurn。起跑前后台会话不占用 → AgentRunRegistry 让路等待，
 *    起跑后登记注册表（前台 send 的双跑防护与停止路由都自动生效）。
 *
 * 防护闸：
 *  - 批合并：第一个完成通知到达后再等 [BATCH_WINDOW_MS]，让同时派出的兄弟路赶进
 *    同一批——N 路完成只花一次唤醒回合，不逐路刷屏烧 token。
 *  - 唤醒并发上限：全局 [wakeSemaphore]（2），防止多会话同时唤醒把低端机压垮。
 *  - 让路超时：前台回合长期占用会话时最多等 [YIELD_TIMEOUT_MS]，超时放弃本批
 *    （结果已落 .haoai-jobs 转录，用户可回查），避免通知无限积压。
 *
 * 注意：本类不做"后台子代理运行进度"的 UI 上报——回合结束后原 onEvent 链已死，
 * 运行态透视属于 UI 四件套阶段的独立通路。
 */
class BackgroundSubagentCoordinator(private val container: AppContainer) {

    /** 一路后台子代理的终态快照（通知原料）。 */
    data class Notice(
        val subagentId: String,
        val task: String,
        val ok: Boolean,
        val result: String
    )

    companion object {
        /** 完成批合并窗口：第一个通知到达后再等这么久再投递。 */
        internal const val BATCH_WINDOW_MS = 4_000L

        /** 前台回合占用会话时的最长让路等待；超时放弃本批通知。 */
        internal const val YIELD_TIMEOUT_MS = 10 * 60_000L

        /** 让路轮询间隔。 */
        internal const val YIELD_POLL_MS = 3_000L

        /** 单路结果进通知文本的截断长度（唤醒回合还要叠加系统提示，别把窗口撑爆）。 */
        internal const val RESULT_CAP = 4_000

        /** 全局同时进行的无头唤醒回合上限。 */
        private const val MAX_CONCURRENT_WAKE = 2
    }

    /** per-sid 待投递批（无锁队列；flushLoop 一次性取空）。 */
    private val pending = ConcurrentHashMap<String, ConcurrentLinkedQueue<Notice>>()

    /** 每批第一个通知的到达时间（批窗口起点）。 */
    private val batchStart = ConcurrentHashMap<String, AtomicLong>()

    /** per-sid flush 互斥：同会话同时只有一个 flush/唤醒在跑（含无头回合全程）。 */
    private val flushing = ConcurrentHashMap.newKeySet<String>()

    private val wakeSemaphore = Semaphore(MAX_CONCURRENT_WAKE)

    // ── P4 后台句柄轨迹登记（右上浮标数据源）──

    /** 一路后台子代理的活体轨迹：handle 本身就是引擎在改的同一对象（state/steps/终态实时）。 */
    data class BackgroundTrack(val sessionId: String, val handle: SubagentHandle)

    /** key = 句柄 id。launch 登记，进程内存活（结果可回查），起新回合/删会话时清理。 */
    private val tracks = ConcurrentHashMap<String, BackgroundTrack>()

    /** 版本号：登记/清理时 +1，UI 轮询判定是否有变化（handle 内部变化由 UI 按帧读）。 */
    private val _tracksVersion = MutableStateFlow(0)
    val tracksVersion = _tracksVersion.asStateFlow()

    /** 引擎 launch 钩子（EngineFactory 接线）：同步入表即返。 */
    fun onSubagentLaunched(sessionId: String, handle: SubagentHandle) {
        if (!handle.isBackground) return
        tracks[handle.id] = BackgroundTrack(sessionId, handle)
        _tracksVersion.value += 1
    }

    /** 某会话的后台轨迹（按句柄 id 排序 = 派发顺序）。 */
    fun tracksFor(sessionId: String): List<BackgroundTrack> =
        tracks.values.filter { it.sessionId == sessionId }.sortedBy { it.handle.id }

    /** 起新回合：清掉该会话已终态的轨迹（浮标「已结束」态随新回合消失，ZCode 同款）。 */
    fun clearEnded(sessionId: String) {
        if (tracks.entries.removeIf {
                it.value.sessionId == sessionId &&
                    it.value.handle.state != "RUNNING" && it.value.handle.state != "STOPPING"
            }
        ) _tracksVersion.value += 1
    }

    /** 会话删除：全清。 */
    fun clearSession(sessionId: String) {
        if (tracks.keys.removeIf { tracks[it]?.sessionId == sessionId }) _tracksVersion.value += 1
    }

    /** 引擎回调入口（EngineFactory 对每个引擎接线）。同步入队即返回，不做任何 IO。 */
    fun onSubagentFinished(sessionId: String, handle: SubagentHandle) {
        if (!handle.isBackground) return
        val result = handle.finalResult ?: return
        val ok = handle.state == "DONE"
        pending.getOrPut(sessionId) { ConcurrentLinkedQueue() }
            .add(Notice(handle.id, handle.task, ok, result))
        batchStart.putIfAbsent(sessionId, AtomicLong(System.currentTimeMillis()))
        scheduleFlush(sessionId)
    }

    /** per-sid 单飞：已在投递中则直接返回——flushLoop 尾部会复查 pending，不会丢通知。 */
    private fun scheduleFlush(sessionId: String) {
        if (!flushing.add(sessionId)) return
        container.applicationScope.launch {
            try {
                flushLoop(sessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 进程退出/作用域取消：批通知留在 pending（同进程内下次完成会再触发）
            } catch (e: Exception) {
                Log.w("HaoWake", "后台子代理唤醒失败: ${e.message}")
            } finally {
                flushing.remove(sessionId)
            }
        }
    }

    private suspend fun flushLoop(sessionId: String) {
        while (true) {
            val queue = pending[sessionId]
            if (queue == null || queue.isEmpty()) return
            // ① 批合并窗口（本批起点起算；投递期间新到的通知会开新批）
            val start = batchStart.remove(sessionId)
            if (start != null) {
                val wait = BATCH_WINDOW_MS - (System.currentTimeMillis() - start.get())
                if (wait > 0) delay(wait)
            }
            // ② 取空本批（此刻起新通知属于下一批，由循环尾部复查兜住）
            val batch = mutableListOf<Notice>()
            while (true) {
                val n = queue.poll() ?: break
                batch.add(n)
            }
            if (batch.isEmpty()) continue
            val text = buildWakeNotice(batch)
            // ③ 前台 VM 通路：有存活 VM 就全权交给它（忙→队列自动续跑，闲→直接起跑），
            //    不再让路等待——VM 通路自身就是"占用感知"的（isSessionBusy 判定）。
            val vmOffer = LiveChatVms.firstOffer()
            if (vmOffer != null && vmOffer(sessionId, text)) continue
            // ④ 无头通路：让路前台运行 → 无人值守引擎跑一轮唤醒回合
            if (!yieldToForeground(sessionId)) {
                Log.w("HaoWake", "会话 $sessionId 前台长期占用，放弃 ${batch.size} 条后台子代理通知")
                continue
            }
            runHeadlessWake(sessionId, text)
            // 循环尾部复查：唤醒回合进行期间新完成的兄弟路 → 下一批
        }
    }

    /** 前台回合占用会话时轮询让路；false=超时放弃。 */
    private suspend fun yieldToForeground(sessionId: String): Boolean {
        val deadline = System.currentTimeMillis() + YIELD_TIMEOUT_MS
        while (AgentRunRegistry.isActive(sessionId)) {
            if (System.currentTimeMillis() > deadline) return false
            delay(YIELD_POLL_MS)
        }
        return true
    }

    /** 无头唤醒回合：加载最新会话副本，按无人值守能力上限起一轮。 */
    private suspend fun runHeadlessWake(sessionId: String, noticeText: String) {
        val st = container.settingsFlow.value
        val provider = container.activeChatProvider() ?: container.activeProvider() ?: return
        wakeSemaphore.withPermit {
            // 先登记再装弹：把「load 期间前台 VM 起跑」的窗口期一并堵住；
            // 登记后前台 send 的双跑拒绝与停止路由（stop→owner.invoke）自动生效。
            val wakeJob = currentCoroutineContext().job
            val stopHandle = { wakeJob.cancel() }
            AgentRunRegistry.register(sessionId, stopHandle)
            try {
                val session: StoredSession = container.sessionStore.load(sessionId) ?: return
                // 熔断上限走引擎默认 0（2026-10-25 对齐 hermes，成本熔断设置已移除）。
                val engine = com.haoai.agent.agent.engine.EngineFactory.newEngine(
                    container = container,
                    session = session,
                    provider = provider,
                    interaction = com.haoai.agent.agent.engine.EngineFactory.unattended(st.permissionMode),
                    workspaceLabel = container.workspace.current?.displayName
                        ?.substringAfterLast('/')?.ifBlank { "根目录" } ?: "未绑定工作空间",
                    autoLearn = false, // 系统通知驱动的回合不自动沉淀记忆（防噪声，同工作流步骤）
                    backgroundScope = container.applicationScope
                )
                engine.runTurn(noticeText, onDelta = {}, onEvent = {})
            } finally {
                AgentRunRegistry.unregister(sessionId, stopHandle)
            }
        }
    }
}

/**
 * 拼唤醒通知文本（纯函数，单测锁定）。ZCode task-notification 风格：
 * 显式前缀 + 逐路任务/状态/结果 + 行为指引（不重复劳动、简短补充）。
 */
internal fun buildWakeNotice(batch: List<BackgroundSubagentCoordinator.Notice>): String {
    val done = batch.count { it.ok }
    return buildString {
        append("[task-notification] 有 ${batch.size} 路后台子代理已结束（完成 $done、失败 ${batch.size - done}），结果如下。")
        appendLine()
        appendLine("请基于这些结果向用户做必要的补充说明（简短即可；不要重复子代理已完成的工作；若结论已无需展开，确认收到并小结即可）。")
        batch.forEachIndexed { i, n ->
            appendLine()
            appendLine("${i + 1}. 子代理 ${n.subagentId}（${if (n.ok) "完成" else "失败"}）")
            appendLine("任务：${n.task.take(200)}")
            appendLine("结果：")
            append(n.result.take(BackgroundSubagentCoordinator.RESULT_CAP))
            appendLine()
        }
    }.trimEnd() + "\n"
}

/**
 * 前台 ChatViewModel 存活登记（弱引用，防泄漏）：协调器投递时优先把通知交给存活的
 * VM（全 UI 可见 + 无副本分叉）。用 (key, offer) 闭包而非直接引 VM 类型，保持
 * platform 层不反向依赖 ui 层。
 */
object LiveChatVms {
    private val vms = java.util.concurrent.CopyOnWriteArrayList<Entry>()

    private class Entry(val key: Any, val offer: (sid: String, text: String) -> Boolean)

    fun register(key: Any, offer: (sid: String, text: String) -> Boolean) {
        unregister(key)
        vms.add(Entry(key, offer))
    }

    fun unregister(key: Any) {
        vms.removeAll { it.key === key }
    }

    /** 任一存活 VM 的通知入口；多个实例并存时取最先注册者（多实例是异常态，不必优选）。 */
    fun firstOffer(): ((sid: String, text: String) -> Boolean)? =
        vms.firstOrNull()?.offer
}
