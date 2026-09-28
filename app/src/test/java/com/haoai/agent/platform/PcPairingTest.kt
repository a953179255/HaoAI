package com.haoai.agent.platform

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对落盘与"电脑上有人在等"的判定 —— 全在 JVM 里跑，不碰设备。
 *
 * 这两件事都值得钉：token 是"能替你点审批"的凭据，而通知是"会反复打扰人"的东西，
 * 两边都不是看一眼界面就能确认对的那种。
 */
class PcPairingTest {

    private fun tmp(name: String = "pc-link.json") =
        Files.createTempDirectory("haoai-pc").resolve(name).toFile()

    @Test
    fun `the token never reaches the disk in plain text`() {
        val f = tmp()
        val store = PcStore(FakePcCipher())
        val out = store.save(f, "192.168.1.5:8720", "tk-very-secret-77", "魅族 20 Pro")
        assertTrue("该存下：${(out as? PcOut.Fail)?.message}", out is PcOut.Ok<String>)
        val raw = f.readText()
        assertFalse("明文 token 出现在盘上了：$raw", raw.contains("tk-very-secret-77"))
        assertTrue("该留一个'这是密文'的标记：$raw", raw.contains("enc:v1:"))
        val back = store.load(f)!!
        assertEquals("tk-very-secret-77", back.token)
        assertEquals("http://192.168.1.5:8720/", back.base)
        assertEquals("魅族 20 Pro", back.device)
    }

    @Test
    fun `a keystore that does not work refuses to store instead of writing plaintext`() {
        val f = tmp()
        val out = PcStore(FakePcCipher(works = false)).save(f, "192.168.1.5", "tk-1", "手机")
        assertTrue("Keystore 坏了该拒绝保存", out is PcOut.Fail)
        val msg = (out as PcOut.Fail).message
        assertTrue("要说清为什么宁可不存：$msg", msg.contains("Keystore") && msg.contains("明文"))
        assertFalse("拒绝之后不该留下半个文件", f.exists())
    }

    @Test
    fun `a token that cannot be decrypted reads as not paired`() {
        // 换机恢复 / 清除 Keystore 之后就是这个现场：文件在、解不开。
        // 这时候"当作没配对"比留着一条点什么都 401 的假配对要诚实。
        val f = tmp()
        f.writeText("""{"base":"http://192.168.1.5:8720/","device":"手机","token":"enc:v1:@@@","pairedAt":1}""")
        assertNull(PcStore(FakePcCipher()).load(f))
    }

    @Test
    fun `a mistyped address is refused before it can be saved`() {
        val store = PcStore(FakePcCipher())
        for (bad in listOf("", "主机名:8720", "http://x:8720/prefix")) {
            val f = tmp()
            val out = store.save(f, bad, "tk-1", "手机")
            assertTrue("[$bad] 该被挡下", out is PcOut.Fail)
            assertTrue("要告诉人要填什么：${(out as PcOut.Fail).message}", out.message.contains("IP"))
            assertFalse("挡下之后不该留下文件", f.exists())
        }
        val noToken = tmp()
        assertTrue(store.save(noToken, "192.168.1.5", "", "手机") is PcOut.Fail)
    }

    @Test
    fun `clear makes the phone forget the pc and masked never shows the whole token`() {
        val f = tmp()
        val store = PcStore(FakePcCipher())
        store.save(f, "192.168.1.5:8720", "tk-abcdef123456", "手机")
        val ep = store.load(f)!!
        val m = store.masked(ep)
        assertFalse("掩码里不能出现整串 token：$m", m.contains("tk-abcdef123456"))
        assertTrue("该让人看出只露了头几个字：$m", m.contains("tk-a") && m.contains("加密"))
        store.clear(f)
        assertNull(store.load(f))
    }

    @Test
    fun `the same pending batch never notifies twice`() {
        val w = PcWatch()
        assertEquals(PcWatchAction.Notify(2, 2), w.onPending(listOf("a", "b")))
        assertEquals("4 秒一次的轮询，重复弹就是震个不停", PcWatchAction.Same, w.onPending(listOf("a", "b")))
        assertEquals(PcWatchAction.Same, w.onPending(listOf("b", "a")))     // 换个顺序也不算新的
        assertEquals(PcWatchAction.Notify(3, 1), w.onPending(listOf("a", "b", "c")))
        assertEquals(PcWatchAction.Same, w.onPending(listOf("a", "b", "c")))
    }

    @Test
    fun `the notification comes back down once nothing waits anymore`() {
        val w = PcWatch()
        assertEquals(PcWatchAction.Notify(1, 1), w.onPending(listOf("a")))
        // 人在电脑上把 a 批了、又冒出别的：这条路径真机上很难复现，这里钉住"换人等"也要响
        assertEquals(PcWatchAction.Notify(1, 1), w.onPending(listOf("b")))
        assertEquals(PcWatchAction.Same, w.onPending(listOf("b")))
        assertEquals(PcWatchAction.Clear, w.onPending(emptyList()))
        assertEquals("已经清空之后再问，不该反复说'收回'", PcWatchAction.Same, w.onPending(emptyList()))
    }

    /** 断线 / 解除配对之后重连：同一批待办要能再提醒一次，不然屏幕上就再也没有它了。 */
    @Test
    fun `after a reset the same items count as new again`() {
        val w = PcWatch()
        assertEquals(PcWatchAction.Notify(1, 1), w.onPending(listOf("a")))
        assertEquals(PcWatchAction.Same, w.onPending(listOf("a")))
        w.reset()
        assertEquals("重连之后同一批该重新提醒", PcWatchAction.Notify(1, 1), w.onPending(listOf("a")))
    }

    @Test
    fun `blank ids do not count as somebody waiting`() {
        val w = PcWatch()
        assertEquals(PcWatchAction.Same, w.onPending(listOf("", "  ")))
        assertEquals(PcWatchAction.Notify(1, 1), w.onPending(listOf("a", "")))
    }
}
