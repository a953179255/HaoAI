package com.haoai.agent.agent.tools

import com.haoai.agent.platform.a11y.HaoAccessibilityService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ScreenTool : Tool {

    override val name = "screen"
    override val description =
        "读取当前手机屏幕：输出可交互控件的编号列表 [index] 类型 \"文本\" 坐标。操作前先看屏幕；记住目标编号，用 tap(index=编号) 点击。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("max_nodes") { put("type", "integer") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = HaoAccessibilityService.instance ?: return ToolResult(HaoAccessibilityService.enableHint(), true)
        val dump = svc.dumpIndexed(args.optInt("max_nodes") ?: 80)
        return ToolResult(TextCap.middle(dump, 6000))
    }
}

class TapTool : Tool {

    override val name = "tap"
    override val description =
        "点击屏幕控件。优先 index（screen 输出的编号，最可靠），其次 text（模糊匹配）或 view_id，或坐标 x,y。long_press=true 长按。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("index") { put("type", "integer") }
            putJsonObject("text") { put("type", "string") }
            putJsonObject("view_id") { put("type", "string") }
            putJsonObject("x") { put("type", "integer") }
            putJsonObject("y") { put("type", "integer") }
            putJsonObject("long_press") { put("type", "boolean") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = HaoAccessibilityService.instance ?: return ToolResult(HaoAccessibilityService.enableHint(), true)
        val text = args.optString("text")
        val viewId = args.optString("view_id")
        val longPress = args.optBool("long_press")
        val idx = args.optInt("index")
        val x = args.optInt("x")
        val y = args.optInt("y")

        fun doAt(x: Int, y: Int, what: String): ToolResult =
            if (longPress) {
                if (svc.longPress(x, y)) ToolResult("已长按 $what ($x,$y)") else ToolResult("长按手势注入失败", true)
            } else {
                if (svc.tapScreen(x, y)) ToolResult("已点击 $what ($x,$y)") else ToolResult("手势注入失败", true)
            }

        return when {
            idx != null -> {
                val n = svc.nodeByIndex(idx)
                    ?: return ToolResult("index=$idx 不存在，请先重新 screen 获取编号", true)
                doAt(n.cx, n.cy, "[$idx] \"${n.label.take(20)}\"")
            }
            text.isNotBlank() -> ToolResult(svc.clickByText(text, longPress))
            viewId.isNotBlank() -> ToolResult(svc.clickById(viewId))
            x != null && y != null -> doAt(x, y, "坐标")
            else -> ToolResult("需要 index / text / view_id / x+y 之一", true)
        }
    }
}

class WaitTool : Tool {

    override val name = "wait"
    override val description =
        "等待条件满足再继续：mode=text 等文案出现、mode=gone 等文案消失、mode=idle 等界面停止变化、mode=time 固定等待。加载慢的页面先 wait 再操作，避免盲点。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("mode") {
                put("type", "string")
                put("enum", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive("text"),
                    kotlinx.serialization.json.JsonPrimitive("gone"),
                    kotlinx.serialization.json.JsonPrimitive("idle"),
                    kotlinx.serialization.json.JsonPrimitive("time")
                )))
            }
            putJsonObject("text") { put("type", "string") }
            putJsonObject("timeout_s") { put("type", "integer") }
            putJsonObject("seconds") { put("type", "number") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = HaoAccessibilityService.instance ?: return ToolResult(HaoAccessibilityService.enableHint(), true)
        val mode = args.optString("mode").ifBlank { "text" }
        val timeoutMs = ((args.optInt("timeout_s") ?: 10).coerceIn(1, 60)) * 1000L
        val deadline = System.currentTimeMillis() + timeoutMs
        return when (mode) {
            "time" -> {
                val s = ((args.optDouble("seconds") ?: 2.0)).coerceIn(0.5, 60.0)
                kotlinx.coroutines.delay((s * 1000).toLong())
                ToolResult("已等待 ${s}s")
            }
            "idle" -> {
                while (!svc.isIdle()) {
                    if (System.currentTimeMillis() > deadline) return ToolResult("等待界面稳定超时（${timeoutMs / 1000}s），界面仍在变化")
                    kotlinx.coroutines.delay(300)
                }
                ToolResult("界面已稳定")
            }
            "gone" -> {
                val text = args.optString("text")
                if (text.isBlank()) return ToolResult("缺少 text", true)
                while (svc.findNode(text = text) != null) {
                    if (System.currentTimeMillis() > deadline) return ToolResult("超时：「$text」仍在屏幕上")
                    kotlinx.coroutines.delay(500)
                }
                ToolResult("「$text」已消失")
            }
            else -> {
                val text = args.optString("text")
                if (text.isBlank()) return ToolResult("缺少 text", true)
                while (svc.findNode(text = text) == null) {
                    if (System.currentTimeMillis() > deadline) return ToolResult("超时：「$text」未出现，可能加载失败")
                    kotlinx.coroutines.delay(500)
                }
                ToolResult("「$text」已出现")
            }
        }
    }
}

