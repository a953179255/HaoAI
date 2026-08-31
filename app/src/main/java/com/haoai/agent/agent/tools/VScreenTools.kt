package com.haoai.agent.agent.tools

import com.haoai.agent.platform.a11y.HaoAccessibilityService
import com.haoai.agent.platform.vdisplay.VirtualScreenController
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 4.3 虚拟屏后台自动化工具组：目标 App 启动到公共虚拟屏，a11y 节点操作为主轴
 * （API 30+ getWindowsOnAllDisplays，无需触摸注入），ImageReader 帧截图 + 点击
 * 标记为视觉通道。全组受设置页总开关门控（关闭时不注册进工具清单）。
 */

/** 公共前置：开关/系统版本/无障碍服务/空闲回收。返回 null=可继续。 */
private suspend fun vscreenGuard(ctx: ToolContext): String? {
    if (!ctx.vscreenEnabled) return "后台自动化总开关未开启：请让用户到 设置 → 后台自动化（虚拟屏） 开启"
    if (!VirtualScreenController.supported) return "虚拟屏需要 Android 11（API 30）及以上，本设备不支持"
    if (HaoAccessibilityService.instance == null) return HaoAccessibilityService.enableHint()
    VirtualScreenController.reapIfIdle()
    return null
}

private fun activeDisplayId(): Int? = VirtualScreenController.displayId

class VScreenLaunchTool : Tool {

    override val name = "vscreen_launch"
    override val description =
        "把目标 App（包名）或网页（URL）启动到后台虚拟屏：用户主屏不被占用、可继续用手机。虚拟屏自动化第一步；启动失败（App 拒绝多屏）时按提示降级前台 screen/tap 流程。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("target") {
                put("type", "string")
                put("description", "应用包名（如 com.android.notes）或 http(s) 网址")
            }
        }
        put("required", kotlinx.serialization.json.JsonArray(listOf(
            kotlinx.serialization.json.JsonPrimitive("target")
        )))
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        vscreenGuard(ctx)?.let { return ToolResult(it, true) }
        val app = ctx.appContext ?: return ToolResult("无应用上下文", true)
        val target = args.optString("target")
        if (target.isBlank()) return ToolResult("缺少 target", true)
        VirtualScreenController.launch(app, target)?.let { return ToolResult(it, true) }
        delay(600)
        VirtualScreenController.awaitFrame()
        return ToolResult(
            "已启动 $target 到虚拟屏（displayId=${activeDisplayId()}）。用 vscreen_screen 读取界面树与截图。"
        )
    }
}

class VScreenScreenTool : Tool {

    override val name = "vscreen_screen"
    override val description =
        "读取虚拟屏：输出可交互控件编号列表 [index]（与主屏 screen 同一编号体系）+ 附截图（红框=上一步动作目标）。操作前先读屏；界面变了要重新读。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("max_nodes") { put("type", "integer") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        vscreenGuard(ctx)?.let { return ToolResult(it, true) }
        val id = activeDisplayId() ?: return ToolResult("虚拟屏未启动，先用 vscreen_launch", true)
        val svc = HaoAccessibilityService.instance!!
        val dump = svc.dumpIndexedOnDisplay(id, args.optInt("max_nodes") ?: 80)
            ?: return ToolResult("读不到虚拟屏窗口（服务未连接或屏上无应用窗口）", true)
        VirtualScreenController.awaitFrame(1500)
        val shot = VirtualScreenController.capture()
        return ToolResult(
            dump + if (shot != null) "\n（截图已附上，红框标注上一步动作位置）"
            else "\n（暂无截图帧：虚拟屏画面未更新）",
            imageDataUrl = shot
        )
    }
}

class VScreenTapTool : Tool {

    override val name = "vscreen_tap"
    override val description = "点击虚拟屏控件（index=vscreen_screen 的编号）。节点点击，不占主屏、无需手势注入。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("index") { put("type", "integer") }
        }
        put("required", kotlinx.serialization.json.JsonArray(listOf(
            kotlinx.serialization.json.JsonPrimitive("index")
        )))
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        vscreenGuard(ctx)?.let { return ToolResult(it, true) }
        val id = activeDisplayId() ?: return ToolResult("虚拟屏未启动，先用 vscreen_launch", true)
        val index = args.optInt("index") ?: return ToolResult("缺少 index", true)
        val (ok, rect) = HaoAccessibilityService.instance!!.clickOnDisplay(id, index)
        if (!ok) return ToolResult("index=$index 不存在或不可点击，请重新 vscreen_screen 获取编号", true)
        rect?.let { VirtualScreenController.markAction(it, "[$index] 点击") }
        delay(300)
        return ToolResult("已点击虚拟屏 [$index]")
    }
}

