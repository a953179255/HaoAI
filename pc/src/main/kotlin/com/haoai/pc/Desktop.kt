package com.haoai.pc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 桌面控制第②③步：屏幕理解（截图 / 窗口列表 / 元素树）与点击级自动化。
 *
 * 为什么值得做：手机端那套 HaoAI 的"手"是无障碍树 + 虚拟屏；PC 上对应的能力是
 * UIAutomation + SendKeys。没有它，agent 只能读写文件与跑命令，
 * 碰不到"只有图形界面才能做的事"（剪映、ComfyUI、任何没有 CLI 的桌面软件）。
 *
 * 为什么用 PowerShell + .NET 而不是引 JNA/原生库：
 * `System.Drawing.CopyFromScreen`、`user32!EnumWindows`、`System.Windows.Automation`、
 * `SendKeys` 全都在系统自带的 .NET Framework 里，一段生成的 .ps1 就能调完。
 * 少一个依赖就少一次"这台机器上没有"。
 *
 * 默认关（[HaoFlag.DESKTOP_CONTROL]）：**它能看见你屏幕上的一切并替你点任何地方**，
 * 比浏览器控制更危险，所以关着时对模型完全不存在；打开后每一次写操作仍逐条过权限闸。
 *
 * 元素树刻意限深限量：桌面根节点的子树在开了几个 Electron 应用的机器上能长到几万个节点，
 * 全吐给模型等于烧掉整个上下文窗口（这也是缺口清单 S4 的同一个病根）。
 */
