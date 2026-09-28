package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * 落盘的原子性。
 *
 * 起因是一次偶发的红：`/api/sessions` 里"凭空少了一条刚建的会话"。
 * 真相是会话文件每回合都在原地重写，而侧栏一直在读它 —— 读到半截 JSON 之后
 * 被读侧的 `runCatching` 咽掉，于是"读不到"看起来像"没有"。
 * 修的时候第一版只兜了 `AtomicMoveNotSupportedException`，
 * 而 Windows 上目标被读句柄握着时连普通 move 都会 ACCESS_DENIED ——
 * 结果整部落库失败，比原来更糟。这两条测试就是把那两个坑各钉一次。
 */
class AtomicWriteTest {

    companion object {
        private lateinit var home: File

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            home = Files.createTempDirectory("haoai-atomic-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            File(home, "home").mkdirs()
        }
    }

    private lateinit var target: File

    @Before
    fun makeTarget() {
        target = File(Env.sessionsDir, "pc-atomic.json")
        target.writeText("""{"n":0,"pad":""}""")
    }

    private fun body(n: Int) =
        """{"n":$n,"pad":"${"x".repeat(40000)}","title":"第 $n 版"}"""

    @Test
    fun `the real reader never loses a session while it is being rewritten`() {
        val f = File(Env.sessionsDir, "pc-live.json")
        f.writeText("""{"id":"pc-live","title":"t","workspace":"C:\\x","mode":"ask","updated":1,"messages":[]}""")
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        var n = 0
        // 写的一侧：产品里那个落盘函数，一直重写
        val writer = Thread {
            while (!stop.get()) {
                n++
                Env.atomicWrite(f,
                    """{"id":"pc-live","title":"第 $n 版","workspace":"C:\\x","mode":"ask","updated":$n,""" +
                        """"messages":[{"role":"user","content":"${"x".repeat(20000)}"}]}""")
                // 5ms 一跳：产品里最快也是"一回合一次"（几百毫秒一次），
                // 不留缝的连写是把测试调成"必然撞上 copy 的那一瞬"，量的是操作系统不是产品
                Thread.sleep(5)
            }
        }
        writer.isDaemon = true
        writer.start()
        var lost = 0
        var reads = 0
        // 读的一侧就用产品里那个函数（它自带三次重试），别自己造一套读法
        val until = System.currentTimeMillis() + 4000L
        while (System.currentTimeMillis() < until) {
            if (SessionIndex.read(f) == null) lost++ else reads++
            Thread.sleep(1)
        }
        stop.set(true)
        writer.join(5000)
        assertTrue("写了几轮：" + n, n > 20)
        assertTrue("一次都没读成功过，量具有问题：reads=$reads", reads > 20)
        assertEquals("边写边读时读侧不该丢会话：lost", 0, lost)
    }

    @Test
    fun `the write still lands when another handle holds the target open`() {
        // Windows：这条就是第一版翻车的那个场景（move 要对目标开 DELETE 访问，
        // 目标被读句柄握着时 ACCESS_DENIED，整部落库失败比"半截文件"严重得多）
        FileInputStream(target).use { Env.atomicWrite(target, body(7)) }
        assertTrue("新内容必须落到位", target.readText().contains("\"n\":7"))
        val left = Env.sessionsDir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        assertTrue("不该留下 .tmp：" + left.joinToString { it.name }, left.isEmpty())
    }
}