class FindTool : Tool {

    override val name = "find"
    override val description =
        "在可滚动列表中查找目标文案：自动朝 direction 方向滚动最多 max_swipes 次，找到返回编号与坐标（同时更新 screen 缓存），找不到返回失败。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") { put("type", "string") }
            putJsonObject("direction") {
                put("type", "string")
                put("enum", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive("down"),
                    kotlinx.serialization.json.JsonPrimitive("up")
                )))
            }
            putJsonObject("max_swipes") { put("type", "integer") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = HaoAccessibilityService.instance ?: return ToolResult(HaoAccessibilityService.enableHint(), true)
        val text = args.optString("text")
        if (text.isBlank()) return ToolResult("缺少 text", true)
        val down = args.optString("direction").let { it.isBlank() || it == "down" }
        val maxSwipes = (args.optInt("max_swipes") ?: 6).coerceIn(1, 20)
        val w = svc.screenWidth()
        val h = svc.screenHeight()
        repeat(maxSwipes) { attempt ->
            val node = svc.findNode(text = text)
            if (node != null) {
                val dump = svc.dumpIndexed()
                val line = dump.lineSequence().firstOrNull { it.contains(text) } ?: "已找到「$text」"
                return ToolResult("第 ${attempt + 1} 次滑动后找到：$line")
            }
            if (down) svc.swipe(w / 2, (h * 0.7).toInt(), w / 2, (h * 0.3).toInt(), 400)
            else svc.swipe(w / 2, (h * 0.3).toInt(), w / 2, (h * 0.7).toInt(), 400)
            kotlinx.coroutines.delay(900)
        }
        val node = svc.findNode(text = text) ?: return ToolResult("滑动 $maxSwipes 次后未找到「$text」", true)
        return ToolResult("已找到「$text」（最后一次检查命中）")
    }
}

class ScrollTool : Tool {