class ScreenTool : Tool(
    "screen",
    "看/操作这台电脑的屏幕。sub ∈ capture(全屏截图)|windows(列窗口)|ui(读某个窗口的控件树)" +
        "|focus(当前键盘焦点在哪个窗口)|click(按坐标点)|invoke(按控件名字点，优先用它——不依赖坐标)" +
        "|type(键入)|key(按键)。" +
        "流程：windows 找窗口 → ui 看树拿控件名 → invoke；只有没有控件名的地方才用 click 点坐标。" +
        "**type/key 之前先 focus**：键盘输入发给的是此刻的前台窗口，不一定是你刚点过的那个。" +
        "type 走 Unicode 直发，中文/特殊字符都不会被输入法改写；" +
        "key 走 SendKeys 语法（^=Ctrl、%=Alt、{Enter}），输入法处于中文模式时 ^a 这类组合键可能被输入法截走。",
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("sub", buildJsonObject { put("type", "string") })
            put("path", buildJsonObject { put("type", "string") })
            put("title", buildJsonObject { put("type", "string") })
            put("name", buildJsonObject { put("type", "string") })
            put("x", buildJsonObject { put("type", "integer") })
            put("y", buildJsonObject { put("type", "integer") })
            put("button", buildJsonObject { put("type", "string") })
            put("double", buildJsonObject { put("type", "boolean") })
            put("text", buildJsonObject { put("type", "string") })
            put("keys", buildJsonObject { put("type", "string") })
            put("max_depth", buildJsonObject { put("type", "integer") })
            put("max_nodes", buildJsonObject { put("type", "integer") })
        })
        put("required", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("sub"))
        })
    },
    kind = "exec",
    flag = HaoFlag.DESKTOP_CONTROL
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val sub = (req(args, "sub") ?: "").trim().lowercase()
        if (sub !in SUBS) return fail("screen 不支持的 sub：$sub。可用：${SUBS.joinToString("|")}")

        // 参数不齐就现在报错：别为一次注定失败的调用弹审批框，
        // 更别在框里写出"键入 0 个字符"这种没信息量的话。
        missingArgs(sub, args)?.let { return fail(it) }

        if (sub !in READ_ONLY) {
            val what = when (sub) {
                "click" -> "点击屏幕 (${req(args, "x") ?: "?"},${req(args, "y") ?: "?"})" +
                    (if (isRight(args)) " 右键" else "") + (if (bool(args, "double")) " 双击" else "")
                "invoke" -> "触发控件「${req(args, "name") ?: "?"}」于「${req(args, "title") ?: "?"}」"
                "type" -> "键入 ${req(args, "text")?.length ?: 0} 个字符：${(req(args, "text") ?: "").take(60)}"
                "key" -> "按下按键 ${(req(args, "keys") ?: "").take(40)}"
                else -> sub
            }
            // 审批卡上必须写清"这一下会落在谁身上"：键盘输入发给此刻的前台窗口，
            // 而前台窗口恰恰是模型看不见的东西。查焦点要跑一次 PowerShell，
            // 所以写成惰性 detail —— 被规则或档位挡掉时不该付这笔钱。
            val why = ctx.guard(
                "screen", "$sub ${req(args, "title") ?: what}", "桌面操作：$what",
                detail = {
                    val focus = PsRunner.run(Ps.focus()).trim().lines()
                        .lastOrNull { it.isNotBlank() }?.take(90) ?: "(焦点未知)"
                    "$what\n当前焦点窗口：$focus"
                },
                subjectIsPath = false
            )
            if (why != null) return fail(why)
        }

        val ps = when (sub) {
            "capture" -> {
                val rel = req(args, "path") ?: "screen-${System.currentTimeMillis()}.png"
                val f = ctx.resolve(rel)
                PsRunner.run(Ps.capture(f.absolutePath)) + "\n图片已存 ${f.absolutePath}" +
                    "（${if (f.isFile) f.length() else 0} 字节）；要看内容用 path=\"$rel\""
            }
            "windows" -> PsRunner.run(Ps.windows())
            "focus" -> PsRunner.run(Ps.focus())
            "ui" -> {
                val title = req(args, "title") ?: return fail("ui 需要 title（窗口标题的片段，先用 screen windows 找）")
                PsRunner.run(
                    Ps.uiTree(title, int(args, "max_depth", 4), int(args, "max_nodes", 200))
                )
            }
            "click" -> {
                val x = (req(args, "x") ?: "").toIntOrNull() ?: return fail("click 需要整数 x")
                val y = (req(args, "y") ?: "").toIntOrNull() ?: return fail("click 需要整数 y")
                PsRunner.run(Ps.click(x, y, right = isRight(args), double = bool(args, "double")))
            }
            "invoke" -> {
                val title = req(args, "title") ?: return fail("invoke 需要 title（窗口标题片段）")
                val name = req(args, "name") ?: return fail("invoke 需要 name（控件名，来自 screen ui）")
                PsRunner.run(Ps.invoke(title, name))
            }
            "type" -> {
                val t = req(args, "text") ?: return fail("type 需要 text")
                if (t.isEmpty()) return fail("type 的 text 是空串：要输入什么？")
                PsRunner.run(Ps.typeText(t))
            }
            "key" -> {
                val k = req(args, "keys") ?: return fail("key 需要 keys，如 {Enter}、^s、%{F4}")
                PsRunner.run(Ps.keys(k))
            }
            else -> "(不该到这里)"
        }
        return ToolResult(ps.trim().ifBlank { "(无输出)" }, card = if (sub == "ui") "generic" else "terminal")
    }

    private fun isRight(args: JsonObject): Boolean = (req(args, "button") ?: "").lowercase() == "right"

    /** 缺参数时给出**能直接照做**的提示（模型会照这句话改下一轮调用）。 */
    private fun missingArgs(sub: String, args: JsonObject): String? = when (sub) {
        "ui" -> if (req(args, "title") == null) "ui 需要 title：窗口标题的片段，先用 screen windows 列出窗口" else null
        "click" -> if ((req(args, "x") ?: "").toIntOrNull() == null ||
            (req(args, "y") ?: "").toIntOrNull() == null
        ) "click 需要整数 x 与 y：先 screen windows / screen ui 拿到矩形，坐标是全屏绝对坐标（虚拟屏可为负）" else null
        "invoke" -> when {
            req(args, "title") == null -> "invoke 需要 title：窗口标题的片段"
            req(args, "name") == null -> "invoke 需要 name：控件的名字，来自 screen ui 的输出"
            else -> null
        }
        "type" -> if ((req(args, "text") ?: "").isEmpty()) "type 需要非空的 text" else null
        "key" -> if ((req(args, "keys") ?: "").isEmpty()) "key 需要 keys，例如 {Enter}、{Esc}、^s、%{F4}" else null
        else -> null
    }

    companion object {
        private val SUBS = setOf("capture", "windows", "ui", "focus", "click", "invoke", "type", "key")
        private val READ_ONLY = setOf("capture", "windows", "ui", "focus")
    }
}

