package com.haoai.pc

import java.io.File
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * ConPTY：Windows 真伪终端（B2 的那一半）。
 *
 * 为什么值得做：管道模式下 `isatty()` 为假 —— 程序自己关颜色/关进度条、REPL 不给行编辑、
 * Ctrl+C 只是往缓冲里塞了个字节（没人当信号）。这四件是管道永远补不上的（见 ROADMAP 第 2 条）。
 *
 * **配方是 2026-09-29 探针（ConPtyProbeTest）逐层试出来的，三层缺一不可**：
 *   1. `STARTUPINFOEXW.cb = 112`（120 会 87 —— 09-28 的卡点根因）；
 *   2. `InitializeProcThreadAttributeList(list, count=1, flags=0, &size)` 两趟建表、缓冲 8 对齐，
 *      `UpdateProcThreadAttribute(0x00020016, hpc, 8)` 挂伪终端；
 *   3. CreateProcess 用 `bInheritHandles=FALSE` + `STARTF_USESTDHANDLES` 且 `hStd*=INVALID_HANDLE_VALUE`
 *      （wezterm/node-pty 同款 —— TRUE 会把 JVM 被重定向的 std 傅给子进程，回显全进别人的终端），
 *      成功后立刻关掉交给伪终端的两端。
 *
 * 实现课（探针用真失败换来的，改这里之前先读）：
 *   - `PeekNamedPipe(NULL 缓冲)` 在这台 Windows 把可用字节写在第 5 个出参，`lpBytesRead` 恒 0 —— 所以
 *     这里带缓冲 peek，拿字节数用 `lpBytesRead`（语义：拷进缓冲的字节数）；
 *   - peek **只看不消费**：拿到 n 必须 ReadFile 掉，否则同一段字节读成 N 遍；
 *   - 输出线程要持续 drain（管道缓冲写满 conhost 会阻塞，症状像"没输出"）；
 *   - 输入 UTF-8 + `\r\n`，且要等客户端接上再发（CreateProcess 返回 ≠ shell 进入输入循环）。
 *
 * 依赖：JDK FFM 直调 kernel32 —— pc 刻意零第三方依赖（见 build.gradle.kts），不引 JNA。
 * 仅 Windows；建不起来时上层明说"这台没有 TTY"，不静默退回管道。
 */
