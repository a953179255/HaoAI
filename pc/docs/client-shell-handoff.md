# 交接文档：PC 客户端壳（WebView2）——黑屏未解 + 后续任务队列

> 交接时间：2026-10-05。前一个 Agent 排障到「用户肉眼全黑」为止，以下是其全部已知事实、
> 主嫌疑与建议排查顺序。**接手前先读完本文再动码**，坑清单里每一条都是实测踩出来的。

## 一、未解决的主问题（2026-10-05 深查后更新：**系统级问题，非 HaoAI 代码**）

**症状**：客户端窗口打开后是一块空的黑/深色表面。用户随后证实 **Octop、Clash Verge 同样白屏**——三款无关应用同病。

**⚠ 旧结论作废**：本文早期版本称“无边框窗口（FormBorderStyle.None）是毒源”——已被证伪：
二分中途原生窗口渲染过一次，随后**原生窗口也黑了**（用户屏幕实拍），窗口样式不是根因。
排查方向改为系统级。

### 深查后的事实清单（全部实测 2026-10-05 06:00 前后）

| 项 | 结果 |
|---|---|
| 三应用形态 | HaoAI=WebView2 进程树齐全、渲染器正常（CDP 截图有完整画面）、**上屏黑**；Octop=只有 python 后端、**UI 无任何 webview/renderer 子进程**；Clash Verge=主逻辑全正常（日志三连“窗口激活成功”）、**无 msedgewebview2 子进程** |
| Electron 应用 | bilibili/WorkBuddy/QQ/ZCode 全部正常渲染（自带 Chromium 路径 OK） |
| Edge 无头渲染 | RENDER-OK（chromium 核心管线完好） |
| 显卡事件 | 近 7 天零 TDR/驱动复位；近 5 天零系统更新；Defender 近 7 天零隔离 |
| DPI/拓扑 | 两屏均 100%；双真屏；虚拟屏适配器在装但**未进桌面拓扑**；dwm 无 GameViewer 注入模块 |
| **WebView2 运行时** | **10/2 19:45 静默更新 .48→.53**（两版并存） |
| **UU远程(GameViewer)** | **全套进程 10/4 17:41 启动**（报障前夜），虚拟显示驱动+常驻服务+与云端有活动连接；Clash 自家服务 10/4 17:49 崩溃重启（8 分钟后） |

### 主嫌疑（按时间线）
1. **UU远程(GameViewer) 的显示层干扰**——远程控制类工具带虚拟显示+抓取/呈现钩子，是“部分内容应用空白”的经典成因；启动时间（10/4 17:41）先于所有报障。
2. WebView2 运行时 .53（10/2 更新）与第三方宿主的兼容——但我们的宿主能正常 spawn 且渲染器正常，此假说较弱。

### 只有用户能回答的两个时间点（决定 H1/H2）
- Clash/Octop **最后一次正常显示**是何时？晚于 10/4 17:41 → 指向 H2；早于 → 指向 H1。
- 10/4 傍晚是否**主动用 UU远程连过这台机器**（现在它的云端连接还活着）？

### 待用户点头的排除实验（未执行，遵守“只查不改”）
1. 退出 UU远程全家（含服务）→ 重开三应用看内容（最快、最可能一击定案）。
2. 重装/回退 WebView2 运行时。
3. 都无效 → 显卡驱动干净重装。

### 已确认无关/已修掉的（不要重复排查）

- 引擎没起来/起不来：已在壳里加重试×4 + 退出码埋点（`engine.log` 有 `!!!! 引擎退出` 行）。
- `Encoding.GetEncoding(936)` 抛异常导致引擎秒死（.NET 10 需注册 CodePagesEncodingProvider，
  已注册）。
- packageExe 只读位误判、装配漏拷 `runtimeconfig.json`、jpackage 启动器自杀连带——均已修。
- 僵尸 msedgewebview2 锁用户数据目录（18 只）→ 已有启动前定向清扫（按数据目录路径认领，
  不误伤其它应用）。
- 浏览器缓存导致「修了像没修」→ 服务端已发 `Cache-Control: no-store`。
- **验证方法论**：CDP 截图绕过合成路径，它正常恰恰说明问题在合成——不要再用 CDP 截图
  当「用户可见正常」的证据。用户肉眼 + `CopyFromScreen` 全屏截屏才算数。

### 快速自检命令

```bash
# 引擎活着吗 + 服务的是不是新页面
cat "$LOCALAPPDATA/HaoAI/webport"; curl -s "http://127.0.0.1:$(cat "$LOCALAPPDATA/HaoAI/webport")/" | grep -c 'id="titlebar"'
# 引擎日志
tail -20 "$LOCALAPPDATA/HaoAI/Client/logs/engine.log"
# WebView2 挂调试口启动（查 DOM/CDP 截图用）
powershell "$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS='--remote-debugging-port=9224'; Start-Process 'G:\HaoAI\build\client\HaoAI-PC\HaoAI-PC.exe'"
curl -s http://127.0.0.1:9224/json/list   # 看目标 URL
# 僵尸 webview 盘点（我们目录的）
powershell "Get-CimInstance Win32_Process -Filter \"Name='msedgewebview2.exe'\" | ? { $_.CommandLine -like '*HaoAI*Client*WebView2*' } | measure"
```

