package com.haoai.agent.platform

/**
 * "电脑上那条定时任务跑完了"的差集判定 —— 与 [PcWatch] 同一类问题，但规则不一样，
 * 所以不复用它。
 *
 * 审批是**当下状态**（还在等 = 屏幕上就该挂着一条），跑完的结果是**过去事件**
 * （看过一次就该过去）。两者最容易各坏一次：
 *
 * ① **第一次看到的一律不弹。** 这条是后台轮询特有的：手机上刚配好对、或者服务重启之后，
 *    电脑上可能攒着三十条历史结果。没有这道闸，装上应用的那一刻就会为每一条历史震一次，
 *    人只会把通知权限整个关掉 —— 那等于这条功能不存在。
 * ② **没有稳定 key 的一律不弹。** `t` 是那一轮的开始时刻（毫秒），是这份 JSON 里唯一的稳定主键。
 *    缺了它（0 或负数）就没法判"这条是不是上次那条"，于是每 20 秒重弹一次。
 *    宁可不提醒，也不能震到人关权限 —— 和 [PcWatch] 那条"同一批不重复弹"是同一个理由。
 * ③ 一次多出好几条只弹一条，正文里说清"还有几条"。
 */
class PcDigestWatch {

    private var seen: Set<Long> = emptySet()
    private var primed = false

    /**
     * 这一轮该提醒谁。空列表 = 没有新结果（不"收回"什么：结果通知是自动可消的，
     * 没有"从有到无"这个状态要维护）。
     */
    fun onDigest(items: List<PcDigestItem>): List<PcDigestItem> {
        val keyed = items.filter { it.t > 0L }
        if (!primed) {
            // 第一次（以及解除配对之后重新配对之后）只登记，不响
            primed = true
            seen = keyed.map { it.t }.toSet()
            return emptyList()
        }
        val fresh = keyed.filter { it.t !in seen }
        if (fresh.isEmpty()) return emptyList()
        seen = seen + fresh.map { it.t }
        // 时间正序：调用方拿最后一条当"最新的那次"，前面的只报个数
        return fresh.sortedBy { it.t }
    }

    /**
     * 重新回到"没见过任何一条"的状态：下一次轮询只登记、不响。
     *
     * 解除配对、换一台电脑、或者服务重启后都要调。两件事一起解决：
     * 一是不能把上一台电脑攒下的 `t` 带过来（不同机器的时间线互不相干，混在一起会误判"这条见过"），
     * 二是换电脑之后同样**不该为那台机器的历史结果震一通** —— 与 ① 是同一条理由。
     */
    fun reset() {
        seen = emptySet()
        primed = false
    }
}
