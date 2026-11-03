package com.example.screenai

import android.graphics.Bitmap
import kotlin.math.max

/** Experimental local controller for Geometry Dash's classic cube mode. */
class GeometryDashAutopilot {
    private var lastObstacleX = -1f
    private var lastTapAt = 0L
    private var armed = true
    private var stableFrames = 0

    data class Decision(val tap: Boolean, val status: String)

    fun reset() {
        lastObstacleX = -1f
        lastTapAt = 0L
        armed = true
        stableFrames = 0
    }

    fun analyze(frame: Bitmap, now: Long = System.currentTimeMillis()): Decision {
        val width = frame.width
        val height = frame.height
        if (width < 200 || height < 200) return Decision(false, "Кадр слишком маленький")
        val xStart = (width * 0.30f).toInt()
        val xEnd = (width * 0.82f).toInt()
        val yStart = (height * 0.58f).toInt()
        val yEnd = (height * 0.88f).toInt()
        val stepX = max(2, width / 240)
        val stepY = max(2, height / 180)
        var bestX = -1
        var evidence = 0
        var x = xStart
        while (x < xEnd) {
            var columnEvidence = 0
            var y = yStart
            while (y < yEnd) {
                val c = frame.getPixel(x, y)
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val brightest = max(r, max(g, b))
                val darkest = minOf(r, g, b)
                if (brightest > 205 && brightest - darkest > 70) columnEvidence++
                y += stepY
            }
            if (columnEvidence > evidence) {
                evidence = columnEvidence
                bestX = x
            }
            x += stepX
        }
        val required = max(3, ((yEnd - yStart) / stepY * 0.055f).toInt())
        if (evidence < required || bestX < 0) {
            stableFrames = 0
            if (lastObstacleX < 0f) armed = true
            return Decision(false, "Ищу препятствие")
        }
        stableFrames++
        val normalizedX = bestX.toFloat() / width
        val approaching = lastObstacleX < 0f || normalizedX <= lastObstacleX + 0.025f
        lastObstacleX = normalizedX
        if (normalizedX < 0.47f) armed = true
        val shouldTap = stableFrames >= 2 && approaching && armed &&
            normalizedX in 0.48f..0.66f && now - lastTapAt > 420L
        if (shouldTap) {
            armed = false
            lastTapAt = now
            return Decision(true, "Прыжок (экспериментальный режим)")
        }
        return Decision(false, "Препятствие обнаружено")
    }
}
