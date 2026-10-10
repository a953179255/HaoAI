package com.haoai.agent.agent.tools.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 写前快照的保留策略守卫（M8 修复时新增）。
 *
 * ## 为什么要有这个测试
 *
 * `enforceRetention` 原先靠 `d.parentFile` 反推 `filesDir`：
 * `d = filesDir/snapshots/<sessionId>` ⇒ `d.parentFile = filesDir/snapshots`
 * ⇒ 再进 `listSession(filesDir=那个, sessionId=d.name)` 去找
 * `filesDir/snapshots/snapshots/<sessionId>/manifest.jsonl` —— 永不存在。
 *
 * 结果：`metas` 恒为空 → 函数第一行就 return → `MAX_PER_SESSION=200` 与
 * `MAX_TOTAL_BYTES=50MB` 两条上限**从未生效**，长会话的快照目录无界增长
 *（每次 write/edit 存 before+after 两份全文）。
 *
 * 这类"上限形同虚设"的 bug 不会报错、不会崩溃、只会在几个月后表现为存储占用异常，
 * 靠代码审查极难发现第二次，所以用测试把"上限真的会触发"钉住。
 */
class FileSnapshotRetentionTest {

    private fun tmpDir(): File = File(System.getProperty("java.io.tmpdir"), "haoai_snap_test_${System.nanoTime()}")

    @Test
    fun `retention evicts oldest snapshots beyond per-session limit`() {
        val filesDir = tmpDir()
        try {
            val sid = "s1"
            // 写 260 条，超过 MAX_PER_SESSION=200
            repeat(260) { i ->
                FileSnapshot.snapshot(
                    filesDir, sid, "call$i", "/ws/f$i.txt",
                    before = "before$i", after = "content-$i"
                )
            }
            val metas = FileSnapshot.listSession(filesDir, sid)
            assertTrue(
                "保留策略应把快照数压到 200 以内，实际 ${metas.size}",
                metas.size <= 200
            )
            // 淘汰的应是最旧的（call0..call59），最新的必须还在
            assertTrue(
                "最新一条快照不应被淘汰",
                metas.any { it.callId == "call259" }
            )
            assertTrue(
                "最旧的快照应被淘汰（call0）",
                metas.none { it.callId == "call0" }
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `retention evicts by total bytes`() {
        val filesDir = tmpDir()
        try {
            val sid = "s2"
            // 每条 1MB，写 60 条 = 60MB > MAX_TOTAL_BYTES=50MB，
            // 但条数（60）远小于 MAX_PER_SESSION（200）⇒ 只能靠字节上限触发淘汰
            val big = "x".repeat(1024 * 1024)
            repeat(60) { i ->
                FileSnapshot.snapshot(
                    filesDir, sid, "big$i", "/ws/big$i.bin",
                    before = big, after = big
                )
            }
            val d = File(filesDir, "snapshots/$sid")
            val alive = d.listFiles { f -> f.name.endsWith(".after") }?.size ?: 0
            assertTrue(
                "字节上限（50MB）应触发淘汰，实际仍存活 $alive 个 after 文件",
                alive < 60
            )
            assertTrue(
                "最新一条不应被淘汰",
                FileSnapshot.listSession(filesDir, sid).any { it.callId == "big59" }
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `manifest stays parseable after eviction`() {
        val filesDir = tmpDir()
        try {
            val sid = "s3"
            repeat(210) { i ->
                FileSnapshot.snapshot(filesDir, sid, "c$i", "/ws/f.txt", before = "b", after = "a$i")
            }
            // 淘汰后重写 manifest，必须仍是合法 JSONL（read / rollback 都依赖它）
            val metas = FileSnapshot.listSession(filesDir, sid)
            assertEquals(
                "manifest 行数应与淘汰后的元数据一致",
                metas.size,
                File(filesDir, "snapshots/$sid/manifest.jsonl").readLines().count { it.isNotBlank() }
            )
            // read()仍能按 callId 精确取回
            val last = metas.last()
            assertTrue("read() 应能取回未淘汰的快照", FileSnapshot.read(filesDir, sid, last.callId) != null)
        } finally {
            filesDir.deleteRecursively()
        }
    }
}