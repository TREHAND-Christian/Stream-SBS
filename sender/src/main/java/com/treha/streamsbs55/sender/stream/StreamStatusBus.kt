package com.treha.streamsbs55.sender.stream

import android.content.Context
import android.content.Intent

object StreamStatusBus {
    const val ACTION = "com.treha.streamsbs55.sender.STREAM_STATUS"
    const val ACTION_RECEIVER_STATUS = "com.treha.streamsbs55.sender.RECEIVER_STATUS"
    const val EXTRA_LINE = "line"
    const val EXTRA_CONFIG = "config"
    const val EXTRA_FIELDS = "fields"

    fun emit(context: Context, line: String) {
        context.sendBroadcast(
            Intent(ACTION)
                .setPackage(context.packageName)
                .putExtra(EXTRA_LINE, line),
        )
    }

    fun emitReceiverStatus(context: Context, config: String, fields: Map<String, String>) {
        context.sendBroadcast(
            Intent(ACTION_RECEIVER_STATUS)
                .setPackage(context.packageName)
                .putExtra(EXTRA_CONFIG, config)
                .putExtra(EXTRA_FIELDS, fields.entries.joinToString(";") { "${it.key}=${it.value}" }),
        )
    }
}
