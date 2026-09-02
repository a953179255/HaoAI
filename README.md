# HaoAI

HaoAI 是一个运行在 Android 手机上的全能 AI Agent 应用，采用"云端大脑 + 端侧肌肉"的设计思路：通过 OpenAI 兼容 API 接入任意大模型作为推理大脑，同时内嵌 llama.cpp 实现完全离线的端侧推理。应用使用 Kotlin + Jetpack Compose 原生开发，界面采用基于 Kyant0 backdrop 库的液态玻璃风格。

代理具备完整的工具执行能力：读写文件、执行 shell、抓取网页、管理待办与长期记忆、通过无障碍服务自动化操作手机、创建定时任务，甚至可以派出只读子代理并行调研。

## 功能特性

### 对话与工具调用

- SSE 流式输出，打字机效果实时呈现
- 多轮工具调用循环，单次任务最多 60 轮，自动持续执行直到完成
- 内置 23 个工具：

| 类别 | 工具 |
|------|------|
| 文件操作 | `read` `write` `edit` `grep` `glob` |
| 网络 | `web_fetch` |
| 命令行 | `bash` |
| 任务管理 | `todo` |
| 记忆 | `memory`（两层：save 长期 / journal 每日日志，带类型与重要性分级） |
| 搜索 | `web_search`（Bing 优先 + DDG 兜底，免 key） |
| 技能沉淀 | `skill`（自进化经验库，见下文） |
| 手机自动化 | `screen`（编号列表）`tap`（支持 index）`swipe` `scroll` `find` `wait` `type_text` `key` `launch_app` `list_apps` |
| 定时任务 | `schedule` |
| 上下文压缩 | `handoff`（五段式交接，见下文） |
| 子代理 | `spawn_agent`（单个）、`spawn_agents`（并行扇出，最多 4 个） |

### 安全与权限

- 三级权限模式：
  - **全部询问**：每次工具调用均需确认
  - **写入时询问**：只读操作放行，写文件、执行命令、触控等需确认
  - **全自动**：所有操作直接执行
- Shell 黑名单：拦截 `sudo`、递归强删根目录、fork 炸弹、`mkfs`、`dd` 写块设备、`shutdown/reboot`、`format`、`diskutil eraseDisk` 等高危命令，任何权限模式下均强制拦截

### 手机自动化（索引式精准操作）

- `screen` 输出可交互控件的**编号列表**（类型/文本/坐标/能力标记），模型"看屏报号"
- `tap(index=编号)` 直接按编号点击，绕开坐标幻觉；也支持文本模糊匹配与坐标
- `wait` 条件等待：等文案出现/消失、等界面稳定（idle）、固定延时——加载慢的页面先等再操作，避免盲点
- `find` 在可滚动列表中自动滑动查找目标文案；`scroll` 语义滚动（方向+幅度）
- `long_press` 长按支持
- 基于无障碍服务，可读取屏幕、点击、滑动、输入、按键、启动应用

### 技能自进化（Skill）

- 代理把走通的多步流程、踩坑经验、被纠正的做法沉淀为技能（`filesDir/skills/<name>/SKILL.md`）
- 系统提示词只注入技能**索引**（名字+描述），全文按需加载，省上下文
- 用法：`skill save/list/view/delete`；正文建议按 When to Use / Procedure / Pitfalls 三段写

### 上下文压缩（Handoff）

- 长任务历史接近上限时，系统自动提示模型调用 `handoff`
- 模型用五段式总结（目标/约束/已完成/关键决定/下一步），引擎用交接文档替换早期历史，任务无缝继续
- 避免长会话静默丢失早期上下文

### 三层记忆系统

借鉴 上游（每日笔记+固化）与 上游（分层记忆）的三层架构：

```
会话上下文(工作记忆) ──handoff──▶ 每日日志(episodic) ──闲置固化──▶ 长期记忆库(semantic)
                                      │ 7 天过期                 importance≥4 晋升
```

- **每日日志**：上游 式 Markdown 日记 `memory/YYYY-MM-DD.md`（每天上限 50 条）。写入来源：
  - 自动：handoff 交接时引擎提取"已完成/下一步"要点落盘（零 token 成本保底）
  - 模型主动：`memory(action=journal)` 记录当天重要进展/事件/教训
  - 手动：记忆管理页"+"按钮直接添加长期记忆或查看概览统计（偏好/事实/决定/事件分项计数）
