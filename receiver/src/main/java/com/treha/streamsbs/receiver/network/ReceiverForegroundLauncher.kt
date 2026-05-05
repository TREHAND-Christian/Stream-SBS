package com.treha.streamsbs.receiver.network

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.treha.streamsbs.receiver.MainActivity

object ReceiverForegroundLauncher {
    const val ACTION_RENDER_CONFIG = "com.treha.streamsbs.receiver.RENDER_CONFIG"
    const val EXTRA_CONFIG = "config"
    const val EXTRA_HOST = "host"

    fun bringToFront(
        context: Context,
        force: Boolean = false,
        serializedConfig: String? = null,
        senderHost: String? = null,
    ) {
        if (!force && !canLaunchNow(context)) return

        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    serializedConfig?.let { putExtra(EXTRA_CONFIG, it) }
                    senderHost?.let { putExtra(EXTRA_HOST, it) }
                },
            )
        }
    }

    private fun canLaunchNow(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val lastLaunch = prefs.getLong(KEY_LAST_LAUNCH_AT, 0L)
        if (now - lastLaunch < LAUNCH_DEBOUNCE_MS) return false
        prefs.edit().putLong(KEY_LAST_LAUNCH_AT, now).apply()
        return true
    }

    private const val PREFS = "receiver_foreground_launcher"
    private const val KEY_LAST_LAUNCH_AT = "last_launch_at"
    private const val LAUNCH_DEBOUNCE_MS = 10_000L
}
