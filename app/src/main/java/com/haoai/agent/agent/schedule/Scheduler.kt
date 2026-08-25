package com.haoai.agent.agent.schedule

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.haoai.agent.data.HaoJson
import com.haoai.agent.platform.AgentWorker
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class ScheduleTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val spec: String,
    val prompt: String,
    var enabled: Boolean = true,
    var lastRunAt: Long = 0,
    var lastResult: String = ""
)

@Serializable
data class ScheduleState(val items: MutableList<ScheduleTask> = mutableListOf())

class ScheduleStore(appFilesDir: File) {

    private val file = File(appFilesDir, "schedules.json")

    @Synchronized
    fun load(): ScheduleState = runCatching {
        if (!file.exists()) return ScheduleState()
        HaoJson.json.decodeFromString(ScheduleState.serializer(), file.readText())
    }.getOrDefault(ScheduleState())

    @Synchronized
    fun save(state: ScheduleState) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(HaoJson.json.encodeToString(ScheduleState.serializer(), state))
        }
    }
}

object Scheduler {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun validateSpec(spec: String): String? {
        val s = spec.trim().lowercase()
        val every = Regex("^every:(\\d+)([mhd])$")
        val daily = Regex("^daily:([01]\\d|2[0-3]):([0-5]\\d)$")
        return when {
            s == "hourly" -> s
            every.matches(s) -> s
            daily.matches(s) -> s
            else -> null
        }
    }

    fun specLabel(spec: String): String = when {
        spec == "hourly" -> "每小时"
        spec.startsWith("every:") -> {
            val (n, unit) = Regex("every:(\\d+)([mhd])").find(spec)!!.destructured
            "每 ${n}${mapOf("m" to "分钟", "h" to "小时", "d" to "天")[unit]}"
        }
        spec.startsWith("daily:") -> "每天 ${spec.removePrefix("daily:")}"
        else -> spec
    }

    fun nextDelayMs(spec: String, now: Calendar = Calendar.getInstance()): Long? {
        val s = spec.trim().lowercase()
        return when {
            s == "hourly" -> 60 * 60 * 1000L
            s.startsWith("every:") -> {
                val m = Regex("every:(\\d+)([mhd])").find(s) ?: return null
                val n = m.groupValues[1].toLong()
                when (m.groupValues[2]) {
                    "m" -> n * 60 * 1000L
                    "h" -> n * 60 * 60 * 1000L
                    else -> n * 24 * 60 * 60 * 1000L
                }.coerceAtLeast(60 * 1000L)
            }
            s.startsWith("daily:") -> {
                val parts = s.removePrefix("daily:").split(':')
                val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val min = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val next = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, min)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
                }
                next.timeInMillis - now.timeInMillis
            }
            else -> null
        }
    }

    fun enqueueNext(task: ScheduleTask) {
        if (!::appContext.isInitialized) return
        val delay = nextDelayMs(task.spec) ?: return
        val request = OneTimeWorkRequestBuilder<AgentWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(AgentWorker.KEY_TASK_ID to task.id))
            .addTag(workTag(task.id))
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            workTag(task.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(taskId: String) {
        if (!::appContext.isInitialized) return
        WorkManager.getInstance(appContext).cancelUniqueWork(workTag(taskId))
    }

    fun syncAll(tasks: List<ScheduleTask>) {
        if (!::appContext.isInitialized) return
        tasks.forEach { if (it.enabled) enqueueNext(it) else cancel(it.id) }
    }

    private fun workTag(id: String) = "haoai-sched-$id"
}