class VScreenTextTool : Tool {

    override val name = "vscreen_text"
    override val description = "向虚拟屏输入框（index=vscreen_screen 编号）填入文本。节点 SET_TEXT，无需键盘。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("index") { put("type", "integer") }
            putJsonObject("text") { put("type", "string") }
        }
        put("required", kotlinx.serialization.json.JsonArray(listOf(
            kotlinx.serialization.json.JsonPrimitive("index"),
            kotlinx.serialization.json.JsonPrimitive("text")
        )))
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        vscreenGuard(ctx)?.let { return ToolResult(it, true) }
        val id = activeDisplayId() ?: return ToolResult("虚拟屏未启动，先用 vscreen_launch", true)
        val index = args.optInt("index") ?: return ToolResult("缺少 index", true)
        val text = args.optString("text")
        if (text.isEmpty()) return ToolResult("缺少 text", true)
        val (ok, rect) = HaoAccessibilityService.instance!!.textOnDisplay(id, index, text)
        if (!ok) return ToolResult("index=$index 填入失败（可能不是输入框），请重新 vscreen_screen 确认", true)
        rect?.let { VirtualScreenController.markAction(it, "[$index] 输入") }
        delay(300)
        return ToolResult("已向虚拟屏 [$index] 填入 ${text.take(30)}")
    }
}

class VScreenScrollTool : Tool {

    override val name = "vscreen_scroll"
    override val description = "虚拟屏滚动：direction=down/up；可给 index 指定可滚动控件，不给则自动找屏上第一个可滚容器。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("direction") {
                put("type", "string")
                put("enum", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive("down"),
                    kotlinx.serialization.json.JsonPrimitive("up")
                )))
            }
            putJsonObject("index") { put("type", "integer") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        vscreenGuard(ctx)?.let { return ToolResult(it, true) }
        val id = activeDisplayId() ?: return ToolResult("虚拟屏未启动，先用 vscreen_launch", true)
        val dir = args.optString("direction").ifBlank { "down" }
        if (dir != "down" && dir != "up") return ToolResult("未知方向：$dir（支持 down/up）", true)
        val msg = HaoAccessibilityService.instance!!.scrollOnDisplay(id, args.optInt("index"), dir == "down")
        delay(300)
        return ToolResult(msg, isError = msg.contains("不存在") || msg.contains("未找到"))
    }
}

class VScreenBackTool : Tool {

    override val name = "vscreen_back"
    override val description = "虚拟屏内返回：点界面里的返回/导航控件（不代按全局返回键，避免打断用户主屏）。找不到返回控件会明确提示。"
    override val parameters = buildJsonObject { put("type", "object") }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        vscreenGuard(ctx)?.let { return ToolResult(it, true) }
        val id = activeDisplayId() ?: return ToolResult("虚拟屏未启动，先用 vscreen_launch", true)
        val msg = HaoAccessibilityService.instance!!.backOnDisplay(id)
        delay(300)
        return ToolResult(msg, isError = !msg.startsWith("已"))
    }
}

class VScreenHomeTool : Tool {

    override val name = "vscreen_home"
    override val description = "让虚拟屏回到桌面（home 定向到虚拟屏，屏上应用退到后台；用户主屏不受影响）。"
    override val parameters = buildJsonObject { put("type", "object") }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        vscreenGuard(ctx)?.let { return ToolResult(it, true) }
        val app = ctx.appContext ?: return ToolResult("无应用上下文", true)
        VirtualScreenController.goHome(app)?.let { return ToolResult(it, true) }
        return ToolResult("虚拟屏已回到桌面")
    }
}

class VScreenCloseTool : Tool {

    override val name = "vscreen_close"
    override val description = "销毁虚拟屏（屏上应用随之退出）。任务完成或失败后必须调用，释放资源省电。"
    override val parameters = buildJsonObject { put("type", "object") }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        vscreenGuard(ctx)?.let { return ToolResult(it, true) }
        if (activeDisplayId() == null) return ToolResult("虚拟屏本就未启动")
        VirtualScreenController.destroy()
        return ToolResult("虚拟屏已销毁")
    }
}