- **闲置固化**（MemoryTidyWorker + DreamTriggerMonitor，上游 "dreaming" 的本地实现）：
  - 触发条件（v0.11.0）：充电中 + 屏幕关闭持续 N 分钟（默认 15，可调）；亮屏/断电即取消，触发前复核
  - 规则层（始终执行、零成本）：importance≥4 的日志条目自动晋升为长期 event 记忆 → 清理 7 天前的过期日志 → 长期库去重整理
  - **深度梦境层**（设置中可选开启）：按「记忆管理模型」整理长期记忆——默认端侧小模型（llama.cpp，零 token），也可选任一云端服务；做语义去重与合并（如把多次记录的同一偏好的不同措辞合并为一条综合表述）；不可用或输出不合法时自动回退规则层，带删除限额保护（≤40%）
  - 固化结果自动写入工作区 DREAMS.md 与 dreaming/ 目录，供人工审查
  - 记忆管理页"固化"按钮可手动触发
- **记忆开关**（gugu 式）：记忆系统总开关 / 自动学习（会话结束自动提取）/ 深度梦境各自独立控制
- **长期记忆银行**：
  - 会话结束后由模型自动从对话中沉淀稳定信息（用户偏好、项目背景、重要决定）
  - 记忆带**类型**（偏好/事实/决定/事件）与**重要性 1-5**，注入时按重要性+新近度排序而非单纯最新
  - 严格的写入纪律（系统提示词约束）：只记偏好/纠正教训/重要约定，不记闲聊与一次性细节
  - 记忆管理界面支持单条删除、一键**整理**（归一化去重+高相似合并+清理 30 天未用的低重要性条目）
- **注入**：每轮对话开始，系统提示词同时携带「长期记忆」（按重要性挑选）与「近期动态」（最近两天日志摘要），模型无需询问即可知晓近期发生的事；聊天顶栏实时显示当前上下文的粗略 token 估算
- **记忆管理界面**：「近期动态」与「长期记忆」两区展示，支持整理/固化/清空

### 首启引导与身份

- 第一次启动时引导用户给助理**起名字**、设定**性格风格**（上游 式"出生仪式"）
- 名字与性格注入系统提示词，聊天界面标题、输入框占位符同步使用

### 工作区文档层（上游 式）

记忆与身份以纯 Markdown 落在默认工作区，用户可用任意文件管理器/编辑器直接查看：

```
workspace/
├── MEMORY.md      长期记忆库（按类型分组、带重要度与日期）
├── USER.md        用户画像
├── IDENTITY.md    助理身份
├── SOUL.md        性格设定
├── AGENTS.md      工作约定说明
├── TOOLS.md       工具清单
├── HEARTBEAT.md   定时任务清单
├── DREAMS.md      梦境日记（每次固化的历史记录）
├── dreaming/YYYY-MM-DD.md  每日固化报告
└── memory/YYYY-MM-DD.md    每日情景日志（每行一条：时间|重要度|来源|内容）
```

- 每日日志为 Markdown 纯文本，可手动补充；程序解析失败的行为自动忽略
- 同步时机：应用启动、每轮对话结束、记忆增删改、固化完成后
- SAF 目录模式下跳过同步（仅默认工作区支持）

### 分组设置导航

- 设置主页按类别分组（上游 移动端式）：模型大脑 / 端侧推理 / 权限与自动化 / 记忆与梦境 / 定时任务 / 工作空间 / 通用 / 关于
- 每个入口卡片带实时状态摘要（当前服务与模型、记忆条数、权限模式等），点击进入子页；子页返回键回到菜单而非退出
- 聊天界面顶栏与输入栏使用 Kyant0 AndroidLiquidGlass 液态玻璃效果（玻璃层置于内容层之外，避免渲染自引用）

### 记忆与梦境

- 三层记忆：会话上下文 → 每日日志（7 天过期）→ 长期记忆库（MEMORY.md）
- **条件触发的梦境整理**（v0.11.0 起，取代每日定时）：充电中 + 屏幕关闭持续 N 分钟（默认 15，可选 1/5/15/30）后自动执行；亮屏或断电立即取消；触发前复核条件
- 计时基于 AlarmManager（ELAPSED_REALTIME_WAKEUP），灭屏 suspend 期间照常走时
- 深度梦境用所选「记忆管理模型」（默认端侧小模型，零 token；也可选云端）做语义去重合并；不可用自动回退纯规则
- 固化历史写入工作区 DREAMS.md 与 dreaming/ 目录；整理完成后端侧服务自动停止

