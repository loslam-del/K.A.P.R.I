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

        // In Stereo Madness the spike outlines are bright/near-white against a
        // saturated blue background. The old detector counted blue background
        // pixels and ignored the white spike edges.
        val xStart = (width * 0.28f).toInt()
        val xEnd = (width * 0.84f).toInt()
        val yStart = (height * 0.60f).toInt()
        // Stop above the bright horizontal floor line so it cannot look like
        // an obstacle in every column.
        val yEnd = (height * 0.715f).toInt()
        val stepX = max(1, width / 420)
        val stepY = max(1, height / 240)

        var bestX = -1
        var evidence = 0
        var x = xStart
        while (x < xEnd) {
            var columnEvidence = 0
            var y = yStart
            while (y < yEnd) {
                val color = frame.getPixel(x, y)
                val r = (color shr 16) and 255
                val g = (color shr 8) and 255
                val b = color and 255
                val brightest = max(r, max(g, b))
                val darkest = minOf(r, g, b)
                val spread = brightest - darkest

                // Bright neutral pixels match the white spike outline;
                // saturated background and most colored UI elements are ignored.
                if (brightest > 190 && darkest > 135 && spread < 110) {
                    columnEvidence++
                }
                y += stepY
            }
            if (columnEvidence > evidence) {
                evidence = columnEvidence
                bestX = x
            }
            x += stepX
        }

        val sampledRows = max(1, (yEnd - yStart) / stepY)
        val required = max(2, (sampledRows * 0.025f).toInt())
        if (evidence < required || bestX < 0) {
            stableFrames = 0
            if (lastObstacleX < 0f) armed = true
            return Decision(false, "Ищу шипы")
        }

        stableFrames++
        val normalizedX = bestX.toFloat() / width
        val approaching = lastObstacleX < 0f || normalizedX <= lastObstacleX + 0.025f
        lastObstacleX = normalizedX

        // Re-arm only after the detected obstacle has passed the player.
        if (normalizedX < 0.38f) armed = true

        // Start the jump a little earlier: the previous 0.48 lower bound
        // could miss the spike when the detector first acquired it.
        val shouldTap = stableFrames >= 2 && approaching && armed &&
            normalizedX in 0.40f..0.64f && now - lastTapAt > 420L

        if (shouldTap) {
            armed = false
            lastTapAt = now
            return Decision(true, "Прыжок по шипу")
        }
        return Decision(false, "Шип обнаружен")
    }
}
