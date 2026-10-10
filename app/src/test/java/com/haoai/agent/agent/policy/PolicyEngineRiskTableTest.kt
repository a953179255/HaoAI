package com.haoai.agent.agent.policy

import com.haoai.agent.agent.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具风险等级表的**穷尽性守卫**（2026-10-10 随 C2/M10 修复引入）。
 *
 * ## 为什么需要这个测试
 *
 * C2 的成因：`vscreen_tap_xy` / `vscreen_swipe_xy` 在 `riskOf` 的 `when` 里漏登记，
 * 落到 `else -> READ` —— 带 root 能力的触摸注入在所有权限档位下都**免审批**。
 * 同类漏登还有 `todo`（全量覆盖写盘）与 `scroll` / `find`（真改屏）。
 *
 * 为什么没被现有测试抓到：`AgentEngineLoopTest` 用的是 `PolicyEngine(PermissionMode.YOLO)`，
 * `requiresApproval` 在 YOLO 下恒返回 false，断言里也从不检查风险等级。
 * 也就是说**整个测试套件对风险等级错配是盲的**。
 *
 * ## 守卫口径
 *
 * `ToolRegistry.ALL_TOOL_NAMES` ⊆ `PolicyEngine.TOOL_RISK.keys()`。
 * 新增工具时若忘记在风险表登记，本测试变红。
 * 漏登的实际代价是 fail-safe 的WRITE（多问一次批准），但"未被人review 的等级"
 * 本身就是缺陷——本测试就是强制那个人去review 一次。
 */
class PolicyEngineRiskTableTest {

    @Test
    fun `every registered tool has an explicit risk level`() {
        val missing = ToolRegistry.ALL_TOOL_NAMES - PolicyEngine.TOOL_RISK.keys
        assertTrue(
            "以下工具没有在 PolicyEngine.TOOL_RISK 里登记风险等级：$missing\n" +
                "请到 PolicyEngine.riskOf 的 TOOL_RISK 表里按语义补一行。" +
                "（兜底是 WRITE，漏登不会被自动发现。）",
            missing.isEmpty()
        )
    }

    @Test
    fun `risk table has no stale entries`() {
        // 反向：表里不该有已下线的工具名，否则会让人误以为该工具受审
        val stale = PolicyEngine.TOOL_RISK.keys - ToolRegistry.ALL_TOOL_NAMES
        assertTrue(
            "PolicyEngine.TOOL_RISK 里有 ToolRegistry 已不存在的工具名：$stale（请删除这些行）",
            stale.isEmpty()
        )
    }

    @Test
    fun `unknown tool falls back to WRITE not READ`() {
        // fail-safe 兜底的方向必须锁死：新增工具漏登记时，宁可多问一次批准，
        // 也不能静默降级为免审批（C2 的教训）。
        val p = PolicyEngine(PermissionMode.ASK_WRITES)
        assertEquals(
            "未登记的工具必须按 WRITE 兜底（fail-safe），实际返回 ${p.riskOf("brand_new_tool_xxx")}",
            RiskLevel.WRITE,
            p.riskOf("brand_new_tool_xxx")
        )
        assertTrue(
            "fail-safe 兜底下必须需要审批",
            p.requiresApproval("brand_new_tool_xxx")
        )
    }

    @Test
    fun `high risk tools are not under classified`() {
        val p = PolicyEngine(PermissionMode.ASK_WRITES)
        // 这几个是 C2 / M10 实锤过的漏登，锁死等级防止将来"优化"时改回去
        val mustNotBeRead = listOf(
            "vscreen_tap", "vscreen_tap_xy", "vscreen_swipe_xy",
            "vscreen_text", "vscreen_scroll", "vscreen_back",
            "vscreen_home", "vscreen_close",
            "todo", "scroll", "find", "config_set", "workflow_save"
        )
        for (name in mustNotBeRead) {
            assertTrue(
                "$name 必须高于 READ（当前 ${p.riskOf(name)}）",
                p.riskOf(name) != RiskLevel.READ
            )
        }
    }

    @Test
    fun `read only tools stay免审`() {
        val p = PolicyEngine(PermissionMode.ASK_WRITES)
        // 反向锁：这几个误判成 WRITE 会连带击穿 Plan 模式、并行白名单、
        // 无人值守工作流（WorkflowRunner 在 ASK_WRITES 下对非 READ 直接抛"审批拒绝"）。
        val mustBeRead = listOf(
            "read", "grep", "glob", "web_fetch", "web_search", "job_output",
            "screen", "wait", "list_apps", "browser_read", "browser_find",
            "app_status", "config_get", "handoff", "tools_enable",
            "ask_user", "ask_user_batch", "collect_agent"
        )
        for (name in mustBeRead) {
            assertEquals("$name 应保持 READ 免审", RiskLevel.READ, p.riskOf(name))
        }
    }

    @Test
    fun `parallel safe tools are all READ`() {
        // 并行分组条件是 `riskOf == READ && in PARALLEL_SAFE`。
        // 白名单里出现非 READ 成员 = 该工具静默退化为串行（性能回归，且不会有任何报错）。
        val p = PolicyEngine(PermissionMode.ASK_WRITES)
        val notRead = com.haoai.agent.agent.engine.PARALLEL_SAFE.filter { p.riskOf(it) != RiskLevel.READ }
        assertTrue(
            "PARALLEL_SAFE 里的工具必须都是 READ，否则并行退化为串行：$notRead",
            notRead.isEmpty()
        )
    }
}