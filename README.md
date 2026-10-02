<div align="center">

# HaoAI

**一个跑在你 Android 手机上的 AI 助理** 📱

接上任何一家大模型，它就能陪你聊天、帮你操作手机、到点提醒你办事，
还能记住你的喜好——越用越懂你。

![Android](https://img.shields.io/badge/Android-%E6%89%8B%E6%9C%BA-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF)
![Compose](https://img.shields.io/badge/Compose%20BOM-2026.06.00-4285F4)

[🚀 快速开始](#-快速开始3-步) · [✨ 功能一览](#-功能一览) · [🛠 开发者专区](#-给开发者) · [⚠ 已知限制](#-已知限制)

</div>

---

## 🌟 一句话介绍

HaoAI = **手机里的全能智能助理**。你可以把它理解成：

- 🧠 **大脑随你挑**：DeepSeek、智谱、千问、Kimi、OpenRouter……任何 OpenAI 兼容的模型服务都能接，贴个 Key 三步搞定；不想花钱就用端侧离线模型
- 🤖 **会动手，不只是会说**：能读文件、逛网页、跑命令，还能**直接替你操作手机**——点按钮、滑列表、打字、开应用
- 📴 **断网也能干活**：内嵌 llama.cpp，本地跑小模型，完全离线可用
- 🧹 **越用越聪明**：踩过的坑自己沉淀成"技能笔记"，你的偏好写进长期记忆，下次不犯同样的错
- ⏰ **到点自动干活**：聊天里说一句"每天早上 9 点提醒我"，任务就建好了
- 🔒 **安全攥在你手里**：三档权限随便切，再怎么放飞，危险命令也永远拦

---

## 🚀 快速开始（3 步）

### ① 安装

从 [Releases](https://github.com/a953179255/HaoAI/releases) 下载最新 APK 安装；
或者自己构建（见 [🛠 给开发者](#-给开发者)）。

第一次打开会带你走一遍引导：**起名字 → 选性格 → 选权限模式 → 接大脑**，跟着点就行。

### ② 接上你的"大脑"

进入 **设置 → 模型大脑 → 添加模型供应商**，一个 3 步小向导：

| 步骤 | 做什么 |
|:---:|---|
| 1️⃣ 选服务商 | DeepSeek / Kimi / 智谱 / 千问 / OpenRouter… 地址协议都帮你填好了，点一下就行 |
| 2️⃣ 贴 Key | 粘贴你的 API Key（🧪 支持一键测试连接，Key 写错了当场告诉你） |
| 3️⃣ 选模型 | 自动拉取服务上有哪些模型，点选一个当默认 ✅ |

> 💰 **不想花钱？** 选「本机」分类接 Ollama，或在「端侧推理」里导入 GGUF 模型文件，纯离线白嫖。

### ③ 开聊！

不知道说什么？试几个：

- 📱 *"帮我设个明早 8 点的闹钟"* —— 它会真的去操作你的手机
- ⏰ *"每天早上 9 点提醒我查看待办"* —— 定时任务自动建好
- 🌐 *"总结一下这个网页 https://…"*
- 📎 输入框回形针还能**发图片**，让它看图、出效果图

---

## ✨ 功能一览

### 💬 对话

- SSE 流式输出，打字机效果实时呈现；长回复回看时不会被"拽回底部"
- 聊天顶栏实时显示上下文用量（token 估算）
- 思考等级可调（默认 / 低 / 中 / 高），支持的模型会更"深思熟虑"
- 📋 **长任务不掉线**：对话太长时它会自己写一份"交接小结"，换掉早期历史继续干，任务无缝衔接
- 🗂 **多会话管理**：左缘滑动（或点顶栏 ☰）打开抽屉切换历史会话；＋ 新建、⌄ 快速切最近 8 条

### 🤳 手机自动化

不是"嘴上说说"——借助系统无障碍能力，它能**真的替你点手机**：

- 👀 **看屏报号**：先列出当前屏幕上可点的控件编号，模型"报编号"点击，不靠坐标瞎猜
- 👆 支持点击 / 滑动 / 长按 / 输入 / 按键 / 启动应用
- ⏳ **会等**：条件等待（"等加载完再点"）、在长列表里自动滑着找目标
- 🔧 **怎么开**：设置 → 权限与自动化 → 打开 HaoAI 的无障碍服务

### 🧠 记忆与梦境

像人的记忆一样分三层，全自动运转：

- 📝 **每日日志**：每天发生的重要进展随手记（会话结束自动提取；你也可以说"记住：……"）
- 😴 **梦境整理**：手机**充电 + 锁屏 15 分钟**后，它会悄悄"做个梦"——把重要的日志升格成长期记忆、清理过期内容、去重整理（**合并不是删掉**：被合的那条会留在「已归档」里并指向保留那条，30 天后才真删——这份 `MEMORY.md` 与电脑端共用，谁整理过都要看得见、也能改回来）。开启"深度梦境"后还会用模型做语义合并（把同一偏好的几种说法合成一条）。**让位**（B10 第二步）：若闲置期间发现 `MEMORY.md` 刚被电脑端改过（mtime 落在刚等过的闲置窗口内），这轮自动整理跳过并在 `DREAMS.md` 留一行原因——两端同时按各自副本重写会互相覆盖；手动触发不受此限
- 🗄️ **长期记忆库**：你的偏好、重要决定、项目背景，带分类和 1-5 重要度，每次对话自动带上

> 📂 所有记忆都是**纯 Markdown 文本**，躺在工作区 `workspace/` 目录里——随时可以用任何文件管理器翻看、手动修改。

### 🪄 技能自进化

- 它走通的多步流程、被你纠正过的错误，会自动沉淀成一份 `SKILL.md`"经验笔记"
- 下次遇到同类任务先翻笔记，**不犯第二次错**
- 设置 → 技能库 可查看 / 删除

### ⏰ 定时任务

- 直接在聊天里说：*"每天早上 9 点提醒我…"*、*"每 30 分钟检查一次剪贴板"*
- 📱 重启手机后任务自动恢复；设置页可查看、启停、删除

### 👥 子代理（多人查资料）

- 大调研任务可以派出**最多 4 个分身**并行查资料再汇总
- 分身是**只读**的：只能看，不能改你的任何东西

### 📴 离线端侧推理

- 内嵌 llama.cpp 本地服务，**一根网线都不用**
- 推荐 **Agents-A1-4B**（约 2.6GB，一个会自己调用工具的国产小模型）；Qwen2.5-0.5B 可一键下载当轻量兜底
- 📂 手机里的 GGUF 模型文件可直接导入；多个模型点选切换
- 👁 放一个 `*.mmproj` 视觉投影文件，端侧模型也能**看图**
- ⚡ CPU 调优版（GPU 加速实测反而更慢，实验记录见文末折叠区）

### 🔐 安全与权限

- **三档权限**：全部询问 / 写入时询问（默认）/ 全自动——按你对它的信任程度随便切
- 🚫 无论哪一档，危险命令（`rm -rf /`、`sudo`、`mkfs`、重启关机…）**永远直接拦截**
- 🔑 API Key 用 Android Keystore 加密存储，明文不落盘
- 🎬 相机、定位、照片等系统权限按需弹窗申请

### 🎨 外观

- 浅色 / 深色 / 跟随系统
- 🖼 **自定义聊天壁纸**：玻璃栏会跟着你的壁纸折射，浑然一体
- 液态玻璃界面风格

<details>
<summary>🧰 完整工具清单（23 个，给好奇的你）</summary>

| 类别 | 工具 |
|------|------|
| 文件操作 | `read` `write` `edit` `grep` `glob` |
| 网络 | `web_fetch` |
| 命令行 | `bash` |
| 任务管理 | `todo` |
| 记忆 | `memory`（save 长期记忆 / journal 每日日志，带类型与重要性分级） |
| 搜索 | `web_search`（免 API Key，Bing 优先 + DuckDuckGo 兜底） |
| 技能沉淀 | `skill`（自进化经验库） |
| 手机自动化 | `screen` `tap` `swipe` `scroll` `find` `wait` `type_text` `key` `launch_app` `list_apps` |
| 定时任务 | `schedule` |
| 上下文压缩 | `handoff`（五段式交接） |
| 子代理 | `spawn_agent`（单发）、`spawn_agents`（并行扇出 ×4） |

</details>

---

## 🛠 给开发者

### 架构说明

```
ui/                    Compose 界面层（聊天、设置、记忆、定时任务）
    ↓
agent/engine/          AgentEngine 核心引擎 + SystemPrompt + PolicyEngine 权限策略
    ↓
agent/tools/           ToolRegistry 注册的工具实现
    ↓
platform/  data/       平台能力（无障碍、Shell、llama.cpp、WorkManager）与数据层（Keystore、会话存储）
```

- **引擎与 UI 分离**：AgentEngine 是纯协程驱动的无界面组件，UI 只消费它的事件流；同一个引擎可被聊天界面和后台定时任务复用
- **ToolContext 能力注入**：工具通过 ToolContext 拿到工作区句柄、shell 目录、记忆库等运行时能力；子代理用降级的 ToolContext 天然获得受限能力集
- **OpenAI 协议兼容**：统一走 OpenAI Chat Completions 格式（含流式与 function calling），云端 API、本地 Ollama / LM Studio、内置 llama-server 全部直接接入
- **权限集中裁决**：PolicyEngine 按 READ / WRITE / EXEC 三级风险划分工具，结合权限模式和 shell 黑名单统一裁决，引擎里不散落权限逻辑

### 构建

环境要求：JDK 17+ · Android SDK（compileSdk 37）· NDK 27（仅编译端侧推理组件需要）

```bash
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/`。

> ⚠️ 项目路径请**避免包含中文等非 ASCII 字符**，否则可能构建失败。

<details>
<summary>🔧 编译 llama.cpp 端侧推理组件（完整流程）</summary>

APK 默认不含 `llama-server` 二进制，需自行交叉编译并放入 jniLibs。

1. 克隆源码：

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp
cd llama.cpp
```

2. CMake 配置（x86_64 示例，真机改 `arm64-v8a`）：

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

4. Strip 后放入 jniLibs：

```bash
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
  build-android-x86_64/bin/llama-server
cp build-android-x86_64/bin/llama-server \
  <项目>/app/src/main/jniLibs/x86_64/libllamaserver.so
```

arm64 真机对应 `app/src/main/jniLibs/arm64-v8a/libllamaserver.so`。

- 二进制必须重命名为 `libllamaserver.so`，应用通过 `nativeLibraryDir` 加载它
- `app/build.gradle.kts` 已配置 `jniLibs.useLegacyPackaging = true`，so 以解压方式安装、可直接 exec
- **Windows**：工作目录含中文时 CMake 会崩（异常码 0xC0000409），请在纯 ASCII 路径下编译再拷回；中文 Windows 编 OpenCL 版本需设 `PYTHONUTF8=1`，否则嵌入脚本以 GBK 读 UTF-8 会报 UnicodeDecodeError
- **arm64 性能调优**：追加 `-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm`（启用 KleidiAI 内核，实测 prefill 4 倍 / gen 2 倍；二进制将要求 ARMv8.6+ 设备）

</details>

### 技术栈

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

### 移动端这一轮（2026-10-02）：冷启动回到哪儿、"换屏"之后状态还在不在

移动端没有独立的界面路由表：`MainActivity` 里就是一个 `screen: Int`，**换一次屏＝整棵 Compose 树重建一次**。
这一批的五处毛病看着不相干（冷启动跳错会话、返回聊天侧栏自己收了、任务面板反复展开、手势返回直接退应用、
某个设置子页不吃壁纸），根子其实是同一条：**该活得比一次组合更久的东西，被放在了组合里**。

| 现象 | 真正的因 | 落点 |
| --- | --- | --- |
| 置顶过的会话，重开应用就被它抢了台 | 冷启动直接取了抽屉排序的第一条（置顶＝排最前） | `data/SessionStartup.kt` 纯函数挑起点 + `SessionStore` 记 `last-opened.txt`：**置顶只管找得到，不管打开哪条** |
| 进设置再回聊天，任务面板重新展开一次 | 展开态寄居在 `remember` 里，重建即归零；且自动展开写在 `LaunchedEffect` 里，首次组合必重放 | `ui/chat/TaskPanelState.kt`（挂在 ViewModel 上，按会话各记一份）＋ 记忆闸 |
| 从设置返回聊天，侧栏自己收了 | 同一条 `LaunchedEffect` 重放：横屏双栏那条 effect 每次进屏都执行一遍"竖屏就收起" | `DrawerController.onPaneMode()`：**只在模式真的变了**才开或收 |
| 电脑联动页用系统手势返回＝退出应用 | 那一页没有自己的 `BackHandler`，事件落到"退出" | `ui/ScreenNav.kt`：`depth` / `parentOf` 一张表，`MainActivity` 兜底 + 该页就地接住 |
| 电脑联动页背景是白的，全局壁纸不生效 | `PcLinkScreen` 收了 `wallpaper` 参数却一次没用，页面铺的是实底色 | 补壁纸＋玻璃接线；`ui/WallpaperWiringTest.kt` 扫源码钉住："声明了 `wallpaper` 的屏必须真的用它"、"调用处必须真的传进去" |

- **判据**：`:app:testDebugUnitTest` **238 条全绿**（本批新增 `SessionStartupTest` 9 / `TaskPanelStateTest` 7 /
  `ScreenNavTest` 6 / `DrawerPaneModeTest` 3 / `WallpaperWiringTest` 3）；逐条在真机（MEIZU 20 Pro，
  release 0.18.6）看过改前改后的对比图，不是只看断言绿
- **两条留档的教训**：① `remember` 不是"跨屏状态"的家，凡"返回之后还得保持"的一律先问它该活多久；
  ② 写在 `LaunchedEffect` 里的自动动作默认每次进屏都会重放一遍——要记忆闸，不能靠 key 没变来兜底

---

## ⚠️ 已知限制

- 📂 **SAF 外部目录当工作区时，`bash` 工具不可用**（SAF 给不了真实 shell 目录）——需要 shell 就切回应用目录模式
- 🔐 `bash`（toybox 后端）与应用同 UID：对工作区工具的约束是硬的，但全自动模式下 bash 属于软约束；敏感场景建议装 Linux 发行版用 proot 沙箱后端
- 🐢 端侧 4B 模型约 7 tok/s（8 Gen 2 调优 CPU），长回复要耐心；轻量问答切 0.5B 换速度。llama-server 首次加载要数十秒（后续命中缓存明显变快）
- ⏰ 定时任务基于 WorkManager，极端省电策略下触发可能延迟
- 🔁 长期记忆的自动合并较保守（相似度阈值高），近似重复靠"深度梦境"兜底；不开深度梦境可在记忆页手动整理
- 📺 虚拟屏后台自动化在 Flyme 等部分 ROM 上画面受限（帧缓冲不合成应用图层）：点击输入都正常，但截图是纯色。已做防御——检测不到画面时明确提示"以控件树为准"，Agent 无需视觉也能完成操作
- 🧪 单元测试在含中文的目录路径下可能无法运行（Gradle worker 编码问题）

<details>
<summary>🔬 GPU 加速实验记录（Adreno / OpenCL，实测不采用）</summary>

在 MEIZU 20 Pro（Snapdragon 8 Gen 2 / Adreno 740）上完整打通了 llama.cpp 的 OpenCL GPU 后端，结论先行：**该机型 + Q4_K_M 量化组合下 GPU 全面慢于调优 CPU，故 APK 保持 CPU 版本**。记录如下供后续机型复测。

#### 实测数据（Agents-A1-4B Q4_K_M）

| 配置 | prefill | gen（流式） | 工具调用 | 输出正确性 |
|------|---------|-------------|----------|------------|
| CPU 调优版（armv8.6+i8mm，-t 6） | **27.4 tok/s** | **7.2 tok/s** | ✓ | ✓ |
| GPU OpenCL（-ngl 99，-t 4） | 25.4 tok/s（2429 tok 大 prompt 仅 20.0） | 5.0 tok/s | ✓ | ✓ 无乱码 |
| CPU 跑 MiniCPM5-1B（对照组） | 4.6 tok/s | 1.2 tok/s | ✓ | ✓ |

#### 为什么 GPU 没有收益

- **生成阶段是内存带宽瓶颈**：8 Gen 2 为 UMA 架构，GPU 与 CPU 共享同一块 LPDDR5X，每 token 遍历 2.7GB 权重的带宽上限一致；叠加 GPU 每层几十次微小内核提交的同步开销，gen 反而慢 30%
- **prefill 未兑现理论优势**：OpenCL 后端不支持 Flash Attention（自动禁用）；高通 Adreno 优化内核主调 Q4_0/Q4_1，Q4_K_M 走较慢路径；内核按 Adreno 750/830 调优，740 老两代
- **MiniCPM5-1B 淘汰**：混合注意力架构在 llama.cpp CPU 后端无优化路径，1.2 tok/s 反而比 4B 慢 6 倍

#### OpenCL 构建配方（已验证可跑通）

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

3. **关键坑：高通驱动不是标准 ICD**。`/vendor/lib64/libOpenCL.so` 是直连驱动，不实现 `clIcdGetPlatformIDsKHR`，Khronos loader 会报 "platform IDs not available" 拒载。**解法：绕过 loader 直连驱动**——从手机 `adb pull /vendor/lib64/libOpenCL.so`，替换 sysroot 中的 loader 库重新编译，运行时设 `LD_LIBRARY_PATH=/vendor/lib64`。已验证 dlopen 与完整推理链路均可工作

#### 何时值得重试 GPU

- 换 **Q4_0 量化**模型重测（高通内核的快路径，现有二进制直接可用）
- Adreno 750/830（8 Gen 3 / 8 Elite）机型，社区实测 1.5B 模型 prefill 24 倍 / gen 2.7 倍
- Vulkan 后端（支持 Flash Attention，prefill 可能反超，但 gen 受同样带宽天花板限制）与 Hexagon NPU 后端（社区反馈尚不成熟）

</details>

---

## 📚 更多说明

- **设置导航**：设置主页按类别分组（模型大脑 / 端侧推理 / 权限与自动化 / 记忆与梦境 / 定时任务 / 工作空间 / 通用 / 关于），每个入口卡片带实时状态摘要
- **工作区文件**：记忆与身份都是 Markdown 纯文本（`workspace/` 下的 `MEMORY.md`、`USER.md`、`SOUL.md`、`DREAMS.md` 等），应用启动、每轮对话结束、记忆增删改时自动同步
- **数据安全**：会话持久化随时回看；双工作空间模式（SAF 外部目录 / 应用私有目录）聊天中可切换；设置页实时统计 Token 用量
