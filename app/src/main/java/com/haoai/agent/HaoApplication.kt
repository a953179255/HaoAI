package com.haoai.agent

import android.app.Application
import com.haoai.agent.agent.schedule.ScheduleStore
import com.haoai.agent.agent.schedule.Scheduler
import com.haoai.agent.data.AppContainer

class HaoApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Scheduler.init(this)
        Scheduler.syncAll(ScheduleStore.list())
    }
}