## 二、已完成并提交的部分（全部可复用，勿重写）

| 提交 | 内容 |
|---|---|
| fcd6091 | 导航「技能」落右栏技能页签（原弹设置）+ EntryLandingTest 前身 |
| 0942767 | EntryLandingTest（入口落点契约五条对照）+ 帮助键位纠偏 |
| d84ff51 | 左下角 ⋮ 系统菜单 + 外观三态（跟随系统/浅色/深色，v6 效果图确认） |
| e32545b | 静态页 Cache-Control: no-store + jpackage 控制台编码修复 |
| 6e6f631 | packageExe 锁探针守卫（先清只读再探占用） |
| bb59705 | 客户端壳 v1（WinForms+WebView2、托盘、单实例、引擎托管） |
| 37bfc27→8df2d9f | 标题栏两代演进 → 最终形态：**网页自绘 #titlebar + app-region:drag**（ZCode/Octop 同构），壳模式满铺 + 行高显式 |
| 1826cc3 | 僵尸 webview 清扫 + 深色底 + 窗体级错误兜底 |
| bef8d0e | 启动状态层（各阶段文字，失败红字给原因） |

关键产物：`build/client/HaoAI-PC/`（壳 exe + `engine\` 引擎目录，免安装自包含）；
壳源码 `pc/shell/`（Program.cs 单文件）；装配 `bash pc/tools/build-client.sh`。

**坑清单（全部实测，细节见代码注释与记忆 haoai-pc-client-shell）**：自包含发布、
publish 全量 cp -r（少 runtimeconfig.json 秒退）、GetEncoding(936) 要注册码页提供器、
引擎拉起重试（杀软锁新 exe）、packageExe 先清只读再探占用、ApplicationIcon 需
Content+CopyToOutput、Main 兜底 MessageBox、无边框最大化要手动贴工作区、
SC_MAXIMIZE 改道、grid-template-rows 必须显式（自动行乱分高度）。

## 三、后续任务队列（客户端黑屏解决后按序做）

1. **代码高亮**：聊天代码块目前纯文本（0 高亮）。本地 highlight.js（断网可用），
   深浅两主题配色。显示效果提升最大的一项。
2. **添加模型向导**（用户点名体验最差，参考 Octop + 手机端已验证的 3 步向导）：
   供应商管理整页（卡片列表/激活标记/多供应商并存一键切换，兼容降级链）；
   选预设（7 家官方 logo 素材手机端有）→ 贴 key → 拉 `/v1/models` 勾选 → 测试连接。
   PC 引擎要加 provider 存储（形状抄手机端 ProviderStore，**手机端一行不动**）。
   动码前先给交互式效果图确认（用户的工作流惯例）。
3. **任务卡进聊天流 + 子任务实时卡**：todo 卡打在对话里实时打勾（现有右栏任务页签保留）；
   子任务卡从结果卡升级实时卡（状态行滚动、点开看工具流水、并行多卡堆叠）。
4. **守护模式完善**：托盘常驻已是地基；剩「关窗后引擎长跑、开机自启默认开、
   离人运行的安全边界（哪些工具允许）」——安全边界要用户拍板。
5. 客户端收尾：图标在任务栏的显示、DPI 多显示器、安装形态（现在免安装目录）。

## 四、工程约束（铁律）

- **用户实例绝不杀**：8712 端口 / 用户手动开着的任何 HaoAI 进程不能动；测试用
  临时端口（87xx 高位）+ 一次性状态根。
- **每段验证过的改动立即提交**，只 add 自己改的文件（pc/ 有并行开发）。
- pc 前端改动提交前跑 `bash pc/tools/smoke.sh`（59 步 90 判据）；测试必须
  `gradle -p pc test`（不带 -p 会静默跑成 Android 套件且同样全绿）。
- UI/交互改动动码前先给可交互 HTML 效果图确认（用户工作流惯例）。
- 涉及 exe 的交付：重打后**必须**在包内做真点击验证 + 让用户肉眼确认
  （CDP 截图不算数——见上文验证方法论）。
- 用户当前使用 ZCode/WorkBuddy 工作，验证窗口内容时别抢前台抢太久。

## 五、关键路径速查

- 壳源码：`pc/shell/Program.cs`（单文件，含全部注释坑位）
- 装配：`pc/tools/build-client.sh` → `build/client/HaoAI-PC/`
- 引擎（jpackage）：`pc/build/package/HaoAI-PC/`（重打有锁探针守卫，实例开着会拒绝）
- 状态根：`%LOCALAPPDATA%\HaoAI`（webport 端口登记、Client/logs/engine.log、
  Client/WebView2 用户数据目录）
- 界面：`pc/src/main/resources/ui/index.html`（单文件 ~4900 行；`#titlebar`、
  `.app.haoshell` 规则、haoshell 注入判定在 `$` 常量定义后）
- 冒烟剧本：`pc/tools/steps/ui-pages.json`（59 步 90 判据）
