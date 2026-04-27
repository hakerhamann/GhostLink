package com.rezerv.app.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UpdateAutoCheckScheduler {

    private const val UNIQUE_WORK_NAME = "ghostlink_update_auto_check_tick"

    fun ensureScheduled(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateAutoCheckWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
