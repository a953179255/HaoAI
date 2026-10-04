// HaoAI PC 客户端壳：真窗口 + 托盘 + 引擎托管。
//
// 为什么这个壳这么薄：整个界面就是引擎（本地 Kotlin 服务）吐出来的 index.html，
// 壳只负责三件事——① 给它一个自己的窗口（进程名/图标/标题栏都是我们的，不再借浏览器）；
// ② 托盘常驻（关窗最小化，引擎照跑——守护模式的地基）；③ 把引擎进程拉起来、看住它。
// 引擎的发现与拉起：读状态根里的 webport → HTTP 探活 → 活着就_attach（用户的 bat 实例也算），
// 全都不活才 spawn 自己的 engine\HaoAI-PC.exe serve（无窗口，日志落 logs\engine.log）。
using Microsoft.Web.WebView2.Core;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using Wv2 = Microsoft.Web.WebView2.WinForms.WebView2;

internal static class Program
{
    [STAThread]
    private static int Main()
    {
        // 码页编码器（GBK 等）在 .NET Core+ 是可选件：不注册，Encoding.GetEncoding(936)
        // 直接抛 NotSupportedException——引擎日志解码就死在这（无声秒退排障半天）
        System.Text.Encoding.RegisterProvider(System.Text.CodePagesEncodingProvider.Instance);
        using var mutex = new Mutex(true, "HaoAI-PC.Shell.SingleInstance", out var isNew);
        if (!isNew) { Native.ActivateExisting(); return 0; }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        // 兜底弹窗：壳要是崩了必须说出原因——无声闪退是排障黑洞（本机排过一次，事件日志才见真相）
        try { Application.Run(new MainShell()); }
        catch (Exception ex)
        {
            MessageBox.Show(ex.ToString(), "HaoAI PC 启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
        return 0;
    }
}

internal sealed class MainShell : Form
{
    private NotifyIcon _tray;
    private ToolStripMenuItem _autostartItem;
    private Wv2 _web;
    private Process _engine;              // 壳自己拉起的引擎；attach 来的不归我们杀
    private bool _attached;               // true=连的是已在跑的实例（退出时不碰它）
    private bool _exitRequested;          // 托盘「退出」置位后 FormClosing 不再转最小化
    private bool _balloonShown;
    private string _url;

    // ---- 无边框：标题栏由**网页自己**画（index.html #titlebar + app-region:drag），
    //      和 ZCode/Octop 同构——顶栏就是功能栏，不另画一条 Windows caption。
    private bool _maximized;
    private Rectangle _restoreBounds;
    private Label _status;                // 启动全程可见的状态层：冷启动那几秒不能是一块哑黑
    private bool _pageReady;

    public MainShell()
    {
        Text = "HaoAI PC";
        Icon = new Icon(Path.Combine(AppContext.BaseDirectory, "app.ico"));
        StartPosition = FormStartPosition.Manual;
        FormBorderStyle = FormBorderStyle.None;   // 边框自绘；命中测试在 WndProc 里还给系统
        BackColor = Color.FromArgb(14, 21, 17);   // 深色底：WebView2 就绪前不该有任何白
        ClientSize = new Size(1440, 900);
        MinimumSize = new Size(960, 600);
        var wa = Screen.PrimaryScreen.WorkingArea;
        Location = new Point(Math.Max(0, (wa.Width - 1440) / 2), Math.Max(0, (wa.Height - 900) / 2));

        // 启动状态层：从窗体出现的第一帧就有字，"深色空窗=坏了"的误会不能再有
        _status = new Label
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(14, 21, 17),
            ForeColor = Color.FromArgb(159, 176, 168),
            Font = new Font("Microsoft YaHei UI", 12f),
            TextAlign = ContentAlignment.MiddleCenter,
            Text = "正在启动 HaoAI…"
        };
        Controls.Add(_status);
        _status.BringToFront();

        BuildTray();
        BuildWeb().ConfigureAwait(false);
    }

    private void SetStatus(string text)
    {
        if (_status == null || _status.IsDisposed) return;
        _status.Text = text;
        _status.Visible = true;
        _status.BringToFront();
    }

    private void HideStatus()
    {
        if (_status == null || _status.IsDisposed) return;
        _status.Visible = false;
        _web?.BringToFront();
    }

    private void ToggleMax()
    {
        if (_maximized)
        {
            Bounds = _restoreBounds;
            _maximized = false;
        }
        else
        {
            // 无边框窗体直接 Maximized 会盖住任务栏：手动贴工作区
            _restoreBounds = Bounds;
            var wa = Screen.FromControl(this).WorkingArea;
            SetBounds(wa.X, wa.Y, wa.Width, wa.Height);
            _maximized = true;
        }
    }

    protected override void OnResizeEnd(EventArgs e)
    {
        // 用户手动拉尺寸后，恢复态基准跟着走
        if (!_maximized) _restoreBounds = Bounds;
        base.OnResizeEnd(e);
    }

    protected override void WndProc(ref Message m)
    {
        const int WM_NCHITTEST = 0x84;
        // 系统要最大化无边框窗体（网页 app-region 的双击/贴边走这里）：改道 ToggleMax，
        // 否则直接 Maximized 会盖住任务栏
        const int WM_SYSCOMMAND = 0x112;
        const int SC_MAXIMIZE = 0xF030;
        if (m.Msg == WM_SYSCOMMAND && (m.WParam.ToInt64() & 0xFFF0) == SC_MAXIMIZE)
        {
            ToggleMax();
            return;
        }
        if (m.Msg == WM_NCHITTEST)
        {
            base.WndProc(ref m);
            if ((int)m.Result != 1 /*HTCLIENT*/ && (int)m.Result != 0) return;
            var lp = m.LParam.ToInt64();
            var scr = new Point((short)(lp & 0xFFFF), (short)((lp >> 16) & 0xFFFF));
            var p = PointToClient(scr);
            // 边缘 6px = 缩放热区（最大化时不给，系统自己有贴边分屏）。
            // 拖动区不在这管：网页 #titlebar 的 app-region:drag 由 WebView2 NC 支持内部处理。
            if (!_maximized)
            {
                const int G = 6;
                bool l = p.X < G, r = p.X > ClientSize.Width - G, t = p.Y < G, b = p.Y > ClientSize.Height - G;
                if (t && l) { m.Result = (IntPtr)13; return; }   // HTTOPLEFT
                if (t && r) { m.Result = (IntPtr)14; return; }   // HTTOPRIGHT
                if (b && l) { m.Result = (IntPtr)16; return; }   // HTBOTTOMLEFT
                if (b && r) { m.Result = (IntPtr)17; return; }   // HTBOTTOMRIGHT
                if (t) { m.Result = (IntPtr)12; return; }        // HTTOP
                if (b) { m.Result = (IntPtr)15; return; }        // HTBOTTOM
                if (l) { m.Result = (IntPtr)10; return; }        // HTLEFT
                if (r) { m.Result = (IntPtr)11; return; }        // HTRIGHT
            }
            return;
        }
        base.WndProc(ref m);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // 边框没了之后，DWM 给回投影与 Win11 圆角
        var margins = new Native.MARGINS { left = 1, right = 1, top = 1, bottom = 1 };
        Native.DwmExtendFrameIntoClientArea(Handle, ref margins);
        int round = 2;   // DWMWCP_ROUND
        Native.DwmSetWindowAttribute(Handle, 33, ref round, sizeof(int));
    }

    // ---- 托盘 ----

    private void BuildTray()
    {
        _tray = new NotifyIcon
        {
            Icon = Icon,
            Text = "HaoAI PC",
            Visible = true
        };
        _autostartItem = new ToolStripMenuItem("开机自启", null, (_, _) => ToggleAutostart())
        { CheckOnClick = true, Checked = AutostartEnabled() };
        var menu = new ContextMenuStrip();
        menu.Items.Add("打开", null, (_, _) => ShowUp());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_autostartItem);
        menu.Items.Add("退出", null, (_, _) => ReallyExit());
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => ShowUp();
    }

