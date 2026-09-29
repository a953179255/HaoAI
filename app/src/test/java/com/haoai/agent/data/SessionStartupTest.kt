package com.haoai.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * 冷启动打开哪条会话。
 *
 * 起因是真机上的一条体验缺陷：在侧栏把某个会话**置顶**之后，关掉应用再打开，
 * 每次都跳进那条置顶会话。根因不在置顶，而在启动时取的是抽屉那份列表的第一项，
 * 而抽屉排序是「置顶优先」——置顶顺手改了这个排序，也就顺手改了启动目标。
 * 置顶的语义只是"让它好找"，不是"每次打开都跳进去"。
 *
 * 这里把判定抽成纯函数 [SessionStartup.pick] 来测：
 * 用例 1、2 是回归闸（按修复前的写法"取列表第一项"必红），
 * 用例 6 断言的是**不依赖入参顺序**，否则改天抽屉换个排法这条缺陷会换个样子回来。
 */
class SessionStartupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun session(
        id: String,
        updatedAt: Long,
        pinned: Boolean = false,
        deletedAt: Long = 0L
    ) = StoredSession(
        id = id,
        title = id,
        createdAt = 1L,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        pinned = pinned
    )

    /** 抽屉那份顺序：置顶优先，其余按 updatedAt 倒序（复刻 ChatViewModel.refreshSessions 的比较器）。 */
    private fun drawerOrder(list: List<StoredSession>) = list.sortedWith(
        compareByDescending<StoredSession> { it.pinned }.thenByDescending { it.updatedAt }
    )

    private val old = session("s-pinned-old", updatedAt = 100L, pinned = true)
    private val recent = session("s-recent", updatedAt = 300L)
    private val middle = session("s-last-opened", updatedAt = 200L)

    @Test
    fun `置顶不该决定打开哪条——回到上次看的那条`() {
        val all = listOf(old, middle, recent)
        // 抽屉第一项是那条被置顶的老会话（这正是修复前会跳进去的那条）
        assertEquals("s-pinned-old", drawerOrder(all).first().id)
        assertEquals("s-last-opened", SessionStartup.pick(drawerOrder(all), lastOpenedId = "s-last-opened"))
    }

    @Test
    fun `没记过上次看谁时取最近更新的，而不是置顶的`() {
        val all = listOf(old, middle, recent)
        assertEquals("s-recent", SessionStartup.pick(drawerOrder(all), lastOpenedId = null))
    }

    @Test
    fun `上次看的那条进了回收站就回落，不把用户丢进一条已删会话`() {
        val trashed = session("s-gone", updatedAt = 900L, deletedAt = 5L)
        val picked = SessionStartup.pick(listOf(trashed, recent, old), lastOpenedId = "s-gone")
        assertEquals("s-recent", picked)
    }

    @Test
    fun `上次看的那条已经被彻底删除（id 查不到）也回落`() {
        val picked = SessionStartup.pick(listOf(recent, old), lastOpenedId = "s-not-in-list-anymore")
        assertEquals("s-recent", picked)
    }

    @Test
    fun `一条会话都没有时返回空让调用方新建`() {
        assertNull(SessionStartup.pick(emptyList(), lastOpenedId = null))
        assertNull(SessionStartup.pick(listOf(session("x", 1L, deletedAt = 9L)), lastOpenedId = "x"))
    }

    @Test
    fun `判定只看字段不看列表顺序`() {
        val all = listOf(old, middle, recent)
        val expect = "s-last-opened"
        // 三种排法（抽屉序 / 更新时间倒序 / 更新时间正序）必须给同一个答案
        val orders = listOf(
            drawerOrder(all),
            all.sortedByDescending { it.updatedAt },
            all.sortedBy { it.updatedAt }
        )
        orders.forEach { assertEquals(expect, SessionStartup.pick(it, lastOpenedId = expect)) }
        // 没有指针时也一样：谁 updatedAt 最大跟它排第几无关
        val byRecent = orders.map { SessionStartup.pick(it, lastOpenedId = null) }
        assertTrue("落点随入参顺序变了：$byRecent", byRecent.distinct().size == 1)
        assertEquals("s-recent", byRecent.first())
    }

    // ── 落盘契约：指针存在 sessions 目录里，绝不能被当成一条会话 ──────────────

    @Test
    fun `记住的会话能读回来，且不会凭空多出一条会话`() {
        val dir = tmp.newFolder("store-" + UUID.randomUUID())
        val store = SessionStore(dir)
        val s = session("s-real", updatedAt = 1L)
        store.save(s)
        assertEquals(1, store.list().size)

        store.rememberOpened("s-real")
        assertEquals("s-real", store.lastOpenedId())
        // 这条断言就是那把闸：指针一旦写成 .json，list() 会多出它（抽屉里凭空一条空白会话）
        assertEquals("last-opened 指针被当成了会话", 1, store.list().size)
        assertTrue("指针文件不是 .json", File(dir, "last-opened.txt").isFile)
        assertNull(File(dir, "last-opened.json").takeIf { it.exists() })
    }

    @Test
    fun `没记过时指针为空`() {
        val dir = tmp.newFolder("store-" + UUID.randomUUID())
        val store = SessionStore(dir)
        assertNull(store.lastOpenedId())
        // 回落路径要能走通（真机第一次打开应用就是这个状态）。
        // 必须 touch=false：save 默认会把 updatedAt 刷成"现在"，两次连存会落在同一毫秒，
        // 那条"取最近更新"的断言就变成看运气（这条测试第一次全量跑就是这么红的）。
        store.save(session("a", 1L), touch = false)
        store.save(session("b", 2L), touch = false)
        assertEquals("b", SessionStartup.pick(store.list(), store.lastOpenedId()))
    }

    @Test
    fun `记住的是最后一次动作，切来切去以最后那次为准`() {
        val dir = tmp.newFolder("store-" + UUID.randomUUID())
        val store = SessionStore(dir)
        listOf(old, middle, recent).forEach { store.save(it) }
        store.rememberOpened(recent.id)
        store.rememberOpened(middle.id)
        store.rememberOpened(old.id)
        assertEquals("s-pinned-old", SessionStartup.pick(store.list(), store.lastOpenedId()))
    }
}
