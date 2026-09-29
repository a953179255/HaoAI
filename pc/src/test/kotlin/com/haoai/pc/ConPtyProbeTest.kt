package com.haoai.pc

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * ConPTY 卡点的判别实验（ROADMAP §3.4 (a)：用 HANDLE_LIST 做对照，分清"属性表整体不行"
 * 还是"只有 PSEUDOCONSOLE 这项不行"，顺带复现 09-28 记的 cb=120 → 87）。
 *
 * 三个假设，一次定案：
 *   1. STARTUPINFOEXW 正确大小 = 112（STARTUPINFOW 104 + 属性表指针 8）——09-28 记的 cb=120
 *      多算了 8 字节，EXTENDED 模式下 cb 不对正是 ERROR_INVALID_PARAMETER(87) 的经典来源；
 *   2. HANDLE_LIST + EXTENDED 能不能起进程（对照组：属性机制本身好不好使）；
 *   3. PSEUDOCONSOLE + 正确 cb 能不能起进程，通了就 echo 一行做回环。
 *
 * 结果落 %TEMP%\conpty-probe.txt（gradle 吞测试 stdout，落文件才看得见），结论记进 ROADMAP §3.4。
 * 用 FFM 不是 JNA：pc 刻意零第三方依赖（见 build.gradle.kts），09-28 那轮也只可能走 FFM。
 */
class ConPtyProbeTest {

    private val linker = Linker.nativeLinker()
    private val arena = java.lang.foreign.Arena.ofAuto()
    private val k32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32.dll", arena)

    /** 按布局分配（这台 JDK 的工厂在 SegmentAllocator/Arena 上，不在 MemorySegment 静态方法里）。 */
    private fun alloc(l: MemoryLayout): MemorySegment = arena.allocate(l.byteSize(), l.byteAlignment())
    private fun allocSeq(elem: MemoryLayout, count: Long): MemorySegment =
        arena.allocate(elem.byteSize() * count, elem.byteAlignment())

    private fun fn(name: String, ret: MemoryLayout, vararg params: MemoryLayout) =
        linker.downcallHandle(
            k32.find(name).orElseThrow { error("找不到 $name") },
            FunctionDescriptor.of(ret, *params)
        )

    private fun fnVoid(name: String, vararg params: MemoryLayout) =
        linker.downcallHandle(
            k32.find(name).orElseThrow { error("找不到 $name") },
            FunctionDescriptor.ofVoid(*params)
        )