/**
 * 生成的 PowerShell 片段。
 *
 * 两个写法上的注意点：
 * - 全部走"写临时 .ps1 再执行"，理由见 Tools.kt 里那条 Windows 引号二次解析坑；
 * - 模板里 PowerShell 的 `$` 一律写成 `%%`，最后 `ps()` 统一换回来 ——
 *   Kotlin 的 raw string 没有转义机制，直接写 `$` 会被当插值，写 `\$` 又只是字面反斜杠。
 */
internal object Ps {
    /** 模板里用 `%%` 代表 PowerShell 的 `$`：Kotlin 的 raw string 没有转义，
     *  `\$` 会被当成字面反斜杠 + 插值。最后统一 replace 一次。 */
    private fun ps(t: String) = t.replace("%%", "$")

    private const val PREAMBLE =
        "\$ErrorActionPreference='Stop';[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;" +
            "Add-Type -AssemblyName System.Drawing,System.Windows.Forms;"

    /** 生成的是**一行**PowerShell（不含换行），所以放在任何模板的最前面都安全。 */

    fun capture(path: String): String = PREAMBLE + ps("""
        |%%b=[System.Windows.Forms.SystemInformation]::VirtualScreen
        |%%bmp=New-Object System.Drawing.Bitmap(%%b.Width,%%b.Height)
        |%%g=[System.Drawing.Graphics]::FromImage(%%bmp)
        |%%g.CopyFromScreen(%%b.Location,[System.Drawing.Point]::Empty,%%b.Size)
        |%%bmp.Save('${esc(path)}')
        |%%g.Dispose();%%bmp.Dispose()
        |"屏幕 %%(%%b.Width)x%%(%%b.Height) 于 %%(%%b.X),%%(%%b.Y)"
        """.trimMargin())

    fun windows(): String = PREAMBLE + ps("""
        |%%sig=@'
        |using System;using System.Runtime.InteropServices;using System.Text;
        |public class Win32{
        | public delegate bool CB(IntPtr h,IntPtr l);
        | [DllImport("user32.dll")]public static extern bool EnumWindows(CB cb,IntPtr l);
        | [DllImport("user32.dll")]public static extern bool IsWindowVisible(IntPtr h);
        | [DllImport("user32.dll")]public static extern int GetWindowTextLength(IntPtr h);
        | [DllImport("user32.dll")]public static extern int GetWindowText(IntPtr h,StringBuilder s,int n);
        | [DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out RECT r);
        | [DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint pid);
        | public struct RECT{public int Left,Top,Right,Bottom;}
        | public static System.Collections.Generic.List<string> List(){
        |  var res=new System.Collections.Generic.List<string>();
        |  EnumWindows((h,l)=>{
        |   if(!IsWindowVisible(h))return true;
        |   int len=GetWindowTextLength(h); if(len<1)return true;
        |   var sb=new StringBuilder(len+1); GetWindowText(h,sb,sb.Capacity);
        |   RECT r; GetWindowRect(h,out r); uint pid; GetWindowThreadProcessId(h,out pid);
        |   res.Add(pid+"\t"+sb.ToString()+"\t"+r.Left+","+r.Top+" "+(r.Right-r.Left)+"x"+(r.Bottom-r.Top));
        |   return true;},IntPtr.Zero);
        |  return res;}}
        |'@
        |Add-Type -TypeDefinition %%sig
        |Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
        |%%seen=@{}
        |foreach(%%row in [Win32]::List()){
        |  %%p=%%row.Split("`t")
        |  if(%%p.Count -lt 3){continue}
        |  %%proc=try{(Get-Process -Id %%p[0] -ErrorAction Stop).ProcessName}catch{'?'}
        |  %%key=%%p[0]+'|'+%%p[1]; if(%%seen.ContainsKey(%%key)){continue}; %%seen[%%key]=1
        |  "{0,-8} {1,-18} {2,-22} {3}" -f %%p[0],%%proc,%%p[2],%%p[1]
        |}
        """.trimMargin())

