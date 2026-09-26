package com.haoai.agent.agent.flags

/**
 * 特性开关的阶段（S6）。对标 codex `codex-rs/features/src/lib.rs` 那张 152 条的表：
 * 每个开关带「阶段 + 默认值」，用户可覆盖，已下线的强制关。
 *
 * 为什么要这一层：此前 HaoAI 每个新能力都是"发版即全量"，出问题只能再发一版。
 * 目标模式、工具结果溢出、浏览器工具收敛这些都得能"上了能关"，
 * 否则每加一个能力都是一次不可逆的发布。
 *
 * 纯数据 + 纯函数，不碰 android / compose —— 抽 `:core` 时整体搬走。
 */
enum class FlagStage {
    /** 已稳定：默认开，仍允许用户关。 */
    STABLE,

    /** 实验中：默认关，界面上归进「实验特性」。 */
    EXPERIMENTAL,

    /** 已下线：一律关，**用户覆盖也无效**（防旧配置把死代码点亮）。 */
    REMOVED
}

/**
 * 开关清单。加新开关就在这里加一条，别在业务代码里散着读 SharedPreferences。
 *
 * `key` 是落库用的稳定标识（改名等于作废用户设置，慎改）；`title`/`what` 直接给设置页显示，
 * 所以 `what` 必须写"它会改变什么行为"，而不是内部术语。
 */
enum class HaoFlag(
    val key: String,
    val title: String,
    val what: String,
    val stage: FlagStage,
    val defaultOn: Boolean
) {
    /**
     * 工具输出超过落库上限（STORED_CAP=16000）时，把全文写进工作区 `.haoai-output/`，
     * 会话里只留头尾摘要 + 文件路径，模型可以用 `read` 分段回查。
     *
     * 默认关的理由：它治的是"信息永久丢失"，代价是"用户项目目录里多出一堆文件"
     * 以及回查时又要花一遍 token —— 谁更在意哪个，交给用户选。
     */
    TOOL_RESULT_SPILL(
        key = "tool_result_spill",
        title = "工具完整输出落文件",
        what = "工具输出过长时，完整内容存进工作区 .haoai-output/，对话里只留头尾摘要和路径，" +
            "我可以按需回读。关掉则维持原样：超过 1.6 万字符的部分直接丢弃、不可恢复。",
        stage = FlagStage.EXPERIMENTAL,
        defaultOn = false
    );

    companion object {

        fun byKey(key: String): HaoFlag? = entries.firstOrNull { it.key == key }

        /**
         * 生效值，三条规则按优先级：REMOVED 一律 false → 用户显式覆盖 → 枚举默认。
         *
         * REMOVED 排第一是有意的：用户可能留着几年前某个开关的 true，
         * 那不该把已经删掉的代码路径点亮。
         */
        fun enabled(flag: HaoFlag, overrides: Map<String, Boolean>): Boolean =
            if (flag.stage == FlagStage.REMOVED) false
            else overrides[flag.key] ?: flag.defaultOn

        /** 设置页要列的：已下线的不展示（展示了也改不动，只会误导）。 */
        fun visible(): List<HaoFlag> = entries.filter { it.stage != FlagStage.REMOVED }.toList()

        /** 只保留"与默认值不同"的覆盖项，避免设置文件里堆一堆无效 true/false。 */
        fun compactOverrides(overrides: Map<String, Boolean>): Map<String, Boolean> =
            entries.mapNotNull { flag ->
                val on = overrides[flag.key] ?: return@mapNotNull null
                if (on == flag.defaultOn && flag.stage != FlagStage.REMOVED) null else flag.key to on
            }.toMap()
    }
}
