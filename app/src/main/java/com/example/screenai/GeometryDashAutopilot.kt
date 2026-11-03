package com.example.screenai

import android.graphics.Bitmap
import kotlin.math.max

/** Experimental predictive controller for Geometry Dash's classic cube mode. */
class GeometryDashAutopilot {
    private var previousObstacleX = -1f
    private var previousFrameAt = 0L
    private var lastTapAt = 0L
    private var armed = true
    private var stableFrames = 0
    private var estimatedVelocity = 0f

    data class Decision(val tap: Boolean, val status: String)

    fun reset() {
        previousObstacleX = -1f
        previousFrameAt = 0L
        lastTapAt = 0L
        armed = true
        stableFrames = 0
        estimatedVelocity = 0f
    }

    fun analyze(frame: Bitmap, now: Long = System.currentTimeMillis()): Decision {
        val width = frame.width
        val height = frame.height
        if (width < 200 || height < 200) return Decision(false, "Кадр слишком маленький")

        val xStart = (width * 0.25f).toInt()
        val xEnd = (width * 0.88f).toInt()
        val yStart = (height * 0.59f).toInt()
        val yEnd = (height * 0.73f).toInt()
        val stepX = max(1, width / 500)
        val stepY = max(1, height / 260)
        val counts = IntArray((xEnd - xStart + stepX - 1) / stepX)
        var xi = 0
        var x = xStart
        while (x < xEnd && xi < counts.size) {
            var y = yStart
            while (y < yEnd) {
                val color = frame.getPixel(x, y)
                val r = (color shr 16) and 255
                val g = (color shr 8) and 255
                val b = color and 255
                val hi = max(r, max(g, b))
                val lo = minOf(r, g, b)
                if (hi > 190 && lo > 135 && hi - lo < 100) counts[xi]++
                y += stepY
            }
            xi++
            x += stepX
        }

        // Select a compact run of bright neutral pixels, not a single noisy column.
        var bestStart = -1
        var bestEnd = -1
        var bestScore = 0
        var i = 0
        while (i < counts.size) {
            if (counts[i] < 2) { i++; continue }
            val start = i
            var score = 0
            while (i < counts.size && counts[i] >= 2) { score += counts[i]; i++ }
            val span = i - start
            if (span in 2..70 && score > bestScore) {
                bestScore = score
                bestStart = start
                bestEnd = i - 1
            }
        }

        val minScore = max(7, ((yEnd - yStart) / stepY * 0.045f).toInt())
        if (bestStart < 0 || bestScore < minScore) {
            stableFrames = 0
            if (previousObstacleX < 0f || previousObstacleX < 0.37f) armed = true
            previousObstacleX = -1f
            previousFrameAt = now
            estimatedVelocity = 0f
            return Decision(false, "Ищу препятствие")
        }

        val centerIndex = (bestStart + bestEnd) / 2f
        val obstacleX = (xStart + centerIndex * stepX) / width.toFloat()
        stableFrames++

        val oldX = previousObstacleX
        if (oldX >= 0f && previousFrameAt > 0L) {
            val dt = (now - previousFrameAt).coerceIn(35L, 250L).toFloat()
            val velocity = (obstacleX - oldX) / dt
            if (velocity < -0.00005f && velocity > -0.0045f) {
                estimatedVelocity = if (estimatedVelocity == 0f) velocity
                    else estimatedVelocity * 0.55f + velocity * 0.45f
            }
        }
        previousObstacleX = obstacleX
        previousFrameAt = now

        if (obstacleX < 0.37f) armed = true
        val movingTowardPlayer = estimatedVelocity < -0.00005f &&
            (oldX < 0f || obstacleX <= oldX + 0.012f)

        // Predict 170 ms ahead to account for capture and tap-dispatch latency.
        val predictedX = obstacleX + estimatedVelocity * 170f
        val inJumpWindow = predictedX <= 0.51f && predictedX >= 0.34f
        val cooldownPassed = now - lastTapAt > 520L

        if (stableFrames >= 2 && movingTowardPlayer && inJumpWindow && armed && cooldownPassed) {
            armed = false
            lastTapAt = now
            return Decision(true, "Прогноз столкновения — прыжок")
        }
        return Decision(false, if (estimatedVelocity < 0f) "Траектория рассчитана" else "Отслеживаю препятствие")
    }
}
