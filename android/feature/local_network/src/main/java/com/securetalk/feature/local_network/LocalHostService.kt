package com.securetalk.feature.local_network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.securetalk.core.network.localhost.LocalHostServer
import com.securetalk.core.network.nsd.NsdDiscovery
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

/**
 * Foreground service hosting the Ktor local host (ARCHITECTURE §2.2):
 *  - Same WS protocol as the backend (`/api/v1/ws`, `/health`).
 *  - Authoritative floor control copy + opaque media relay; host CANNOT decrypt media.
 *  - NSD registration `_securetalk._tcp`.
 */
@AndroidEntryPoint
class LocalHostService : Service() {

    @Inject lateinit var host: LocalHostServer
    @Inject lateinit var nsdDiscovery: NsdDiscovery

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var nsdRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting local host..."))
        startHost()
    }

    private fun startHost() {
        val result = host.start(NsdDiscovery.DEFAULT_PORT)
        val port = result.getOrNull()
        if (port != null) {
            runCatching {
                nsdDiscovery.register("SecureTalk Host", port) { ok -> nsdRegistered = ok }
            }
            updateNotification("Local host on $port — relaying encrypted packets (cannot decrypt)")
        } else {
            updateNotification("Local host failed to bind port ${NsdDiscovery.DEFAULT_PORT}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        host.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Local host",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("SecureTalk offline host")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "securetalk_local_host"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, LocalHostService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocalHostService::class.java))
        }
    }
}
