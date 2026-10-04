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

    // ---- 自绘标题栏（v6 效果图形态）：应用深色一体延伸到窗口顶，系统原生拖/贴边/圆角保留 ----
    private const int TitleBarH = 38;
    private Panel _tb;
    private Button _btnMin, _btnMax, _btnClose;
    private bool _maximized;
    private Rectangle _restoreBounds;
    private bool _dark = true;

    private static readonly Color TbDark = Color.FromArgb(16, 22, 19);
    private static readonly Color TbDarkHover = Color.FromArgb(34, 42, 38);
    private static readonly Color TbLight = Color.FromArgb(246, 244, 237);
    private static readonly Color TbLightHover = Color.FromArgb(224, 222, 213);
    private static readonly Color CloseHover = Color.FromArgb(196, 43, 28);

    public MainShell()
    {
        Text = "HaoAI PC";
        Icon = new Icon(Path.Combine(AppContext.BaseDirectory, "app.ico"));
        StartPosition = FormStartPosition.Manual;
        FormBorderStyle = FormBorderStyle.None;   // 边框自绘；命中测试在 WndProc 里还给系统
        ClientSize = new Size(1440, 900);
        MinimumSize = new Size(960, 600);
        var wa = Screen.PrimaryScreen.WorkingArea;
        Location = new Point(Math.Max(0, (wa.Width - 1440) / 2), Math.Max(0, (wa.Height - 900) / 2));

        BuildTitlebar();
        BuildTray();
        BuildWeb().ConfigureAwait(false);
    }

    private void BuildTitlebar()
    {
        _tb = new Panel { Dock = DockStyle.Top, Height = TitleBarH };
        ApplyTitlebarTheme();
        // 品牌：小图标 + 名字（对着 v6 效果图；中段留白以后可以放功能件）
        var icon = new PictureBox
        {
            Image = new Icon(Path.Combine(AppContext.BaseDirectory, "app.ico")).ToBitmap(),
            Size = new Size(18, 18),
            Location = new Point(12, (TitleBarH - 18) / 2)
        };
        _tb.Controls.Add(icon);
        _tb.Controls.Add(new Label
        {
            Text = "HaoAI PC",
            ForeColor = _dark ? Color.FromArgb(207, 216, 211) : Color.FromArgb(45, 61, 53),
            Font = new Font("Microsoft YaHei UI", 9.5f),
            Location = new Point(38, (TitleBarH - 22) / 2),
            Size = new Size(120, 22)
        });
        _btnClose = TbButton("✕", (_, _) => Close());
        _btnMax = TbButton("▢", (_, _) => ToggleMax());
        _btnMin = TbButton("―", (_, _) => WindowState = FormWindowState.Minimized);
        _btnClose.Location = new Point(ClientSize.Width - 40, 0);
        _btnMax.Location = new Point(ClientSize.Width - 80, 0);
        _btnMin.Location = new Point(ClientSize.Width - 120, 0);
        _tb.Controls.Add(_btnClose);
        _tb.Controls.Add(_btnMax);
        _tb.Controls.Add(_btnMin);
        _tb.Resize += (_, _) =>
        {
            if (_btnClose.Width == 0) return;
            _btnClose.Left = _tb.Width - 40;
            _btnMax.Left = _tb.Width - 80;
            _btnMin.Left = _tb.Width - 120;
        };
        Controls.Add(_tb);
    }

    private Button TbButton(string glyph, EventHandler onClick)
    {
        var b = new Button
        {
            Text = glyph,
            Size = new Size(40, TitleBarH),
            FlatStyle = FlatStyle.Flat,
            ForeColor = _dark ? Color.FromArgb(159, 176, 168) : Color.FromArgb(93, 111, 102),
            BackgroundImageLayout = ImageLayout.Center,
            TabStop = false
        };
        b.FlatAppearance.BorderSize = 0;
        b.FlatAppearance.MouseOverBackColor = _dark ? TbDarkHover : TbLightHover;
        b.FlatAppearance.MouseDownBackColor = _dark ? TbDarkHover : TbLightHover;
        b.Click += onClick;
        if (b == _btnClose)
        {
            b.FlatAppearance.MouseOverBackColor = CloseHover;
            b.FlatAppearance.MouseDownBackColor = Color.FromArgb(160, 33, 20);
            b.ForeColor = Color.White;
        }
        return b;
    }

    private void ApplyTitlebarTheme()
    {
        _tb.BackColor = _dark ? TbDark : TbLight;
        foreach (Control c in _tb.Controls)
            if (c is Button { } b && b != _btnClose)
            {
                b.ForeColor = _dark ? Color.FromArgb(159, 176, 168) : Color.FromArgb(93, 111, 102);
                b.FlatAppearance.MouseOverBackColor = _dark ? TbDarkHover : TbLightHover;
                b.FlatAppearance.MouseDownBackColor = _dark ? TbDarkHover : TbLightHover;
            }
    }

    private void ToggleMax()
    {
        if (_maximized)
        {
            Bounds = _restoreBounds;
            _maximized = false;
            _btnMax.Text = "▢";
        }
        else
        {
            // 无边框窗体直接 Maximized 会盖住任务栏：手动贴工作区
            _restoreBounds = Bounds;
            var wa = Screen.FromControl(this).WorkingArea;
            SetBounds(wa.X, wa.Y, wa.Width, wa.Height);
            _maximized = true;
            _btnMax.Text = "❐";
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
        if (m.Msg == WM_NCHITTEST)
        {
            base.WndProc(ref m);
            if ((int)m.Result != 1 /*HTCLIENT*/ && (int)m.Result != 0) return;
            var lp = m.LParam.ToInt64();
            var scr = new Point((short)(lp & 0xFFFF), (short)((lp >> 16) & 0xFFFF));
            var p = PointToClient(scr);
            // 边缘 6px = 缩放热区（最大化时不给，系统自己有贴边分屏）
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
            // 标题栏条带 → 拖动/双击最大化/贴边全归系统。**按钮矩形必须还回 HTCLIENT**：
            // 父窗口的 WM_NCHITTEST 先于子控件被问，这里吞了按钮就永远点不到（实测撞过）
            if (p.Y < TitleBarH)
            {
                if (_btnMin.Bounds.Contains(p) || _btnMax.Bounds.Contains(p) || _btnClose.Bounds.Contains(p))
                    m.Result = (IntPtr)1;
                else
                    m.Result = (IntPtr)2;                        // HTCAPTION
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
        _web = new Wv2 { Dock = DockStyle.None, Bounds = new Rectangle(0, TitleBarH, ClientSize.Width, ClientSize.Height - TitleBarH) };
        _web.Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top | AnchorStyles.Bottom;
        Controls.Add(_web);
        try
        {
            var dataDir = Path.Combine(ShellPaths.StateRoot, "Client", "WebView2");
            Directory.CreateDirectory(dataDir);
            var env = await CoreWebView2Environment.CreateAsync(null, dataDir);
            await _web.EnsureCoreWebView2Async(env);
            _web.CoreWebView2.NewWindowRequested += (_, e) =>
            {
                // 站内不开新窗；外部链接交给系统默认浏览器
                e.Handled = true;
                try { Process.Start(new ProcessStartInfo(e.Uri) { UseShellExecute = true }); } catch { }
            };
            // 每个文档创建时就位：把主题色上报给壳（DOM 一加载报一次，之后 data-theme 变化再报）
            await _web.CoreWebView2.AddScriptToExecuteOnDocumentCreatedAsync("""
                (function(){
                  var f=function(){try{chrome.webview.postMessage('theme:'+document.documentElement.getAttribute('data-theme'))}catch(e){}};
                  if(document.readyState!=='loading')f();
                  document.addEventListener('DOMContentLoaded',f);
                  new MutationObserver(f).observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
                })();
                """);
            _web.CoreWebView2.WebMessageReceived += (_, e) =>
            {
                var msg = e.TryGetWebMessageAsString();
                if (msg.StartsWith("theme:"))
                {
                    _dark = !msg.EndsWith("light");
                    ApplyTitlebarTheme();
                }
            };
            _url = await ResolveEngineAsync();
            _web.CoreWebView2.Navigate(_url);
        }
        catch (Exception ex)
        {
            // 引擎起不来不能白屏：把原因与日志位置画进窗口里
            NavigateToString(_web, "<html><meta charset=\"utf-8\"><body style=\"background:#0e1511;color:#f27d72;"
                + "font-family:system-ui,'Microsoft YaHei';padding:40px\"><h3>HaoAI 引擎启动失败</h3><pre style=\"white-space:pre-wrap\">"
                + System.Net.WebUtility.HtmlEncode(ex.Message)
                + "</pre><p style=\"color:#9fb0a8\">日志：" + System.Net.WebUtility.HtmlEncode(ShellPaths.EngineLog) + "</p></body></html>");
        }
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
        var spawned = SpawnEngine();
        var deadline = Environment.TickCount64 + 20_000;
        while (Environment.TickCount64 < deadline)
        {
            await Task.Delay(350);
            if (await Alive(spawned)) return $"http://127.0.0.1:{spawned}/";
            if (_engine is { HasExited: true })
                throw new InvalidOperationException("引擎进程退出了，日志见 " + ShellPaths.EngineLog);
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

    private int SpawnEngine()
    {
        // 端口要真空闲：先占住一个再还回去，中间有竞态也只是极小概率撞上重试一次的事
        var l = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        l.Start();
        int port = ((System.Net.IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();

        var exe = Path.Combine(AppContext.BaseDirectory, "engine", "HaoAI-PC.exe");
        if (!File.Exists(exe))
            throw new InvalidOperationException("找不到引擎 " + exe + "（客户端目录里要有 engine\\ 子目录）");
        var psi = new ProcessStartInfo
        {
            FileName = exe,
            Arguments = $"serve --port {port}",
            UseShellExecute = false,
            CreateNoWindow = true,                 // 不再弹那个 CMD 黑窗
            WorkingDirectory = Path.GetDirectoryName(exe)!
        };
        Directory.CreateDirectory(ShellPaths.LogDir);
        // 日志单文件超 5MB 就截断重写：排障要最近的，不要无限膨胀
        if (File.Exists(ShellPaths.EngineLog) && new FileInfo(ShellPaths.EngineLog).Length > 5_000_000)
            File.Delete(ShellPaths.EngineLog);
        using (var sw = new StreamWriter(File.Open(ShellPaths.EngineLog, FileMode.Append, FileAccess.Write)))
            sw.WriteLine($"---- {DateTime.Now:yyyy-MM-dd HH:mm:ss} 壳拉起引擎 :{port} ----");
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;
        psi.StandardOutputEncoding = Encoding.UTF8;
        psi.StandardErrorEncoding = Encoding.UTF8;
        var p = Process.Start(psi)!;
        p.OutputDataReceived += (_, e) => AppendLog(e.Data);
        p.ErrorDataReceived += (_, e) => AppendLog(e.Data);
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        _engine = p;
        return port;
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
<body style="margin:0;height:100vh;display:grid;place-items:center;background:#0e1511;color:#9fb0a8;font-family:system-ui,'Microsoft YaHei',sans-serif">
<div style="text-align:center"><div style="font-size:34px;margin-bottom:14px">🦞</div>正在启动 HaoAI 引擎…</div></body>
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
