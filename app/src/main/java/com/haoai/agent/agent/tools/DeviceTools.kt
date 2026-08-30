package com.haoai.agent.agent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 设备工具包（2.4）：剪贴板 / 日历 / 联系人 / OCR / 闹钟。
 * 权限模型沿用三级审批（风险标注在 riskOf），运行时权限被拒时返回可读错误引导授权。
 */
class ClipboardReadTool : Tool {

    override val name = "clipboard_read"
    override val description =
        "读取系统剪贴板的文本内容。注意：Android 10 起仅当应用在前台时才允许读取，" +
            "若读取失败请告知用户复制后立即在本应用内重试。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val app = ctx.appContext ?: return@withContext ToolResult("应用上下文不可用", true)
            runCatching {
                val cm = app.getSystemService(android.content.ClipboardManager::class.java)
                    ?: return@withContext ToolResult("剪贴板服务不可用", true)
                val clip = cm.primaryClip
                if (clip == null || clip.itemCount == 0) {
                    return@withContext ToolResult("剪贴板为空")
                }
                val text = clip.getItemAt(0).coerceToText(app)?.toString()
                if (text.isNullOrBlank()) ToolResult("剪贴板为空或不含文本")
                else ToolResult(text.take(20_000))
            }.getOrElse { ToolResult("读取剪贴板失败：${it.message}（Android 10+ 要求应用在前台）", true) }
        }
}

class CalendarQueryTool : Tool {

    override val name = "calendar_query"
    override val description = "查询手机日历中指定时间范围内的事件（标题/时间/地点/描述）。首次使用需要日历权限。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("days_ahead") {
                put("type", "integer")
                put("description", "向后查询的天数，默认 7，最大 90")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val app = ctx.appContext ?: return@withContext ToolResult("应用上下文不可用", true)
            // 运行时权限：复用 PermissionCenter（前台时弹系统授权框，超时/拒绝返回 false）
            if (!com.haoai.agent.platform.PermissionCenter.ensure(app, com.haoai.agent.platform.PermissionCenter.CALENDAR)) {
                return@withContext ToolResult("日历权限被拒绝：请在系统设置中授权后重试", true)
            }
            val days = (args.optInt("days_ahead") ?: 7).coerceIn(1, 90)
            val now = System.currentTimeMillis()
            val end = now + days * 86_400_000L
            runCatching {
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .appendPath(now.toString()).appendPath(end.toString()).build()
                val cursor = app.contentResolver.query(
                    uri,
                    arrayOf(
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.EVENT_LOCATION,
                        CalendarContract.Instances.DESCRIPTION
                    ),
                    null, null, CalendarContract.Instances.BEGIN + " ASC"
                )
                val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                val lines = mutableListOf<String>()
                cursor?.use { c ->
                    while (c.moveToNext() && lines.size < 50) {
                        val title = c.getString(0) ?: "(无标题)"
                        val begin = c.getLong(1)
                        val endAt = c.getLong(2)
                        val loc = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " · 地点:$it" } ?: ""
                        lines.add("${fmt.format(java.util.Date(begin))} ~ ${fmt.format(java.util.Date(endAt))} $title$loc")
                    }
                }
                if (lines.isEmpty()) ToolResult("未来 $days 天没有日历事件")
                else ToolResult("未来 $days 天共 ${lines.size} 条日历事件：\n${lines.joinToString("\n")}")
            }.getOrElse { ToolResult("查询日历失败：${it.message}", true) }
        }
}

class CalendarCreateTool : Tool {

    override val name = "calendar_create"
    override val description = "在系统日历中创建一个事件（写入操作，执行前会请求用户确认）。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "string") }
            putJsonObject("start_ms") {
                put("type", "integer")
                put("description", "开始时间的毫秒时间戳")
            }
            putJsonObject("duration_min") {
                put("type", "integer")
                put("description", "时长（分钟），默认 60")
            }
            putJsonObject("location") { put("type", "string") }
            putJsonObject("description") { put("type", "string") }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("title")) }
    }

    @SuppressLint("MissingPermission")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val app = ctx.appContext ?: return@withContext ToolResult("应用上下文不可用", true)
            if (!com.haoai.agent.platform.PermissionCenter.ensure(app, com.haoai.agent.platform.PermissionCenter.CALENDAR)) {
                return@withContext ToolResult("日历权限被拒绝：请在系统设置中授权后重试", true)
            }
            val title = args.optString("title")
            if (title.isBlank()) return@withContext ToolResult("标题不能为空", true)
            val start = args.optInt("start_ms")?.toLong()
                ?: System.currentTimeMillis() + 3600_000L
            val duration = (args.optInt("duration_min") ?: 60).coerceIn(1, 24 * 60)
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DTSTART, start)
                    put(CalendarContract.Events.DTEND, start + duration * 60_000L)
                    put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                    args.optString("location").takeIf { it.isNotBlank() }?.let {
                        put(CalendarContract.Events.EVENT_LOCATION, it)
                    }
                    args.optString("description").takeIf { it.isNotBlank() }?.let {
                        put(CalendarContract.Events.DESCRIPTION, it)
                    }
                    put(CalendarContract.Events.CALENDAR_ID, primaryCalendarId(app) ?: 1L)
                }
                val uri = app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) ToolResult("已创建日历事件「$title」")
                else ToolResult("日历事件创建失败", true)
            }.getOrElse { ToolResult("创建日历事件失败：${it.message}", true) }
        }

    private fun primaryCalendarId(app: android.content.Context): Long? =
        runCatching {
            app.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.IS_PRIMARY}=1", null, null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        }.getOrNull()
}

