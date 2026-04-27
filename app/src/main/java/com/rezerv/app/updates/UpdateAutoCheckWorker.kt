package com.rezerv.app.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rezerv.app.AppContainer

class UpdateAutoCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching { AppContainer.updateRepository.checkForUpdatesAndDownload() }
        UpdateAutoCheckScheduler.ensureScheduled(applicationContext)
        return Result.success()
    }
}