### 会话与导航

- 侧滑抽屉（左缘手势或点顶栏 ☰）：玻璃面板实时折射壁纸，含代理头像、开启新会话、历史会话切换（当前高亮）、删除会话、底部设置入口
- 顶栏三件套：☰ 开抽屉 / ＋ 直接新建会话 / ⌄ 弹出最近会话下拉菜单（最多 8 条，含时间与消息数，当前会话主色高亮，底部"查看全部会话"入口）
- 点击标题区也可打开抽屉；顶栏副标题显示当前工作空间名（路径末段）与上下文 token 估算

### 外观与模型行为

- 主题模式：跟随系统 / 浅色 / 深色（设置 → 通用 → 外观，默认浅色）
- **自定义聊天壁纸**：聊天背景全屏显示所选图片，液态玻璃顶栏/输入栏以同一张图为折射背景（视觉一致）；默认浅色渐变（深色底会导致玻璃上文字难辨认）
- 思考等级：默认 / 低 / 中 / 高（reasoning_effort，对支持的云服务生效；端侧模型忽略）
- 上下文用量：聊天顶栏实时显示粗略 token 估算
- 聊天顶栏玻璃效果 + 输入栏液态玻璃（Kyant0 AndroidLiquidGlass；玻璃层必须置于内容采样子树之外，否则渲染自引用会崩溃）
- 流式输出自动跟随：新内容到达时视口钉在消息底部；用户上滑阅读时不再自动拉回

### 系统权限

- 设置 → 权限与自动化 → 系统权限：相机 / 定位 / 照片与视频 / 文件管理 / 通知 的状态一览与授权引导
- 运行时权限自动弹系统对话框；「文件管理」（所有文件访问）跳系统设置并轮询等待
- 工具按需请求：`camera` 需相机权限，`location` 需定位权限，`bash` 访问共享存储时引导文件管理权限

### 端侧多模态与模型导入

- **从手机选择模型文件**：设置 → 端侧推理 → "从手机选择模型文件"，SAF 选取后自动拷入模型目录并设为当前模型（带 MB 级进度）
- **图像识别**：模型目录放入视觉投影文件（`*.mmproj` 或文件名含 `mmproj` 的 gguf，与模型同名优先）即自动启用 `--mmproj`；聊天输入栏 📎 按钮附加图片（自动降采样到 1024px），端侧视觉模型识别图像内容
- 端侧上下文 32k；健康检查等待 120s（大模型冷启动加载较慢）
- mmproj 文件不会出现在端侧模型列表中（避免误选为对话模型）

### 网页搜索

- `web_search` 工具（免 API key）：Bing（cn.bing.com，国内可达）优先、DuckDuckGo 兜底，返回标题/链接/摘要列表，配合 web_fetch 获取全文
- 子代理同样可用（只读工具集）

### 技能库管理

- 设置 → 技能库：查看/删除代理自沉淀的 SKILL.md（skill 工具的图形入口）

### 定时任务

- 基于 WorkManager 实现，支持 `every:30m`（间隔）、`daily:09:30`（每日定点）、`hourly`（每小时）三种规格
- 到点后自动以指定提示词唤起代理执行
- 设备重启后自动恢复全部任务
- 直接通过聊天创建："每天早上9点提醒我查看待办"

### 子代理

- 主代理可派出只读子代理执行调研任务，仅开放 `read` `grep` `glob` `web_fetch` `memory` 五个工具，禁止写入和执行
- 支持单个派发或最多 4 路并行扇出，适合多方向同时检索后汇总

### 端侧推理

- 内嵌 llama.cpp `llama-server`，本地 HTTP 服务直连引擎，启动参数含 `--reasoning off`（关闭思考模式，实测工具调用场景提速 10 倍且质量无损）
- 本地请求固定 `max_tokens=1024`，防止小模型失控生成（曾出现单轮静默跑 10 分钟的案例）
- 推荐模型 **Agents-A1-4B**（上海 AI Lab 原生工具调用模型，Q4_K_M 约 2.6GB）：多步工具路由实测全部正确；Qwen2.5-0.5B 作为一键下载的兜底（简单问答可用，多工具路由会选错）
- **多模型手动切换**：设置页列出设备上全部 GGUF，点选即切换（自动重启服务生效），不再固定加载最大模型
- arm64 真机构建建议开启 `GGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm`（启用 KleidiAI 内核，实测 prefill 4 倍 / gen 2 倍，需 ARMv8.6+ CPU）
- 完全离线可用，不依赖任何云服务

