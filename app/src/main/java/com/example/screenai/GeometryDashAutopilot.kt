package com.example.screenai

import android.graphics.Bitmap
import kotlin.math.max

/** Screen-only Geometry Dash controller using a look-ahead grid and obstacle sequence. */
class GeometryDashAutopilot {
    private data class Obstacle(val left: Float, val right: Float, val height: Float) {
        val center: Float get() = (left + right) * 0.5f
    }

    private var previousObstacleX = -1f
    private var previousFrameAt = 0L
    private var estimatedVelocity = 0f
    private var lastTapAt = 0L
    private var airborneUntil = 0L
    private var armed = true
    private var stableFrames = 0
    private var lastGridHazards = 0

    data class Decision(val tap: Boolean, val status: String)

    companion object {
        private const val COLS = 30
        private const val ROWS = 18
        private const val PLAYER_X = 0.40f
        private const val JUMP_MS = 680f
        private const val TAKEOFF_MIN_MS = 270f
        private const val TAKEOFF_MAX_MS = 440f
        private const val HEIGHT_MARGIN = 0.10f
        private const val COOLDOWN_MS = 480L
        private const val AIRBORNE_GUARD_MS = 560L
        private const val MAX_SEQUENCE_MS = 610f
    }

    fun reset() {
        previousObstacleX = -1f
        previousFrameAt = 0L
        estimatedVelocity = 0f
        lastTapAt = 0L
        airborneUntil = 0L
        armed = true
        stableFrames = 0
        lastGridHazards = 0
    }

    private fun arcHeight(ms: Float): Float {
        val t = (ms / JUMP_MS).coerceIn(0f, 1f)
        return 4f * t * (1f - t)
    }

    private fun buildGrid(frame: Bitmap): Array<IntArray> {
        val grid = Array(ROWS) { IntArray(COLS) }
        val x0 = (frame.width * 0.20f).toInt()
        val x1 = (frame.width * 0.94f).toInt()
        val y0 = (frame.height * 0.34f).toInt()
        val y1 = (frame.height * 0.78f).toInt()
        for (row in 0 until ROWS) {
            val y = y0 + ((row + 0.5f) * (y1 - y0) / ROWS).toInt()
            for (col in 0 until COLS) {
                val x = x0 + ((col + 0.5f) * (x1 - x0) / COLS).toInt()
                var evidence = 0
                for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
                    val c = frame.getPixel((x + dx).coerceIn(0, frame.width - 1),
                        (y + dy).coerceIn(0, frame.height - 1))
                    val r = (c shr 16) and 255
                    val g = (c shr 8) and 255
                    val b = c and 255
                    val hi = max(r, max(g, b))
                    val lo = minOf(r, g, b)
                    if (hi > 190 && lo > 135 && hi - lo < 105) evidence++
                }
                grid[row][col] = evidence
            }
        }
        return grid
    }

    /** Split occupied columns into separate runs and estimate each run's height. */
    private fun detectObstacles(grid: Array<IntArray>): List<Obstacle> {
        val strength = IntArray(COLS)
        val topRow = IntArray(COLS) { ROWS }
        var hazards = 0
        for (col in 0 until COLS) {
            for (row in 5 until ROWS - 1) {
                if (grid[row][col] >= 2) {
                    strength[col] += grid[row][col]
                    topRow[col] = minOf(topRow[col], row)
                    hazards++
                }
            }
        }
        lastGridHazards = hazards
        val found = ArrayList<Obstacle>()
        var col = 0
        while (col < COLS) {
            if (strength[col] < 3) { col++; continue }
            val start = col
            var end = col
            var score = 0
            var top = ROWS
            while (col < COLS && strength[col] >= 3) {
                score += strength[col]
                top = minOf(top, topRow[col])
                end = col
                col++
            }
            if (score >= 7) {
                val verticalSpan = (ROWS - 1 - top).coerceAtLeast(1)
                val height = (verticalSpan.toFloat() / 12f).coerceIn(0.12f, 0.82f)
                found.add(Obstacle(
                    0.20f + start * (0.74f / COLS),
                    0.20f + (end + 1) * (0.74f / COLS),
                    height
                ))
            }
        }
        return found
    }

    fun analyze(frame: Bitmap, now: Long = System.currentTimeMillis()): Decision {
        if (frame.width < 200 || frame.height < 200) return Decision(false, "Кадр слишком маленький")
        val obstacles = detectObstacles(buildGrid(frame))
            .filter { it.right > PLAYER_X - 0.04f }
            .sortedBy { it.left }

        if (obstacles.isEmpty()) {
            stableFrames = 0
            if (previousObstacleX >= 0f && previousObstacleX < PLAYER_X - 0.08f) {
                armed = true
                previousObstacleX = -1f
                estimatedVelocity = 0f
            }
            return Decision(false, if (lastGridHazards == 0) "Сканирую сетку препятствий" else "Проверяю отдельные столбы")
        }

        val target = obstacles.first()
        stableFrames++
        if (previousObstacleX >= 0f && previousFrameAt > 0L) {
            val dt = (now - previousFrameAt).coerceIn(35L, 300L).toFloat()
            val measured = (target.center - previousObstacleX) / dt
            if (measured < -0.00004f && measured > -0.005f) {
                estimatedVelocity = if (estimatedVelocity == 0f) measured
                    else estimatedVelocity * 0.60f + measured * 0.40f
            }
        }
        previousObstacleX = target.center
        previousFrameAt = now
        val speed = -estimatedVelocity
        if (speed <= 0.00005f) return Decision(false, "Столбы найдены — измеряю скорость")

        val nearMs = (target.left - PLAYER_X).coerceAtLeast(0f) / speed
        val airborne = now < airborneUntil
        if (target.right < PLAYER_X - 0.035f && !airborne) armed = true

        // Validate every obstacle the cube is predicted to meet during one jump.
        val sequence = obstacles.mapNotNull { obstacle ->
            val entry = (obstacle.left - PLAYER_X) / speed
            val exit = (obstacle.right - PLAYER_X) / speed
            if (exit < 0f || entry > MAX_SEQUENCE_MS) null
            else Triple(obstacle, entry.coerceAtLeast(0f), exit.coerceAtLeast(0f))
        }
        val sequenceClears = sequence.isNotEmpty() && sequence.all { item ->
            val obstacle = item.first
            val entryArc = arcHeight(item.second)
            val exitArc = arcHeight(item.third)
            val required = obstacle.height + HEIGHT_MARGIN
            entryArc >= required && exitArc >= required && item.third <= JUMP_MS - 45f
        }

        val canTap = stableFrames >= 2 && armed && !airborne &&
            nearMs in TAKEOFF_MIN_MS..TAKEOFF_MAX_MS &&
            sequenceClears && now - lastTapAt >= COOLDOWN_MS

        if (canTap) {
            armed = false
            lastTapAt = now
            airborneUntil = now + AIRBORNE_GUARD_MS
            return Decision(true, "Рассчитан прыжок через несколько столбов")
        }

        val status = when {
            airborne -> "В воздухе — анализирую следующие столбы"
            !armed -> "Жду, пока текущий столб останется позади"
            nearMs > TAKEOFF_MAX_MS -> "Столбы найдены — рассчитываю подход"
            sequence.isEmpty() -> "Ищу последовательность препятствий"
            !sequenceClears -> "Проверяю высоту и интервалы столбов"
            else -> "Траектория рассчитана — проверяю момент прыжка"
        }
        return Decision(false, status)
    }
}
