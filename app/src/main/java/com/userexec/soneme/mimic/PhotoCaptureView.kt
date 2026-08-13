package com.userexec.soneme.mimic

import android.content.Context
import android.graphics.Color
import android.hardware.Camera
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import java.io.File

@Suppress("DEPRECATION")
class PhotoCaptureView(
    context: Context,
    private val outputFile: File,
    private val onCaptured: (File) -> Unit,
    private val onCameraError: (String) -> Unit
) : FrameLayout(context), SurfaceHolder.Callback {

    private val surface = SurfaceView(context)
    private var camera: Camera? = null
    private var cameraId = -1
    private var sensorRotation = 90
    private var previewStarted = false
    private var stopped = false
    private var capturing = false
    private var torch = false

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surface.holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun start() {
        stopped = false
        if (surface.holder.surface?.isValid == true) openCamera()
    }

    fun stop() {
        stopped = true
        previewStarted = false
        capturing = false
        camera?.let { c ->
            runCatching { c.cancelAutoFocus() }
            runCatching { c.stopPreview() }
            runCatching { c.release() }
        }
        camera = null
        torch = false
    }

    fun toggleTorch(): Boolean? {
        val c = camera ?: return null
        val p = runCatching { c.parameters }.getOrNull() ?: return null
        val modes = p.supportedFlashModes ?: return null
        val desired = if (torch) Camera.Parameters.FLASH_MODE_OFF else Camera.Parameters.FLASH_MODE_TORCH
        if (desired !in modes) return null
        p.flashMode = desired
        return runCatching {
            c.parameters = p
            torch = !torch
            torch
        }.getOrNull()
    }

    fun adjustZoom(direction: Int): Int? {
        if (direction == 0) return currentZoomRatio()
        val c = camera ?: return null
        val p = runCatching { c.parameters }.getOrNull() ?: return null
        if (!p.isZoomSupported) return null
        val ratios = runCatching { p.zoomRatios }.getOrNull().orEmpty()
        if (ratios.isEmpty()) return null

        val currentIndex = p.zoom.coerceIn(0, minOf(p.maxZoom, ratios.lastIndex))
        val currentRatio = ratios[currentIndex]
        val targetRatio = currentRatio + if (direction > 0) ZOOM_STEP_RATIO else -ZOOM_STEP_RATIO
        val candidates = ratios.indices.filter { index ->
            index <= p.maxZoom && if (direction > 0) ratios[index] > currentRatio else ratios[index] < currentRatio
        }
        val nextIndex = candidates.minByOrNull { kotlin.math.abs(ratios[it] - targetRatio) } ?: currentIndex
        if (nextIndex != currentIndex) {
            p.zoom = nextIndex
            if (!runCatching { c.parameters = p }.isSuccess) return currentRatio
        }
        return ratios[nextIndex]
    }

    private fun currentZoomRatio(): Int? {
        val p = runCatching { camera?.parameters }.getOrNull() ?: return null
        if (!p.isZoomSupported) return null
        val ratios = runCatching { p.zoomRatios }.getOrNull().orEmpty()
        if (ratios.isEmpty()) return null
        return ratios[p.zoom.coerceIn(0, minOf(p.maxZoom, ratios.lastIndex))]
    }

    fun capture() {
        if (stopped || capturing || !previewStarted) return
        val c = camera ?: return
        capturing = true
        val takePicture = {
            runCatching {
                c.takePicture(null, null) { data, _ ->
                    if (data == null) {
                        capturing = false
                        restartPreview()
                    } else {
                        Thread {
                            val saved = runCatching {
                                outputFile.parentFile?.mkdirs()
                                outputFile.writeBytes(data)
                                outputFile
                            }.getOrNull()
                            post {
                                if (stopped) {
                                    saved?.delete()
                                    return@post
                                }
                                if (saved != null) {
                                    stop()
                                    onCaptured(saved)
                                } else {
                                    capturing = false
                                    restartPreview()
                                    onCameraError("Photo could not be saved.")
                                }
                            }
                        }.start()
                    }
                }
            }.onFailure {
                capturing = false
                restartPreview()
                onCameraError(it.message ?: "Photo could not be captured.")
            }
        }

        val params = runCatching { c.parameters }.getOrNull()
        val focusMode = params?.focusMode
        val canAutofocus = focusMode == Camera.Parameters.FOCUS_MODE_AUTO ||
            focusMode == Camera.Parameters.FOCUS_MODE_MACRO ||
            focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE ||
            focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        if (canAutofocus) {
            runCatching {
                c.autoFocus { _, _ -> takePicture() }
            }.onFailure { takePicture() }
        } else {
            takePicture()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!stopped) openCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (camera != null && !previewStarted) startPreview()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stop()
    }

    private fun openCamera() {
        if (camera != null || stopped) return
        try {
            cameraId = findBackCamera()
            camera = if (cameraId >= 0) Camera.open(cameraId) else Camera.open()
            if (cameraId >= 0) {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(cameraId, info)
                sensorRotation = info.orientation
            }
            camera?.setPreviewDisplay(surface.holder)
            configureCamera()
            startPreview()
        } catch (e: Exception) {
            stop()
            onCameraError(e.message ?: "Camera could not be opened.")
        }
    }

    private fun configureCamera() {
        val c = camera ?: return
        c.setDisplayOrientation(sensorRotation)
        val p = c.parameters

        val largest = p.supportedPictureSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        largest?.let { p.setPictureSize(it.width, it.height) }

        val pictureRatio = largest?.let { it.width.toDouble() / it.height.toDouble() } ?: (4.0 / 3.0)
        val preview = p.supportedPreviewSizes?.minByOrNull { size ->
            val ratioPenalty = kotlin.math.abs(size.width.toDouble() / size.height.toDouble() - pictureRatio) * 1_000_000.0
            val areaPenalty = kotlin.math.abs(size.width.toLong() * size.height.toLong() - 1280L * 960L).toDouble()
            ratioPenalty + areaPenalty
        }
        preview?.let { p.setPreviewSize(it.width, it.height) }

        val modes = p.supportedFocusModes ?: emptyList()
        p.focusMode = when {
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in modes -> Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            Camera.Parameters.FOCUS_MODE_AUTO in modes -> Camera.Parameters.FOCUS_MODE_AUTO
            Camera.Parameters.FOCUS_MODE_MACRO in modes -> Camera.Parameters.FOCUS_MODE_MACRO
            else -> p.focusMode
        }

        // The XP3900 camera focuses more reliably on card-sized subjects when the phone can
        // be held farther away. Start near 2x and let the D-pad adjust zoom while framing.
        if (p.isZoomSupported) {
            val ratios = runCatching { p.zoomRatios }.getOrNull().orEmpty()
            if (ratios.isNotEmpty()) {
                val bestIndex = ratios.indices
                    .filter { it <= p.maxZoom }
                    .minByOrNull { kotlin.math.abs(ratios[it] - DEFAULT_ZOOM_RATIO) }
                    ?: 0
                p.setZoom(bestIndex)
            }
        }
        p.setRotation(sensorRotation)
        c.parameters = p
    }

    private fun startPreview() {
        val c = camera ?: return
        try {
            c.startPreview()
            previewStarted = true
        } catch (e: Exception) {
            onCameraError(e.message ?: "Camera preview could not start.")
        }
    }

    private fun restartPreview() {
        val c = camera ?: return
        runCatching {
            c.startPreview()
            previewStarted = true
        }
    }

    private fun findBackCamera(): Int {
        for (i in 0 until Camera.getNumberOfCameras()) {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i
        }
        return -1
    }

    companion object {
        private const val DEFAULT_ZOOM_RATIO = 200 // Camera API uses 100 == 1.0x.
        private const val ZOOM_STEP_RATIO = 25     // About 0.25x per D-pad press.
    }
}