### 模型服务管理

- **快速预设**：DeepSeek / OpenRouter / Kimi / 智谱 / Ollama 一键填充（opencode 式体验）
- **测试连接**：保存前真实调用一次（1 token 非流式），即时暴露 key 错误、模型 ID 错误、上游限流
- **拉取模型列表**：从服务的 `GET /models` 拉取全部可用模型 ID，点击选择，不用手抄
- 表单校验带明确错误提示（URL 格式/模型 ID 必填/云端必须填 key）
- API Key 经 Android Keystore 加密存储
- 兼容严格校验的服务端（如 DeepSeek）：请求自动清理空 `tool_calls` 数组等历史消息问题

### 数据安全与存储

- 会话持久化保存，随时回看历史
- API Key 通过 Android Keystore 加密存储，明文不落盘
- 双工作空间模式：SAF 授权的外部目录，或应用私有目录，聊天中可切换
- Token 用量统计：设置页实时显示云端累计输入/输出 tokens（流式 usage 解析）

## 架构说明

```
ui/                    Compose 界面层（聊天、设置、记忆、定时任务）
    ↓
agent/engine/          AgentEngine 核心引擎 + SystemPrompt + PolicyEngine 权限策略
    ↓
agent/tools/           ToolRegistry 注册的 18 个工具实现
    ↓
platform/  data/       平台能力（无障碍、Shell、llama.cpp、WorkManager）与数据层（Keystore、会话存储）
```

- **引擎与 UI 分离**：AgentEngine 是纯协程驱动的无界面组件，UI 仅消费其事件流（消息增量、工具状态变更、结束事件），同一引擎可被聊天界面和后台定时任务复用。
- **ToolContext 能力注入**：每个工具通过 ToolContext 获取工作空间句柄、shell 目录、待办存储、记忆库等运行时能力，子代理通过降级的 ToolContext 天然获得受限能力集。
- **OpenAiCompatClient 协议兼容**：统一走 OpenAI Chat Completions 格式（含流式与 function calling），兼容任何实现了该协议的服务端——云端 API、本地 Ollama/LM Studio，以及内置的 llama-server 均可直接接入。
- **权限集中裁决**：PolicyEngine 按 READ/WRITE/EXEC 三级风险划分工具，结合用户选择的权限模式和 shell 黑名单统一裁决，引擎内不散落权限逻辑。

## 构建指南

环境要求：

- JDK 17+
- Android SDK（compileSdk 37）
- Android NDK 27（仅在需要自行编译端侧推理组件时）

### 普通构建

```bash
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/`。

注意：项目工作目录路径请避免包含非 ASCII 字符（如中文），否则可能遇到构建问题（见已知限制）。

### 编译 llama.cpp 端侧推理组件

APK 默认不含 `llama-server` 二进制，需自行交叉编译并放入 jniLibs。

1. 克隆源码：

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp
cd llama.cpp
```

2. CMake 配置（以 x86_64 为例，真机改为 `arm64-v8a`）：

```bash
cmake -B build-android-x86_64 -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=x86_64 \
  -DANDROID_PLATFORM=android-26 \
  -DBUILD_SHARED_LIBS=OFF \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_UI=OFF \
  -DLLAMA_USE_PREBUILT_UI=OFF \
  -DGGML_OPENMP=OFF
```

3. 编译目标 `llama-server`：

```bash
cmake --build build-android-x86_64 --target llama-server
```

4. Strip 并放入 jniLibs：

```bash
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
  build-android-x86_64/bin/llama-server
cp build-android-x86_64/bin/llama-server \
  <项目>/app/src/main/jniLibs/x86_64/libllamaserver.so
