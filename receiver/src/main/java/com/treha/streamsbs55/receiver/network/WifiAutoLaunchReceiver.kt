package com.treha.streamsbs55.receiver.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

class WifiAutoLaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return
        if (!isConnectedToWifi(context)) return
        ReceiverForegroundLauncher.bringToFront(context)
    }

    private fun isConnectedToWifi(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

}
