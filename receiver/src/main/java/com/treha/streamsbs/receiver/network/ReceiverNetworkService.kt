package com.treha.streamsbs.receiver.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.treha.streamsbs.common.protocol.ReceiverRenderConfig
import com.treha.streamsbs.receiver.MainActivity
import com.treha.streamsbs.receiver.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetAddress

class ReceiverNetworkService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastDiscoveryLaunchAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        getSystemService(NotificationManager::class.java).cancelAll()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        acquireLocks()
        startDiscovery()
        startControl()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        serviceScope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startDiscovery() {
        serviceScope.launch {
            runCatching {
                DiscoveryResponder(deviceName = android.os.Build.MODEL ?: "Receiver") {
                    onSenderDiscoveryRequest()
                }.run()
            }
        }
    }

    private fun startControl() {
        serviceScope.launch {
            runCatching {
                ControlServer { config, address ->
                    onSenderConnected(config, address)
                }.run()
            }
        }
    }

    private fun onSenderConnected(config: ReceiverRenderConfig, address: InetAddress) {
        val host = address.hostAddress ?: return
        sendBroadcast(
            Intent(ReceiverForegroundLauncher.ACTION_RENDER_CONFIG).apply {
                setPackage(packageName)
                putExtra(ReceiverForegroundLauncher.EXTRA_CONFIG, config.serialize())
                putExtra(ReceiverForegroundLauncher.EXTRA_HOST, host)
            },
        )
        ReceiverForegroundLauncher.bringToFront(
            context = this,
            force = true,
            serializedConfig = config.serialize(),
            senderHost = host,
        )
    }

    private fun onSenderDiscoveryRequest() {
        if (!shouldHandleDiscoveryLaunch()) return
        wakeDisplayForLaunch()
        ReceiverForegroundLauncher.bringToFront(context = this, force = true)
    }

    private fun shouldHandleDiscoveryLaunch(): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastDiscoveryLaunchAt < DISCOVERY_LAUNCH_DEBOUNCE_MS) return false
            lastDiscoveryLaunchAt = now
            return true
        }
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:receiver-network")
            .apply { acquire() }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "$packageName:receiver-wifi")
            .apply { acquire() }
    }

    private fun wakeDisplayForLaunch() {
        runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "$packageName:receiver-launch",
                )
                .apply { acquire(5_000L) }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.receiver_service_channel),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.receiver_service_notification))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "receiver_network"
        private const val NOTIFICATION_ID = 2001
        private const val DISCOVERY_LAUNCH_DEBOUNCE_MS = 15_000L

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, ReceiverNetworkService::class.java),
            )
        }
    }
}
