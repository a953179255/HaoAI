package com.haoai.agent.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 恢复事务（[RestoreTx]）的回滚守卫 —— M5 修复时新增。
 *
 * ## 为什么要测这个
 *
 * 修复前恢复是"逐域顺序直写、失败不撤销"：配置写成功了、会话复制到一半抛异常，
 * 用户只看到"恢复失败"，机器上却留下半新半旧的混合状态 —— 配置是备份包的、
 * 记忆是备份包的、会话还是上周的。这种状态比明确失败更难排查，而且用户很可能
 * 在不知情的情况下继续用。
 *
 * 回滚逻辑本身极难靠正常路径测：得先让某个域在写盘时抛异常，而且要验证
 * "已写的域真的回到了原样、原本不存在的文件真的被删掉"。这里直接驱动事务对象
 * 本身（纯文件操作，无需 Android 运行时），把两个方向都钉死。
 */
class RestoreTxRollbackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(f: File, content: String) {
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun read(f: File): String? = if (f.isFile) f.readText() else null

    @Test
    fun `rollback restores files that were overwritten`() {
        val a = tmp.newFile("a.json").also { it.writeText("OLD-A") }
        val b = tmp.newFile("b.json").also { it.writeText("OLD-B") }
        val tx = DataBackupManager.RestoreTx(tmp.newFolder("rb"))

        tx.record(a); tx.record(b)
        // 模拟"恢复写入"
        a.writeText("NEW-A"); b.writeText("NEW-B")

        tx.rollback(RuntimeException("boom"))

        assertEquals("OLD-A", read(a))
        assertEquals("OLD-B", read(b))
    }

    @Test
    fun `rollback deletes files that did not exist before`() {
        val existing = tmp.newFile("exists.json").also { it.writeText("KEEP") }
        val fresh = File(tmp.root, "sub/fresh.json")   // 恢复前不存在
        val tx = DataBackupManager.RestoreTx(tmp.newFolder("rb"))

        tx.record(existing); tx.record(fresh)
        existing.writeText("NEW"); write(fresh, "BRAND-NEW")

        tx.rollback(RuntimeException("boom"))

        assertEquals("原本存在的文件应还原", "KEEP", read(existing))
        assertFalse("恢复前不存在的文件必须被删掉，不能留残留", fresh.exists())
    }

    @Test
    fun `commit discards the undo log`() {
        val a = tmp.newFile("c.json").also { it.writeText("OLD") }
        val rb = tmp.newFolder("rb")
        val tx = DataBackupManager.RestoreTx(rb)

        tx.record(a)
        a.writeText("NEW")
        tx.commit()

        assertEquals("commit 后不再回滚", "NEW", read(a))
        assertFalse("备份目录应被清掉", rb.exists())
    }

    @Test
    fun `recording the same file twice keeps the first snapshot`() {
        // 同一文件被两个域先后写（sessions 与 extras 范围可能重叠）：
        // 第二次 record 若覆盖第一次的备份，存的就会是"中间态"而不是"原样"。
        val a = tmp.newFile("d.json").also { it.writeText("ORIGINAL") }
        val tx = DataBackupManager.RestoreTx(tmp.newFolder("rb"))

        tx.record(a)
        a.writeText("MID")       // 域1 写
        tx.record(a)              // 域2 又要写同一文件
        a.writeText("FINAL")

        tx.rollback(RuntimeException("boom"))
        assertEquals("必须回到最原始的内容", "ORIGINAL", read(a))
    }
}