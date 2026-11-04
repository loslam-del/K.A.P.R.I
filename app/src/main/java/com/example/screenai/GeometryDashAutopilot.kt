package com.example.screenai

import android.graphics.Bitmap
import kotlin.math.max

/**
 * Experimental Geometry Dash controller.
 * Uses a simple parabolic jump model to estimate whether the cube will be
 * high enough while crossing the detected obstacle cluster.
 */
class GeometryDashAutopilot {
    private var previousObstacleX = -1f
    private var previousFrameAt = 0L
    private var lastTapAt = 0L
    private var armed = true
    private var stableFrames = 0
    private var estimatedVelocity = 0f

    data class Decision(val tap: Boolean, val status: String)

    companion object {
        private const val PLAYER_X = 0.40f
        private const val JUMP_DURATION_MS = 680f
        private const val JUMP_TRIGGER_MIN_MS = 165f
        private const val JUMP_TRIGGER_MAX_MS = 255f
        private const val MIN_ARC_CLEARANCE = 0.34f
        private const val TAP_COOLDOWN_MS = 520L
    }

    fun reset() {
        previousObstacleX = -1f
        previousFrameAt = 0L
        lastTapAt = 0L
        armed = true
        stableFrames = 0
        estimatedVelocity = 0f
    }

    /** Normalized height of a parabolic jump: 0 at takeoff/landing, 1 at apex. */
    private fun arcHeight(elapsedMs: Float): Float {
        val t = (elapsedMs / JUMP_DURATION_MS).coerceIn(0f, 1f)
        return 4f * t * (1f - t)
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

        // Find the strongest compact obstacle cluster and retain its width.
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
            if (span in 2..90 && score > bestScore) {
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
        val halfWidth = ((bestEnd - bestStart + 1) * stepX / 2f) / width.toFloat()
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

        if (obstacleX + halfWidth < PLAYER_X - 0.025f) armed = true
        val speed = -estimatedVelocity
        if (speed <= 0.00005f) return Decision(false, "Отслеживаю препятствие")

        // Estimate when the near and far edges reach the player.
        val nearDistance = (obstacleX - halfWidth - PLAYER_X).coerceAtLeast(0f)
        val farDistance = (obstacleX + halfWidth - PLAYER_X).coerceAtLeast(0f)
        val nearMs = nearDistance / speed
        val farMs = farDistance / speed

        // A tap is sent only when the predicted parabolic arc clears the
        // obstacle's full detected width, not just its center.
        val arcClearsNearEdge = arcHeight(nearMs) >= MIN_ARC_CLEARANCE
        val arcClearsFarEdge = arcHeight(farMs) >= MIN_ARC_CLEARANCE
        val goodTakeoffWindow = nearMs in JUMP_TRIGGER_MIN_MS..JUMP_TRIGGER_MAX_MS
        val crossingFitsArc = farMs <= JUMP_DURATION_MS - 70f

        if (stableFrames >= 2 && armed && goodTakeoffWindow &&
            crossingFitsArc && arcClearsNearEdge && arcClearsFarEdge &&
            now - lastTapAt > TAP_COOLDOWN_MS) {
            armed = false
            lastTapAt = now
            return Decision(true, "Дуга рассчитана — прыжок")
        }

        val status = when {
            !armed -> "Кубик в прыжке / жду новое препятствие"
            nearMs > JUMP_TRIGGER_MAX_MS -> "Рассчитываю дугу прыжка"
            !crossingFitsArc -> "Препятствие шире расчётной дуги"
            else -> "Проверяю высоту дуги"
        }
        return Decision(false, status)
    }
}
