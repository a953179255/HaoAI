package com.haoai.agent.ui.chat

/**
 * 界面上那根"已等 N 秒"的秒表该怎么算。
 *
 * 只解决一件事：**起点不许由显示方自己造**。
 * 移动端换一次屏＝整棵 Compose 树重建一次，所以在组合里 `val t0 = System.currentTimeMillis()`
 * 起一个表，回到聊天界面时那个 t0 就是"现在"，屏幕上的秒数从 0 重跳 ——
 * 任务根本没重启，是量它的尺被重置了（10-01 那批"任务面板每次返回都重新展开"是同一类毛病）。
 *
 * 所以起点优先取**回合起点**（`ChatViewModel.turnStartAt`，跨换屏不丢）；
 * 只有它没记上（0，例如还没进过任何一轮）才退回本次组合自己的起点。
 */
object ElapsedClock {

    /**
     * @param turnStartAt 本轮起点时刻（毫秒）；0 表示没记上
     * @param localT0     本次组合自己的起点，只当兜底
     * @param now         现在（参数化是为了能在测试里钉住时刻，不靠 sleep）
     */
    fun ms(turnStartAt: Long, localT0: Long, now: Long): Long {
        val from = when {
            turnStartAt > 0L -> turnStartAt
            localT0 > 0L -> localT0
            else -> return 0L     // 两个起点都没有：宁可说 0 秒，也不拿 epoch 0 算出个天文数字
        }
        return (now - from).coerceAtLeast(0L)   // 两端时钟不一致/取到未来的起点时不打负数
    }
}
