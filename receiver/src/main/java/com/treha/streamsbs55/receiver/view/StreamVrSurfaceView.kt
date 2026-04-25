package com.treha.streamsbs55.receiver.view

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.Surface
import com.treha.streamsbs55.common.protocol.ReceiverRenderConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class StreamVrSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {
    private val renderer = StreamRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun whenSurfaceReady(callback: (Surface) -> Unit) {
        renderer.whenStreamSurfaceReady(this::requestRender, callback)
    }

    fun whenCameraSurfaceReady(callback: (Surface) -> Unit) {
        renderer.whenCameraSurfaceReady(this::requestRender, callback)
    }

    fun applyConfig(config: ReceiverRenderConfig) {
        renderer.config = config
        requestRender()
    }

    fun computeDisplayRects(): List<Rect> = renderer.computeDisplayRects(width, height)
}

private class StreamRenderer : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    @Volatile
    var config: ReceiverRenderConfig = ReceiverRenderConfig()

    private val streamFrameAvailable = AtomicBoolean(false)
    private val cameraFrameAvailable = AtomicBoolean(false)
    private val streamReadyCallback = AtomicReference<((Surface) -> Unit)?>(null)
    private val cameraReadyCallback = AtomicReference<((Surface) -> Unit)?>(null)
    private var requestRender: (() -> Unit)? = null
    private var streamSurfaceTexture: SurfaceTexture? = null
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var streamSurface: Surface? = null
    private var cameraSurface: Surface? = null
    private var streamTextureId = 0
    private var cameraTextureId = 0
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var mvpHandle = 0
    private var texMatrixHandle = 0
    private var alphaHandle = 0
    private val streamTexMatrix = FloatArray(16)
    private val cameraTexMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16)
    private val cameraCorrectionMatrix = FloatArray(16)
    private var width = 1
    private var height = 1
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTICES)
            position(0)
        }

    fun whenStreamSurfaceReady(invalidator: () -> Unit, callback: (Surface) -> Unit) {
        requestRender = invalidator
        streamSurface?.let(callback) ?: streamReadyCallback.set(callback)
        invalidator()
    }

    fun whenCameraSurfaceReady(invalidator: () -> Unit, callback: (Surface) -> Unit) {
        requestRender = invalidator
        cameraSurface?.let(callback) ?: cameraReadyCallback.set(callback)
        invalidator()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
        streamTextureId = createExternalTexture()
        cameraTextureId = createExternalTexture()

        streamSurfaceTexture = SurfaceTexture(streamTextureId).apply {
            setOnFrameAvailableListener(this@StreamRenderer)
        }
        cameraSurfaceTexture = SurfaceTexture(cameraTextureId).apply {
            setDefaultBufferSize(CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT)
            setOnFrameAvailableListener(this@StreamRenderer)
        }
        streamSurface = Surface(streamSurfaceTexture)
        cameraSurface = Surface(cameraSurfaceTexture)
        streamReadyCallback.getAndSet(null)?.invoke(checkNotNull(streamSurface))
        cameraReadyCallback.getAndSet(null)?.invoke(checkNotNull(cameraSurface))
        Matrix.setIdentityM(streamTexMatrix, 0)
        Matrix.setIdentityM(cameraTexMatrix, 0)
        Matrix.setIdentityM(identityMatrix, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (streamFrameAvailable.compareAndSet(true, false)) {
            streamSurfaceTexture?.updateTexImage()
            streamSurfaceTexture?.getTransformMatrix(streamTexMatrix)
        }
        if (cameraFrameAvailable.compareAndSet(true, false)) {
            cameraSurfaceTexture?.updateTexImage()
            cameraSurfaceTexture?.getTransformMatrix(cameraTexMatrix)
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        drawTexture(
            textureId = streamTextureId,
            texMatrix = streamTexMatrix,
            alpha = 1f,
            base = identityMatrix,
            mvpHandle = mvpHandle,
            texMatrixHandle = texMatrixHandle,
            alphaHandle = alphaHandle,
        )
        if (config.cameraEnabled && config.cameraOpacity > 0f) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            drawTexture(
                textureId = cameraTextureId,
                texMatrix = correctedCameraTexMatrix(),
                alpha = config.cameraOpacity.coerceIn(0f, 1f),
                base = identityMatrix,
                mvpHandle = mvpHandle,
                texMatrixHandle = texMatrixHandle,
                alphaHandle = alphaHandle,
            )
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun correctedCameraTexMatrix(): FloatArray {
        cameraTexMatrix.copyInto(cameraCorrectionMatrix)
        Matrix.translateM(cameraCorrectionMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(cameraCorrectionMatrix, 0, -90f, 0f, 0f, 1f)
        Matrix.translateM(cameraCorrectionMatrix, 0, -0.5f, -0.5f, 0f)
        return cameraCorrectionMatrix
    }

    private fun drawTexture(
        textureId: Int,
        texMatrix: FloatArray,
        alpha: Float,
        base: FloatArray,
        mvpHandle: Int,
        texMatrixHandle: Int,
        alphaHandle: Int,
    ) {
        GLES20.glUniform1f(alphaHandle, alpha)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        if (config.sbsEnabled) {
            drawSbsTexture(base, mvpHandle)
        } else {
            drawMono(base, mvpHandle)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        when (surfaceTexture) {
            streamSurfaceTexture -> streamFrameAvailable.set(true)
            cameraSurfaceTexture -> cameraFrameAvailable.set(true)
        }
        requestRender?.invoke()
    }

    fun computeDisplayRects(viewWidth: Int, viewHeight: Int): List<Rect> {
        if (viewWidth <= 0 || viewHeight <= 0) return emptyList()
        return if (config.sbsEnabled) {
            val eyeWidth = viewWidth / 2
            val frameHeight = if (config.fullFrameEnabled) {
                (eyeWidth * 9f / 16f).toInt().coerceAtLeast(1)
            } else {
                viewHeight
            }
            val top = if (config.fullFrameEnabled) {
                ((viewHeight - frameHeight) / 2).coerceAtLeast(0)
            } else {
                0
            }
            listOf(
                Rect(0, top, eyeWidth, top + frameHeight),
                Rect(eyeWidth, top, eyeWidth * 2, top + frameHeight),
            )
        } else {
            val frameHeight = if (config.fullFrameEnabled) {
                (viewWidth * 9f / 16f).toInt().coerceAtLeast(1)
            } else {
                viewHeight
            }
            val top = if (config.fullFrameEnabled) {
                ((viewHeight - frameHeight) / 2).coerceAtLeast(0)
            } else {
                0
            }
            listOf(Rect(0, top, viewWidth, top + frameHeight))
        }
    }

    private fun drawSbsTexture(base: FloatArray, handle: Int) {
        drawEye(base, true, handle)
        drawEye(base, false, handle)
    }

    private fun drawEye(base: FloatArray, left: Boolean, handle: Int) {
        val matrix = base.copyOf()
        Matrix.scaleM(matrix, 0, config.zoom, config.verticalZoom, 1f)
        val shift = if (left) -config.horizontalOffset else config.horizontalOffset
        Matrix.translateM(matrix, 0, shift * 0.2f, config.verticalOffset * 0.2f, 0f)
        GLES20.glUniformMatrix4fv(handle, 1, false, matrix, 0)
        val eyeWidth = width / 2
        val viewportHeight = if (config.fullFrameEnabled) (eyeWidth * 9f / 16f).toInt().coerceAtLeast(1) else height
        val viewportY = if (config.fullFrameEnabled) ((height - viewportHeight) / 2).coerceAtLeast(0) else 0
        GLES20.glViewport(if (left) 0 else eyeWidth, viewportY, eyeWidth, viewportHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawMono(base: FloatArray, handle: Int) {
        val matrix = base.copyOf()
        Matrix.scaleM(matrix, 0, config.zoom, config.verticalZoom, 1f)
        Matrix.translateM(matrix, 0, config.horizontalOffset * 0.2f, config.verticalOffset * 0.2f, 0f)
        GLES20.glUniformMatrix4fv(handle, 1, false, matrix, 0)
        val viewportHeight = if (config.fullFrameEnabled) (width * 9f / 16f).toInt().coerceAtLeast(1) else height
        val viewportY = if (config.fullFrameEnabled) ((height - viewportHeight) / 2).coerceAtLeast(0) else 0
        GLES20.glViewport(0, viewportY, width, viewportHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun createExternalTexture(): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textureIds[0]
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        return GLES20.glCreateProgram().also { programId ->
            GLES20.glAttachShader(programId, vertexShader)
            GLES20.glAttachShader(programId, fragmentShader)
            GLES20.glLinkProgram(programId)
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }

    companion object {
        private val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            uniform float uAlpha;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            uniform float uAlpha;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(sTexture, vec2(vTexCoord.x, 1.0 - vTexCoord.y));
                gl_FragColor = vec4(color.rgb, color.a * uAlpha);
            }
        """

        private const val CAMERA_PREVIEW_WIDTH = 1280
        private const val CAMERA_PREVIEW_HEIGHT = 720
    }
}
