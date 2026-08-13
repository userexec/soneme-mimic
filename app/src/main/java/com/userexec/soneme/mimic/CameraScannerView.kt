package com.userexec.soneme.mimic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Suppress("DEPRECATION")
class CameraScannerView(
    context: Context,
    private val onResult: (Result) -> Unit,
    private val onCameraError: (String) -> Unit
) : FrameLayout(context), SurfaceHolder.Callback {

    private data class Candidate(
        var result: Result,
        var hits: Int,
        val firstAt: Long,
        var lastAt: Long
    )

    private val surface = SurfaceView(context)
    private val reticle = ReticleView(context)
    private var camera: Camera? = null
    private var sensorRotation = 90
    private var previewStarted = false
    private var torch = false
    private var stopped = false
    private var decoding = false
    private var autofocusCapable = false
    private var focusMovingUntil = 0L
    private val decoderExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val candidates = linkedMapOf<String, Candidate>()
    private val reader = MultiFormatReader().apply {
        setHints(EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, Formats.all.map { it.format })
            put(DecodeHintType.TRY_HARDER, true)
            put(DecodeHintType.CHARACTER_SET, "UTF-8")
        })
    }

    private val focusRunnable = object : Runnable {
        override fun run() {
            if (stopped || !previewStarted || !autofocusCapable) return
            val c = camera ?: return
            runCatching {
                // In continuous modes autoFocus() reports/locks the current focus; cancelAutoFocus()
                // in the callback resumes continuous scanning. In AUTO/MACRO, cancel first so each
                // periodic request performs a fresh focus cycle.
                val mode = c.parameters.focusMode
                if (mode == Camera.Parameters.FOCUS_MODE_AUTO || mode == Camera.Parameters.FOCUS_MODE_MACRO) {
                    focusMovingUntil = SystemClock.elapsedRealtime() + FOCUS_MOVE_GUARD_MS
                }
                c.cancelAutoFocus()
                c.autoFocus { _, cam ->
                    if (mode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE ||
                        mode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) {
                        runCatching { cam.cancelAutoFocus() }
                    } else {
                        focusMovingUntil = 0L
                    }
                }
            }
            main.postDelayed(this, FOCUS_INTERVAL_MS)
        }
    }

    init {
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(reticle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surface.holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun start() {
        stopped = false
        candidates.clear()
        if (surface.holder.surface?.isValid == true) openCamera()
    }

    fun stop() {
        stopped = true
        main.removeCallbacks(focusRunnable)
        previewStarted = false
        decoding = false
        autofocusCapable = false
        focusMovingUntil = 0L
        candidates.clear()
        camera?.let { c ->
            runCatching { c.setPreviewCallback(null) }
            runCatching { c.cancelAutoFocus() }
            runCatching { c.stopPreview() }
            runCatching { c.release() }
        }
        camera = null
        torch = false
    }

    fun toggleTorch(): Boolean {
        val c = camera ?: return false
        val params = runCatching { c.parameters }.getOrNull() ?: return false
        val modes = params.supportedFlashModes ?: return false
        val desired = if (torch) Camera.Parameters.FLASH_MODE_OFF else Camera.Parameters.FLASH_MODE_TORCH
        if (desired !in modes) return torch
        params.flashMode = desired
        return runCatching {
            c.parameters = params
            torch = !torch
            torch
        }.getOrDefault(torch)
    }

    fun adjustZoom(direction: Int): Int? {
        if (direction == 0) return currentZoomRatio()
        val c = camera ?: return null
        val p = runCatching { c.parameters }.getOrNull() ?: return null
        if (!p.isZoomSupported) return null
        val ratios = runCatching { p.zoomRatios }.getOrNull().orEmpty()
        if (ratios.isEmpty()) return null

        val maxIndex = minOf(p.maxZoom, ratios.lastIndex)
        val currentIndex = p.zoom.coerceIn(0, maxIndex)
        val currentRatio = ratios[currentIndex]
        val targetRatio = currentRatio + if (direction > 0) ZOOM_STEP_RATIO else -ZOOM_STEP_RATIO
        val candidatesForDirection = ratios.indices.filter { index ->
            index <= maxIndex && if (direction > 0) ratios[index] > currentRatio else ratios[index] < currentRatio
        }
        val nextIndex = candidatesForDirection.minByOrNull { abs(ratios[it] - targetRatio) } ?: currentIndex
        if (nextIndex != currentIndex) {
            p.zoom = nextIndex
            if (!runCatching { c.parameters = p }.isSuccess) return currentRatio
            candidates.clear()
            if (autofocusCapable) {
                focusMovingUntil = SystemClock.elapsedRealtime() + FOCUS_MOVE_GUARD_MS
                main.removeCallbacks(focusRunnable)
                main.postDelayed(focusRunnable, ZOOM_FOCUS_DELAY_MS)
            }
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

    fun toggleReticle(): Boolean {
        reticle.square = !reticle.square
        candidates.clear()
        return reticle.square
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
            val id = findBackCamera()
            camera = if (id >= 0) Camera.open(id) else Camera.open()
            if (id >= 0) {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(id, info)
                sensorRotation = info.orientation
            } else {
                sensorRotation = 90
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

        var p = c.parameters
        if (Camera.Parameters.SCENE_MODE_BARCODE in (p.supportedSceneModes ?: emptyList())) {
            runCatching {
                p.sceneMode = Camera.Parameters.SCENE_MODE_BARCODE
                c.parameters = p
                p = c.parameters // Scene mode is allowed to alter other supported parameters.
            }.onFailure {
                p = c.parameters
            }
        }

        if (ImageFormat.NV21 in (p.supportedPreviewFormats ?: emptyList())) p.previewFormat = ImageFormat.NV21
        choosePreviewSize(p)?.let { p.setPreviewSize(it.width, it.height) }

        // A little zoom lets the fixed-focus-distance limitations of the XP3900 camera work
        // in our favor: the phone can be held farther away while the code still occupies a
        // useful part of the preview. Use the driver's closest advertised ratio to 2x.
        if (p.isZoomSupported) {
            val ratios = runCatching { p.zoomRatios }.getOrNull().orEmpty()
            if (ratios.isNotEmpty()) {
                val targetRatio = DEFAULT_ZOOM_RATIO
                val bestIndex = ratios.indices.minByOrNull { abs(ratios[it] - targetRatio) } ?: 0
                p.zoom = bestIndex.coerceIn(0, p.maxZoom)
            }
        }

        val focusModes = p.supportedFocusModes ?: emptyList()
        p.focusMode = when {
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in focusModes -> Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in focusModes -> Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            Camera.Parameters.FOCUS_MODE_AUTO in focusModes -> Camera.Parameters.FOCUS_MODE_AUTO
            Camera.Parameters.FOCUS_MODE_MACRO in focusModes -> Camera.Parameters.FOCUS_MODE_MACRO
            else -> p.focusMode
        }
        autofocusCapable = p.focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE ||
            p.focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO ||
            p.focusMode == Camera.Parameters.FOCUS_MODE_AUTO ||
            p.focusMode == Camera.Parameters.FOCUS_MODE_MACRO

        c.parameters = p

        // Bias focus/exposure toward the center where the reticle lives when the driver supports it.
        // Apply this as a second, optional parameter update so a quirky legacy driver cannot make
        // the whole camera setup fail merely because it dislikes focus/metering areas.
        runCatching {
            val tuned = c.parameters
            val centerArea = listOf(Camera.Area(Rect(-600, -600, 600, 600), 1000))
            if (tuned.maxNumFocusAreas > 0) tuned.focusAreas = centerArea
            if (tuned.maxNumMeteringAreas > 0) tuned.meteringAreas = centerArea
            c.parameters = tuned
        }

        if (p.focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE ||
            p.focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) {
            runCatching {
                c.setAutoFocusMoveCallback { moving, _ ->
                    focusMovingUntil = if (moving) SystemClock.elapsedRealtime() + FOCUS_MOVE_GUARD_MS else 0L
                }
            }
        }
    }

    private fun startPreview() {
        val c = camera ?: return
        try {
            c.startPreview()
            previewStarted = true
            candidates.clear()
            if (autofocusCapable) main.postDelayed(focusRunnable, INITIAL_FOCUS_DELAY_MS)
            // Let exposure and focus begin settling before asking ZXing to interpret anything.
            main.postDelayed({ requestFrame() }, INITIAL_SCAN_DELAY_MS)
        } catch (e: Exception) {
            onCameraError(e.message ?: "Camera preview could not start.")
        }
    }

    private fun requestFrame() {
        if (stopped || !previewStarted || decoding) return
        val now = SystemClock.elapsedRealtime()
        if (now < focusMovingUntil) {
            main.postDelayed({ requestFrame() }, FRAME_INTERVAL_MS)
            return
        }
        val c = camera ?: return
        runCatching {
            c.setOneShotPreviewCallback { data, cam ->
                if (stopped) return@setOneShotPreviewCallback
                if (data == null) {
                    main.postDelayed({ requestFrame() }, FRAME_INTERVAL_MS)
                    return@setOneShotPreviewCallback
                }
                val size = runCatching { cam.parameters.previewSize }.getOrNull()
                if (size == null) {
                    main.postDelayed({ requestFrame() }, FRAME_INTERVAL_MS)
                    return@setOneShotPreviewCallback
                }
                decoding = true
                val crop = reticle.cropRect(width, height)
                decoderExecutor.execute {
                    val result = runCatching {
                        decodeFrame(data, size.width, size.height, crop, width, height)
                    }.getOrNull()
                    main.post {
                        decoding = false
                        if (stopped) return@post
                        val confirmed = result?.let { considerCandidate(it) }
                        if (confirmed != null) {
                            onResult(confirmed)
                        } else {
                            pruneCandidates()
                            main.postDelayed({ requestFrame() }, FRAME_INTERVAL_MS)
                        }
                    }
                }
            }
        }.onFailure {
            main.postDelayed({ requestFrame() }, FRAME_INTERVAL_MS)
        }
    }

    private fun considerCandidate(result: Result): Result? {
        val now = SystemClock.elapsedRealtime()
        pruneCandidates(now)
        val key = "${result.barcodeFormat}\u0000${result.text}"
        val existing = candidates[key]
        val candidate = if (existing == null) {
            Candidate(result, 1, now, now).also { candidates[key] = it }
        } else {
            existing.result = result
            existing.hits += 1
            existing.lastAt = now
            existing
        }

        val upcEan = result.barcodeFormat == BarcodeFormat.UPC_A ||
            result.barcodeFormat == BarcodeFormat.UPC_E ||
            result.barcodeFormat == BarcodeFormat.EAN_8 ||
            result.barcodeFormat == BarcodeFormat.EAN_13
        val requiredHits = if (upcEan) UPC_EAN_REQUIRED_HITS else DEFAULT_REQUIRED_HITS
        val requiredSpan = if (upcEan) UPC_EAN_REQUIRED_SPAN_MS else DEFAULT_REQUIRED_SPAN_MS
        return if (candidate.hits >= requiredHits && now - candidate.firstAt >= requiredSpan) candidate.result else null
    }

    private fun pruneCandidates(now: Long = SystemClock.elapsedRealtime()) {
        val iterator = candidates.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.lastAt > CANDIDATE_TIMEOUT_MS) iterator.remove()
        }
    }

    private fun decodeFrame(
        data: ByteArray,
        rawWidth: Int,
        rawHeight: Int,
        screenCrop: Rect,
        viewWidth: Int,
        viewHeight: Int
    ): Result? {
        if (rawWidth <= 0 || rawHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
        val rotatedFrame = rotateLuma(data, rawWidth, rawHeight, sensorRotation)
        val rotated = rotatedFrame.first
        val portraitWidth = rotatedFrame.second.first
        val portraitHeight = rotatedFrame.second.second

        val left = (screenCrop.left.toFloat() / viewWidth * portraitWidth).toInt().coerceIn(0, portraitWidth - 1)
        val top = (screenCrop.top.toFloat() / viewHeight * portraitHeight).toInt().coerceIn(0, portraitHeight - 1)
        val right = (screenCrop.right.toFloat() / viewWidth * portraitWidth).toInt().coerceIn(left + 1, portraitWidth)
        val bottom = (screenCrop.bottom.toFloat() / viewHeight * portraitHeight).toInt().coerceIn(top + 1, portraitHeight)

        val source = PlanarYUVLuminanceSource(
            rotated, portraitWidth, portraitHeight,
            left, top, right - left, bottom - top, false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            val result = reader.decodeWithState(bitmap)
            if (plausibleOneDimensionalExtent(result, source.width, source.height)) result else null
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun plausibleOneDimensionalExtent(result: Result, sourceWidth: Int, sourceHeight: Int): Boolean {
        if (!BarcodeCodec.isOneDimensional(result.barcodeFormat)) return true
        val points = result.resultPoints ?: return true
        if (points.size < 2 || sourceWidth <= 0 || sourceHeight <= 0) return true
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val xFraction = (maxX - minX) / sourceWidth.toFloat()
        val yFraction = (maxY - minY) / sourceHeight.toFloat()
        return max(xFraction, yFraction) >= MIN_ONED_EXTENT_FRACTION
    }

    private fun rotateLuma(data: ByteArray, width: Int, height: Int, degrees: Int): Pair<ByteArray, Pair<Int, Int>> {
        val normalized = ((degrees % 360) + 360) % 360
        val out = ByteArray(width * height)
        var i = 0
        when (normalized) {
            90 -> {
                for (x in 0 until width) for (y in height - 1 downTo 0) out[i++] = data[y * width + x]
                return out to (height to width)
            }
            180 -> {
                for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) out[i++] = data[y * width + x]
                return out to (width to height)
            }
            270 -> {
                for (x in width - 1 downTo 0) for (y in 0 until height) out[i++] = data[y * width + x]
                return out to (height to width)
            }
            else -> {
                System.arraycopy(data, 0, out, 0, width * height)
                return out to (width to height)
            }
        }
    }

    private fun choosePreviewSize(p: Camera.Parameters): Camera.Size? {
        val sizes = p.supportedPreviewSizes ?: return null
        return sizes.minByOrNull { size ->
            val areaPenalty = abs(size.width * size.height - 640 * 480)
            val aspectPenalty = abs(size.width.toDouble() / size.height - 4.0 / 3.0) * 100000
            areaPenalty + aspectPenalty.toInt()
        }
    }

    private fun findBackCamera(): Int {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i
        }
        return -1
    }

    override fun onDetachedFromWindow() {
        stop()
        decoderExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val DEFAULT_ZOOM_RATIO = 200 // Camera API uses 100 == 1.0x.
        private const val ZOOM_STEP_RATIO = 25     // About 0.25x per D-pad press.
        private const val ZOOM_FOCUS_DELAY_MS = 80L
        private const val INITIAL_FOCUS_DELAY_MS = 120L
        private const val INITIAL_SCAN_DELAY_MS = 500L
        private const val FOCUS_INTERVAL_MS = 1600L
        private const val FOCUS_MOVE_GUARD_MS = 700L
        private const val FRAME_INTERVAL_MS = 70L
        private const val CANDIDATE_TIMEOUT_MS = 1200L
        private const val DEFAULT_REQUIRED_HITS = 3
        private const val DEFAULT_REQUIRED_SPAN_MS = 260L
        private const val UPC_EAN_REQUIRED_HITS = 5
        private const val UPC_EAN_REQUIRED_SPAN_MS = 650L
        private const val MIN_ONED_EXTENT_FRACTION = 0.24f
    }
}

private class ReticleView(context: Context) : View(context) {
    var square: Boolean = true
        set(value) { field = value; invalidate() }
    private val dimPaint = Paint().apply { color = 0x66000000 }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }

    fun cropRect(w: Int = width, h: Int = height): Rect {
        val margin = (min(w, h) * 0.12f).toInt()
        val rw: Int
        val rh: Int
        if (square) {
            val side = (min(w, h) * 0.72f).toInt()
            rw = side
            rh = side
        } else {
            rw = max(1, w - margin * 2)
            rh = max(1, (h * 0.42f).toInt())
        }
        val l = (w - rw) / 2
        val t = (h - rh) / 2
        return Rect(l, t, l + rw, t + rh)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = cropRect()
        canvas.drawRect(0f, 0f, width.toFloat(), r.top.toFloat(), dimPaint)
        canvas.drawRect(0f, r.bottom.toFloat(), width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, r.top.toFloat(), r.left.toFloat(), r.bottom.toFloat(), dimPaint)
        canvas.drawRect(r.right.toFloat(), r.top.toFloat(), width.toFloat(), r.bottom.toFloat(), dimPaint)
        canvas.drawRect(r, linePaint)
    }
}