    private val BOOL = ValueLayout.JAVA_INT
    private val DWORD = ValueLayout.JAVA_INT
    private val SIZE_T = ValueLayout.JAVA_LONG
    private val DWORD_PTR = ValueLayout.JAVA_LONG
    private val PTR = ValueLayout.ADDRESS
    private val COORD = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("X"), ValueLayout.JAVA_SHORT.withName("Y")
    )

    // STARTUPINFOEXW：显式 padding（这版 structLayout 不自动补），算出 104+8=112 —— 本实验钉死的第一件事
    private val SI = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("cb"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("lpReserved"),
        ValueLayout.ADDRESS.withName("lpDesktop"),
        ValueLayout.ADDRESS.withName("lpTitle"),
        ValueLayout.JAVA_INT.withName("dwX"), ValueLayout.JAVA_INT.withName("dwY"),
        ValueLayout.JAVA_INT.withName("dwXSize"), ValueLayout.JAVA_INT.withName("dwYSize"),
        ValueLayout.JAVA_INT.withName("dwXCountChars"), ValueLayout.JAVA_INT.withName("dwYCountChars"),
        ValueLayout.JAVA_INT.withName("dwFillAttribute"), ValueLayout.JAVA_INT.withName("dwFlags"),
        ValueLayout.JAVA_SHORT.withName("wShowWindow"), ValueLayout.JAVA_SHORT.withName("cbReserved2"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("lpReserved2"),
        ValueLayout.ADDRESS.withName("hStdInput"),
        ValueLayout.ADDRESS.withName("hStdOutput"),
        ValueLayout.ADDRESS.withName("hStdError"),
        ValueLayout.ADDRESS.withName("lpAttributeList")
    )
    private fun off(name: String): Long =
        SI.byteOffset(MemoryLayout.PathElement.groupElement(name))

    private val PI = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("hProcess"), ValueLayout.ADDRESS.withName("hThread"),
        ValueLayout.JAVA_INT.withName("pid"), ValueLayout.JAVA_INT.withName("tid")
    )
    private val SA = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("nLength"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("lpSecurityDescriptor"),
        ValueLayout.JAVA_INT.withName("bInheritHandle"),
        MemoryLayout.paddingLayout(4)
    )

    private val kCreatePipe = fn("CreatePipe", BOOL, PTR, PTR, PTR, DWORD)
    private val kCreateProcessW = fn("CreateProcessW", BOOL, PTR, PTR, PTR, PTR, BOOL, DWORD, PTR, PTR, PTR, PTR)
    private val kCloseHandle = fn("CloseHandle", BOOL, PTR)
    private val kInitAttr = fn("InitializeProcThreadAttributeList", BOOL, PTR, DWORD, DWORD, PTR)
    private val kUpdateAttr = fn("UpdateProcThreadAttribute", BOOL, PTR, DWORD, DWORD_PTR, PTR, SIZE_T, PTR, PTR)
    private val kDelAttr = fnVoid("DeleteProcThreadAttributeList", PTR)
    private val kCreatePseudoConsole = fn("CreatePseudoConsole", BOOL, COORD, PTR, PTR, DWORD, PTR)
    private val kClosePseudoConsole = fnVoid("ClosePseudoConsole", PTR)
    private val kGetLastError = fn("GetLastError", DWORD)
    private val kReadFile = fn("ReadFile", BOOL, PTR, PTR, DWORD, PTR, PTR)
    private val kPeek = fn("PeekNamedPipe", BOOL, PTR, PTR, DWORD, PTR, PTR, PTR)
    private val kWriteFile = fn("WriteFile", BOOL, PTR, PTR, DWORD, PTR, PTR)
    private val kSleep = fnVoid("Sleep", DWORD)

    /** 往管道写一段（ASCII 足够：探针只发命令）。 */
    private fun wbytes(p: MemorySegment, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val buf = allocSeq(ValueLayout.JAVA_BYTE, bytes.size.toLong())
        bytes.forEachIndexed { i, b -> buf.set(ValueLayout.JAVA_BYTE, i.toLong(), b) }
        val written = alloc(ValueLayout.JAVA_INT)
        return kWriteFile.invokeWithArguments(p, buf, bytes.size, written, MemorySegment.NULL) as Int != 0
    }

    private fun lastErr(): Int = kGetLastError.invokeWithArguments() as Int

    /** UTF-16LE（含 NUL）。FFM 的 allocateFrom(String) 是 UTF-8，W 系函数不能用。 */
    private fun wstr(s: String): MemorySegment {
        val chars = s + "\u0000"
        val seg = allocSeq(ValueLayout.JAVA_CHAR, chars.length.toLong())
        chars.forEachIndexed { i, c -> seg.set(ValueLayout.JAVA_CHAR, i.toLong() * 2, c) }
        return seg
    }

    private fun sa(inherit: Boolean): MemorySegment {
        val s = alloc(SA)
        s.set(ValueLayout.JAVA_INT, SA.byteOffset(MemoryLayout.PathElement.groupElement("nLength")), SA.byteSize().toInt())
        s.set(PTR, SA.byteOffset(MemoryLayout.PathElement.groupElement("lpSecurityDescriptor")), MemorySegment.NULL)
        s.set(ValueLayout.JAVA_INT, SA.byteOffset(MemoryLayout.PathElement.groupElement("bInheritHandle")), if (inherit) 1 else 0)
        return s
    }

    private class Pipe(val read: MemorySegment, val write: MemorySegment)

    private fun pipe(inheritWriteEnd: Boolean): Pipe {
        val r = alloc(PTR)
        val w = alloc(PTR)
        val ok = kCreatePipe.invokeWithArguments(r, w, sa(inheritWriteEnd), 0) as Int
        check(ok != 0) { "CreatePipe 失败 err=${lastErr()}" }
        return Pipe(r.get(PTR, 0), w.get(PTR, 0))
    }

    private fun close(h: MemorySegment) {
        if (h != MemorySegment.NULL) kCloseHandle.invokeWithArguments(h)
    }

    private data class Attempt(val ok: Boolean, val err: Int, val pid: Int)

    /** 两趟建属性表：先问尺寸，再初始化。返回**表缓冲区本身**（它就是 lpAttributeList）。 */
    private fun initAttr(note: (String) -> Unit = {}): MemorySegment? {
        val sizeRef = alloc(SIZE_T)
        val r1 = kInitAttr.invokeWithArguments(MemorySegment.NULL, 0, 1, sizeRef) as Int
        var want = sizeRef.get(SIZE_T, 0)
        var nFlags = 0
        var nCount = 1
        note("init#1(flags=0,count=1) ret=$r1 want=$want err=${lastErr()}")
        if (want <= 0L) {
            // 参数语义对照：万一 SDK 顺序是 (count, flags)
            val s2 = alloc(SIZE_T)
            val r1b = kInitAttr.invokeWithArguments(MemorySegment.NULL, 1, 0, s2) as Int
            val w2 = s2.get(SIZE_T, 0)
            note("init#1-alt(count=1,flags=0 即反序传 1,0) ret=$r1b want=$w2 err=${lastErr()}")
            if (w2 > 0L) { want = w2; nFlags = 1; nCount = 0 }
        }
        if (want <= 0L) return null
        // 注意：want 可能来自上面的 alt 查询（写进了 s2），init 这趟要重新把容量写进 sizeRef ——
        // 上一版把 value=0 的 sizeRef 传进去，API 读到容量 0 回 122，差点误判成 Windows 的锅。
        sizeRef.set(SIZE_T, 0, want)
        // 对齐是这一步的暗坑：按 1 字节分配的缓冲可能不是 8 的倍数，
        // InitializeProcThreadAttributeList 对表指针的对齐有要求（JNA 那轮用的 Native.malloc 天然对齐，所以没踩到）。
        val buf = arena.allocate(want, 8L)
        val ok = kInitAttr.invokeWithArguments(buf, nFlags, nCount, sizeRef) as Int
        note("init#2(flags=$nFlags,count=$nCount) ret=$ok err=${lastErr()} buf=$want align=8")
        return if (ok != 0) buf else null
    }

    private fun updateAttr(list: MemorySegment, attr: Long, value: MemorySegment, size: Long): Boolean =
        kUpdateAttr.invokeWithArguments(list, 0, attr, value, size, MemorySegment.NULL, MemorySegment.NULL) as Int != 0

    /**
     * STARTUPINFOEXW 段。[stdIn]/[stdOut] 非空才设 STARTF_USESTDHANDLES（PSEUDOCONSOLE
     * 那路不能设：句柄由控制台接管，塞 NULL+USESTDHANDLES 反而引入一个新变量）。
     */
    private fun startupEx(
        stdIn: MemorySegment = MemorySegment.NULL,
        stdOut: MemorySegment = MemorySegment.NULL,
        attrList: MemorySegment = MemorySegment.NULL,
        cbOverride: Long = -1,
        stdInvalid: Boolean = false
    ): MemorySegment {
        val si = alloc(SI)
        si.set(ValueLayout.JAVA_INT, off("cb"), (if (cbOverride > 0) cbOverride else SI.byteSize()).toInt())
        if (stdInvalid) {
            // 照抄 wezterm/node-pty：显式 STARTF_USESTDHANDLES + 三个无效句柄 ——
            // 专治"子进程继承父进程（JVM）被重定向的 std，输出再也不进伪终端"。
            si.set(ValueLayout.JAVA_INT, off("dwFlags"), 0x100)
            si.set(PTR, off("hStdInput"), MemorySegment.ofAddress(-1L))
            si.set(PTR, off("hStdOutput"), MemorySegment.ofAddress(-1L))
            si.set(PTR, off("hStdError"), MemorySegment.ofAddress(-1L))
        } else {
            if (stdIn != MemorySegment.NULL || stdOut != MemorySegment.NULL) {
                si.set(ValueLayout.JAVA_INT, off("dwFlags"), 0x100) // STARTF_USESTDHANDLES
            }
            si.set(PTR, off("hStdInput"), stdIn)
            si.set(PTR, off("hStdOutput"), stdOut)
        }
        si.set(PTR, off("lpAttributeList"), attrList)
        return si
    }

    private fun createProcess(cmd: String, flags: Int, si: MemorySegment, inheritHandles: Boolean): Attempt {
        val app = wstr("C:\\Windows\\System32\\cmd.exe")
        val cl = wstr(cmd)
        val pi = alloc(PI)
        val ok = kCreateProcessW.invokeWithArguments(
            app, cl, MemorySegment.NULL, MemorySegment.NULL,
            if (inheritHandles) 1 else 0, flags,
            MemorySegment.NULL, MemorySegment.NULL, si, pi
        ) as Int
        if (ok == 0) return Attempt(false, lastErr(), -1)
        val pid = pi.get(ValueLayout.JAVA_INT, PI.byteOffset(MemoryLayout.PathElement.groupElement("pid")))
        close(pi.get(PTR, 0))
        close(pi.get(PTR, 8))
        return Attempt(true, 0, pid)
    }

    /**
     * 非阻塞轮询读。两课都要了命：
     * 1. `PeekNamedPipe(h, NULL, 0, …)` 时这台 Windows 把可用字节写在第 5 个出参而不是
     *    lpBytesRead——按「经典 NULL peek」读 avail 恒 0，ConPTY 明明有输出也判成无数据；
     * 2. peek **只看不消费**：拿到 n 之后必须 ReadFile 掉那 n 个字节，否则下一轮又从头
     *    拷一遍同样的字节（自检里 ABC 读成 ABC×7 就是这么来的），真输出永远排在后面。
     */
    private fun readPipe(p: MemorySegment, waitMs: Int): String {
        val deadline = System.currentTimeMillis() + waitMs
        val out = StringBuilder()
        val peekBuf = allocSeq(ValueLayout.JAVA_BYTE, 256)
        val rd = alloc(ValueLayout.JAVA_INT)
        val lf = alloc(ValueLayout.JAVA_INT)
        val wr = alloc(ValueLayout.JAVA_INT)
        var quiet = 0
        while (System.currentTimeMillis() < deadline) {
            val peeked = kPeek.invokeWithArguments(p, peekBuf, 256, rd, lf, MemorySegment.NULL) as Int
            val n = if (peeked != 0) rd.get(ValueLayout.JAVA_INT, 0) else 0
            if (n > 0) {
                quiet = 0
                val take = minOf(n, 256)
                val rb = allocSeq(ValueLayout.JAVA_BYTE, take.toLong())
                kReadFile.invokeWithArguments(p, rb, take, wr, MemorySegment.NULL)
                val got = wr.get(ValueLayout.JAVA_INT, 0)
                for (i in 0 until got) out.append(rb.get(ValueLayout.JAVA_BYTE, i.toLong()).toInt().toChar())
                if (out.length >= 500) break
                kSleep.invokeWithArguments(60)
                continue
            }
            // 安静判定要连续多次：conhost 先发十几个字节的模式序言，紧跟一个空轮询就收工
            // 会把真正的回显漏在外面（踩过：输出只剩 ESC[?9001h）。
            quiet++
            if (out.isNotEmpty() && quiet >= 6) break
            kSleep.invokeWithArguments(150)
        }
        return if (out.isEmpty()) "(轮询 ${waitMs}ms 无数据)" else out.toString()
    }

    private val EXTENDED = 0x00080000
    private val ATTR_HANDLE_LIST = 0x00020012L
    private val ATTR_PSEUDOCONSOLE = 0x00020016L

    private fun writeReport(sb: StringBuilder) =
        File(System.getenv("TEMP"), "conpty-probe.txt").writeText(sb.toString(), Charsets.UTF_8)

    @Test(timeout = 120_000)
    fun `conpty 卡点判别实验`() {
        assumeTrue("非 Windows 跳过", Env.isWindows)
        val out = StringBuilder()
        fun note(s: String) { out.append(s).append('\n'); writeReport(out); println("[probe] $s") }

        // ---- 0) 布局：先把"cb 应该是多少"钉死 ----
        if (SI.byteSize() != 112L) {
            note("FATAL: STARTUPINFOEXW 大小=${SI.byteSize()} ≠ 112 —— 布局假设错了")
            writeReport(out); error("STARTUPINFOEXW 应为 112，实测 ${SI.byteSize()}")
        }
        if (off("lpAttributeList") != 104L || off("dwFlags") != 60L) {
            note("FATAL: 关键偏移 lpAttributeList=${off("lpAttributeList")} dwFlags=${off("dwFlags")}")
            writeReport(out); error("STARTUPINFO 偏移与 Windows 文档不符")
        }
        note("layout: STARTUPINFOEXW=112 lpAttributeList@104 dwFlags@60")

        // ---- P) 自检：普通管道里写->读通不通（排除本探针自己把读写端搞反）----
        run {
            val p = pipe(inheritWriteEnd = false)
            val wrote = wbytes(p.write, "ABC")
            val text = readPipe(p.read, 500)
            note("P: 普通管道 wrote=$wrote read=[$text]")
            close(p.read); close(p.write)
            if (text != "ABC") {
                writeReport(out)
                error("普通管道自检都没过（读到 [$text]），下面的对照全不作数")
            }
        }

        // ---- C) 基线：不带 EXTENDED 必须能起，否则上面的对照全不作数 ----
        run {
            val inP = pipe(inheritWriteEnd = false)
            val outP = pipe(inheritWriteEnd = true)
            val si = startupEx(inP.read, outP.write)
            si.set(ValueLayout.JAVA_INT, off("cb"), (SI.byteSize() - 8).toInt()) // 纯 STARTUPINFO=104、不带属性表
            val r = createProcess("/c echo baseline-ok", 0, si, inheritHandles = true)
            note("C: 基线 plain-STARTUPINFO(104) ok=${r.ok} err=${r.err}")
            if (!r.ok) { writeReport(out); error("基线 CreateProcess 失败 err=${r.err}，对照无意义") }
            close(inP.read); close(inP.write); close(outP.read); close(outP.write)
        }

        // ---- A) 对照组：HANDLE_LIST + EXTENDED ----
        run {
            val inP = pipe(inheritWriteEnd = false)
            val outP = pipe(inheritWriteEnd = true)
            val list = initAttr { note(it) }
            if (list == null) {
                note("A: InitializeProcThreadAttributeList 失败 err=${lastErr()}")
            } else {
                val arr = allocSeq(PTR, 2)
                arr.set(PTR, 0, inP.read)
                arr.set(PTR, 8, outP.write)
                val up = updateAttr(list, ATTR_HANDLE_LIST, arr, 16L)
                note("A: updateAttr(HANDLE_LIST)=$up err=${if (up) 0 else lastErr()}")
                val r = createProcess("/c echo handlelist-ok", EXTENDED, startupEx(inP.read, outP.write, attrList = list), inheritHandles = true)
                note("A: HANDLE_LIST+EXTENDED+cb=112 ok=${r.ok} err=${r.err} pid=${r.pid}")
                if (r.ok) note("A: child stdout=${readPipe(outP.read, 500).trim()}")
                kDelAttr.invokeWithArguments(list)
            }
            close(inP.read); close(inP.write); close(outP.read); close(outP.write)
        }

        // ---- B) 主实验 + D) 复现 cb=120 ----
        run {
            val conIn = pipe(inheritWriteEnd = false)   // 父写 conIn.write，控制台读 conIn.read
            val conOut = pipe(inheritWriteEnd = true)   // 控制台写 conOut.write，父读 conOut.read
            val hpcRef = alloc(PTR)
            val coord = alloc(COORD)
            coord.set(ValueLayout.JAVA_SHORT, 0, 120.toShort())
            coord.set(ValueLayout.JAVA_SHORT, 2, 40.toShort())
            val hr = kCreatePseudoConsole.invokeWithArguments(coord, conIn.read, conOut.write, 0, hpcRef) as Int
            val hpc = hpcRef.get(PTR, 0)
            note("B: CreatePseudoConsole hr=0x${Integer.toHexString(hr)} built=${hpc != MemorySegment.NULL}")
            if (hpc == MemorySegment.NULL) {
                note("B: 伪终端本体没建起来（err=${lastErr()}）——实验到此")
            } else {
                // 不关交给伪终端的两端（样例里关是因为它们会 dup；这台机器上先不赌——
                // 输入侧还要留着 conIn.read 做消费探针）。
                val list = initAttr { note(it) }
                val up = list?.let { updateAttr(it, ATTR_PSEUDOCONSOLE, hpc, 8L) } ?: false
                note("B: updateAttr(PSEUDOCONSOLE)=$up err=${if (up) 0 else lastErr()}")
                File(System.getenv("TEMP"), "pm-b.txt").delete()
                File(System.getenv("TEMP"), "mc.txt").delete()
                // 交互 cmd（/q 不出横幅）：客户端活着才能反复喂——一次性 /c echo 的输出
                // 会在客户端退出时被收走，分不清「输出路径坏」和「读晚了」。
                // bInheritHandles=FALSE（四个参考实现唯一共同点）+ std 显式无效句柄（wezterm/node-pty 同款）
                val r = createProcess(
                    "/q", EXTENDED,
                    startupEx(attrList = list ?: MemorySegment.NULL, stdInvalid = true), inheritHandles = false
                )
                note("B: PSEUDOCONSOLE+cb=112 ok=${r.ok} err=${r.err} pid=${r.pid}")
                if (r.ok) {
                    // 文档要求：CreateProcess 之后把交给伪终端的两端关掉（conhost 已 dup，
                    // 关掉才能在会话结束时正确检测断链）
                    close(conIn.read)
                    close(conOut.write)
                    // 输入要等客户端真接上再发：CreateProcess 返回≠cmd 已进入输入循环
                    kSleep.invokeWithArguments(800)
                    val sent = wbytes(conIn.write, "echo hello-conpty\r\n")
                    val text = readPipe(conOut.read, 3000)
                    note("B: wrote=$sent output=${text.trim().take(80)} contains(hello)=${text.contains("hello-conpty")}")
                    wbytes(conIn.write, "exit\r\n")
                    // ★ 判据：整条链要真的把 echo 捞回来（这是 09-28 卡点被击破的实证）
                    if (!text.contains("hello-conpty")) {
                        writeReport(out)
                        error("ConPTY 回环失败：属性表/进程都起来了，但 echo 没从 conOut.read 回来（详见 %TEMP%\\conpty-probe.txt）")
                    }
                } else {
                    writeReport(out)
                    error("PSEUDOCONSOLE+cb=112 CreateProcess 失败 err=${r.err}——机制本身坏了")
                }

                // D) 同样的属性、cb=120（09-28 记的值）——若这里 87 而 B 通过，根因就是 cb
                val r2 = createProcess(
                    "/c echo cb120", EXTENDED,
                    startupEx(attrList = list ?: MemorySegment.NULL, cbOverride = 120), inheritHandles = true
                )
                note("D: PSEUDOCONSOLE+cb=120 ok=${r2.ok} err=${r2.err}（期望 87=复现旧卡点）")

                kSleep.invokeWithArguments(400)
                list?.let { kDelAttr.invokeWithArguments(it) }
                kClosePseudoConsole.invokeWithArguments(hpc)
            }
            close(conIn.write); close(conOut.read)   // conIn.read/conOut.write 已在 CreateProcess 后关过（别二次 CloseHandle 误伤复用的句柄值）
        }

        writeReport(out)
    }
}
