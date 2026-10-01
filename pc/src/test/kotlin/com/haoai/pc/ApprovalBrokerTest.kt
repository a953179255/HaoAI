package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 审批等待状态机的判据（S8）。
 *
 * 最要紧的一条是**超时**：旧实现 `fut.get(300, SECONDS)` 摆在那儿，这条路径
 * 从来没被测过 —— 没人会为一条判据等五分钟。现在超时是构造参数，1 秒就能把
 * "没人应答 → 按拒绝处理 + 通知发过 + 收摊干净"真跑一遍。
 *
 * 其余主张：答复从别的线程进来时值原样返回；停止中止时审批=deny、提问=""（fail-closed
 * 与"用户没回答"两套语义）；complete 的回执里 isAsk/sid 在 complete **之前**取好。
 */
class ApprovalBrokerTest {

    private val sent = CopyOnWriteArrayList<Triple<String, String, String>>()

    private fun broker(approvalSec: Int = 30, askSec: Int = 30) =
        ApprovalBroker({ e, p, s -> sent += Triple(e, p, s) }, approvalTimeoutSec = approvalSec, askTimeoutSec = askSec)

    /** 等第一条 approval 卡推出去，取它的 id（载荷由测试的 makePayload 现造，含 id）。 */
    private fun waitCard(b: ApprovalBroker, event: String = "approval", ms: Long = 3000): String {
        val re = Regex("\"id\":\"(\\w+)\"")
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            sent.firstOrNull { it.first == event }?.let { m ->
                return re.find(m.second)?.groupValues?.get(1) ?: error("载荷里没有 id：" + m.second)
            }
            Thread.sleep(20)
        }
        error("${event} 卡没推出去：" + sent)
    }

    @Test
    fun `an answer from another thread lands value untouched and cleans up`() {
        val b = broker(approvalSec = 10)
        val t = thread {
            val id = waitCard(b)
            val c = b.complete(id, "allow_once")
            assertTrue("这条等待该在", c.existed)
            assertFalse("还没人答过", c.already)
            assertFalse("这是审批不是提问", c.isAsk)
            assertEquals("sid 要原样带回", "s1", c.sid)
        }
        val r = b.awaitApproval("s1") { id -> """{"id":"$id"}""" }
        t.join(3000)
        assertEquals("allow_once", r.value)
        assertFalse("不是超时", r.timedOut)
        assertFalse("不是中止", r.aborted)
        assertEquals("收摊要干净", 0, b.sizeForTest())
    }

    @Test
    fun `nobody answers within the timeout - deny, notice, clean`() {
        val b = broker(approvalSec = 1)
        val r = b.awaitApproval("s1") { """{"id":"x"}""" }
        assertTrue("1 秒没人答 = 超时", r.timedOut)
        assertEquals("fail-closed：按拒绝处理", "deny", r.value)
        assertTrue(
            "要发那句通知（带真实秒数）：" + sent,
            sent.any { it.first == "notice" && it.second.contains("1 秒无人应答") }
        )
        assertEquals("超时也要收摊", 0, b.sizeForTest())
    }

    @Test
    fun `ask timeout returns empty and stays quiet`() {
        val b = broker(askSec = 1)
        val r = b.awaitAsk("s1", AskReq("红的还是绿的？", listOf(AskOpt("红", "暖色"), AskOpt("绿", "冷色"))))
        assertTrue(r.timedOut)
        assertEquals("提问超时 = 没人回答", "", r.value)
        // 与旧实现一致：提问超时**不发通知**（那条通知是审批专属的）
        assertFalse("提问超时该安静", sent.any { it.first == "notice" })
        // 载荷是完整 AskReq（B22）：对象选项 + 三个开关，桌面卡与手机卡同读这一份
        val askPayload = sent.firstOrNull { it.first == "ask" }?.second ?: ""
        assertTrue("载荷里要有对象选项：" + askPayload, askPayload.contains("\"label\":\"红\"") && askPayload.contains("\"description\":\"暖色\""))
        assertTrue("三个开关要在载荷里（缺省 true）", askPayload.contains("\"confirm\":true") && askPayload.contains("\"recommend\":true") && askPayload.contains("\"allowFreeText\":true"))
        assertEquals(0, b.sizeForTest())
    }

    @Test
    fun `abort settles approvals as deny and asks as empty`() {
        // 时序：先让 await 把卡推出去（waiter 已注册），再 abort —— 否则 abort 可能
        // 跑在注册前头，账就是 0，断言随机红。旧实现没这条判据是因为 300 秒等不起。
        val b = broker(approvalSec = 30)
        var res: ApprovalBroker.Resolution? = null
        val t = thread { res = b.awaitApproval("s1") { id -> """{"id":"$id"}""" } }
        waitCard(b)
        val sum = b.abort("s1")
        t.join(3000)
        assertEquals("拒掉的是审批", 1, sum.approvals)
        assertEquals(1, sum.total)
        assertEquals("中止 = deny（fail-closed）", "deny", res!!.value)
        assertFalse("complete 走的是答复路，不是超时", res!!.timedOut || res!!.aborted)
        assertEquals(0, b.sizeForTest())

        // 提问的中止语义：空串（模型看到"用户没回答"）
        val b2 = broker(askSec = 30)
        var res2: ApprovalBroker.Resolution? = null
        val t2 = thread { res2 = b2.awaitAsk("s2", AskReq("选哪个？", emptyList())) }
        waitCard(b2, event = "ask")
        val sum2 = b2.abort("s2")
        t2.join(3000)
        assertEquals("中止的是提问", 1, sum2.asks)
        assertEquals("", res2!!.value)
        assertEquals(0, b2.sizeForTest())
    }

    @Test
    fun `complete on a missing id reports stale instead of throwing`() {
        val b = broker()
        val c = b.complete("a404", "deny")
        assertFalse(c.existed)
        assertFalse(c.already)
    }

    @Test
    fun `double answer marks the second one already`() {
        val b = broker(approvalSec = 10)
        val t = thread {
            val id = waitCard(b)
            assertTrue(b.complete(id, "deny").existed)
            val second = b.complete(id, "deny")
            assertTrue("第二次要报已答过", second.already)
        }
        b.awaitApproval("s1") { id -> """{"id":"$id"}""" }
        t.join(3000)
        assertEquals(0, b.sizeForTest())
    }

    @Test
    fun `rows and kind feed the hydration projections`() {
        val b = broker(approvalSec = 10)
        val t = thread {
            val id = waitCard(b)
            assertEquals("approval", b.kind(id))
            assertEquals("只投影这条会话的", 1, b.pendingFor("s1").size)
            assertEquals("别的会话看不到", 0, b.pendingFor("other").size)
            assertEquals("rows 里要有这条", 1, b.rows().size)
            b.complete(id, "deny")
        }
        b.awaitApproval("s1") { id -> """{"id":"$id"}""" }
        t.join(3000)
        assertEquals("答完全部销号", 0, b.rows().size)
    }
}