    private void ShowUp()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    private void ReallyExit()
    {
        _exitRequested = true;
        if (_engine is { HasExited: false })
        {
            // 壳拉起的孩子随壳一起走（attach 来的实例是用户自己的，不碰）
            try { _engine.Kill(entireProcessTree: true); } catch { /* 已经死了就算了 */ }
        }
        _tray.Visible = false;
        Application.Exit();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // 点 × = 缩进托盘继续跑（守护模式的落点）；真退出走托盘菜单「退出」。
        if (!_exitRequested && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            if (!_balloonShown)
            {
                _balloonShown = true;
                _tray.BalloonTipTitle = "HaoAI PC 还在运行";
                _tray.BalloonTipText = "已最小化到托盘，任务照常跑。托盘图标右键可退出。";
                _tray.ShowBalloonTip(2500);
            }
            return;
        }
        _web?.Dispose();
        _tray.Visible = false;
        base.OnFormClosing(e);
    }

    // ---- WebView ----

    private async Task BuildWeb()
    {
        _web = new Wv2 { Dock = DockStyle.Top, Height = ClientSize.Height };
        _web.Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top | AnchorStyles.Bottom;
        Controls.Add(_web);
        _web.BringToFront();
        SetStatus("正在初始化渲染引擎…");
        try
        {
            var dataDir = Path.Combine(ShellPaths.StateRoot, "Client", "WebView2");
            Directory.CreateDirectory(dataDir);
            // 僵尸 webview 清扫：壳被强杀时它的 msedgewebview2 子进程会活着占住
            // 用户数据目录，下一次启动 CreateAsync 就「目录已在使用」→ 白屏
            // （2026-10-05 实测 18 只僵尸）。只认命令行里带我们数据目录的，不误伤别人。
            CoreWebView2Environment env = null;
            for (var attempt = 1; attempt <= 3; attempt++)
            {
                KillStrayWebviews(dataDir);
                try { env = await CoreWebView2Environment.CreateAsync(null, dataDir); break; }
                catch (Exception ex) when (attempt < 3)
                {
                    AppendLog($"WebView2 环境第 {attempt}/3 次创建失败：{ex.Message}");
                    await Task.Delay(800);
                }
            }
            await _web.EnsureCoreWebView2Async(env);
            // NC 区域支持：网页里的 app-region:drag（index.html #titlebar）就是窗口拖动区，
            // 双击/拖到屏幕边由系统接管——与 Electron 的 -webkit-app-region 同机制
            _web.CoreWebView2.Settings.IsNonClientRegionSupportEnabled = true;
            _web.CoreWebView2.NewWindowRequested += (_, e) =>
            {
                // 站内不开新窗；外部链接交给系统默认浏览器
                e.Handled = true;
                try { Process.Start(new ProcessStartInfo(e.Uri) { UseShellExecute = true }); } catch { }
            };
            // 每个文档创建前注入：亮出网页标题栏（浏览器里没有这个标记，标题栏保持隐藏）
            await _web.CoreWebView2.AddScriptToExecuteOnDocumentCreatedAsync("window.__haoaiShell=true;");
            _web.CoreWebView2.WebMessageReceived += (_, e) =>
            {
                switch (e.TryGetWebMessageAsString())
                {
                    case "win:min": WindowState = FormWindowState.Minimized; break;
                    case "win:max": ToggleMax(); break;
                    case "win:close": Close(); break;   // 走 OnFormClosing：缩托盘
                }
            };
            SetStatus("正在连接 HaoAI 引擎…");
            _url = await ResolveEngineAsync();
            SetStatus("正在打开界面…");
            _web.CoreWebView2.NavigationCompleted += (_, _) => { if (_url != null) HideStatus(); };
            _web.CoreWebView2.Navigate(_url);
        }
        catch (Exception ex)
        {
            // 启动失败必须说出原因：WebView2 起来了画网页错误页，没起来就打在状态层上
            var reason = ex.Message;
            if (_web.CoreWebView2 != null)
                NavigateToString(_web, "<html><meta charset=\"utf-8\"><body style=\"background:#0e1511;color:#f27d72;"
                    + "font-family:system-ui,'Microsoft YaHei';padding:40px\"><h3>HaoAI 启动失败</h3><pre style=\"white-space:pre-wrap\">"
                    + System.Net.WebUtility.HtmlEncode(reason) + "</pre><p style=\"color:#9fb0a8\">日志：" + System.Net.WebUtility.HtmlEncode(ShellPaths.EngineLog) + "</p></body></html>");
            else
            {
                _web.Visible = false;
                SetStatus("HaoAI 启动失败：" + reason + "（日志：" + ShellPaths.EngineLog + "）");
                _status.ForeColor = Color.FromArgb(242, 125, 114);
            }
        }
    }

