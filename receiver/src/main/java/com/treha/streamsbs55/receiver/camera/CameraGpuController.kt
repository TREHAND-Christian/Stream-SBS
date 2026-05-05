package com.treha.streamsbs55.receiver.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

class CameraGpuController(
    private val context: Context,
    private val surface: Surface,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("receiver-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var started = false
    private val cameraExecutor = Executor { command -> cameraHandler.post(command) }

    fun start() {
        if (started) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onStatus("camera permission missing")
            return
        }
        val cameraId = rearCameraId() ?: run {
            onStatus("rear camera not found")
            return
        }
        started = true
        @Suppress("MissingPermission")
        cameraManager.openCamera(cameraId, deviceCallback, cameraHandler)
    }

    fun stop() {
        started = false
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
    }

    private fun rearCameraId(): String? =
        cameraManager.cameraIdList.firstOrNull { id ->
            val facing = cameraManager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            stop()
            onStatus("camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            stop()
            onStatus("camera error $error")
        }
    }

    private fun createSession(camera: CameraDevice) {
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(request.build(), null, cameraHandler)
                    onStatus("camera active")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onStatus("camera configure failed")
                }
            },
        )
        camera.createCaptureSession(sessionConfig)
    }

    override fun close() {
        stop()
        cameraThread.quitSafely()
    }
}