class ConPty private constructor(
    private val hpc: MemorySegment,
    private val hProcess: MemorySegment,
    private val conInWrite: MemorySegment,
    private val conOutRead: MemorySegment
) : AutoCloseable {

    companion object {
        private val linker = Linker.nativeLinker()
        private val arena = java.lang.foreign.Arena.ofAuto()
        private val k32: SymbolLookup = SymbolLookup.libraryLookup("kernel32.dll", arena)

        private val BOOL = ValueLayout.JAVA_INT
        private val DWORD = ValueLayout.JAVA_INT
        private val SIZE_T = ValueLayout.JAVA_LONG
        private val PTR = ValueLayout.ADDRESS
        private val COORD = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT
        )

        // STARTUPINFOEXW：显式 padding（这版 structLayout 不自动补），104+8=112 —— 卡点复现里钉死的数。
        // 用到的字段要 withName：off() 按名字取偏移（探针实测 cb@0 dwFlags@60 hStd@80/88/96 lpAttributeList@104）。
        private val SI: MemoryLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("cb"), MemoryLayout.paddingLayout(4),
            PTR, PTR, PTR,                                   // lpReserved / lpDesktop / lpTitle
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,      // dwX dwY
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,      // dwXSize dwYSize
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,      // dwXCountChars dwYCountChars
            ValueLayout.JAVA_INT,                            // dwFillAttribute
            ValueLayout.JAVA_INT.withName("dwFlags"),
            ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT,  // wShowWindow cbReserved2
            MemoryLayout.paddingLayout(4),
            PTR,                                            // lpReserved2
            PTR.withName("hStdInput"),
            PTR.withName("hStdOutput"),
            PTR.withName("hStdError"),
            PTR.withName("lpAttributeList")                  // STARTUPINFOEX 的 8 字节
        )

        private val PI = MemoryLayout.structLayout(
            PTR, PTR, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT
        )
        private val SA = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT, MemoryLayout.paddingLayout(4),
            PTR, ValueLayout.JAVA_INT, MemoryLayout.paddingLayout(4)
        )

        private fun fn(name: String, ret: MemoryLayout, vararg params: MemoryLayout) =
            linker.downcallHandle(k32.find(name).orElseThrow { error("找不到 kernel32!$name") },
                FunctionDescriptor.of(ret, *params))

        private fun fnVoid(name: String, vararg params: MemoryLayout) =
            linker.downcallHandle(k32.find(name).orElseThrow { error("找不到 kernel32!$name") },
                FunctionDescriptor.ofVoid(*params))

        private val kCreatePipe = fn("CreatePipe", BOOL, PTR, PTR, PTR, DWORD)
        private val kCreateProcessW = fn("CreateProcessW", BOOL, PTR, PTR, PTR, PTR, BOOL, DWORD, PTR, PTR, PTR, PTR)
        private val kCloseHandle = fn("CloseHandle", BOOL, PTR)
        private val kInitAttr = fn("InitializeProcThreadAttributeList", BOOL, PTR, DWORD, DWORD, PTR)
        private val kUpdateAttr = fn("UpdateProcThreadAttribute", BOOL, PTR, DWORD, SIZE_T, PTR, SIZE_T, PTR, PTR)
        private val kDelAttr = fnVoid("DeleteProcThreadAttributeList", PTR)
        private val kCreatePseudoConsole = fn("CreatePseudoConsole", BOOL, COORD, PTR, PTR, DWORD, PTR)
        private val kClosePseudoConsole = fnVoid("ClosePseudoConsole", PTR)
        private val kPeek = fn("PeekNamedPipe", BOOL, PTR, PTR, DWORD, PTR, PTR, PTR)
        private val kReadFile = fn("ReadFile", BOOL, PTR, PTR, DWORD, PTR, PTR)
        private val kWriteFile = fn("WriteFile", BOOL, PTR, PTR, DWORD, PTR, PTR)
        private val kGetExitCodeProcess = fn("GetExitCodeProcess", BOOL, PTR, PTR)
        private val kTerminateProcess = fn("TerminateProcess", BOOL, PTR, DWORD)
        private val kGetLastError = fn("GetLastError", DWORD)
        private val kSleep = fnVoid("Sleep", DWORD)

        private const val EXTENDED_STARTUPINFO_PRESENT = 0x00080000
        private const val PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016L
        private const val STILL_ACTIVE = 259

        private fun alloc(l: MemoryLayout): MemorySegment = arena.allocate(l.byteSize(), l.byteAlignment())
        private fun allocSeq(elem: MemoryLayout, count: Long): MemorySegment =
            arena.allocate(elem.byteSize() * count, elem.byteAlignment())

        /** UTF-16LE（含 NUL）。FFM 的字符串工厂是 UTF-8，W 系函数不能用。 */
        private fun wstr(s: String): MemorySegment {
            val chars = s + "\u0000"
            val seg = allocSeq(ValueLayout.JAVA_CHAR, chars.length.toLong())
            chars.forEachIndexed { i, c -> seg.set(ValueLayout.JAVA_CHAR, i.toLong() * 2, c) }
            return seg
        }

        private fun off(name: String): Long =
            SI.byteOffset(MemoryLayout.PathElement.groupElement(name))

        private fun sa(inherit: Boolean): MemorySegment {
            val s = alloc(SA)
            s.set(ValueLayout.JAVA_INT, 0, SA.byteSize().toInt())
            s.set(PTR, 8, MemorySegment.NULL)
            s.set(ValueLayout.JAVA_INT, 16, if (inherit) 1 else 0)
            return s
        }

        /** 两趟建属性表。返回表缓冲区本身（它就是 lpAttributeList）；参数序 (count, flags) 是实测的。 */
        private fun initAttr(): MemorySegment? {
            val sizeRef = alloc(SIZE_T)
            kInitAttr.invokeWithArguments(MemorySegment.NULL, 1, 0, sizeRef)
            val want = sizeRef.get(SIZE_T, 0)
            if (want <= 0L) return null
            sizeRef.set(SIZE_T, 0, want)
            val buf = arena.allocate(want, 8L)   // 8 对齐是暗坑（1 字节对齐的分配会失败）
            val ok = kInitAttr.invokeWithArguments(buf, 1, 0, sizeRef) as Int
            return if (ok != 0) buf else null
        }

        /**
         * 起一个挂在伪终端上的进程。[command] = 可执行文件 + 参数。
         * 返回 null = 这台建不起来（非 Windows / 属性表失败 / CreateProcess 失败）——
         * 调用方必须**明说**而不是静默退回管道。
         */
        fun spawn(command: List<String>, cwd: File): ConPty? {
            if (!Env.isWindows || command.isEmpty()) return null
            // 失败一律带步骤与 GetLastError（失败立刻读 —— DeleteProcThreadAttributeList 会覆盖它）
            return runCatching {
                // 1) 两根管道：父写 conInWrite → 控制台读；控制台写 conOutWrite → 父读 conOutRead
                val conR = alloc(PTR); val conW = alloc(PTR)
                if ((kCreatePipe.invokeWithArguments(conR, conW, sa(false), 0) as Int) == 0)
                    error("CreatePipe(输入管) 失败 err=${lastErr()}")
                val conInRead = conR.get(PTR, 0); val conInWrite = conW.get(PTR, 0)
                val outR = alloc(PTR); val outW = alloc(PTR)
                if ((kCreatePipe.invokeWithArguments(outR, outW, sa(true), 0) as Int) == 0) {
                    close0(conInRead); close0(conInWrite)
                    error("CreatePipe(输出管) 失败 err=${lastErr()}")
                }
                val conOutRead = outR.get(PTR, 0); val conOutWrite = outW.get(PTR, 0)

                // 2) 伪终端本体
                val hpcRef = alloc(PTR)
                val coord = alloc(COORD)
                coord.set(ValueLayout.JAVA_SHORT, 0, 120.toShort())  // 列：终端宽度，输出按它折行
                coord.set(ValueLayout.JAVA_SHORT, 2, 300.toShort())  // 行
                val hr = kCreatePseudoConsole.invokeWithArguments(coord, conInRead, conOutWrite, 0, hpcRef) as Int
                val hpc = hpcRef.get(PTR, 0)
                if (hr != 0 || hpc == MemorySegment.NULL) {
                    close0(conInRead); close0(conInWrite); close0(conOutRead); close0(conOutWrite)
                    error("CreatePseudoConsole hr=0x${Integer.toHexString(hr)} err=${lastErr()}（需要 Windows 1809+）")
                }

                // 3) 属性表挂伪终端
                val attr = initAttr() ?: run {
                    kClosePseudoConsole.invokeWithArguments(hpc)
                    close0(conInRead); close0(conInWrite); close0(conOutRead); close0(conOutWrite)
                    error("InitializeProcThreadAttributeList 建表失败 err=${lastErr()}")
                }
                val up = kUpdateAttr.invokeWithArguments(
                    attr, 0, PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, hpc, 8L, MemorySegment.NULL, MemorySegment.NULL
                ) as Int
                if (up == 0) {
                    val e = lastErr()
                    kDelAttr.invokeWithArguments(attr)
                    kClosePseudoConsole.invokeWithArguments(hpc)
                    close0(conInRead); close0(conInWrite); close0(conOutRead); close0(conOutWrite)
                    error("UpdateProcThreadAttribute(PSEUDOCONSOLE) 失败 err=$e")
                }

                // 4) STARTUPINFOEX：cb=112、STARTF_USESTDHANDLES+无效句柄（防继承 JVM 的 std）、挂表
                val si = alloc(SI)
                si.set(ValueLayout.JAVA_INT, off("cb"), SI.byteSize().toInt())
                si.set(ValueLayout.JAVA_INT, off("dwFlags"), 0x100)
                si.set(PTR, off("hStdInput"), MemorySegment.ofAddress(-1L))
                si.set(PTR, off("hStdOutput"), MemorySegment.ofAddress(-1L))
                si.set(PTR, off("hStdError"), MemorySegment.ofAddress(-1L))
                si.set(PTR, off("lpAttributeList"), attr)

                // 5) CreateProcess：bInheritHandles=FALSE（wezterm/node-pty 注释里写死的 IMPORTANT）
                val app = wstr(command.first())
                val cl = wstr(command.joinToString(" ") { if (it.contains(' ') || it.contains('"')) "\"${it.replace("\"", "\\\"")}\"" else it })
                val cwdW = wstr(cwd.absolutePath)
                val pi = alloc(PI)
                val ok = kCreateProcessW.invokeWithArguments(
                    app, cl, MemorySegment.NULL, MemorySegment.NULL,
                    0, EXTENDED_STARTUPINFO_PRESENT,
                    MemorySegment.NULL, cwdW, si, pi
                ) as Int
                val cErr = if (ok == 0) lastErr() else 0   // 立刻抓：delAttr 会覆盖 last-error
                kDelAttr.invokeWithArguments(attr)
                if (ok == 0) {
                    kClosePseudoConsole.invokeWithArguments(hpc)
                    close0(conInRead); close0(conInWrite); close0(conOutRead); close0(conOutWrite)
                    error("CreateProcessW(app=${command.first()}) 失败 err=$cErr")
                }
                val hProcess = pi.get(PTR, 0)
                val hThread = pi.get(PTR, 8)
                close0(hThread)
                // 6) 文档要求：CreateProcess 之后把交给伪终端的两端关掉（conhost 已 dup，
                //    关掉才能在会话结束时正确检测断链）
                close0(conInRead)
                close0(conOutWrite)
                ConPty(hpc, hProcess, conInWrite, conOutRead)
            }.getOrElse { e ->
                // IllegalStateException 是我们自己带步骤的诊断，原样上抛；其余包装后上抛——
                // 调用方（ProcRegistry.open 的 runCatching）会把 message 交给用户，不能吞成 null。
                if (e is IllegalStateException) throw e
                throw IllegalStateException("ConPTY 建立过程异常：${e.message}", e)
            }
        }

        /** 失败后立刻读 GetLastError（调用之间别插别的 Win32 调用）。 */
        private fun lastErr(): Int = kGetLastError.invokeWithArguments() as Int

        private fun close0(h: MemorySegment) {
            if (h != MemorySegment.NULL) kCloseHandle.invokeWithArguments(h)
        }
    }

    /** UTF-8 输出的残留字节：多字节字符可能被管道边界劈开，先攒着下次一起解。 */
    private val carry = java.io.ByteArrayOutputStream()

    /** 客户端还活着吗（GetExitCodeProcess == STILL_ACTIVE）。 */
    fun alive(): Boolean {
        val code = alloc(DWORD)
        val ok = kGetExitCodeProcess.invokeWithArguments(hProcess, code) as Int
        return ok != 0 && code.get(ValueLayout.JAVA_INT, 0) == STILL_ACTIVE
    }

    /** 客户端退出码；还活着返回 null。 */
    fun exitCode(): Int? {
        val code = alloc(DWORD)
        val ok = kGetExitCodeProcess.invokeWithArguments(hProcess, code) as Int
        if (ok == 0) return null
        val v = code.get(ValueLayout.JAVA_INT, 0)
        return if (v == STILL_ACTIVE) null else v
    }

    /** 往终端写一段输入（调用方补 `\r\n`）。返回 false = 通道已断。 */
    fun write(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return true
        val buf = allocSeq(ValueLayout.JAVA_BYTE, bytes.size.toLong())
        bytes.forEachIndexed { i, b -> buf.set(ValueLayout.JAVA_BYTE, i.toLong(), b) }
        val written = alloc(DWORD)
        val ok = kWriteFile.invokeWithArguments(conInWrite, buf, bytes.size, written, MemorySegment.NULL) as Int
        return ok != 0
    }

    /**
     * 非阻塞捞输出：带缓冲 peek 看有多少（lpBytesRead = 拷进缓冲的字节数），ReadFile **消费**掉
     * 那 n 字节（peek 只看不消费 —— 不消费的话同一段字节会读成 N 遍），UTF-8 解码；
     * 劈在管道边界上的多字节字符留在 carry 里下次解。没有数据返回空串，轮询节奏归调用方。
     */
    fun readAvailable(maxChars: Int = 8192): String {
        val peekBuf = allocSeq(ValueLayout.JAVA_BYTE, 512)
        val rd = alloc(DWORD)
        val lf = alloc(DWORD)
        val peeked = kPeek.invokeWithArguments(conOutRead, peekBuf, 512, rd, lf, MemorySegment.NULL) as Int
        val n = if (peeked != 0) rd.get(ValueLayout.JAVA_INT, 0) else 0
        if (n <= 0) return ""
        val rb = allocSeq(ValueLayout.JAVA_BYTE, n.toLong())
        val wr = alloc(DWORD)
        kReadFile.invokeWithArguments(conOutRead, rb, n, wr, MemorySegment.NULL)
        val got = wr.get(ValueLayout.JAVA_INT, 0)
        if (got <= 0) return ""
        val chunk = ByteArray(got)
        for (i in 0 until got) chunk[i] = rb.get(ValueLayout.JAVA_BYTE, i.toLong())
        carry.write(chunk)
        val all = carry.toByteArray()
        // 尾部若停在多字节序列中间，从引导字节截断；String(…, UTF_8) 对非法字节用 U+FFFD 兜底
        var cut = all.size
        var start = all.size - 1
        var steps = 0
        while (start >= 0 && steps < 4) {
            val b = all[start].toInt() and 0xFF
            if (b and 0xC0 == 0x80) { start--; steps++; continue }
            val len = when {
                b < 0x80 -> 1
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> -1
            }
            if (len > 0 && start + len > all.size) cut = start   // 序列跨尾，留下次
            break
        }
        carry.reset()
        if (cut < all.size) carry.write(all, cut, all.size - cut)
        return String(all, 0, cut, Charsets.UTF_8).take(maxChars)
    }

    /** 先 Terminate 再 ClosePseudoConsole：ClosePseudoConsole 会等客户端退出，卡住的客户端会把它挂死。 */
    override fun close() {
        runCatching {
            if (alive()) kTerminateProcess.invokeWithArguments(hProcess, 1)
        }
        runCatching { kClosePseudoConsole.invokeWithArguments(hpc) }
        runCatching { kCloseHandle.invokeWithArguments(hProcess) }
        runCatching { kCloseHandle.invokeWithArguments(conInWrite) }
        runCatching { kCloseHandle.invokeWithArguments(conOutRead) }
    }
}
