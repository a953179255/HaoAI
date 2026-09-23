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

/**
 * 定时任务持久化。全局单例：多处（Worker/工具/UI/启动）共享同一把对象锁，
 * 避免各自 new 实例导致 @Synchronized 失效、并发读改写互相覆盖。
 */
object ScheduleStore {

    @Volatile
    private var file: File? = null

    // 内存态为真源：UI/Worker/工具高频读写，磁盘写异步化（原子写保序）
    @Volatile
    private var mem: ScheduleState? = null

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun init(appFilesDir: File) {
        file = File(appFilesDir, "schedules.json")
    }

    private val f: File
        get() = file ?: throw IllegalStateException("ScheduleStore 未初始化（需先调用 init）")

    @Synchronized
    fun load(): ScheduleState {
        mem?.let { return it }
        val loaded = com.haoai.agent.data.HaoJson.readJsonSafe(f, ScheduleState.serializer()) ?: ScheduleState()
        mem = loaded
        return loaded
    }

    @Synchronized
    fun save(state: ScheduleState) {
        mem = state
        io.execute {
            runCatching {
                HaoJson.writeAtomic(f, HaoJson.json.encodeToString(ScheduleState.serializer(), state))
            }
        }
    }

    /** 原子读改写：在对象锁内完成 load→edit→save，杜绝并发覆盖。 */
    @Synchronized
    fun update(taskId: String, edit: (ScheduleTask) -> Unit): Boolean {
        val state = load()
        val target = state.items.find { it.id == taskId } ?: return false
        edit(target)
        save(state)
        return true
    }

    /** 锁内整表读改写：create/remove/toggle 等非单任务编辑走这里，避免 load→改→save 窗口竞态。 */
    @Synchronized
    fun mutate(edit: (ScheduleState) -> Unit) {
        val state = load()
        edit(state)
        save(state)
    }

    /** 只读快照：返回拷贝，调用方可安全迭代而不怕并发 mutate。 */
    @Synchronized
    fun list(): List<ScheduleTask> = load().items.toList()
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
            val found = Regex("every:(\\d+)([mhd])").find(spec)?.destructured ?: return spec
            val (n, unit) = found
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

    /** 应用启动时对账：后台线程执行（getWorkInfosForUniqueWork().get() 是阻塞调用，主线程会卡启动）。 */
    fun syncAll(tasks: List<ScheduleTask>) {
        if (!::appContext.isInitialized) return
        Thread {
            val wm = runCatching { WorkManager.getInstance(appContext) }.getOrNull() ?: return@Thread
            tasks.forEach { t ->
                if (!t.enabled) {
                    cancel(t.id)
                    return@forEach
                }
                // 已有未完成的调度就保持不动：WorkManager 自身跨重启持久，
                // 若每次启动都 REPLACE 重排，周期任务的倒计时会被反复重置而永远到不了点。
                val hasLiveWork = runCatching {
                    wm.getWorkInfosForUniqueWork(workTag(t.id)).get().any { !it.state.isFinished }
                }.getOrDefault(false)
                if (!hasLiveWork) enqueueNext(t)
            }
        }.apply { isDaemon = true; name = "haoai-sched-sync" }.start()
    }

    private fun workTag(id: String) = "haoai-sched-$id"
}