    /**
     * 某个窗口的控件树。每行：缩进 + 控件类型 + 名称 + 矩形 + 是否已离屏。
     *
     * 限深限量是刻意的：Electron 应用的树能长到几万节点，一次调用就能把上下文烧光
     * （同一个病根见缺口清单 S4）。
     */
    fun uiTree(title: String, maxDepth: Int, maxNodes: Int): String = PREAMBLE + ps("""
        |Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
        |%%want='${esc(title)}'
        |%%cond=[System.Windows.Automation.Condition]::TrueCondition
        |%%root=[System.Windows.Automation.AutomationElement]::RootElement
        |%%wins=%%root.FindAll([System.Windows.Automation.TreeScope]::Children,%%cond)
        |%%hit=%%null
        |foreach(%%w in %%wins){ if(%%w.Current.Name -like "*%%want*"){%%hit=%%w;break} }
        |if(%%hit -eq %%null){"找不到标题含「%%want」的窗口（先 screen windows 看看）";exit}
        |%%n=0;%%max=[Math]::Min(${maxNodes.coerceIn(10, 400)},400);%%dmax=${maxDepth.coerceIn(1, 8)}
        |function Walk(%%e,%%d){ if(%%script:n -ge %%max){return}
        |  if(%%d -gt %%dmax){return}
        |  foreach(%%c in %%e.FindAll([System.Windows.Automation.TreeScope]::Children,%%cond)){
        |    if(%%script:n -ge %%max){return}
        |    %%script:n++
        |    %%cu=%%c.Current; %%r=%%cu.BoundingRectangle
        |    %%rect='-'
        |    if(%%r.Width -gt 0){%%rect='{0},{1} {2}x{3}' -f [int]%%r.X,[int]%%r.Y,[int]%%r.Width,[int]%%r.Height}
        |    %%nm=%%cu.Name; if(%%nm.Length -gt 60){%%nm=%%nm.Substring(0,60)+'…'}
        |    %%off=if(%%cu.IsOffscreen){' (离屏)'}else{''}
        |    ('{0}{1} {2} {3}{4}' -f ('  '*%%d),%%cu.ControlType.ProgrammaticName.Replace('ControlType.',''),%%nm,%%rect,%%off)
        |    Walk %%c (%%d+1)
        |  }
        |}
        |"窗口：%%(%%hit.Current.Name)"
        |Walk %%hit 1
        |"（共 %%n 个节点，上限 %%max；要更深用 max_depth）"
        """.trimMargin())

    /**
     * 真点击（SetCursorPos + mouse_event），支持左/右与单击/双击。
     *
     * **先确认光标真的到了那里，才按下去。** 这不是多余的啰嗦：实测第一次点
     * `-1613,810`（左半屏的空白桌面）时脚本回了一句"已点击"，可事后单独读光标
     * 却停在别处 —— 因为 `SetCursorPos` 用的是**调用进程自己的**虚拟桌面坐标系，
     * 而进程是否 DPI-aware 会改变这套坐标的缩放。所以 C# 里做的是：
     * SetCursorPos → GetCursorPos 回读 → 不一致就**不点**，并把 DPI 和虚拟桌面报出来。
     * 报"已点击"而实际没点到，比不点危险得多（模型会以为界面已经变了，接着往下推）。
     *
     * 为什么自己 P/Invoke 而不是 `[Windows.Forms.Cursor]::Position`：
     * 实测 `Add-Type -TypeDefinition` 编译内联 C# 时默认不引用 System.Windows.Forms，
     * 代码里写 `Cursor.Position` 会报 SOURCE_CODE_ERROR（第一版就栽在这）。
     * user32 那几个函数只需要 `System.Runtime.InteropServices`，与已经跑通的
     * [windows] 那份签名同一套依赖 —— 少一个"这台机器上也许没有"。
     *
     * **第二道闸**：脚本默认只报坐标不点，必须 `HAOAI_ALLOW_CLICK=1` 才真按下去。
     * 理由不是不信任模型，而是"点错一个坐标"在这台机器上不可撤销 ——
     * 它落在哪个窗口上取决于此刻谁在最前面，而这取决于模型看不见的东西。
     * 实验特性开关（desktop_control）+ 权限闸（每次问）+ 这道环境变量闸，
     * 三层里任何一层没打开都不会真点。
     */
    fun click(x: Int, y: Int, right: Boolean = false, double: Boolean = false): String =
        ps(clickTemplate(x, y, right, double))

