package com.haoai.agent.data

import com.haoai.agent.agent.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * 会话落库单测：验证「待写合并」既真的减少了写盘次数，又不丢最后一次状态、
 * 也不会让彻底删除的会话被后台迟到写盘复活。文件 IO 走 @TempDir 真目录。
 */
class SessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    /** 每个用例一个独立目录：SessionStore 的后台线程会往自己的目录里写。 */
    private fun newStore(): SessionStore {
        dir = tmp.newFolder("s-" + UUID.randomUUID())
        return SessionStore(dir)
    }

    private fun session(id: String, msgs: Int) = StoredSession(
        id = id, title = "测试", createdAt = 1L, updatedAt = 1L,
        messages = (1..msgs).map {
            StoredMessage(role = ChatMessage.ROLE_USER, content = "m$it-" + "x".repeat(200))
        }.toMutableList()
    )

    /** 直接读磁盘：load() 优先返回内存态，验证落库必须绕开它。 */
    private fun disk(id: String): StoredSession? = runCatching {
        // writeAtomic 用 rename 轮转，旧文件会在"存在"判定之后被挪走，读失败按未落库处理
        File(dir, "$id.json").takeIf { it.exists() }?.readText()?.let {
            HaoJson.json.decodeFromString(StoredSession.serializer(), it)
        }
    }.getOrNull()

    private fun awaitDisk(id: String, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (disk(id)?.messages?.size == count) return
            Thread.sleep(10)
        }
        assertTrue("等待落库超时（期望 $count 条）", false)
    }

    @Test
    fun `同一会话连续保存只落几次盘`() {
        val store = newStore()
        val id = "s1"
        // 首份快照很大：后台还在序列化+写盘的那几十毫秒里，后面的 save 全并进 pending 互相覆盖
        store.save(session(id, 20000), touch = false)
        val saves = 60
        // 后续 save 故意造得极便宜：调用方越快，越能体现"合并"而不是"两边一样快"
        for (i in 1..saves) store.save(session(id, 4).also { it.messages[0] = it.messages[0].copy(content = "i$i") }, touch = false)
        awaitDisk(id, 4)
        // 计数放在等待之后：此时最后一次写盘已落地，读到的才是完整次数
        val n = store.writes.get()
        assertEquals("落库内容必须是最后一次保存的状态", 4, disk(id)?.messages?.size)
        assertTrue("save ${saves + 1} 次只应写盘个位数次，实际 $n", n <= 8)
    }

    @Test
    fun `合并后落的是最后一次状态`() {
        val store = newStore()
        val id = "s2"
        store.save(session(id, 20000), touch = false)
        for (n in 1..30) store.save(session(id, n), touch = false)
        awaitDisk(id, 30)
        assertEquals(30, store.load(id)?.messages?.size)
    }

    @Test
    fun `多个会话的待写各自都会落盘`() {
        val store = newStore()
        for (i in 1..8) store.save(session("multi-$i", 20), touch = false)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && (1..8).any { disk("multi-$it") == null })
            Thread.sleep(10)
        for (i in 1..8) assertEquals(20, disk("multi-$i")?.messages?.size)
    }

    @Test
    fun `彻底删除后迟到写盘不会让会话复活`() {
        val store = newStore()
        val id = "gone"
        repeat(20) { store.save(session(id, 3000), touch = false) }
        store.deleteForever(id)
        Thread.sleep(500)
        assertTrue("主文件不应存在", !File(dir, "$id.json").exists())
        assertTrue("不应留下 .bak", !File(dir, "$id.json.bak").exists())
        assertEquals(null, store.load(id))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `回收站与列表以内存态为准`() {
        val store = newStore()
        store.save(session("a", 3), touch = false)
        store.delete("a")
        assertTrue(store.list().isEmpty())
        assertEquals(1, store.listDeleted().size)
        store.restore("a")
        assertEquals(1, store.list().size)
    }

    /**
     * 冷启动清扫：引擎只活在本进程，上个进程留下的 running 必然是被杀留下的。
     * 不转成 interrupted 的话 resumable() 不认它 —— 会话既没有恢复横幅也没有停止键，
     * 永久卡在那里（真机 force-stop 后实测到的正是这个状态）。
     */
    @Test
    fun `遗留的running在冷启动时判为中断`() {
        val store = newStore()
        val running = session("r1", 2).also { it.runState = StoredSession.RUN_RUNNING }
        val idle = session("r2", 2).also { it.runState = StoredSession.RUN_IDLE }
        listOf(running, idle).forEach { store.save(it, touch = false) }
        awaitDisk("r1", 2); awaitDisk("r2", 2)

        val store2 = SessionStore(dir)  // 新实例 = 模拟进程重启
        assertEquals(1, store2.markStaleRunsInterrupted())
        assertEquals(StoredSession.RUN_INTERRUPTED, store2.load("r1")?.runState)
        assertEquals(StoredSession.RUN_IDLE, store2.load("r2")?.runState)
        assertTrue(StoredSession.resumable(store2.load("r1")?.runState))
        assertEquals(0, store2.markStaleRunsInterrupted())  // 幂等：扫第二次没有遗留
    }
}
