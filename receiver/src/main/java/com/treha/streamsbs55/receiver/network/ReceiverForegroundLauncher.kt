package com.treha.streamsbs55.receiver.network

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.treha.streamsbs55.receiver.MainActivity

object ReceiverForegroundLauncher {
    fun bringToFront(context: Context, force: Boolean = false) {
        if (!force && !canLaunchNow(context)) return

        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
