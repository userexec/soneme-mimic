package com.userexec.soneme.mimic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import kotlin.math.floor
import kotlin.math.max

class BarcodeView(context: Context) : View(context) {
    private var matrix: BitMatrix? = null
    private var format: BarcodeFormat = BarcodeFormat.QR_CODE
    var rotation: Int = -1
        set(value) { field = value; invalidate() }
    var inverted: Boolean = false
        set(value) { field = value; invalidate() }

    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

    fun setCode(payload: String, format: BarcodeFormat) {
        this.format = format
        matrix = BarcodeCodec.encode(payload, format)
        invalidate()
    }

    fun effectiveRotation(): Int {
        if (rotation >= 0) return normalized(rotation)
        val m = matrix ?: return 0
        val scale0 = scaleFor(m, 0)
        val scale90 = scaleFor(m, 90)
        return when {
            scale90 > scale0 -> 90
            scale90 == scale0 && m.width > m.height && height > width -> 90
            else -> 0
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = matrix ?: return
        val fg = if (inverted) Color.WHITE else Color.BLACK
        val bg = if (inverted) Color.BLACK else Color.WHITE
        canvas.drawColor(bg)
        paint.color = fg

        val r = effectiveRotation()
        if (BarcodeCodec.isOneDimensional(format)) drawOneDimensional(canvas, m, r)
        else drawTwoDimensional(canvas, m, r)
    }

    private fun drawOneDimensional(canvas: Canvas, m: BitMatrix, r: Int) {
        val scale = max(1, scaleFor(m, r))
        val quietPixels = oneDimensionalQuietPixels()
        val axisLength = m.width * scale

        if (r % 180 == 0) {
            val left = (width - axisLength) / 2
            val top = quietPixels
            val bottom = height - quietPixels
            if (bottom <= top) return
            for (x in 0 until m.width) {
                if (!m[x, 0]) continue
                val dx = if (r == 180) m.width - 1 - x else x
                val l = left + dx * scale
                canvas.drawRect(l.toFloat(), top.toFloat(), (l + scale).toFloat(), bottom.toFloat(), paint)
            }
        } else {
            val top = (height - axisLength) / 2
            val left = quietPixels
            val right = width - quietPixels
            if (right <= left) return
            for (x in 0 until m.width) {
                if (!m[x, 0]) continue
                val dy = if (r == 270) m.width - 1 - x else x
                val t = top + dy * scale
                canvas.drawRect(left.toFloat(), t.toFloat(), right.toFloat(), (t + scale).toFloat(), paint)
            }
        }
    }

    private fun drawTwoDimensional(canvas: Canvas, m: BitMatrix, r: Int) {
        val quiet = BarcodeCodec.quietModules(format)
        val mw = if (r % 180 == 0) m.width else m.height
        val mh = if (r % 180 == 0) m.height else m.width
        val scale = max(1, floor(minOf(width.toDouble() / (mw + quiet * 2), height.toDouble() / (mh + quiet * 2))).toInt())
        val symbolW = mw * scale
        val symbolH = mh * scale
        val left = (width - symbolW) / 2
        val top = (height - symbolH) / 2

        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (!m[x, y]) continue
                val (rx, ry) = rotate(x, y, m.width, m.height, r)
                val l = left + rx * scale
                val t = top + ry * scale
                canvas.drawRect(l.toFloat(), t.toFloat(), (l + scale).toFloat(), (t + scale).toFloat(), paint)
            }
        }
    }

    private fun scaleFor(m: BitMatrix, degrees: Int): Int {
        if (width <= 0 || height <= 0) return 0
        if (BarcodeCodec.isOneDimensional(format)) {
            val axisPixels = if (degrees % 180 == 0) width else height
            val usableAxis = max(1, axisPixels - oneDimensionalQuietPixels() * 2)
            return floor(usableAxis.toDouble() / m.width).toInt()
        }
        val quiet = BarcodeCodec.quietModules(format)
        val mw = if (degrees % 180 == 0) m.width else m.height
        val mh = if (degrees % 180 == 0) m.height else m.width
        return floor(minOf(width.toDouble() / (mw + quiet * 2), height.toDouble() / (mh + quiet * 2))).toInt()
    }

    private fun oneDimensionalQuietPixels(): Int =
        max(6, (resources.displayMetrics.density * 8f).toInt())

    private fun rotate(x: Int, y: Int, w: Int, h: Int, degrees: Int): Pair<Int, Int> = when (normalized(degrees)) {
        90 -> (h - 1 - y) to x
        180 -> (w - 1 - x) to (h - 1 - y)
        270 -> y to (w - 1 - x)
        else -> x to y
    }

    private fun normalized(v: Int) = ((v % 360) + 360) % 360
}