    /** 先当普通字符串拼，再统一 replace：`ps()` 里不含 `${'$'}{…}`，所以不会二次替换。 */
    private fun clickTemplate(x: Int, y: Int, right: Boolean, double: Boolean): String = PREAMBLE + """
        |${mousePrelude()}
        |%%b=[System.Windows.Forms.SystemInformation]::VirtualScreen
        |if(%%env:HAOAI_ALLOW_CLICK -eq '1'){
        |  %%r=[Mouse]::Click(${x},${y},${if (right) "%%true" else "%%false"},${if (double) "%%true" else "%%false"})
        |  "%%r（虚拟桌面 %%(%%b.X),%%(%%b.Y) %%(%%b.Width)x%%(%%b.Height)）"
        |} else {
        |  "已定位到 ${x},${y}，但没真点：这是不可撤销的操作，需要环境变量 HAOAI_ALLOW_CLICK=1 才执行" +
        |    "（虚拟桌面 %%(%%b.X),%%(%%b.Y) %%(%%b.Width)x%%(%%b.Height)）"
        |}
        """.trimMargin()

    /** 鼠标那一份 C# 声明，click 与 invoke 共用（各自在自己的 PowerShell 进程里编译）。 */
    private fun mousePrelude() = """
        |%%sig=@'
        |using System;using System.Runtime.InteropServices;
        |public class Mouse{
        | [StructLayout(LayoutKind.Sequential)]public struct P{public int X;public int Y;}
        | [DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);
        | [DllImport("user32.dll")]public static extern bool GetCursorPos(out P p);
        | [DllImport("user32.dll")]public static extern uint GetDpiForSystem();
        | [DllImport("user32.dll")]public static extern void mouse_event(uint f,uint dx,uint dy,uint d,IntPtr e);
        | const uint DOWN=0x02u,UP=0x04u,RDOWN=0x08u,RUP=0x10u;
        | static int CX(){P p;GetCursorPos(out p);return p.X;}
        | static int CY(){P p;GetCursorPos(out p);return p.Y;}
        | public static string Click(int x,int y,bool right,bool dbl){
        |  uint dpi=GetDpiForSystem();
        |  bool moved=SetCursorPos(x,y);
        |  System.Threading.Thread.Sleep(120);
        |  int ax=CX(),ay=CY();
        |  string where="请求 "+x+","+y+"；光标实际 "+ax+","+ay+"；系统 DPI "+dpi;
        |  if(!moved||ax!=x||ay!=y)
        |   return "× 没有点击。"+where+"。SetCursorPos 走的是本进程的虚拟桌面坐标，" +
        |     "DPI 不为 96 时缩放会让同一个数字落到别处 —— 拿 screen windows 的矩形直接点会点偏。";
        |  uint d=right?RDOWN:DOWN,u=right?RUP:UP;
        |  mouse_event(d,0,0,0,IntPtr.Zero);System.Threading.Thread.Sleep(40);mouse_event(u,0,0,0,IntPtr.Zero);
        |  if(dbl){System.Threading.Thread.Sleep(60);
        |   mouse_event(d,0,0,0,IntPtr.Zero);System.Threading.Thread.Sleep(40);mouse_event(u,0,0,0,IntPtr.Zero);}
        |  return "OK 已在 "+x+","+y+" "+(right?"右":"左")+"键"+(dbl?"双击":"单击")+"。"+where;}}
        |'@
        |Add-Type -TypeDefinition %%sig
        """.trimMargin()

