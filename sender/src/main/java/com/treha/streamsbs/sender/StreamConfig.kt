package com.treha.streamsbs.sender

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class StreamConfig(
    val receiverHost: String,
    val receiverName: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitRate: Int,
) : Parcelable
