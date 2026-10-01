package com.haoai.pc

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 审批 / 提问的**等待状态机**（S8）。
 *
 * 对标 OpenClaw `exec-approval-manager`：request / wait / 超时 / 中止归一个主人，
 * 工具那边只问一次（[Gate]）。以前这摊状态（futures、载荷、id 序列）散在 `WebServer`
 * 的四个字段里，`try/finally` 手写两遍，"超时"这条路径**没有任何测试** —— 300 秒
 * 没人会去等。现在：
 *
 * - 状态只有一条线：`PENDING → ANSWERED / TIMED_OUT / ABORTED`，出路唯一且在
 *   [finally] 里收摊（maps 不会漏删 —— 漏删的表现是"这条审批永远在等人"）；
 * - **超时可注入**：`approvalTimeoutSec / askTimeoutSec` 是构造参数，
 *   测试用 1 秒就把超时路径真跑一遍（判据：超时=按拒绝处理 + 通知发过 + 收摊干净）；
 * - fail-closed 写死在返回值里：超时给 `deny`（审批）/ `""`（提问，模型看到"没人回答"），
 *   与从前逐字节一致；**失败（异常）也只记不闹** —— 等待被打断时按拒绝走，不抛给引擎。
 *
 * **阻塞仍然发生在调用方线程**（引擎是同步语义："一次只跑一个回合"）——
 * 这个状态机描述的是"等待"这段的内部结构，不是把审批闸改异步。
 *
 * 每个 `WebServer` 一个实例：测试同 JVM 里会起好几台服务，状态绝不许是全局的。
 */
class ApprovalBroker(
    /**
     * 发 SSE 的口子由主人注入（事件名、负载、会话）。
     * **名字刻意叫 `publish`、事件名写成字面量**：`UiContractTest` 与 `ui-check`
     * 靠"扫全源码 `publish(\"...\"`"对账事件名单，包一层 `out(...)` 就会把
     * approval/ask 从名单里弄丢（S8 第一版就是这么红的）。
     */
    private val publish: (event: String, payload: String, sid: String) -> Unit,
    private val approvalTimeoutSec: Int = 300,
    private val askTimeoutSec: Int = 900,
) {

    /** 一条挂着的等待。payload 是给界面/手机的完整载荷（含 id）。 */
    internal data class Waiter(val ev: String, val payload: String, val sid: String)

    /** 等待的结局。[value] 已经是 fail-closed 之后的答案，调用方不需要再判超时。 */
    data class Resolution(val value: String, val timedOut: Boolean, val aborted: Boolean)

    /** 一次 [complete] 的回执：这三个值都在 **complete 之前**取好 ——
     *  complete 之后引擎会立刻销号（收摊在 finally），事后补读就读不到了
     *  （旧代码在两个地方各写了一遍这个时序注释，现在收进一处）。 */
    data class Completed(val existed: Boolean, val already: Boolean, val isAsk: Boolean, val sid: String)

    /** [abort] 的账：拒了几个审批、几个提问（给"顺手拒掉 N 个"那句通知用）。 */
    data class AbortSummary(val approvals: Int, val asks: Int) {
        val total: Int get() = approvals + asks
    }

    private val seq = AtomicInteger()
    private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val waiters = ConcurrentHashMap<String, Waiter>()

    /** 当前还挂着的（id → 等待），给 stateJson / 手机 pendingJson 投影用。 */
    internal fun rows(): List<Pair<String, Waiter>> = waiters.map { it.key to it.value }

    internal fun pendingFor(sid: String): List<Waiter> = waiters.values.filter { it.sid == sid }

    fun kind(id: String): String = waiters[id]?.ev ?: ""

    /**
     * 审批：注册 → 推卡 → 等人答。[makePayload] 拿到 id 之后现造载荷
     * （载荷里要写 id，所以 id 必须先出生）。超时发"没人应答"的通知并按拒绝处理。
     *
     * （旧实现还存了一份 `pendingRule: tool→pattern`，查证**只写不读** ——
     * `allow_rule` 用的是闭包里的 tool/pattern，手机端答复也走同一条 await 回来的
     * `ans`。死状态，随这次收编一并删掉。）
     */
    fun awaitApproval(
        sid: String,
        makePayload: (id: String) -> String
    ): Resolution {
        val id = "a${seq.incrementAndGet()}"
        val fut = CompletableFuture<String>()
        pending[id] = fut
        val payload = makePayload(id)
        waiters[id] = Waiter("approval", payload, sid)
        publish("approval", payload, sid)
        return try {
            Resolution(fut.get(approvalTimeoutSec.toLong(), TimeUnit.SECONDS), timedOut = false, aborted = false)
        } catch (e: TimeoutException) {
            publish("notice", js("${approvalTimeoutSec} 秒无人应答，按拒绝处理"), sid)
            Resolution("deny", timedOut = true, aborted = false)
        } catch (e: Exception) {
            Resolution("deny", timedOut = false, aborted = true)
        } finally {
            pending.remove(id)
            waiters.remove(id)
        }
    }

    /** 提问：同上，但超时/失败都回 `""`（模型看到"用户没回答"），不发通知 —— 与旧实现一致。
     *  载荷带完整 AskReq（对象选项 + 三个开关）：桌面卡与 LAN 手机卡读同一份。 */
    fun awaitAsk(sid: String, req: AskReq): Resolution {
        val id = "q${seq.incrementAndGet()}"
        val fut = CompletableFuture<String>()
        pending[id] = fut
        val payload = buildJsonObject {
            put("id", id)
            put("question", req.question)
            put("options", buildJsonArray {
                req.options.forEach { o ->
                    add(buildJsonObject {
                        put("label", o.label)
                        put("description", o.desc)
                    })
                }
            })
            put("allowFreeText", req.allowFree)
            put("confirm", req.confirm)
            put("recommend", req.recommend)
        }.toString()
        waiters[id] = Waiter("ask", payload, sid)
        publish("ask", payload, sid)
        return try {
            Resolution(fut.get(askTimeoutSec.toLong(), TimeUnit.SECONDS), timedOut = false, aborted = false)
        } catch (e: TimeoutException) {
            Resolution("", timedOut = true, aborted = false)
        } catch (e: Exception) {
            Resolution("", timedOut = false, aborted = true)
        } finally {
            pending.remove(id)
            waiters.remove(id)
        }
    }

    /**
     * 答复（网页与手机同一个口）。返回值里带 complete **之前**取到的 isAsk/sid ——
     * 调用方不需要再抢时序。`existed=false` = 这条已经不在了（刚被处理/超时）。
     */
    fun complete(id: String, value: String): Completed {
        val fut = pending[id] ?: return Completed(existed = false, already = false, isAsk = false, sid = "")
        val w = waiters[id]
        val ok = fut.complete(value)
        return Completed(existed = true, already = !ok, isAsk = w?.ev == "ask", sid = w?.sid ?: "")
    }

    /**
     * 停止时把某条会话挂着的等待一次性判掉：审批给 `deny`（fail-closed），
     * 提问给 `""`（模型看到"用户没回答"）。收摊由各自的 finally 完成。
     */
    fun abort(sid: String): AbortSummary {
        var approvals = 0
        var asks = 0
        waiters.entries.filter { it.value.sid == sid }.forEach { (id, w) ->
            if (w.ev == "ask") { pending[id]?.complete(""); asks++ }
            else { pending[id]?.complete("deny"); approvals++ }
        }
        return AbortSummary(approvals, asks)
    }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

    /** 给将来排障留的：谁在等（测里也用它断言收摊干净）。 */
    internal fun sizeForTest(): Int = waiters.size
}