    /**
     * 按控件名字点 —— 模型不用自己猜坐标，坐标从 UIA 的矩形里算。
     *
     * 为什么不用 UIA 的 InvokePattern：**实测拿不到**。PowerShell 里
     * `[System.Windows.Automation.PatternIdentifiers]::InvokePattern` 静默返回 `$null`
     * （类名不对时 PS 不报错），换 `Invoke.InvokePatternIdentifiers` 也仍然 NULL，
     * 于是 `GetCurrentPattern($null)` 抛"值不能为 null"；`PropertyCondition` 那条路
     * 也被 PowerShell 的类型转换堵死（NameProperty 被当成 DependencyProperty）。
     * 与其跟适配器较劲，不如用已经实测能跑的两件：UIA 找元素拿矩形 + 真鼠标点中心。
     * 代价是会移动光标（可观察、可撤销），收益是不依赖那些拿不到的模式。
     */
    fun invoke(title: String, name: String): String = PREAMBLE + ps("""
        |Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
        |${mousePrelude()}
        |%%want='${esc(title)}';%%what='${esc(name)}'
        |%%anyCond=[System.Windows.Automation.Condition]::TrueCondition
        |%%root=[System.Windows.Automation.AutomationElement]::RootElement
        |%%hit=%%null
        |foreach(%%w in %%root.FindAll([System.Windows.Automation.TreeScope]::Children,%%anyCond)){
        |  if(%%w.Current.Name -like "*%%want*"){%%hit=%%w;break} }
        |if(%%hit -eq %%null){"找不到标题含「%%want」的窗口（先 screen windows 看看）";exit}
        |%%all=%%hit.FindAll([System.Windows.Automation.TreeScope]::Descendants,%%anyCond)
        |if(%%all.Count -gt 4000){"窗口「%%want」有 %%(%%all.Count) 个控件，太多：先 screen ui 缩小范围";exit}
        |%%el=%%null
        |foreach(%%c in %%all){ if(%%c.Current.Name -eq %%what){%%el=%%c;break} }
        |if(%%el -eq %%null){foreach(%%c in %%all){ if(%%c.Current.Name -like "*%%what*"){%%el=%%c;break} }}
        |if(%%el -eq %%null){"窗口「%%want」的 %%(%%all.Count) 个控件里没有名为「%%what」的（先 screen ui 看树拿名字）";exit}
        |%%r=%%el.Current.BoundingRectangle
        |if(%%r.Width -lt 1 -or %%r.Height -lt 1){
        |  "控件「%%(%%el.Current.Name)」没有可见矩形（多半在后台/折叠了），不能点";exit }
        |%%cx=[int](%%r.X+%%r.Width/2);%%cy=[int](%%r.Y+%%r.Height/2)
        |%%ct=%%el.Current.ControlType.ProgrammaticName.Replace('ControlType.','')
        |%%rect='{0},{1} {2}x{3}' -f [int]%%r.X,[int]%%r.Y,[int]%%r.Width,[int]%%r.Height
        |%%who=%%ct+' 「'+%%el.Current.Name+'」 矩形 '+%%rect
        |if(%%env:HAOAI_ALLOW_CLICK -ne '1'){
        |  "已定位到 %%who 的中心 %%(%%cx),%%(%%cy)，但没真点：需要环境变量 HAOAI_ALLOW_CLICK=1 才执行";exit }
        |%%m=[Mouse]::Click(%%cx,%%cy,%%false,%%false)
        |"%%m（目标是 %%who）"
        """.trimMargin())

    /**
     * 共享片段：编译出 `[Fc]::Now()`，报告此刻的前台窗口。
     *
     * 注意这里**不能**用 `$true`/`$false` 当变量名去赋值 —— PowerShell 的这两个是只读
     * 自动变量，`$true = ...` 直接报"无法覆盖变量 True"（invoke 的第一版就这么死的）。
     */
    private fun focusPrelude() = """
        |%%sig=@'
        |using System;using System.Runtime.InteropServices;using System.Text;
        |public class Fc{
        | [DllImport("user32.dll")]public static extern IntPtr GetForegroundWindow();
        | [DllImport("user32.dll")]public static extern int GetWindowText(IntPtr h,StringBuilder s,int n);
        | [DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint pid);
        | public static string Now(){
        |  IntPtr h=GetForegroundWindow(); if(h==IntPtr.Zero)return "$NO_FOCUS";
        |  var sb=new StringBuilder(512); GetWindowText(h,sb,sb.Capacity);
        |  uint pid; GetWindowThreadProcessId(h,out pid);
        |  return pid+" "+sb.ToString();}}
        |'@
        |Add-Type -TypeDefinition %%sig
        """.trimMargin()