```

arm64 设备对应 `app/src/main/jniLibs/arm64-v8a/libllamaserver.so`。

说明：

- 二进制必须重命名为 `libllamaserver.so`，应用通过 `nativeLibraryDir` 加载它
- `app/build.gradle.kts` 中已配置 `jniLibs.useLegacyPackaging = true`，保证 so 以解压方式安装、可直接 exec，无需改动
- **Windows 特别提醒**：若工作目录含非 ASCII 字符（如中文），CMake 会崩溃（异常码 0xC0000409）。请在纯 ASCII 路径下运行 CMake，编译完成后再将产物复制回项目目录
- **中文 Windows 编译含 OpenCL 的版本**：需设置 `PYTHONUTF8=1` 环境变量，否则内核嵌入脚本（embed_kernel.py）以 GBK 读取 UTF-8 的 .cl 文件会报 UnicodeDecodeError
- **arm64 真机性能调优**：追加 `-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm`（Snapdragon 8 Gen 2 实测 prefill 6.6→27.4 tok/s、gen 3.5→7.2 tok/s；二进制将要求 ARMv8.6+ 设备）

## GPU 加速实验记录（Adreno / OpenCL，实测不采用）

在 MEIZU 20 Pro（Snapdragon 8 Gen 2 / Adreno 740）上完整打通了 llama.cpp 的 OpenCL GPU 后端，结论先行：**该机型 + Q4_K_M 量化组合下 GPU 全面慢于调优 CPU，故 APK 保持 CPU 版本**。记录如下供后续机型复测。

### 实测数据（Agents-A1-4B Q4_K_M）

| 配置 | prefill | gen（流式） | 工具调用 | 输出正确性 |
|------|---------|-------------|----------|------------|
| CPU 调优版（armv8.6+i8mm，-t 6） | **27.4 tok/s** | **7.2 tok/s** | ✓ | ✓ |
| GPU OpenCL（-ngl 99，-t 4） | 25.4 tok/s（2429 tok 大 prompt 仅 20.0） | 5.0 tok/s | ✓ | ✓ 无乱码 |
| CPU 跑 MiniCPM5-1B（对照组） | 4.6 tok/s | 1.2 tok/s | ✓ | ✓ |

### 为什么 GPU 没有收益

- **生成阶段是内存带宽瓶颈**：8 Gen 2 为 UMA 架构，GPU 与 CPU 共享同一块 LPDDR5X，每 token 遍历 2.7GB 权重的带宽上限一致；叠加 GPU 每层几十次微小内核提交的同步开销（参见 llama.cpp issue #27191 的 KGSL 提交间隙掉频问题），gen 反而慢 30%
- **prefill 未兑现理论优势**：OpenCL 后端不支持 Flash Attention（启动日志警告并自动禁用）；高通 Adreno 优化内核主调 Q4_0/Q4_1，Q4_K_M 走较慢路径；且内核按 Adreno 750/830 调优，740 老两代
- **MiniCPM5-1B 淘汰**：其混合注意力架构在 llama.cpp CPU 后端无优化路径，1.2 tok/s 反而比 4B 慢 6 倍

### OpenCL 构建配方（已验证可跑通）

1. 准备依赖（NDK 27 交叉编译）：

```bash
git clone https://github.com/KhronosGroup/OpenCL-Headers && \
  cp -r OpenCL-Headers/CL $NDK/toolchains/llvm/prebuilt/<host>/sysroot/usr/include/
git clone https://github.com/KhronosGroup/OpenCL-ICD-Loader && \
  cmake -S OpenCL-ICD-Loader -B ocl-build -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DBUILD_SHARED_LIBS=OFF \
    -DOPENCL_ICD_LOADER_HEADERS_DIR=<OpenCL-Headers 路径> && \
  cmake --build ocl-build && \
  cp ocl-build/libOpenCL.a $NDK/.../sysroot/usr/lib/aarch64-linux-android/
