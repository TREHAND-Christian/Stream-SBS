package com.treha.streamsbs55.receiver.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.treha.streamsbs55.receiver.MainActivity

class WifiAutoLaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return
        if (!isConnectedToWifi(context)) return
        if (!canLaunchNow(context)) return

        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }
    }

    private fun isConnectedToWifi(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun canLaunchNow(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val lastLaunch = prefs.getLong(KEY_LAST_LAUNCH_AT, 0L)
        if (now - lastLaunch < LAUNCH_DEBOUNCE_MS) return false
        prefs.edit().putLong(KEY_LAST_LAUNCH_AT, now).apply()
        return true
    }

    private companion object {
        const val PREFS = "receiver_auto_launch"
        const val KEY_LAST_LAUNCH_AT = "last_launch_at"
        const val LAUNCH_DEBOUNCE_MS = 10_000L
    }
}