    /**
     * 当前真正获得键盘焦点的窗口（pid + 标题），没有前台窗口时返回 [NO_FOCUS]。
     *
     * 为什么要单独问一次：`type`/`key` 是"发给此刻在前台的那个东西"，
     * 而"此刻谁在前台"恰好是模型看不见、人也容易看错的。审批卡上必须写出这个，
     * 不然"允许键入 12 个字符"这句话是没有意义的。
     */
    fun focus(): String = PREAMBLE + ps(focusPrelude() + "\n[Fc]::Now()")

    /** 没有前台窗口时 [Fc]::Now() 的返回值。两处必须一致，所以写成一份常量。 */
    private const val NO_FOCUS = "(无前台窗口)"

    /**
     * 输入类动作的统一收尾：先记接收方，再发，再报告。
     *
     * "没有前台窗口"必须单独说一句 —— 实测这台机器就出现过整段时间
     * `GetForegroundWindow` 返回 NULL 的状态，那时 SendInput 照样"全部接收"
     * （返回 32 个事件），但没有任何应用收到字。只报"已键入 N 个字符"
     * 就是假绿：模型会以为界面变了，接着往下推。
     */
    private fun sendAndReport(sendLine: String, okLine: String, prelude: String = ""): String =
        PREAMBLE + ps(
            focusPrelude() + (if (prelude.isEmpty()) "" else "\n$prelude") +
                "\n%%to=[Fc]::Now()" +
                "\n%%r=$sendLine" +
                "\nif(%%to -eq '$NO_FOCUS'){" +
                "\n  \"× 没有前台窗口，这串输入没有接收方，多半已被系统丢弃。" +
                "先 screen click 目标窗口（或让用户点一下），再输入。\"" +
                "\n} else {" +
                "\n  \"$okLine\"" +
                "\n}"
        )

    /**
     * 键入文本 —— 用 SendInput 的 KEYEVENTF_UNICODE 直发字符，**不走 SendKeys**。
     *
     * 为什么换：实测这台机器开着中文输入法时，SendKeys 发的拉丁字母会被输入法吃掉：
     * `HaoAI键入测试` 只落进"键入测试"，`(x)` 被当成拼音候选变成了"（行）"，
     * `^a` 触发的是输入法的中英切换而不是全选。SendKeys 走的是"虚拟按键码"，
     * 中间隔着键盘布局 + IME；而 KEYEVENTF_UNICODE 发的是 VK_PACKET + UTF-16 码元，
     * IME 不合成它，应用直接收到 WM_CHAR。
     *
     * 附带两个好处：不再需要 `{}` 转义（`100%` 就是 `100%`），
     * 而且能输入任何 Unicode 字符（包括 emoji，按码元逐个发即可）。
     *
     * **`SendInput` 的返回值不是"送达"的证明**，这条是实测出来的：
     * 第一版把 `KEYEVENTF_UNICODE` 写成 0x20（正确值 0x4），18 个字符 →
     * 系统照样回报"36 个事件全部接收"，记事本里一个字都没有；
     * 同一时刻同一窗口用旧的 SendKeys 发 `HaoAI` 立刻进了标题。
     * 所以这里报的是"入队"而不是"送达"，并且**每次改动都要用真窗口对照验一次**。
     */
    fun typeText(t: String): String = sendAndReport(
        "[Kbd]::Type('${esc(t)}')",
        "已键入 ${t.length} 个字符（入队 %%(%%r) 个按键事件；入队不等于送达，要验效果看界面），" +
            "发给了：%%to",
        prelude = KBD_PRELUDE
    )

    /**
     * 组合键仍用 SendKeys：`^s`、`%{F4}` 这类"按下修饰键+另一个键"是它的语法，
     * Unicode 直发替代不了。
     *
     * 已知坑要写在这里，因为工具改不了它：**中文输入法会截走一部分组合键**
     * （实测 `^a` 变成输入法的中英切换）。所以别用 key 去发裸字母组合，
     * 或者先让用户把输入法切到英文。
     *
     * 刻意不把 `k` 回显进 PowerShell 的双引号串里 —— 那是模型可控字符串，
     * 一个 `"` 或 `$` 就能改写这条命令（单引号串里的 `''` 转义救不了双引号串）。
     * "按的是什么"由工具自己的审批标题与返回文案负责说清。
     */
    fun keys(k: String): String = sendAndReport(
        "[System.Windows.Forms.SendKeys]::SendWait('${esc(k)}')",
        "已按下按键（语法原样传给 SendKeys），发给了：%%to"
    )

