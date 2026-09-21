package com.netscope.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class HostMonitorTarget(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val intervalMinutes: Long,
)

/** Schedules Android-compliant periodic reachability checks. */
@Singleton
class HostMonitorScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    fun schedule(target: HostMonitorTarget) {
        require(target.intervalMinutes >= 15) {
            "Background monitoring must be 15 minutes or slower on Android."
        }
        require(target.port in 1..65535) { "Port must be 1-65535." }
        val request = PeriodicWorkRequestBuilder<HostMonitorWorker>(
            target.intervalMinutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(
                androidx.work.workDataOf(
                    HostMonitorWorker.KEY_ID to target.id,
                    HostMonitorWorker.KEY_LABEL to target.label,
                    HostMonitorWorker.KEY_HOST to target.host,
                    HostMonitorWorker.KEY_PORT to target.port,
                ),
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            workName(target.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(id: String) {
        workManager.cancelUniqueWork(workName(id))
    }

    private fun workName(id: String) = "netscope-host-monitor-" + id
}

/**
 * One bounded TCP reachability check.
 *
 * CONNECTED and REFUSED both prove that a host answered at the network stack; they are
 * kept distinct so "closed port" is never misreported as "open service".
 */
class HostMonitorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val label = inputData.getString(KEY_LABEL)?.take(96).orEmpty().ifBlank { "Host" }
        val host = inputData.getString(KEY_HOST)?.take(253) ?: return Result.failure()
        val port = inputData.getInt(KEY_PORT, 0)
        if (port !in 1..65535) return Result.failure()

        val status = probe(host, port)
        val preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "status_" + id
        val previous = preferences.getString(key, null)
        preferences.edit().putString(key, status.name).apply()

        if (previous != null && previous != status.name) {
            notifyTransition(label, host, port, status)
        }
        return Result.success()
    }

    private fun probe(host: String, port: Int): MonitorStatus {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MILLIS)
            MonitorStatus.PORT_OPEN
        } catch (_: java.net.ConnectException) {
            MonitorStatus.HOST_RESPONDED_PORT_CLOSED
        } catch (_: java.net.SocketTimeoutException) {
            MonitorStatus.NO_RESPONSE
        } catch (_: java.net.NoRouteToHostException) {
            MonitorStatus.NO_ROUTE
        } catch (_: Exception) {
            MonitorStatus.ERROR
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun notifyTransition(
        label: String,
        host: String,
        port: Int,
        status: MonitorStatus,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Host monitor",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Alerts only when a monitored host changes reachability state." },
            )
        }

        val detail = host + ":" + port + " · " + status.label
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(label + " changed state")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext)
            .notify((NOTIFICATION_BASE + idHash(host, port)), notification)
    }

    private fun idHash(host: String, port: Int): Int =
        (host.hashCode() * 31 + port).and(0x3FFF)

    enum class MonitorStatus(val label: String) {
        PORT_OPEN("TCP port open"),
        HOST_RESPONDED_PORT_CLOSED("Host answered; TCP port refused"),
        NO_RESPONSE("No response / timeout"),
        NO_ROUTE("No route to host"),
        ERROR("Probe error"),
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_LABEL = "label"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"

        private const val PREFS = "netscope_host_monitor_state"
        private const val CHANNEL_ID = "netscope_host_monitor"
        private const val NOTIFICATION_BASE = 5000
        private const val TIMEOUT_MILLIS = 3_000
    }
}
