package com.haoai.agent.agent.engine

/**
 * 引擎的常量与阈值（从 AgentEngine 的 companion 整体搬出来，逐字未改）。
 *
 * 为什么不再嵌在类里：这些数是被引擎、子代理、压缩、工具执行、UI 面板好几处共用的
 * 同一批口径，放在 2500 行类的 companion 里等于"藏在最深处"，改的时候最容易漏掉
 * 另一处仍在用旧值。顶层声明在同包内仍可裸名引用，行为完全不变。
 *
 * 每个数的来历都写在这里 —— 它们都是调出来的，不是拍的：改动前先读注释。
 */

/** 主循环回合上限（一轮 = 一次模型请求 + 其工具执行）。 */
const val MAX_TURNS = 60

/** 历史条数保险上限：真正的窗口由 token 预算决定，这里只兜估算失准。 */
const val MAX_HISTORY = 80

/** 单个工具调用的超时：与流式看门狗同量级，超了判失败而不是吊死整轮。 */
const val TOOL_TIMEOUT_MS = 180_000L

/** E4a 工具定义开销兜底下限：正常路径按真实序列化求和（_toolsTokenCache），
 *  仅异常/未构建时回退此值。 */
const val TOOLS_BASE_TOKENS = 3500

/**
 * 工具结果的两段式截断。两级分工不同，动任何一级都要想清楚：
 *
 * - [STORED_CAP]：**落库前**。单条工具结果最多 1.6 万字符进会话 JSON。
 *   不省这一级就是拿手机存储当对象池 —— 几十条几百 KB 的网页抓取会把会话文件
 *   撑到几十 MB，list()/load() 的解析和写盘全线变慢。OpenClaw 的做法是原文全留、
 *   只在发请求时截，它有家里的桌面磁盘和 SQLite 账本；移动端**刻意不照抄**（路线图里
 *   记着这条偏离）。
 * - [REQ_CAP]：**发请求前**。同一条结果在请求里再截到 4000 字符，落库的那 1.6 万
 *   仍可回查/导出 —— 别让"发给模型的窗口"决定"用户能看到多少"。
 *   所有 token 估算一律按 REQ_CAP 口径算：拿落库全文估算会高估到压缩提前触发。
 */
const val STORED_CAP = 16_000
const val REQ_CAP = 4_000

/** 子代理回合上限（它只有 10 轮，不到主循环的 1/5）。 */
const val SUB_MAX_TURNS = 10

/** 瞬态错误（429/超时/网关抖动）退避秒数：主循环与子代理共用同一套节奏。 */
val SUB_BACKOFFS_SEC = intArrayOf(5, 12, 25)

/** P3-B research 子代理的检索硬预算（web_search + web_fetch 合计次数），依据见 RetrievalBudget。 */
const val SUB_RETRIEVAL_CAP = 8

/** 历史预算的安全余量：估算永远不如供应商准，留出余量避免按窗口边界发请求撞 overflow。 */
const val HISTORY_MARGIN_TOKENS = 4096

/** 历史预算下限：小窗口模型（端侧 4K/8K）也要能带上最近几轮，否则任务无法继续。 */
const val MIN_HISTORY_BUDGET_TOKENS = 2048

/** 会话存储条数上限保险：压缩不再删历史，靠这个数兜住会话文件无界增长（只裁水位之前）。 */
const val SESSION_MAX_MESSAGES = 2000

/**
 * E6 并行安全白名单：纯读、无写盘；新增成员必须逐个评审
 * （a11y/相机/定位/todo/memory 永不入列）。
 *
 * M10：两个成员被移出，都是"看起来像只读、实际会写盘"的混合语义工具：
 *  - `todo`：全量替换语义，传完整清单覆盖 todos.json。同轮两个 todo 调用
 *    并发写同一文件 ⇒ 后写覆盖前写；写中崩溃留半截 JSON，`load()` 的 runCatching
 *    静默返回空清单 ⇒ **任务清单无声清空**。提示词恰恰要求"完成一项立刻更新"，
 *    正好命中这个并发窗口。
 *  - `memory`：`search`/`list` 纯读，但 `save`/`journal`/`merge`/`forget` 写 MEMORY.md，
 *    同轮两个 memory 调用同样并发写。
 *
 * 双保险：`TodoStore.save` 已加 `@Synchronized` + 原子写，即便将来有人加回白名单
 * 也不会写坏文件（但仍会丢更新，故不建议加回）。
 */
val PARALLEL_SAFE = setOf(
    "read", "grep", "glob", "web_fetch", "web_search",
    "list_apps", "app_status", "browser_read", "browser_find"
)

/** E6 并发上限：同时执行的并行工具体数量。 */
const val PARALLEL_MAX_CONCURRENCY = 4

/** handoff 催办阈值：token 占用比例（自动压缩 0.5 之后、危险线 0.9 之前）。 */
const val HANDOFF_NUDGE_RATIO = 0.75f

/** handoff 交接后保留的近期消息条数。 */
const val HANDOFF_KEEP = 6

/** 交接文档在历史里的标记（去重与识别都按它）。 */
const val HANDOFF_MARKER = "## 任务交接文档"