    /** Unicode 直发的那份 C# 声明。`cbSize` 用 Marshal.SizeOf 现算，不写死 32/40。 */
    private val KBD_PRELUDE = """
        |%%ksig=@'
        |using System;using System.Runtime.InteropServices;
        |using System.Collections.Generic;
        |public class Kbd{
        | [StructLayout(LayoutKind.Sequential)]struct KI{public ushort vk;public ushort scan;
        |   public uint flags;public uint time;public IntPtr extra;}
        | [StructLayout(LayoutKind.Sequential)]struct MI{public int dx;public int dy;
        |   public uint data;public uint flags;public uint time;public IntPtr extra;}
        | [StructLayout(LayoutKind.Explicit)]struct U{[FieldOffset(0)]public KI k;[FieldOffset(0)]public MI m;}
        | [StructLayout(LayoutKind.Sequential)]struct IN{public uint type;public U u;}
        | [DllImport("user32.dll",SetLastError=true)]static extern uint SendInput(uint n,IN[] v,int cb);
        | const uint UNICODE=0x4u,KEYUP=0x0002u;   // KEYEVENTF_UNICODE 是 0x4，不是 0x20
        | public static string Type(string s){
        |   var list=new List<IN>();
        |   foreach(char c in s){
        |     var a=new IN(); a.type=1; a.u.k.vk=0; a.u.k.scan=(ushort)c;
        |     a.u.k.flags=UNICODE; a.u.k.time=0; a.u.k.extra=IntPtr.Zero;
        |     var b=a; b.u.k.flags=UNICODE|KEYUP;
        |     list.Add(a); list.Add(b); }
        |   var arr=list.ToArray();
        |   uint sent=SendInput((uint)arr.Length,arr,Marshal.SizeOf(typeof(IN)));
        |   if(sent==0){int e=Marshal.GetLastWin32Error();return "0（SendInput 失败 win32="+e+"）";}
        |   return sent.ToString();}}
        |'@
        |Add-Type -TypeDefinition %%ksig
        """.trimMargin()

    /** PowerShell 单引号串里唯一要处理的是单引号本身（翻倍）。 */
    private fun esc(s: String): String = s.replace("'", "''")
}

/** PowerShell 执行器：写临时 .ps1 → 跑 → 拿合并输出。与 shell 工具同一套 Windows 处理。 */
object PsRunner {
    fun run(script: String, timeoutSec: Int = 90): String {
        val exe = ShellLauncher.forName("pwsh") ?: ShellLauncher.forName("powershell")
            ?: return "这台机器上没有 PowerShell（不可能，但代码要写全）"
        val f = File.createTempFile("haoai-ps-", ".ps1", File(System.getProperty("java.io.tmpdir")))
        // Windows PowerShell 5.1 读 .ps1 时，没有 BOM 就按 ANSI(GBK) 解 ——
        // 脚本里任何一个中文字符串都会因此变成非法字节并让整段解析失败（实测：
        // "找不到标题含…" 这类提示行直接把脚本炸掉）。带 BOM 才按 UTF-8 读。
        f.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + script.toByteArray(Charsets.UTF_8))
        return try {
            val p = ProcessBuilder(listOf(exe.first) + exe.second + f.absolutePath)
                .redirectErrorStream(true).start()
            val out = StringBuilder()
            val t = Thread { p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { out.append(it).append('\n') } }
            t.isDaemon = true; t.start()
            if (!p.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return "PowerShell 超时 ${timeoutSec}s"
            }
            t.join(2000)
            val code = p.exitValue()
            if (code == 0) out.toString().trimEnd() else "exit=$code\n$out"
        } catch (e: Exception) {
            "执行失败：${e.message}"
        } finally {
            runCatching { f.delete() }
        }
    }
}