```

2. llama.cpp 追加：`-DGGML_OPENCL=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON -DGGML_OPENCL_EMBED_KERNELS=ON`（Windows 记得 `PYTHONUTF8=1`）

3. **关键坑：高通驱动不是标准 ICD**。`/vendor/lib64/libOpenCL.so` 是直连驱动，不实现 `clIcdGetPlatformIDsKHR`，Khronos loader 会报 "platform IDs not available" 拒载（`OCL_ICD_FILENAMES` 环境变量也救不了）。**解法：绕过 loader 直连驱动**——从手机 `adb pull /vendor/lib64/libOpenCL.so`，替换 sysroot 中的 loader 库重新编译，运行时设 `LD_LIBRARY_PATH=/vendor/lib64`。已验证 dlopen 与完整推理链路均可工作

### 何时值得重试 GPU

- 换 **Q4_0 量化**模型重测（高通内核的快路径，无需装 Vulkan SDK，现有二进制直接可用）
- Adreno 750/830（8 Gen 3 / 8 Elite）机型，社区实测 1.5B 模型 prefill 24 倍 / gen 2.7 倍
- Vulkan 后端（支持 Flash Attention，prefill 可能反超，但 gen 受同样的带宽天花板限制，且 Adreno 740 有输出乱码前科）与 Hexagon NPU 后端（社区反馈尚不成熟）

## 使用说明

1. **配置模型服务**：进入"设置"，任选其一：
   - 云端/局域网服务：填写 Base URL、模型 ID 和 API Key（任何 OpenAI 兼容服务均可，如官方 API、第三方中转、Ollama 等）
   - 端侧模型：推荐导入 Agents-A1-4B GGUF（原生工具调用，实测多步路由正确），或一键下载 Qwen2.5-0.5B 兜底；切换到本地供应商即可完全离线使用
2. **选择权限模式**：初次使用建议"写入时询问"；信任环境（如端侧模型）可选"全自动"。高危 shell 命令在任何模式下都会被黑名单拦截。
3. **开启手机自动化**：如需让代理操作手机，前往系统"设置 → 无障碍 → 已安装的应用"，开启 HaoAI 的无障碍服务。
4. **定时任务**：直接在聊天中告诉代理即可，例如"每天早上9点提醒我查看待办"、"每30分钟检查一次剪贴板"。也可在"定时任务"页查看、启停和删除。

## 已知限制

- 单元测试在含中文的工作目录路径下因 Gradle worker 问题无法运行（构建脚本中已尝试 UTF-8 编码参数规避，但部分环境下仍失败）
- `bash` 工具（toybox 后端）与 App 同 UID 运行：工作目录虽在工作区，但进程物理可达应用私有目录（含配置状态目录、会话存储）。"配置仅能经 config_set 恒审批修改"的边界对 read/write/glob/grep 等工作区工具成立，对全自动（YOLO）模式下的 bash 是软约束；proot 沙箱后端受绑定挂载隔离无此问题，敏感场景建议安装 Linux 发行版并使用沙箱后端
- 使用 SAF 授权目录作为工作空间时，`bash` 工具不可用（SAF 无法提供真实 shell 工作目录）；需要 shell 时请切换到应用目录模式
- 端侧 4B 模型（Agents-A1，约 2.6GB、占用约 3GB 内存）已实测可完成多步工具调用，但生成速度约 7 tok/s（8 Gen 2 调优 CPU），长回复仍需耐心；轻量问答可切 0.5B 换取速度
- 定时任务依赖 WorkManager，未使用前台服务常驻，极端省电策略下触发时间可能有延迟
- 手机 GPU 加速经实测（OpenCL / Adreno 740 / Q4_K_M）无收益甚至更慢，APK 保持 CPU 调优版；重试条件与完整配方见"GPU 加速实验记录"
- llama-server 首次加载模型需要数十秒，期间界面显示启动进度，请耐心等待（后续轮次命中前缀缓存后明显加快）
- 长期记忆的相似合并阈值较保守（Jaccard>0.85），不同措辞的近重复记忆由**深度梦境**（端侧模型语义去重）兜底处理；不开启深度梦境时可在记忆页手动整理
- 虚拟屏后台自动化在 Flyme 等部分 ROM 上受限：系统会把上屏应用的窗口挂回主屏（AM 任务记录在虚拟屏，窗口实际在 display 0），虚拟屏仅合成启动画面/纯色，表现为"白屏/纯色画面"且看不到应用界面。实测无法从应用层绕过（逐个减少 TRUSTED/display-group flags、每次重建屏、3 缓冲+独立取帧线程均不影响窗口重挂载）。已做防御：`vscreen_launch` 启动后检测目标窗口是否真的在虚拟屏（a11y 多屏窗口表），不在则明确报错并引导改用主屏 screen/tap 自动化；在支持真实多屏合成的 ROM/设备上可以正常工作

## 技术栈

| 组件 | 版本 |
|------|------|
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.4.10 |
| Jetpack Compose BOM | 2026.06.00 |
| 液态玻璃 UI（Kyant0 backdrop） | 2.0.0 |
| WorkManager | 2.10.1 |
| OkHttp | 4.12.0 |
| kotlinx.serialization | 1.9.0 |
| Coroutines | 1.10.2 |
| llama.cpp | 2026-08 master |