    /** 杀掉命令行里带着**我们**用户数据目录的 msedgewebview2 残留——
     *  只按数据目录路径认领，别的应用（包括 ZCode 自己）的 webview 一个不碰。 */
    private static void KillStrayWebviews(string dataDir)
    {
        try
        {
            var marker = dataDir.TrimEnd('\\').ToLowerInvariant();
            foreach (var p in Process.GetProcessesByName("msedgewebview2"))
            {
                try
                {
                    using var q = new System.Management.ManagementObjectSearcher(
                        $"SELECT CommandLine FROM Win32_Process WHERE ProcessId={p.Id}");
                    var cmd = (q.Get().OfType<System.Management.ManagementObject>()
                        .FirstOrDefault()?["CommandLine"] as string) ?? "";
                    if (cmd.ToLowerInvariant().Contains(marker)) p.Kill();
                }
                catch { /* 查不到命令行的（权限/已退出）跳过 */ }
                finally { p.Dispose(); }
            }
        }
        catch { /* 清扫失败不拦启动——下一轮重试还会再扫 */ }
    }

    // ---- 引擎发现与拉起 ----

    /** 活着的引擎地址；找不着就自己拉一个。顺序：状态根登记的 webport → 默认 8712 → 自起。 */
    private async Task<string> ResolveEngineAsync()
    {
        foreach (var port in CandidatePorts())
        {
            if (await Alive(port)) return $"http://127.0.0.1:{port}/";
        }
        NavigateToString(_web, StartingPage());
        SetStatus("正在启动 HaoAI 引擎（首次约需几秒）…");
        var spawned = await SpawnEngine();
        SetStatus("等待引擎就绪…");
        var deadline = Environment.TickCount64 + 20_000;
        while (Environment.TickCount64 < deadline)
        {
            await Task.Delay(350);
            if (await Alive(spawned)) return $"http://127.0.0.1:{spawned}/";
            if (_engine is { HasExited: true })
                throw new InvalidOperationException(
                    $"引擎进程退出了（ExitCode={_engine.ExitCode}），日志见 " + ShellPaths.EngineLog);
        }
        throw new InvalidOperationException("引擎 20 秒内没有就绪，日志见 " + ShellPaths.EngineLog);
    }

