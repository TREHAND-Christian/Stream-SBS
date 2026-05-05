package com.treha.streamsbs.sender

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.registerReceiver
import androidx.lifecycle.lifecycleScope
import com.treha.streamsbs.common.protocol.Ports
import com.treha.streamsbs.common.protocol.ReceiverEndpoint
import com.treha.streamsbs.common.protocol.ReceiverRenderConfig
import com.treha.streamsbs.common.protocol.VideoProfiles
import com.treha.streamsbs.common.util.buildDeviceName
import com.treha.streamsbs.sender.databinding.ActivityMainBinding
import com.treha.streamsbs.sender.network.ReceiverControlClient
import com.treha.streamsbs.sender.network.ReceiverDiscoveryClient
import com.treha.streamsbs.sender.stream.ScreenStreamService
import com.treha.streamsbs.sender.stream.StreamStatusBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        const val PREFS = "sender_prefs"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FPS = "fps"
        const val KEY_BITRATE = "bitrate"
        const val KEY_GLASSES_MODE = "glasses_mode"
        const val KEY_CAMERA = "camera"
        const val KEY_CAMERA_OPACITY = "camera_opacity"
        const val KEY_ZOOM = "zoom"
        const val KEY_VZOOM = "vzoom"
        const val KEY_HOFF = "hoff"
        const val KEY_VOFF = "voff"
        const val KEY_LOCAL_VIDEO_PROFILE_CHANGED_AT = "local_video_profile_changed_at"
        const val KEY_LAST_RECEIVER_CONFIG_VERSION = "last_receiver_config_version"
        private const val REQUEST_NOTIFICATIONS = 44
        private const val LOCAL_VIDEO_PROFILE_GRACE_MS = 5_000L

        fun resolutionIndex(width: Int, height: Int): Int = VideoProfiles.resolutionIndex(width, height)
        fun frameRateIndex(frameRate: Int): Int = VideoProfiles.frameRateIndex(frameRate)
        fun bitRateIndex(bitRate: Int): Int = VideoProfiles.bitRateIndex(bitRate)
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var projectionManager: MediaProjectionManager
    private val discoveryClient = ReceiverDiscoveryClient()
    private val controlClient = ReceiverControlClient()
    private var receiverEndpoint: ReceiverEndpoint? = null
    private var pendingStreamConfig: StreamConfig? = null
    private var configPushJob: Job? = null
    private var profileUpdateJob: Job? = null
    private var receiverDiscoveryJob: Job? = null
    private var applyingReceiverStatus = false
    private var settingsPinnedByUser = false
    private var receiverLatencyLabel: String? = null
    private val logs = ArrayDeque<String>()

    private val streamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                StreamStatusBus.ACTION -> {
                    val line = intent.getStringExtra(StreamStatusBus.EXTRA_LINE) ?: return
                    appendLog(line)
                    if (line == "projection stopped" || line == "stream stopped") {
                        syncStreamUi()
                    }
                }
                StreamStatusBus.ACTION_RECEIVER_STATUS -> {
                    val serializedConfig = intent.getStringExtra(StreamStatusBus.EXTRA_CONFIG) ?: return
                    val config = ReceiverRenderConfig.parse(serializedConfig) ?: return
                    val fields = parseStatusFields(intent.getStringExtra(StreamStatusBus.EXTRA_FIELDS).orEmpty())
                    applyReceiverRenderStatus(config, fields)
                }
                StreamStatusBus.ACTION_STREAM_STATE -> {
                    setStreamRunningUi(intent.getBooleanExtra(StreamStatusBus.EXTRA_RUNNING, false))
                }
            }
        }
    }

    private val captureLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        val config = pendingStreamConfig ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            pendingStreamConfig = null
            setStatus("Screen capture denied")
            syncStreamUi()
            return@registerForActivityResult
        }
        ContextCompat.startForegroundService(
            this,
            ScreenStreamService.startIntent(this, result.resultCode, Intent(result.data), config),
        )
        pendingStreamConfig = null
        setStreamRunningUi(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        binding.deviceText.text = buildDeviceName(Build.MANUFACTURER, Build.MODEL, "Sender")
        requestNotificationPermissionIfNeeded()

        setupVideoControls()
        setupRenderControls()
        renderLabels()
        updateModeUi()
        updateSettingsPanelVisibility()
        setStatus(getString(R.string.status_idle))

        binding.settingsButton.setOnClickListener {
            settingsPinnedByUser = true
            updateSettingsPanelVisibility()
        }
        binding.normalModeButton.setOnClickListener {
            settingsPinnedByUser = false
            updateSettingsPanelVisibility()
        }
        binding.toggleStreamButton.setOnClickListener {
            val wasShowingStop = binding.toggleStreamButton.text == getString(R.string.stop_stream)
            syncStreamUi()
            if (ScreenStreamService.isRunning) {
                stopService(Intent(this, ScreenStreamService::class.java))
                setStreamRunningUi(false)
            } else if (wasShowingStop) {
                setStreamRunningUi(false)
            } else {
                discoverAndStart()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(StreamStatusBus.ACTION)
            addAction(StreamStatusBus.ACTION_RECEIVER_STATUS)
            addAction(StreamStatusBus.ACTION_STREAM_STATE)
        }
        registerReceiver(
            this,
            streamReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverDiscoveryJob = lifecycleScope.launch {
            observeReceiverAvailability()
        }
        syncStreamUi()
    }

    override fun onResume() {
        super.onResume()
        syncStreamUi()
    }

    override fun onStop() {
        receiverDiscoveryJob?.cancel()
        receiverDiscoveryJob = null
        runCatching { unregisterReceiver(streamReceiver) }
        super.onStop()
    }

    private suspend fun observeReceiverAvailability() {
        while (true) {
            val endpoint = discoveryClient.discover(timeoutMs = 1200)
            if (endpoint != null) {
                applyReceiverEndpoint(endpoint)
            } else if (!ScreenStreamService.isRunning && receiverEndpoint == null) {
                binding.receiverText.text = getString(R.string.receiver_not_found)
            }
            delay(3000)
        }
    }

    private fun applyReceiverEndpoint(endpoint: ReceiverEndpoint) {
        val changed = receiverEndpoint?.host != endpoint.host || receiverEndpoint?.deviceName != endpoint.deviceName
        receiverEndpoint = endpoint
        renderReceiverText()
        if (changed) {
            appendLog("receiver found ${endpoint.host}")
            lifecycleScope.launch { pushRenderConfig() }
        }
    }

    private fun setupVideoControls() {
        bindSpinner(
            binding.resolutionSpinner,
            VideoProfiles.resolutions.map { it.label },
            KEY_RESOLUTION,
            VideoProfiles.DEFAULT_RESOLUTION_INDEX,
        )
        bindSpinner(binding.fpsSpinner, VideoProfiles.frameRates.map { it.label }, KEY_FPS, VideoProfiles.DEFAULT_FRAME_RATE_INDEX)
        bindSpinner(
            binding.bitrateSpinner,
            VideoProfiles.bitRates.map { it.label },
            KEY_BITRATE,
            VideoProfiles.DEFAULT_BIT_RATE_INDEX,
        )
    }

    private fun bindSpinner(
        spinner: android.widget.Spinner,
        items: List<String>,
        prefKey: String,
        defaultIndex: Int,
    ) {
        val adapter = ArrayAdapter(this, R.layout.spinner_item_sender, items)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_sender)
        spinner.adapter = adapter
        spinner.setSelection(prefs.getInt(prefKey, defaultIndex).coerceIn(0, items.lastIndex))
        var initialized = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (applyingReceiverStatus) {
                    initialized = true
                    return
                }
                if (initialized) {
                    prefs.edit()
                        .putInt(prefKey, position)
                        .putLong(KEY_LOCAL_VIDEO_PROFILE_CHANGED_AT, SystemClock.elapsedRealtime())
                        .apply()
                    appendLog("video profile changed")
                    schedulePush()
                    if (ScreenStreamService.isRunning) {
                        applyVideoProfile()
                    }
                } else {
                    prefs.edit().putInt(prefKey, position).apply()
                }
                initialized = true
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupRenderControls() {
        binding.glassesModeSwitch.isChecked = prefs.getBoolean(KEY_GLASSES_MODE, true)
        binding.cameraSwitch.isChecked = prefs.getBoolean(KEY_CAMERA, false)
        binding.cameraOpacitySeekBar.max = 100
        binding.zoomSeekBar.max = 200
        binding.verticalZoomSeekBar.max = 200
        binding.horizontalOffsetSeekBar.max = 200
        binding.verticalOffsetSeekBar.max = 200
        binding.cameraOpacitySeekBar.progress = (prefs.getFloat(KEY_CAMERA_OPACITY, 1f) * 100f).toInt()
        binding.zoomSeekBar.progress = ((prefs.getFloat(KEY_ZOOM, 1f) - 0.5f) * 100f).toInt()
        binding.verticalZoomSeekBar.progress = ((prefs.getFloat(KEY_VZOOM, 1f) - 0.5f) * 100f).toInt()
        binding.horizontalOffsetSeekBar.progress = ((prefs.getFloat(KEY_HOFF, 0f) + 1f) * 100f).toInt()
        binding.verticalOffsetSeekBar.progress = ((prefs.getFloat(KEY_VOFF, 0f) + 1f) * 100f).toInt()

        binding.glassesModeSwitch.setOnCheckedChangeListener { _, value ->
            if (applyingReceiverStatus) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(KEY_GLASSES_MODE, value).apply()
            updateModeUi()
            renderLabels()
            schedulePush()
        }
        binding.cameraSwitch.setOnCheckedChangeListener { _, value ->
            if (applyingReceiverStatus) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(KEY_CAMERA, value).apply()
            renderLabels()
            schedulePush()
        }

        bindSeek(binding.cameraOpacitySeekBar, KEY_CAMERA_OPACITY) { it / 100f }
        bindSeek(binding.zoomSeekBar, KEY_ZOOM) { 0.5f + (it / 100f) }
        bindSeek(binding.verticalZoomSeekBar, KEY_VZOOM) { 0.5f + (it / 100f) }
        bindSeek(binding.horizontalOffsetSeekBar, KEY_HOFF) { (it / 100f) - 1f }
        bindSeek(binding.verticalOffsetSeekBar, KEY_VOFF) { (it / 100f) - 1f }
    }

    private fun bindSeek(seekBar: SeekBar, key: String, mapper: (Int) -> Float) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit().putFloat(key, mapper(progress)).apply()
                renderLabels()
                if (fromUser) schedulePush()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun renderLabels() {
        val glassesMode = isGlassesMode()
        val config = currentRenderConfig()
        binding.cameraOpacityLabel.text = getString(R.string.camera_opacity, config.cameraOpacity * 100f)
        binding.zoomLabel.text = if (glassesMode) {
            "Zoom %.2fx".format(config.zoom)
        } else {
            getString(R.string.render_inactive_tablet, getString(R.string.zoom_label))
        }
        binding.verticalZoomLabel.text = if (glassesMode) {
            "Vertical Zoom %.2fx".format(config.verticalZoom)
        } else {
            getString(R.string.render_inactive_tablet, getString(R.string.vertical_zoom_label))
        }
        binding.horizontalOffsetLabel.text = if (glassesMode) {
            "Horizontal Offset %.2f".format(config.horizontalOffset)
        } else {
            getString(R.string.render_inactive_tablet, getString(R.string.horizontal_offset_label))
        }
        binding.verticalOffsetLabel.text = if (glassesMode) {
            "Vertical Offset %.2f".format(config.verticalOffset)
        } else {
            getString(R.string.render_inactive_tablet, getString(R.string.vertical_offset_label))
        }
    }

    private fun updateModeUi() {
        val glassesMode = isGlassesMode()
        binding.glassesModeSwitch.text = if (glassesMode) {
            getString(R.string.mode_glasses)
        } else {
            getString(R.string.mode_tablet)
        }
        setRenderControlsEnabled(glassesMode)
    }

    private fun setRenderControlsEnabled(enabled: Boolean) {
        binding.zoomSeekBar.isEnabled = enabled
        binding.verticalZoomSeekBar.isEnabled = enabled
        binding.horizontalOffsetSeekBar.isEnabled = enabled
        binding.verticalOffsetSeekBar.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.45f
        binding.zoomLabel.alpha = alpha
        binding.verticalZoomLabel.alpha = alpha
        binding.horizontalOffsetLabel.alpha = alpha
        binding.verticalOffsetLabel.alpha = alpha
        binding.zoomSeekBar.alpha = alpha
        binding.verticalZoomSeekBar.alpha = alpha
        binding.horizontalOffsetSeekBar.alpha = alpha
        binding.verticalOffsetSeekBar.alpha = alpha
    }

    private fun isGlassesMode(): Boolean = prefs.getBoolean(KEY_GLASSES_MODE, true)

    private fun discoverAndStart() {
        setStatus(getString(R.string.status_searching))
        lifecycleScope.launch {
            val endpoint = discoveryClient.discover()
            if (endpoint == null) {
                setStatus(getString(R.string.receiver_not_found))
                return@launch
            }
            applyReceiverEndpoint(endpoint)
            pushRenderConfig()
            pendingStreamConfig = currentStreamConfig(endpoint.host, endpoint.deviceName)
            setStatus(getString(R.string.status_select_full_screen_capture))
            captureLauncher.launch(createCaptureIntent())
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun applyVideoProfile() {
        val endpoint = receiverEndpoint ?: return
        val config = currentStreamConfig(endpoint.host, endpoint.deviceName)
        profileUpdateJob?.cancel()
        profileUpdateJob = lifecycleScope.launch {
            setStatus(getString(R.string.status_applying_profile))
            delay(100)
            startService(ScreenStreamService.updateProfileIntent(this@MainActivity, config))
            syncStreamUi()
        }
    }

    private fun syncStreamUi() {
        setStreamRunningUi(ScreenStreamService.isRunning)
    }

    private fun setStreamRunningUi(running: Boolean) {
        binding.toggleStreamButton.text = getString(
            if (running) R.string.stop_stream else R.string.start_stream,
        )
        if (running) {
            setStatus(getString(R.string.status_streaming))
        } else if (binding.statusText.text == getString(R.string.status_streaming) ||
            binding.statusText.text == getString(R.string.status_applying_profile)
        ) {
            setStatus(getString(R.string.status_idle))
        }
    }

    private fun createCaptureIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay(),
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
    }

    private fun schedulePush() {
        configPushJob?.cancel()
        configPushJob = lifecycleScope.launch {
            delay(100)
            pushRenderConfig()
        }
    }

    private suspend fun pushRenderConfig() {
        val endpoint = receiverEndpoint ?: return
        runCatching {
            controlClient.send(endpoint.host, currentRenderConfig())
            appendLog("receiver config synced")
        }.onFailure {
            appendLog("config push failed: ${it.message}")
        }
    }

    private fun currentRenderConfig(): ReceiverRenderConfig {
        val glassesMode = isGlassesMode()
        return ReceiverRenderConfig(
            sbsEnabled = glassesMode,
            fullFrameEnabled = glassesMode,
            cameraEnabled = prefs.getBoolean(KEY_CAMERA, false),
            cameraOpacity = prefs.getFloat(KEY_CAMERA_OPACITY, 1f),
            zoom = if (glassesMode) prefs.getFloat(KEY_ZOOM, 1f) else 1f,
            verticalZoom = if (glassesMode) prefs.getFloat(KEY_VZOOM, 1f) else 1f,
            horizontalOffset = if (glassesMode) prefs.getFloat(KEY_HOFF, 0f) else 0f,
            verticalOffset = if (glassesMode) prefs.getFloat(KEY_VOFF, 0f) else 0f,
            videoWidth = currentResolution().width,
            videoHeight = currentResolution().height,
            videoFrameRate = currentFrameRate(),
            videoBitRate = currentBitRate(),
        )
    }

    private fun applyReceiverRenderStatus(config: ReceiverRenderConfig, fields: Map<String, String>) {
        val receiverConfigChanged = markReceiverConfigVersion(fields)
        val effectiveConfig = if (!receiverConfigChanged && localVideoProfileChangeIsFresh()) {
            config.copy(
                videoWidth = currentResolution().width,
                videoHeight = currentResolution().height,
                videoFrameRate = currentFrameRate(),
                videoBitRate = currentBitRate(),
            )
        } else {
            config
        }
        receiverLatencyLabel = buildLatencyLabel(fields)
        renderReceiverText()
        applyingReceiverStatus = true
        prefs.edit()
            .putBoolean(KEY_GLASSES_MODE, effectiveConfig.sbsEnabled)
            .putInt(KEY_RESOLUTION, resolutionIndex(effectiveConfig.videoWidth, effectiveConfig.videoHeight))
            .putInt(KEY_FPS, frameRateIndex(effectiveConfig.videoFrameRate))
            .putInt(KEY_BITRATE, bitRateIndex(effectiveConfig.videoBitRate))
            .putBoolean(KEY_CAMERA, effectiveConfig.cameraEnabled)
            .putFloat(KEY_CAMERA_OPACITY, effectiveConfig.cameraOpacity)
            .putFloat(KEY_ZOOM, effectiveConfig.zoom)
            .putFloat(KEY_VZOOM, effectiveConfig.verticalZoom)
            .putFloat(KEY_HOFF, effectiveConfig.horizontalOffset)
            .putFloat(KEY_VOFF, effectiveConfig.verticalOffset)
            .apply()
        binding.glassesModeSwitch.isChecked = effectiveConfig.sbsEnabled
        binding.resolutionSpinner.setSelection(resolutionIndex(effectiveConfig.videoWidth, effectiveConfig.videoHeight))
        binding.fpsSpinner.setSelection(frameRateIndex(effectiveConfig.videoFrameRate))
        binding.bitrateSpinner.setSelection(bitRateIndex(effectiveConfig.videoBitRate))
        binding.cameraSwitch.isChecked = effectiveConfig.cameraEnabled
        binding.cameraOpacitySeekBar.progress = (effectiveConfig.cameraOpacity * 100f).toInt().coerceIn(0, 100)
        binding.zoomSeekBar.progress = ((effectiveConfig.zoom - 0.5f) * 100f).toInt().coerceIn(0, 200)
        binding.verticalZoomSeekBar.progress = ((effectiveConfig.verticalZoom - 0.5f) * 100f).toInt().coerceIn(0, 200)
        binding.horizontalOffsetSeekBar.progress = ((effectiveConfig.horizontalOffset + 1f) * 100f).toInt().coerceIn(0, 200)
        binding.verticalOffsetSeekBar.progress = ((effectiveConfig.verticalOffset + 1f) * 100f).toInt().coerceIn(0, 200)
        applyingReceiverStatus = false
        if (effectiveConfig !== config) {
            schedulePush()
        }
        updateModeUi()
        renderLabels()
        appendLog("receiver local settings synced")
    }

    private fun markReceiverConfigVersion(fields: Map<String, String>): Boolean {
        val version = fields["receiver_config_version"]?.toLongOrNull() ?: return false
        val lastVersion = prefs.getLong(KEY_LAST_RECEIVER_CONFIG_VERSION, 0L)
        if (version <= lastVersion) return false
        prefs.edit()
            .putLong(KEY_LAST_RECEIVER_CONFIG_VERSION, version)
            .putLong(KEY_LOCAL_VIDEO_PROFILE_CHANGED_AT, 0L)
            .apply()
        return true
    }

    private fun localVideoProfileChangeIsFresh(): Boolean {
        val changedAt = prefs.getLong(KEY_LOCAL_VIDEO_PROFILE_CHANGED_AT, 0L)
        return changedAt > 0L && SystemClock.elapsedRealtime() - changedAt <= LOCAL_VIDEO_PROFILE_GRACE_MS
    }

    private fun buildLatencyLabel(fields: Map<String, String>): String? {
        val latency = fields["video_latency_ms"] ?: return null
        val average = fields["video_latency_avg_ms"]
        val fps = fields["video_fps"]
        val width = fields["video_width"]
        val height = fields["video_height"]
        val targetFps = fields["video_target_fps"]
        return buildString {
            if (width != null && height != null) {
                append(width)
                append("x")
                append(height)
                if (targetFps != null) {
                    append("@")
                    append(targetFps)
                }
                append(" | ")
            }
            append("Lat ")
            append(latency)
            append(" ms")
            if (average != null) {
                append(" moy ")
                append(average)
                append(" ms")
            }
            if (fps != null) {
                append(" ")
                append(fps)
                append(" fps")
            }
        }
    }

    private fun renderReceiverText() {
        val endpoint = receiverEndpoint
        if (endpoint == null) {
            binding.receiverText.text = getString(R.string.receiver_not_found)
            return
        }
        val base = getString(
            R.string.receiver_found,
            endpoint.deviceName,
            endpoint.host,
            Ports.STREAM,
        )
        binding.receiverText.text = receiverLatencyLabel?.let { "$base | $it" } ?: base
    }

    private fun updateSettingsPanelVisibility() {
        binding.settingsPage.visibility = if (settingsPinnedByUser) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.streamPage.visibility = if (settingsPinnedByUser) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun currentStreamConfig(host: String, name: String): StreamConfig {
        val resolution = currentResolution()
        return StreamConfig(
            receiverHost = host,
            receiverName = name,
            width = resolution.width,
            height = resolution.height,
            frameRate = currentFrameRate(),
            bitRate = currentBitRate(),
        )
    }

    private fun currentResolution() =
        VideoProfiles.resolutions[prefs.getInt(KEY_RESOLUTION, VideoProfiles.DEFAULT_RESOLUTION_INDEX).coerceIn(0, VideoProfiles.resolutions.lastIndex)]

    private fun currentFrameRate(): Int =
        VideoProfiles.frameRates[prefs.getInt(KEY_FPS, VideoProfiles.DEFAULT_FRAME_RATE_INDEX).coerceIn(0, VideoProfiles.frameRates.lastIndex)].value

    private fun currentBitRate(): Int =
        VideoProfiles.bitRates[prefs.getInt(KEY_BITRATE, VideoProfiles.DEFAULT_BIT_RATE_INDEX).coerceIn(0, VideoProfiles.bitRates.lastIndex)].value

    private fun parseStatusFields(message: String): Map<String, String> =
        message.split(';').mapNotNull { field ->
            val index = field.indexOf('=')
            if (index <= 0) return@mapNotNull null
            field.substring(0, index) to field.substring(index + 1)
        }.toMap()

    private fun setStatus(text: String) {
        binding.statusText.text = text
        appendLog(text)
    }

    private fun appendLog(text: String) {
        if (logs.size >= 10) logs.removeFirst()
        logs.addLast(text)
    }
}
