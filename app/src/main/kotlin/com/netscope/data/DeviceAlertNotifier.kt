package com.netscope.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.netscope.core.model.DiscoveredDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notifyNewDevices(devices: List<DiscoveredDevice>) {
        if (devices.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "New network devices",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Alerts when a completed scan sees a device not present in the saved baseline."
                },
            )
        }

        val preview = devices.take(4).joinToString(", ") { device ->
            device.userLabel
                ?: device.friendlyName.value
                ?: device.hostname.value
                ?: device.ipv4?.toCanonicalString()
                ?: "unknown"
        }
        val extra = if (devices.size > 4) " +" + (devices.size - 4) + " more" else ""
        val text = preview + extra

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(
                if (devices.size == 1) "New device discovered" else devices.size.toString() + " new devices discovered",
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "netscope_new_devices"
        const val NOTIFICATION_ID = 4107
    }
}
