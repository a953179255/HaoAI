package com.haoai.agent

import android.app.Application
import com.haoai.agent.agent.schedule.ScheduleStore
import com.haoai.agent.agent.schedule.Scheduler
import com.haoai.agent.data.AppContainer
import kotlinx.coroutines.launch

class HaoApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Scheduler.init(this)
        // A1：ScheduleStore.list() 会 readText 磁盘（load 全量排程表），不在 Application.onCreate
        // 主线程跑——丢容器 IO 作用域，syncAll 内部再自开线程对接 WorkManager。
        container.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { Scheduler.syncAll(ScheduleStore.list()) }
        }
    }
}