    private static IEnumerable<int> CandidatePorts()
    {
        var p = ShellPaths.DiscoveredPort();
        if (p is int v) yield return v;
        yield return 8712;   // 双击引擎 exe 的老习惯端口
    }

    private static async Task<bool> Alive(int port)
    {
        try
        {
            using var c = new HttpClient { Timeout = TimeSpan.FromMilliseconds(1500) };
            using var r = await c.GetAsync($"http://127.0.0.1:{port}/api/state");
            return r.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    private async Task<int> SpawnEngine()
    {
        // 端口要真空闲：先占住一个再还回去，中间有竞态也只是极小概率撞上重试一次的事
        var l = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        l.Start();
        int port = ((System.Net.IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();

        var exe = Path.Combine(AppContext.BaseDirectory, "engine", "HaoAI-PC.exe");
        if (!File.Exists(exe))
            throw new InvalidOperationException("找不到引擎 " + exe + "（客户端目录里要有 engine\\ 子目录）");
        Directory.CreateDirectory(ShellPaths.LogDir);
        // 日志单文件超 5MB 就截断重写：排障要最近的，不要无限膨胀
        if (File.Exists(ShellPaths.EngineLog) && new FileInfo(ShellPaths.EngineLog).Length > 5_000_000)
            File.Delete(ShellPaths.EngineLog);
        using (var sw = new StreamWriter(File.Open(ShellPaths.EngineLog, FileMode.Append, FileAccess.Write)))
            sw.WriteLine($"---- {DateTime.Now:yyyy-MM-dd HH:mm:ss} 壳拉起引擎 :{port} ----");

        // **拉起要重试**：装配刚拷完 60MB 引擎，Defender 常锁着新 exe 扫描，
        // Process.Start 会撞分享冲突——2026-10-05 三连秒死全是它（手动跑又都正常，
        // 因为那时扫描早结束了）。800ms × 4 次基本覆盖扫描窗口。
        Exception last = null;
        for (var attempt = 1; attempt <= 4; attempt++)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = exe,
                    Arguments = $"serve --port {port}",
                    UseShellExecute = false,
                    CreateNoWindow = true,                 // 不再弹那个 CMD 黑窗
                    WorkingDirectory = Path.GetDirectoryName(exe)!
                };
                psi.RedirectStandardOutput = true;
                psi.RedirectStandardError = true;
                // 引擎被管道接管时按 JEP 400 走**本机编码**（中文 Windows = GBK，不是 UTF-8——
                // 实测按 UTF-8 读全是 U+FFFD）。按 936 解码后再落 UTF-8 日志文件。
                psi.StandardOutputEncoding = Encoding.GetEncoding(936);
                psi.StandardErrorEncoding = Encoding.GetEncoding(936);
                var p = Process.Start(psi)!;
                p.OutputDataReceived += (_, e) => AppendLog(e.Data);
                p.ErrorDataReceived += (_, e) => AppendLog(e.Data);
                // 引擎要是悄悄退了，把退出码钉在日志上——0=代码路径自己退，负数=被系统/人杀
                p.Exited += (_, _) => AppendLog($"!!!! 引擎退出 HasExited={p.HasExited} ExitCode={p.ExitCode}");
                p.EnableRaisingEvents = true;
                p.BeginOutputReadLine();
                p.BeginErrorReadLine();
                _engine = p;
                return port;
            }
            catch (Exception ex)
            {
                last = ex;
                AppendLog($"第 {attempt}/4 次拉起失败：{ex.Message}");
                await Task.Delay(800);
            }
        }
        throw new InvalidOperationException("引擎连续 4 次拉起失败：" + last?.Message, last);
    }

    private static void AppendLog(string line)
    {
        if (string.IsNullOrEmpty(line)) return;
        try { File.AppendAllText(ShellPaths.EngineLog, line + Environment.NewLine); }
        catch { /* 日志写不进去不该拖垮壳 */ }
    }

    private static string StartingPage() => """
<!doctype html><meta charset="utf-8">
<title>HaoAI PC</title>
<body style="margin:0;height:100vh;display:grid;place-items:center;background:#0e1511;color:#9fb0a8;font-family:system-ui,'Microsoft YaHei',sans-serif;app-region:drag">
<div style="text-align:center">
<div style="margin:0 auto 14px;width:44px;height:44px;border-radius:12px;background:#2fbd7f;display:grid;place-items:center">
<svg viewBox="0 0 24 24" width="26" height="26"><path d="M9 6v12M15 6v12M9 12h6" stroke="#fff" stroke-width="2.4" stroke-linecap="round" fill="none"/></svg></div>
正在启动 HaoAI 引擎…</div></body>
""";

    private static void NavigateToString(Wv2 w, string html)
    {
        try { w.CoreWebView2?.NavigateToString(html); } catch { /* 还没初始化就跳过 */ }
    }

    // ---- 开机自启（HKCU Run，用户级，不要管理员） ----

    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValue = "HaoAI-PC";

    private static bool AutostartEnabled()
    {
        using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(RunKey);
        return k?.GetValue(RunValue) is string v && v.Contains(AppContext.BaseDirectory.TrimEnd('\\'));
    }

    private void ToggleAutostart()
    {
        using var k = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(RunKey);
        if (_autostartItem.Checked)
            k.SetValue(RunValue, $"\"{Process.GetCurrentProcess().MainModule!.FileName}\"");
        else
            k.DeleteValue(RunValue, throwOnMissingValue: false);
    }
}

internal static class ShellPaths
{
    public static string StateRoot => Environment.GetEnvironmentVariable("HAOAI_HOME") is { Length: > 0 } h
        ? h : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HaoAI");
    public static string LogDir => Path.Combine(StateRoot, "Client", "logs");
    public static string EngineLog => Path.Combine(LogDir, "engine.log");

