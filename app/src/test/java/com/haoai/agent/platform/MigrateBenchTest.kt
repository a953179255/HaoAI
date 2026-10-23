package com.haoai.agent.platform

import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 迁移性能基准（非回归测试，按需手动跑）：
 * 在系统临时目录放一个哨兵文件 haoai-bench.flag，再跑
 * gradle :app:testDebugUnitTest --tests "*.MigrateBenchTest"
 * （Gradle daemon 不传递 shell 环境变量给 fork 的测试 JVM，故用哨兵文件开关。）
 * 在同一块盘上对比串行 copyDirTree vs 并行 parallelCopy 拷 N 个小文件。
 * 说明：宿主 SSD 的文件系统行为与安卓 sdcardfs/F2FS 不同，这里只看
 * 相对加速比的方向性证据；设备端绝对耗时以真机/模拟器实测为准。
 */
class MigrateBenchTest {

    // 哨兵文件开关：临时目录存在 haoai-bench.flag 才跑（不拖慢常规单测）
    private val enabled =
        java.io.File(System.getProperty("java.io.tmpdir"), "haoai-bench.flag").exists()

    @Test
    fun `bench serial vs parallel on 20k small files`() {
        if (!enabled) return // 默认跳过，不拖慢常规单测
        val n = 20_000
        val src = Files.createTempDirectory("haoai-bench-src").toFile().apply { deleteOnExit() }
        val payload = ByteArray(1500) { (it % 251).toByte() }
        // 分 40 个子目录 × 500 文件，接近真实 repos 形态
        for (d in 0 until 40) {
            val sub = File(src, "repo$d").apply { mkdirs() }
            for (i in 0 until 500) File(sub, "f$i.txt").writeBytes(payload)
        }

        // 预热一次（页缓存），保证公平
        warmup(src)

        val dst1 = Files.createTempDirectory("haoai-bench-d1").toFile().apply { deleteOnExit() }
        val t0 = System.currentTimeMillis()
        val c1 = copyDirTree(src, dst1)
        val serialMs = System.currentTimeMillis() - t0

        val dst2 = Files.createTempDirectory("haoai-bench-d2").toFile().apply { deleteOnExit() }
        val t1 = System.currentTimeMillis()
        val c2 = parallelCopy(src, dst2) { _, _ -> true }
        val parMs = System.currentTimeMillis() - t1

        println("BENCH n=$n serial=${serialMs}ms(copied=$c1) parallel=${parMs}ms(copied=$c2) speedup=${"%.1f".format(serialMs.toDouble() / parMs)}x")
        org.junit.Assert.assertEquals(c1, c2)
    }

    private fun warmup(src: File) {
        val dst = Files.createTempDirectory("haoai-bench-warm").toFile()
        copyDirTree(src, dst)
        dst.deleteRecursively()
    }
}
