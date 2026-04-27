package com.rezerv.app

import android.app.Application
import com.rezerv.app.notifications.PushManager
import com.rezerv.app.updates.UpdateAutoCheckScheduler

class ReservApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
        PushManager.initialize(this)
        UpdateAutoCheckScheduler.ensureScheduled(this)
    }
}
