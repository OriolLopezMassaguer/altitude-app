package com.example.altitudeapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import org.osmdroid.util.GeoPoint
import kotlin.math.*

class ElevationProfileView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val MAX_DISTANCE_KM = 50.0

    private val bgPaint = Paint().apply {
        color = Color.argb(180, 20, 20, 20)
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint().apply {
        color = Color.argb(160, 25, 118, 210)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        color = Color.argb(230, 100, 181, 246)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val positionPaint = Paint().apply {
        color = Color.argb(200, 255, 82, 82)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private var samples: List<Pair<Double, Double>> = emptyList() // (distanceKm, altMeters)

    fun setTrackPoints(points: List<GeoPoint>) {
        if (points.size < 2) {
            samples = emptyList()
            invalidate()
            return
        }

        // Build (cumulativeDistance, altitude) pairs from the end
        val result = mutableListOf<Pair<Double, Double>>()
        var totalDist = 0.0
        for (i in points.indices.reversed()) {
            val alt = points[i].altitude
            result.add(0, Pair(totalDist, alt))
            if (i > 0) {
                totalDist += haversineKm(
                    points[i - 1].latitude, points[i - 1].longitude,
                    points[i].latitude, points[i].longitude
                )
                if (totalDist > MAX_DISTANCE_KM) break
            }
        }

        // Re-express distances from start of window
        val windowStart = result.first().first
        samples = result.map { Pair(it.first - windowStart, it.second) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (samples.size < 2) return

        val pad = 36f
        val chartLeft = pad + 10f
        val chartRight = w - 8f
        val chartTop = 8f
        val chartBottom = h - pad

        val minAlt = samples.minOf { it.second }
        val maxAlt = samples.maxOf { it.second }
        val altRange = if (maxAlt - minAlt < 20) 20.0 else maxAlt - minAlt
        val maxDist = samples.last().first.coerceAtLeast(0.1)

        fun xOf(dist: Double) = (chartLeft + ((dist / maxDist) * (chartRight - chartLeft))).toFloat()
        fun yOf(alt: Double) = (chartBottom - ((alt - minAlt) / altRange) * (chartBottom - chartTop)).toFloat()

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, 8f, 8f, bgPaint)

        // Grid lines (3 horizontal)
        for (i in 0..2) {
            val y = chartTop + i * (chartBottom - chartTop) / 2
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }

        // Build path
        val path = Path()
        path.moveTo(xOf(samples.first().first), chartBottom)
        for ((dist, alt) in samples) path.lineTo(xOf(dist), yOf(alt))
        path.lineTo(xOf(samples.last().first), chartBottom)
        path.close()

        canvas.drawPath(path, fillPaint)

        val linePath = Path()
        linePath.moveTo(xOf(samples.first().first), yOf(samples.first().second))
        for ((dist, alt) in samples) linePath.lineTo(xOf(dist), yOf(alt))
        canvas.drawPath(linePath, linePaint)

        // Current position marker (rightmost point)
        val cx = xOf(samples.last().first)
        canvas.drawLine(cx, chartTop, cx, chartBottom, positionPaint)

        // Altitude labels
        val lo = "${minAlt.toInt()}m"
        val hi = "${maxAlt.toInt()}m"
        canvas.drawText(hi, 2f, chartTop + textPaint.textSize, textPaint)
        canvas.drawText(lo, 2f, chartBottom, textPaint)

        // Distance label
        val distLabel = "${String.format("%.0f", maxDist)}km"
        canvas.drawText(distLabel, chartRight - textPaint.measureText(distLabel) - 2f, h - 4f, textPaint)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
