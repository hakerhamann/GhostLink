package com.rezerv.app.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rezerv.app.data.repository.UpdateRepository

class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        UpdateRepository.cleanupAfterPackageReplaced(context.applicationContext)
    }
}