class ContactsSearchTool : Tool {

    override val name = "contacts_search"
    override val description = "按名字模糊搜索手机联系人，返回姓名与号码（最多 20 条）。首次使用需要联系人权限。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") { put("type", "string") }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("query")) }
    }

    @SuppressLint("MissingPermission")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val app = ctx.appContext ?: return@withContext ToolResult("应用上下文不可用", true)
            if (!com.haoai.agent.platform.PermissionCenter.ensure(app, com.haoai.agent.platform.PermissionCenter.CONTACTS)) {
                return@withContext ToolResult("联系人权限被拒绝：请在系统设置中授权后重试", true)
            }
            val query = args.optString("query")
            if (query.isBlank()) return@withContext ToolResult("搜索词不能为空", true)
            runCatching {
                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter(ContactsContract.Contacts.EXTRA_ADDRESS_BOOK_INDEX, "true")
                    .build()
                val cursor = app.contentResolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$query%"),
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )
                val lines = mutableListOf<String>()
                cursor?.use { c ->
                    while (c.moveToNext() && lines.size < 20) {
                        val name = c.getString(0) ?: continue
                        val number = c.getString(1) ?: ""
                        lines.add("$name：$number")
                    }
                }
                if (lines.isEmpty()) ToolResult("没有匹配「$query」的联系人")
                else ToolResult("找到 ${lines.size} 位联系人：\n${lines.joinToString("\n")}")
            }.getOrElse { ToolResult("搜索联系人失败：${it.message}", true) }
        }
}

class AlarmSetTool : Tool {

    override val name = "alarm_set"
    override val description =
        "通过系统时钟 App 设置一个闹钟（跳转到系统时钟确认，无需任何权限）。" +
            "hour 为 0-23，minute 为 0-59；重复规则如 MON,TUE（可省略表示只响一次）。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("hour") { put("type", "integer") }
            putJsonObject("minute") { put("type", "integer") }
            putJsonObject("label") { put("type", "string") }
            putJsonObject("days") {
                put("type", "string")
                put("description", "重复星期，如 MON,TUE,WED（省略=仅一次）")
            }
        }
        putJsonArray("required") {
            add(kotlinx.serialization.json.JsonPrimitive("hour"))
            add(kotlinx.serialization.json.JsonPrimitive("minute"))
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val hour = args.optInt("hour")?.takeIf { it in 0..23 }
            ?: return ToolResult("hour 需为 0-23 的整数", true)
        val minute = args.optInt("minute")?.takeIf { it in 0..59 }
            ?: return ToolResult("minute 需为 0-59 的整数", true)
        val app = ctx.appContext ?: return ToolResult("应用上下文不可用", true)
        return runCatching {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                args.optString("label").takeIf { it.isNotBlank() }?.let {
                    putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it)
                }
                args.optString("days").takeIf { it.isNotBlank() }?.let {
                    // EXTRA_DAYS 需要 ArrayList<Integer>（Calendar.SUNDAY=1 起算）
                    val dayMap = mapOf("SUN" to java.util.Calendar.SUNDAY, "MON" to java.util.Calendar.MONDAY,
                        "TUE" to java.util.Calendar.TUESDAY, "WED" to java.util.Calendar.WEDNESDAY,
                        "THU" to java.util.Calendar.THURSDAY, "FRI" to java.util.Calendar.FRIDAY,
                        "SAT" to java.util.Calendar.SATURDAY)
                    val days = java.util.ArrayList(
                        it.split(",").mapNotNull { d -> dayMap[d.trim().uppercase()] }
                    )
                    if (days.isNotEmpty()) putExtra(android.provider.AlarmClock.EXTRA_DAYS, days)
                }
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
            ToolResult("已跳转系统时钟设置闹钟：${"%02d".format(hour)}:${"%02d".format(minute)}，请在系统时钟里确认保存")
        }.getOrElse { ToolResult("设置闹钟失败：${it.message}", true) }
    }
}

/** 通知读取（2.4）：列出最近捕获的系统通知（应用/标题/内容/时间）。 */
class NotificationsReadTool : Tool {

    override val name = "notifications_read"
    override val description =
        "读取最近收到的系统通知（应用名/标题/内容/时间，最多 50 条）。" +
            "需要用户在系统设置 → 通知使用权中授权本应用。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "返回条数，默认 10，最大 50")
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val cap = com.haoai.agent.platform.NotificationCapture
        if (!cap.connected) {
            return ToolResult(
                "尚未授予通知使用权：请在系统设置 →「通知使用权/设备与应用助手」中允许本应用读取通知后重试",
                true
            )
        }
        val limit = (args.optInt("limit") ?: 10).coerceIn(1, 50)
        val items = synchronized(cap.recent) { cap.recent.take(limit) }
        if (items.isEmpty()) return ToolResult("当前没有已捕获的通知")
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        val sep = "\n"
        val body = items.joinToString(sep) {
            "· ${it.appName}｜${it.title}｜${it.text}（${fmt.format(java.util.Date(it.postedAt))}）"
        }
        return ToolResult("最近 ${items.size} 条通知：$sep$body")
    }
}
