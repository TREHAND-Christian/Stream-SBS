package com.treha.streamsbs55.sender.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.treha.streamsbs55.common.protocol.Ports
import com.treha.streamsbs55.common.protocol.ReceiverRenderConfig
import com.treha.streamsbs55.common.protocol.VideoProfiles
import com.treha.streamsbs55.sender.MainActivity
import com.treha.streamsbs55.sender.R
import com.treha.streamsbs55.sender.StreamConfig
import com.treha.streamsbs55.sender.network.ReceiverRenderStatusListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer

class ScreenStreamService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var udpSender: UdpVideoSender? = null
    private var encodeJob: Job? = null
    private var receiverStatusJob: Job? = null
    private var activeConfig: StreamConfig? = null
    private val pipelineMutex = Mutex()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            log("projection stopped")
            stopSelf()
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            log("captured content visible: $isVisible")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            log("captured content size ${width}x${height}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        StreamStatusBus.emitStreamState(this, true)
        createChannel()
        startReceiverStatusListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val safeIntent = intent ?: return stopInvalidStart()
        val config = safeIntent.getParcelableCompat<StreamConfig>(EXTRA_CONFIG) ?: return stopInvalidStart()

        when (safeIntent.action) {
            ACTION_UPDATE_PROFILE -> updateProfile(config)
            else -> startWithProjection(safeIntent, config)
        }
        return START_STICKY
    }

    private fun stopInvalidStart(): Int {
        log("stream start ignored: missing start data")
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runBlocking { tearDownAll() }
        receiverStatusJob?.cancel()
        receiverStatusJob = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        serviceScope.cancel()
        isRunning = false
        StreamStatusBus.emitStreamState(this, false)
        StreamStatusBus.emit(this, "stream stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        log("sender task moved away; stream service remains active")
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun startPipeline(config: StreamConfig) {
        tearDownPipeline()
        val sender = createVideoSender(config)
        val (newEncoder, newSurface) = try {
            createEncoder(config)
        } catch (error: Throwable) {
            sender.close()
            throw error
        }
        try {
            attachVirtualDisplay(config, newSurface)
        } catch (error: Throwable) {
            releaseEncoder(newEncoder, newSurface)
            sender.close()
            throw error
        }
        udpSender = sender
        encoder = newEncoder
        inputSurface = newSurface
        activeConfig = config
        encodeJob = launchEncodeLoop(newEncoder, sender)
        updateNotification(config)
        requestKeyFrame()
        log("stream active ${config.width}x${config.height}@${config.frameRate}")
    }

    private suspend fun switchPipeline(config: StreamConfig) {
        val oldJob = encodeJob
        val oldEncoder = encoder
        val oldSurface = inputSurface
        val oldSender = udpSender

        val newSender = createVideoSender(config)
        val (newEncoder, newSurface) = try {
            createEncoder(config)
        } catch (error: Throwable) {
            newSender.close()
            throw error
        }

        try {
            attachVirtualDisplay(config, newSurface)
        } catch (error: Throwable) {
            releaseEncoder(newEncoder, newSurface)
            newSender.close()
            throw error
        }

        udpSender = newSender
        encoder = newEncoder
        inputSurface = newSurface
        activeConfig = config
        encodeJob = launchEncodeLoop(newEncoder, newSender)
        updateNotification(config)
        requestKeyFrame()
        log("stream active ${config.width}x${config.height}@${config.frameRate}")

        oldJob?.cancelAndJoin()
        releaseEncoder(oldEncoder, oldSurface)
        oldSender?.close()
    }

    private fun createEncoder(config: StreamConfig): Pair<MediaCodec, Surface> {
        val codec = MediaCodec.createEncoderByType(MIME)
        var surface: Surface? = null
        try {
            codec.configure(
                createVideoFormat(config),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            surface = codec.createInputSurface()
            codec.start()
            return codec to surface
        } catch (error: Throwable) {
            releaseEncoder(codec, surface)
            throw error
        }
    }

    private fun createVideoFormat(config: StreamConfig): MediaFormat =
        MediaFormat.createVideoFormat(MIME, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, config.frameRate)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }
            setFloat("max-fps-to-encoder", config.frameRate.toFloat())
            setFloat(MediaFormat.KEY_CAPTURE_RATE, config.frameRate.toFloat())
            setInteger("low-latency", 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / config.frameRate.coerceAtLeast(1))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }

    private fun createVideoSender(config: StreamConfig): UdpVideoSender =
        UdpVideoSender(
            host = config.receiverHost,
            port = Ports.STREAM,
            width = config.width,
            height = config.height,
            frameRate = config.frameRate,
            bitRate = config.bitRate,
            logger = ::log,
        )

    private fun attachVirtualDisplay(config: StreamConfig, surface: Surface) {
        val display = virtualDisplay
        if (display == null) {
            virtualDisplay = projection?.createVirtualDisplay(
                "stream-sbs-55-sender",
                config.width,
                config.height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )
            checkNotNull(virtualDisplay) { "MediaProjection unavailable" }
            return
        }

        display.resize(config.width, config.height, resources.displayMetrics.densityDpi)
        display.setSurface(surface)
    }

    private fun launchEncodeLoop(codec: MediaCodec, sender: UdpVideoSender): Job =
        serviceScope.launch {
            val info = MediaCodec.BufferInfo()
            while (isActive) {
                when (val outputIndex = codec.dequeueOutputBuffer(info, 1_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sender.onOutputFormatChanged(codec.outputFormat)
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        val codecConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (buffer != null && info.size > 0 && !codecConfig) {
                            sender.sendFrame(
                                data = buffer.copyRange(info.offset, info.size),
                                ptsUs = info.presentationTimeUs,
                                flags = info.flags,
                            )
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

    private fun startWithProjection(intent: Intent, config: StreamConfig) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.getParcelableCompat<Intent>(EXTRA_RESULT_DATA) ?: return

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Streaming to ${config.receiverName}"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        serviceScope.launch {
            runCatching {
                pipelineMutex.withLock {
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    projection?.unregisterCallback(projectionCallback)
                    projection = manager.getMediaProjection(resultCode, resultData)
                    projection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                    startPipeline(config)
                }
            }.onFailure {
                log("stream start failed: ${it.message}")
                stopSelf()
            }
        }
    }

    private fun updateProfile(config: StreamConfig) {
        if (projection == null) {
            log("profile update ignored: projection unavailable")
            stopSelf()
            return
        }
        serviceScope.launch {
            runCatching {
                pipelineMutex.withLock {
                    val current = activeConfig
                    if (current != null && canUpdateBitrateOnly(current, config)) {
                        updateBitrate(config)
                    } else {
                        log("applying video profile ${config.width}x${config.height}@${config.frameRate}")
                        switchPipeline(config)
                    }
                }
            }.onFailure {
                log("profile update failed: ${it.message}")
            }
        }
    }

    private fun canUpdateBitrateOnly(current: StreamConfig, next: StreamConfig): Boolean =
        current.receiverHost == next.receiverHost &&
            current.width == next.width &&
            current.height == next.height &&
            current.frameRate == next.frameRate

    private fun updateBitrate(config: StreamConfig) {
        val codec = encoder ?: return
        runCatching {
            codec.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, config.bitRate)
                },
            )
            activeConfig = config
            updateNotification(config)
            requestKeyFrame()
            log("bitrate applied ${config.bitRate / 1_000_000} Mbps")
        }.onFailure {
            log("bitrate update failed: ${it.message}")
        }
    }

    private fun startReceiverStatusListener() {
        if (receiverStatusJob != null) return
        receiverStatusJob = serviceScope.launch {
            runCatching {
                ReceiverRenderStatusListener { config, fields ->
                    handleReceiverStatus(config, fields)
                }.run()
            }.onFailure {
                log("receiver status stopped: ${it.message}")
            }
        }
    }

    private fun handleReceiverStatus(config: ReceiverRenderConfig, fields: Map<String, String>) {
        val effectiveConfig = keepRecentLocalVideoProfile(config)
        StreamStatusBus.emitReceiverStatus(this, effectiveConfig.serialize(), fields)
        saveReceiverSettings(effectiveConfig)

        val current = activeConfig ?: return
        val next = current.copy(
            width = effectiveConfig.videoWidth,
            height = effectiveConfig.videoHeight,
            frameRate = effectiveConfig.videoFrameRate,
            bitRate = effectiveConfig.videoBitRate,
        )
        if (next.width == current.width &&
            next.height == current.height &&
            next.frameRate == current.frameRate &&
            next.bitRate == current.bitRate
        ) {
            return
        }
        updateProfile(next)
    }

    private fun keepRecentLocalVideoProfile(config: ReceiverRenderConfig): ReceiverRenderConfig {
        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val changedAt = prefs.getLong(MainActivity.KEY_LOCAL_VIDEO_PROFILE_CHANGED_AT, 0L)
        val isFresh = changedAt > 0L && SystemClock.elapsedRealtime() - changedAt <= LOCAL_VIDEO_PROFILE_GRACE_MS
        if (!isFresh) return config

        val resolution = VideoProfiles.resolutions[
            prefs.getInt(MainActivity.KEY_RESOLUTION, VideoProfiles.DEFAULT_RESOLUTION_INDEX)
                .coerceIn(0, VideoProfiles.resolutions.lastIndex),
        ]
        return config.copy(
            videoWidth = resolution.width,
            videoHeight = resolution.height,
            videoFrameRate = VideoProfiles.frameRates[
                prefs.getInt(MainActivity.KEY_FPS, VideoProfiles.DEFAULT_FRAME_RATE_INDEX)
                    .coerceIn(0, VideoProfiles.frameRates.lastIndex),
            ].value,
            videoBitRate = VideoProfiles.bitRates[
                prefs.getInt(MainActivity.KEY_BITRATE, VideoProfiles.DEFAULT_BIT_RATE_INDEX)
                    .coerceIn(0, VideoProfiles.bitRates.lastIndex),
            ].value,
        )
    }

    private fun saveReceiverSettings(config: ReceiverRenderConfig) {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putInt(MainActivity.KEY_RESOLUTION, MainActivity.resolutionIndex(config.videoWidth, config.videoHeight))
            .putInt(MainActivity.KEY_FPS, MainActivity.frameRateIndex(config.videoFrameRate))
            .putInt(MainActivity.KEY_BITRATE, MainActivity.bitRateIndex(config.videoBitRate))
            .putBoolean(MainActivity.KEY_GLASSES_MODE, config.sbsEnabled)
            .putBoolean(MainActivity.KEY_CAMERA, config.cameraEnabled)
            .putFloat(MainActivity.KEY_CAMERA_OPACITY, config.cameraOpacity)
            .putFloat(MainActivity.KEY_ZOOM, config.zoom)
            .putFloat(MainActivity.KEY_VZOOM, config.verticalZoom)
            .putFloat(MainActivity.KEY_HOFF, config.horizontalOffset)
            .putFloat(MainActivity.KEY_VOFF, config.verticalOffset)
            .apply()
    }

    private fun requestKeyFrame() {
        runCatching {
            encoder?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                },
            )
        }
    }

    private suspend fun tearDownAll() {
        tearDownPipeline()
    }

    private suspend fun tearDownPipeline() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        encodeJob?.cancelAndJoin()
        encodeJob = null
        releaseEncoder(encoder, null)
        encoder = null
        udpSender?.close()
        udpSender = null
        activeConfig = null
    }

    private fun releaseEncoder(codec: MediaCodec?, surface: Surface?) {
        runCatching { surface?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sender stream", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .build()
    }

    private fun updateNotification(config: StreamConfig) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification("Streaming to ${config.receiverName}"))
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        StreamStatusBus.emit(this, message)
    }

    companion object {
        private const val MIME = "video/avc"
        private const val TAG = "ScreenStreamService"
        private const val CHANNEL_ID = "stream_sender"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.treha.streamsbs55.sender.START_STREAM"
        private const val ACTION_UPDATE_PROFILE = "com.treha.streamsbs55.sender.UPDATE_PROFILE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_CONFIG = "config"
        private const val LOCAL_VIDEO_PROFILE_GRACE_MS = 5_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startIntent(context: Context, resultCode: Int, data: Intent, config: StreamConfig): Intent =
            Intent(context, ScreenStreamService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_CONFIG, config)
            }

        fun updateProfileIntent(context: Context, config: StreamConfig): Intent =
            Intent(context, ScreenStreamService::class.java).apply {
                action = ACTION_UPDATE_PROFILE
                putExtra(EXTRA_CONFIG, config)
            }
    }
}

private inline fun <reified T> Intent.getParcelableCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

private fun ByteBuffer.copyRange(offset: Int, size: Int): ByteArray {
    val duplicate = duplicate()
    duplicate.position(offset)
    duplicate.limit(offset + size)
    return ByteArray(size).also { duplicate.get(it) }
}
