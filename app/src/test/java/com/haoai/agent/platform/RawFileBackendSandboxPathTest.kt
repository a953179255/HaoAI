package com.haoai.agent.platform

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * bash 沙箱路径映射（/workspace = 工作区根）：91ba4cc9 会话实测「read 工具跟工作区
 * 路径对不上」的回归锁。模型在沙箱里 ls/curl 看到的路径全是 /workspace/… 形态，
 * 拿去调 read/write 必须落到工作区根，而不是字面的 workspace/ 子目录。
 */
class RawFileBackendSandboxPathTest {

    @Test
    fun `sandbox absolute path resolves to workspace root`() = runBlocking {
        val root = Files.createTempDirectory("haows").toFile()
        try {
            val be = RawFileBackend(root)
            be.writeText("/workspace/docs/a.txt", "hello")
            assertEquals("hello", File(root, "docs/a.txt").readText())
            assertEquals("hello", be.readText("/workspace/docs/a.txt"))
            // 沙箱内 /workspace/workspace/x 与工作区里真实的 workspace/ 子目录同物
            be.writeText("/workspace/workspace/note.md", "n")
            assertEquals("n", be.readText("workspace/note.md"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reading the mount root falls into directory-listing semantics`() = runBlocking {
        val root = Files.createTempDirectory("haows2").toFile()
        try {
            val be = RawFileBackend(root)
            be.writeText("x.txt", "1")
            try {
                be.readText("/workspace")
                throw AssertionError("root is a directory, readText must refuse")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("目录"))
            }
            // 列根目录等价
            assertTrue(be.listDir("/workspace").contains("x.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `mutations refuse the workspace root itself`() = runBlocking {
        val root = Files.createTempDirectory("haows3").toFile()
        try {
            val be = RawFileBackend(root)
            try {
                be.writeText("/workspace", "evil")
                throw AssertionError("root write must be refused")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("根"))
            }
            try {
                be.delete("/workspace")
                throw AssertionError("root delete must be refused")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("根"))
            }
            assertEquals(0, be.listDir("").size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing sandbox file keeps the readable error`() = runBlocking {
        val root = Files.createTempDirectory("haows4").toFile()
        try {
            val be = RawFileBackend(root)
            try {
                be.readText("/workspace/nope.txt")
                throw AssertionError("should not exist")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("文件不存在"))
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
