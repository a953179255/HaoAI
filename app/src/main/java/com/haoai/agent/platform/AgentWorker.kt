package com.haoai.agent.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.haoai.agent.HaoApplication
import com.haoai.agent.agent.engine.AgentEngine
import com.haoai.agent.agent.policy.PermissionMode
import com.haoai.agent.agent.policy.PolicyEngine
import com.haoai.agent.agent.schedule.ScheduleStore
import com.haoai.agent.agent.schedule.Scheduler
import com.haoai.agent.agent.schedule.ScheduleState
import com.haoai.agent.agent.schedule.ScheduleTask
import com.haoai.agent.agent.tools.TodoStore
import com.haoai.agent.agent.tools.TextCap
import com.haoai.agent.agent.tools.ToolContext
import com.haoai.agent.agent.tools.ToolRegistry
import com.haoai.agent.data.StoredSession
import java.io.File

class AgentWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val container = (applicationContext as HaoApplication).container
        val state = ScheduleStore.load()
        val task = state.items.find { it.id == taskId }

        if (task == null || !task.enabled) {
            return Result.success()
        }

        // 无人值守执行必须尊重用户设置的权限模式：
        // YOLO 才全自动放行；ASK_WRITES/ALWAYS_ASK 下后台一律拒绝高危工具，防提示注入静默提权。
        val mode = container.settingsFlow.value.permissionMode

        val provider0 = container.activeProvider()
        if (provider0 == null) {
            updateTask(task, "未配置模型服务，任务跳过")
            Scheduler.enqueueNext(task)
            return Result.success()
        }
        val provider = if (provider0.baseUrl.startsWith("local")) {
            if (!container.llama.ensureStarted()) {
                updateTask(task, "端侧模型启动失败，任务跳过")
                Scheduler.enqueueNext(task)
                return Result.success()
            }
            provider0.copy(baseUrl = com.haoai.agent.platform.llama.LlamaServerController.LOCAL_BASE_URL)
        } else provider0

        val session = StoredSession.create(container.workspace.workspaceUriForSession)
        session.title = "⏰ ${task.name}"
        val engine = AgentEngine(
            httpClient = container.client,
            provider = provider,
            apiKey = container.cipher.decrypt(provider.apiKeyCipher),
            customPrompt = container.settingsFlow.value.customPrompt,
            policy = PolicyEngine(mode),
            approve = { mode == PermissionMode.YOLO },
            session = session,
            persist = { container.sessionStore.save(session) },
            backend = container.workspace.current,
            appFilesDir = container.appFilesDir,
            workspaceLabel = container.workspace.current?.displayName ?: "定时任务",
            memoryBank = container.memoryBank,
            memoryEnabled = container.settingsFlow.value.memoryEnabled,
            journal = container.journal,
            okHttpClient = container.okHttpClient,
            appContext = container.appContext,
            backgroundScope = null
        )

        var resultText = ""
        runCatching {
            engine.runTurn(
                userText = "【定时任务 · ${task.name}】\n${task.prompt}",
                onDelta = { },
                onEvent = { }
            )
        }.onSuccess {
            val last = session.messages.lastOrNull { m -> m.role == "assistant" && !m.error }
            resultText = last?.content ?: "（无输出）"
        }.onFailure {
            resultText = "执行失败：${it.message ?: it.javaClass.simpleName}"
        }

        session.updatedAt = System.currentTimeMillis()
        container.sessionStore.save(session)

        updateTask(task, resultText)
        notifyDone(applicationContext, task.name, resultText)
        Scheduler.enqueueNext(task)
        return Result.success()
    }

    /** 读改写收敛到全局单例的原子 update 内，避免覆盖用户并发编辑。 */
    private fun updateTask(
        task: ScheduleTask,
        result: String
    ) {
        ScheduleStore.update(task.id) {
            it.lastRunAt = System.currentTimeMillis()
            it.lastResult = TextCap.head(result.replace('\n', ' '), 200)
        }
    }

    private fun notifyDone(context: Context, name: String, result: String) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "定时任务",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "HaoAI 定时任务的执行结果" }
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.haoai.agent.R.drawable.ic_notification)
                .setContentTitle("定时任务完成：$name")
                .setContentText(result.lineSequence().firstOrNull()?.take(80) ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(result.take(2000)))
                .setAutoCancel(true)
                .build()
            manager.notify(name.hashCode() and 0xFFFF, notification)
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val CHANNEL_ID = "haoai_schedules"
    }
}