    override val name = "scroll"
    override val description =
        "语义滚动：direction=up/down/left/right（up=内容向上滚即看下方），amount=幅度比例 0-1 默认 0.5。比 swipe 方便，无需算坐标。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("direction") {
                put("type", "string")
                put("enum", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive("up"),
                    kotlinx.serialization.json.JsonPrimitive("down"),
                    kotlinx.serialization.json.JsonPrimitive("left"),
                    kotlinx.serialization.json.JsonPrimitive("right")
                )))
            }
            putJsonObject("amount") { put("type", "number") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = HaoAccessibilityService.instance ?: return ToolResult(HaoAccessibilityService.enableHint(), true)
        val dir = args.optString("direction").ifBlank { "down" }
        val amount = ((args.optDouble("amount") ?: 0.5)).coerceIn(0.1, 0.9)
        val w = svc.screenWidth()
        val h = svc.screenHeight()
        val ok = when (dir) {
            // 终点坐标钳制在屏幕内：amount>0.6 时算出的越界坐标会让手势被系统拒绝、滚动静默失败
            "up" -> svc.swipe(w / 2, (h * (0.3 + amount / 2)).toInt().coerceIn(0, h), w / 2, (h * (0.3 - amount / 2)).toInt().coerceIn(0, h), 350)
            "down" -> svc.swipe(w / 2, (h * (0.7 - amount / 2)).toInt().coerceIn(0, h), w / 2, (h * (0.7 + amount / 2)).toInt().coerceIn(0, h), 350)
            "left" -> svc.swipe((w * 0.8).toInt().coerceIn(0, w), h / 2, (w * (0.8 - amount)).toInt().coerceIn(0, w), h / 2, 350)
            "right" -> svc.swipe((w * 0.2).toInt().coerceIn(0, w), h / 2, (w * (0.2 + amount)).toInt().coerceIn(0, w), h / 2, 350)
            else -> return ToolResult("未知方向：$dir", true)
        }
        return if (ok) ToolResult("已向 $dir 滚动") else ToolResult("手势注入失败", true)
    }
}

class SwipeTool : Tool {

    override val name = "swipe"
    override val description = "从 (x1,y1) 滑动到 (x2,y2)，duration_ms 默认 300。列表滚动、翻页、下拉刷新用。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("x1") { put("type", "integer") }
            putJsonObject("y1") { put("type", "integer") }
            putJsonObject("x2") { put("type", "integer") }
            putJsonObject("y2") { put("type", "integer") }
            putJsonObject("duration_ms") { put("type", "integer") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = HaoAccessibilityService.instance ?: return ToolResult(HaoAccessibilityService.enableHint(), true)
        val x1 = args.optInt("x1") ?: return ToolResult("缺少 x1", true)
        val y1 = args.optInt("y1") ?: return ToolResult("缺少 y1", true)
        val x2 = args.optInt("x2") ?: return ToolResult("缺少 x2", true)
        val y2 = args.optInt("y2") ?: return ToolResult("缺少 y2", true)
        val dur = args.optInt("duration_ms") ?: 300
        return if (svc.swipe(x1, y1, x2, y2, dur)) ToolResult("已滑动 ($x1,$y1)→($x2,$y2)")
        else ToolResult("手势注入失败", true)
    }
}

class TypeTextTool : Tool {

    override val name = "type_text"
    override val description = "向当前屏幕的输入框填入文本（先确保目标输入框已聚焦，必要时先 tap 它）。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") { put("type", "string") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = HaoAccessibilityService.instance ?: return ToolResult(HaoAccessibilityService.enableHint(), true)
        val text = args.optString("text")
        if (text.isEmpty()) return ToolResult("缺少 text", true)
        return ToolResult(svc.typeText(text))
    }
}

class KeyTool : Tool {

    override val name = "key"
    override val description = "系统级按键：back / home / recents / notifications / quick_settings。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = HaoAccessibilityService.instance ?: return ToolResult(HaoAccessibilityService.enableHint(), true)
        return ToolResult(svc.pressKey(args.optString("action")))
    }
}

class LaunchAppTool : Tool {

    override val name = "launch_app"
    override val description = "按包名启动应用（可用 list_apps 先查询包名）。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("package") { put("type", "string") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = HaoAccessibilityService.instance ?: return ToolResult(HaoAccessibilityService.enableHint(), true)
        val pkg = args.optString("package")
        if (pkg.isEmpty()) return ToolResult("缺少 package", true)
        return ToolResult(svc.launchApp(pkg))
    }
}

class ListAppsTool : Tool {

    override val name = "list_apps"
    override val description = "列出手机上可启动的应用（名称 + 包名）。"
    override val parameters = buildJsonObject { put("type", "object") }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val svc = HaoAccessibilityService.instance ?: return ToolResult(HaoAccessibilityService.enableHint(), true)
        return ToolResult(svc.listApps())
    }
}
