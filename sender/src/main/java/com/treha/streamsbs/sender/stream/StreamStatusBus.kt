package com.treha.streamsbs.sender.stream

import android.content.Context
import android.content.Intent

object StreamStatusBus {
    const val ACTION = "com.treha.streamsbs.sender.STREAM_STATUS"
    const val ACTION_RECEIVER_STATUS = "com.treha.streamsbs.sender.RECEIVER_STATUS"
    const val ACTION_STREAM_STATE = "com.treha.streamsbs.sender.STREAM_STATE"
    const val EXTRA_LINE = "line"
    const val EXTRA_CONFIG = "config"
    const val EXTRA_FIELDS = "fields"
    const val EXTRA_RUNNING = "running"

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

    fun emitStreamState(context: Context, running: Boolean) {
        context.sendBroadcast(
            Intent(ACTION_STREAM_STATE)
                .setPackage(context.packageName)
                .putExtra(EXTRA_RUNNING, running),
        )
    }
}
