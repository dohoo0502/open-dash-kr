package com.example.opendash

import android.app.Application
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import com.example.opendash.media.MediaInfoProvider
import com.example.opendash.media.OpenDashNotificationListener

class OpenDashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && MediaInfoProvider.isAccessGranted(this)) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(this, OpenDashNotificationListener::class.java),
                )
            }
        }
    }
}