    /** 引擎启动时在状态根留的 webport 文件（Server.kt 写的）；读不到/不合法返回 null。 */
    public static int? DiscoveredPort()
    {
        try
        {
            var t = File.ReadAllText(Path.Combine(StateRoot, "webport")).Trim();
            if (int.TryParse(t, out var v) && v is > 0 and < 65536) return v;
        }
        catch { }
        return null;
    }
}

internal static class Native
{
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumProc cb, IntPtr lp);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("dwmapi.dll")] public static extern int DwmExtendFrameIntoClientArea(IntPtr hWnd, ref MARGINS m);
    [DllImport("dwmapi.dll")] public static extern int DwmSetWindowAttribute(IntPtr hWnd, int attr, ref int val, int size);

    public struct MARGINS { public int left, right, top, bottom; }
    private delegate bool EnumProc(IntPtr hWnd, IntPtr lp);

    /** 二次启动：把已存在的壳窗口拽到前台，而不是再开一个。 */
    public static void ActivateExisting()
    {
        var me = Environment.ProcessId;
        IntPtr found = IntPtr.Zero;
        EnumWindows((h, _) =>
        {
            if (!IsWindowVisible(h)) return true;
            GetWindowThreadProcessId(h, out var pid);
            if (pid == me) { found = h; return false; }
            return true;
        }, IntPtr.Zero);
        if (found != IntPtr.Zero) { ShowWindow(found, 9 /*SW_RESTORE*/); SetForegroundWindow(found); }
    }
}
