package com.treha.streamsbs55.receiver

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.treha.streamsbs55.common.protocol.ReceiverRenderConfig
import com.treha.streamsbs55.common.protocol.Ports
import com.treha.streamsbs55.common.protocol.VideoProfiles
import com.treha.streamsbs55.receiver.databinding.ActivityMainBinding
import com.treha.streamsbs55.receiver.camera.CameraGpuController
import com.treha.streamsbs55.receiver.network.ControlServer
import com.treha.streamsbs55.receiver.network.DiscoveryResponder
import com.treha.streamsbs55.receiver.stream.UdpVideoReceiver
import com.treha.streamsbs55.receiver.stream.VideoStats
import com.treha.streamsbs55.receiver.view.MenuOverlayRow
import com.treha.streamsbs55.receiver.view.MenuOverlayState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : AppCompatActivity() {
    private companion object {
        const val LONG_PRESS_MS = 450L
    }

    private lateinit var binding: ActivityMainBinding
    private var discoveryJob: Job? = null
    private var controlJob: Job? = null
    private var receiverJob: Job? = null
    private var udpReceiver: UdpVideoReceiver? = null
    private var renderConfig: ReceiverRenderConfig = ReceiverRenderConfig()
    private var overlayJob: Job? = null
    private var lastModeKey: String? = null
    private var cameraSurface: android.view.Surface? = null
    private var cameraController: CameraGpuController? = null
    private var menuVisible = false
    private var menuEditing = false
    private var selectedMenuIndex = 0
    private var screenBlackout = false
    private var volumeUpPress: ButtonPress? = null
    private var volumeDownPress: ButtonPress? = null
    private var senderAddress: InetAddress? = null
    private var latestVideoStats: VideoStats? = null

    private enum class MenuItem(val label: String) {
        RESOLUTION("Definition"),
        FRAME_RATE("FPS"),
        BIT_RATE("Bitrate"),
        CAMERA_OPACITY("Opacite cam"),
        ZOOM_H("Zoom H"),
        ZOOM_V("Zoom V"),
        OFFSET_H("Callage H"),
        OFFSET_V("Callage V"),
    }

    private data class ButtonPress(
        val job: Job,
        var longPressTriggered: Boolean = false,
    )

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            applyCameraState()
        } else {
            setOverlayText("camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configurePresentation()
        hideSystemBars()
        prepareOverlayLayers()

        binding.streamView.applyConfig(renderConfig)
        binding.streamView.whenSurfaceReady { surface ->
            if (udpReceiver != null) return@whenSurfaceReady
            udpReceiver = UdpVideoReceiver(surface, ::setOverlayText, ::onVideoStats)
            receiverJob = lifecycleScope.launch { udpReceiver?.run() }
        }
        binding.streamView.whenCameraSurfaceReady { surface ->
            cameraSurface = surface
            applyCameraState()
        }

        discoveryJob = lifecycleScope.launch {
            DiscoveryResponder(deviceName = android.os.Build.MODEL ?: "Receiver").run()
        }
        controlJob = lifecycleScope.launch {
            ControlServer { config, address ->
                runOnUiThread {
                    senderAddress = address
                    val previousKey = displayModeKey(renderConfig)
                    renderConfig = config
                    binding.streamView.applyConfig(config)
                    applyCameraState()
                    if (menuVisible) {
                        renderMenu()
                    } else if (displayModeKey(config) != previousKey) {
                        showDisplayModeOverlay(config)
                    }
                }
            }.run()
        }

        showDisplayModeOverlay(renderConfig)
    }

    override fun onResume() {
        super.onResume()
        configurePresentation()
        hideSystemBars()
        if (menuVisible) renderMenu()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configurePresentation()
            hideSystemBars()
            if (menuVisible) renderMenu()
        }
    }

    override fun onDestroy() {
        overlayJob?.cancel()
        receiverJob?.cancel()
        controlJob?.cancel()
        discoveryJob?.cancel()
        udpReceiver?.close()
        cameraController?.close()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                handleVolumeUp(event)
                true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleVolumeDown(event)
                true
            }

            KeyEvent.KEYCODE_POWER -> {
                if (event.action == KeyEvent.ACTION_DOWN) toggleBlackout()
                true
            }

            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun showDisplayModeOverlay(config: ReceiverRenderConfig) {
        if (menuVisible) return
        binding.root.post {
            if (menuVisible) return@post
            val screenWidth = maxOf(binding.root.width, binding.root.height)
            val screenHeight = minOf(binding.root.width, binding.root.height)
            val rects = binding.streamView.computeDisplayRects()
            if (screenWidth <= 0 || screenHeight <= 0 || rects.isEmpty()) return@post

            val modeLabel = if (config.sbsEnabled) "Mode Lunette" else "Mode Tablette"
            val framesLabel = if (rects.size > 1) {
                val eye = rects.first()
                "2 x ${eye.width()}x${eye.height()}"
            } else {
                val frame = rects.first()
                "1 x ${frame.width()}x${frame.height()}"
            }
            val overlayText = "$modeLabel | écran ${screenWidth}x${screenHeight} | cadres $framesLabel"
            val overlayKey = "$modeLabel|$screenWidth|$screenHeight|$framesLabel"
            if (overlayKey == lastModeKey && binding.displayModeText.visibility == android.view.View.VISIBLE) return@post
            lastModeKey = overlayKey
            showOverlayText(overlayText)
            overlayJob?.cancel()
            overlayJob = lifecycleScope.launch {
                delay(3000)
                if (!menuVisible && binding.displayModeText.text == overlayText) {
                    binding.displayModeText.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun setOverlayText(text: String) {
        binding.displayModeText.post {
            if (!menuVisible) {
                showOverlayText(text)
            }
        }
    }

    private fun onVideoStats(stats: VideoStats) {
        latestVideoStats = stats
        binding.displayModeText.post {
            if (menuVisible) return@post
            val profile = stats.profile
            val profileLabel = if (profile.width > 0 && profile.height > 0) {
                "${profile.width}x${profile.height} @${profile.targetFps} fps"
            } else {
                "profil inconnu"
            }
            showOverlayText("$profileLabel | rendu ${"%.1f".format(stats.fps)} fps | lat ${stats.latencyMs} ms")
        }
        sendRenderConfigToSender()
    }

    private fun applyCameraState() {
        val surface = cameraSurface ?: return
        if (!renderConfig.cameraEnabled) {
            cameraController?.close()
            cameraController = null
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (cameraController == null) {
            cameraController = CameraGpuController(this, surface, ::setOverlayText)
        }
        cameraController?.start()
    }

    private fun handleVolumeUp(event: KeyEvent) {
        handleVolumeKey(event, isVolumeUp = true)
    }

    private fun handleVolumeDown(event: KeyEvent) {
        handleVolumeKey(event, isVolumeUp = false)
    }

    private fun handleVolumeKey(event: KeyEvent, isVolumeUp: Boolean) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return
                startPendingVolumePress(isVolumeUp)
            }
            KeyEvent.ACTION_UP -> finishPendingVolumePress(isVolumeUp)
        }
    }

    private fun startPendingVolumePress(isVolumeUp: Boolean) {
        cancelPendingPress(isVolumeUp)
        val press = ButtonPress(
            job = lifecycleScope.launch {
                delay(LONG_PRESS_MS)
                val current = pendingPress(isVolumeUp) ?: return@launch
                current.longPressTriggered = true
                performVolumeLongPress(isVolumeUp)
            },
        )
        setPendingPress(isVolumeUp, press)
    }

    private fun finishPendingVolumePress(isVolumeUp: Boolean) {
        val press = pendingPress(isVolumeUp) ?: return
        press.job.cancel()
        setPendingPress(isVolumeUp, null)
        if (!press.longPressTriggered) {
            performVolumeClick(isVolumeUp)
        }
    }

    private fun cancelPendingPress(isVolumeUp: Boolean) {
        pendingPress(isVolumeUp)?.job?.cancel()
        setPendingPress(isVolumeUp, null)
    }

    private fun pendingPress(isVolumeUp: Boolean): ButtonPress? =
        if (isVolumeUp) volumeUpPress else volumeDownPress

    private fun setPendingPress(isVolumeUp: Boolean, press: ButtonPress?) {
        if (isVolumeUp) {
            volumeUpPress = press
        } else {
            volumeDownPress = press
        }
    }

    private fun performVolumeClick(isVolumeUp: Boolean) {
        if (!menuVisible) {
            if (isVolumeUp) toggleCamera() else openMenu()
            return
        }
        if (menuEditing) {
            adjustSelectedItem(positive = isVolumeUp)
            return
        }
        selectedMenuIndex = if (isVolumeUp) {
            (selectedMenuIndex + 1) % MenuItem.entries.size
        } else {
            (selectedMenuIndex - 1 + MenuItem.entries.size) % MenuItem.entries.size
        }
        renderMenu()
    }

    private fun performVolumeLongPress(isVolumeUp: Boolean) {
        if (!menuVisible) {
            if (!isVolumeUp) openMenu()
            return
        }
        if (menuEditing) {
            menuEditing = false
            renderMenu()
            return
        }
        if (isVolumeUp) {
            menuEditing = true
            renderMenu()
        } else {
            closeMenu()
        }
    }

    private fun toggleCamera() {
        renderConfig = renderConfig.copy(cameraEnabled = !renderConfig.cameraEnabled)
        applyRenderConfig()
        showTemporaryMessage(if (renderConfig.cameraEnabled) "Camera ON" else "Camera OFF")
    }

    private fun openMenu() {
        menuVisible = true
        menuEditing = false
        binding.displayModeText.visibility = View.GONE
        renderMenu()
    }

    private fun closeMenu() {
        menuVisible = false
        menuEditing = false
        binding.streamView.setMenuOverlay(null)
        sendRenderConfigToSender()
    }

    private fun adjustSelectedItem(positive: Boolean) {
        val step = if (positive) 1f else -1f
        renderConfig = when (MenuItem.entries[selectedMenuIndex]) {
            MenuItem.RESOLUTION -> {
                val current = VideoProfiles.resolutionIndex(renderConfig.videoWidth, renderConfig.videoHeight)
                val next = nextIndex(current, VideoProfiles.resolutions.size, positive)
                val profile = VideoProfiles.resolutions[next]
                renderConfig.copy(videoWidth = profile.width, videoHeight = profile.height)
            }
            MenuItem.FRAME_RATE -> {
                val current = VideoProfiles.frameRateIndex(renderConfig.videoFrameRate)
                val next = nextIndex(current, VideoProfiles.frameRates.size, positive)
                renderConfig.copy(videoFrameRate = VideoProfiles.frameRates[next].value)
            }
            MenuItem.BIT_RATE -> {
                val current = VideoProfiles.bitRateIndex(renderConfig.videoBitRate)
                val next = nextIndex(current, VideoProfiles.bitRates.size, positive)
                renderConfig.copy(videoBitRate = VideoProfiles.bitRates[next].value)
            }
            MenuItem.CAMERA_OPACITY -> renderConfig.copy(
                cameraOpacity = (renderConfig.cameraOpacity + step * 0.05f).coerceIn(0f, 1f),
            )
            MenuItem.ZOOM_V -> renderConfig.copy(
                verticalZoom = (renderConfig.verticalZoom + step * 0.05f).coerceIn(0.5f, 2.5f),
            )
            MenuItem.ZOOM_H -> renderConfig.copy(
                zoom = (renderConfig.zoom + step * 0.05f).coerceIn(0.5f, 2.5f),
            )
            MenuItem.OFFSET_V -> renderConfig.copy(
                verticalOffset = (renderConfig.verticalOffset + step * 0.05f).coerceIn(-1f, 1f),
            )
            MenuItem.OFFSET_H -> renderConfig.copy(
                horizontalOffset = (renderConfig.horizontalOffset + step * 0.05f).coerceIn(-1f, 1f),
            )
        }
        applyRenderConfig()
        renderMenu()
    }

    private fun nextIndex(current: Int, size: Int, positive: Boolean): Int =
        if (positive) {
            (current + 1) % size
        } else {
            (current - 1 + size) % size
        }

    private fun applyRenderConfig() {
        binding.streamView.applyConfig(renderConfig)
        applyCameraState()
        sendRenderConfigToSender()
    }

    private fun sendRenderConfigToSender() {
        val address = senderAddress ?: return
        val status = buildString {
            append(renderConfig.serialize())
            append(";remote_active=")
            append(if (menuVisible) 1 else 0)
            append(";remote_index=")
            append(selectedMenuIndex)
            append(";remote_editing=")
            append(if (menuEditing) 1 else 0)
            latestVideoStats?.let { stats ->
                append(";video_latency_ms=")
                append(stats.latencyMs)
                append(";video_latency_avg_ms=")
                append(stats.averageLatencyMs)
                append(";video_fps=")
                append(String.format(java.util.Locale.US, "%.1f", stats.fps))
                if (stats.profile.width > 0 && stats.profile.height > 0) {
                    append(";video_width=")
                    append(stats.profile.width)
                    append(";video_height=")
                    append(stats.profile.height)
                    append(";video_target_fps=")
                    append(stats.profile.targetFps)
                    append(";video_bitrate_kbps=")
                    append(stats.profile.bitrateKbps)
                }
            }
        }
        val payload = status.toByteArray(Charsets.UTF_8)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.send(DatagramPacket(payload, payload.size, address, Ports.STATUS))
                }
            }
        }
    }

    private fun renderMenu() {
        overlayJob?.cancel()
        binding.displayModeText.visibility = View.GONE
        binding.streamView.setMenuOverlay(menuState())
        sendRenderConfigToSender()
    }

    private fun menuState(): MenuOverlayState {
        val title = if (menuEditing) "REGLAGES  [EDITION]" else "REGLAGES"
        return MenuOverlayState(
            title = title,
            rows = MenuItem.entries.mapIndexed { index, item ->
                MenuOverlayRow(
                    label = item.label,
                    value = menuValue(item),
                    selected = index == selectedMenuIndex,
                    editing = menuEditing && index == selectedMenuIndex,
                )
            },
        )
    }

    private fun menuValue(item: MenuItem): String =
        when (item) {
            MenuItem.RESOLUTION -> VideoProfiles.resolutions[
                VideoProfiles.resolutionIndex(renderConfig.videoWidth, renderConfig.videoHeight)
            ].label
            MenuItem.FRAME_RATE -> VideoProfiles.frameRates[
                VideoProfiles.frameRateIndex(renderConfig.videoFrameRate)
            ].label
            MenuItem.BIT_RATE -> VideoProfiles.bitRates[
                VideoProfiles.bitRateIndex(renderConfig.videoBitRate)
            ].label
            MenuItem.CAMERA_OPACITY -> "${(renderConfig.cameraOpacity * 100f).toInt()}%"
            MenuItem.ZOOM_H -> "%.2fx".format(renderConfig.zoom)
            MenuItem.ZOOM_V -> "%.2fx".format(renderConfig.verticalZoom)
            MenuItem.OFFSET_H -> "%.2f".format(renderConfig.horizontalOffset)
            MenuItem.OFFSET_V -> "%.2f".format(renderConfig.verticalOffset)
        }

    private fun showTemporaryMessage(text: String) {
        if (menuVisible) return
        showOverlayText(text)
        overlayJob?.cancel()
        overlayJob = lifecycleScope.launch {
            delay(1200)
            if (!menuVisible && binding.displayModeText.text == text) {
                binding.displayModeText.visibility = View.GONE
            }
        }
    }

    private fun prepareOverlayLayers() {
        binding.streamView.setZOrderOnTop(false)
        binding.streamView.setZOrderMediaOverlay(false)
        binding.blackOverlay.elevation = 8f
        binding.displayModeText.elevation = 16f
        binding.displayModeText.bringToFront()
    }

    private fun showOverlayText(text: String) {
        binding.displayModeText.text = text
        binding.displayModeText.visibility = View.VISIBLE
        binding.displayModeText.bringToFront()
    }

    private fun toggleBlackout() {
        screenBlackout = !screenBlackout
        binding.blackOverlay.visibility = if (screenBlackout) View.VISIBLE else View.GONE
    }

    private fun displayModeKey(config: ReceiverRenderConfig): String =
        if (config.sbsEnabled) "glasses" else "tablet"

    private fun configurePresentation() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.requestDismissKeyguard(this, null)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
