package com.example.screenai

import android.graphics.Bitmap
import kotlin.math.max

/** Screen-only Geometry Dash controller inspired by occupancy-grid agents. */
class GeometryDashAutopilot {
    private data class Obstacle(val left: Float, val right: Float, val score: Int) {
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
        private const val COLS = 24
        private const val ROWS = 16
        private const val PLAYER_X = 0.40f
        private const val JUMP_MS = 680f
        private const val TAKEOFF_MIN_MS = 270f
        private const val TAKEOFF_MAX_MS = 430f
        private const val MIN_CLEARANCE = 0.28f
        private const val COOLDOWN_MS = 480L
        private const val AIRBORNE_GUARD_MS = 560L
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

    /** A compact 24x16 look-ahead occupancy grid from screen pixels. */
    private fun buildGrid(frame: Bitmap): Array<IntArray> {
        val grid = Array(ROWS) { IntArray(COLS) }
        val x0 = (frame.width * 0.22f).toInt()
        val x1 = (frame.width * 0.92f).toInt()
        val y0 = (frame.height * 0.38f).toInt()
        val y1 = (frame.height * 0.76f).toInt()
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

    private fun detectObstacles(grid: Array<IntArray>): List<Obstacle> {
        val columns = IntArray(COLS)
        var hazards = 0
        for (col in 0 until COLS) for (row in 7 until 15) {
            if (grid[row][col] >= 2) {
                columns[col] += grid[row][col]
                hazards++
            }
        }
        lastGridHazards = hazards
        val result = ArrayList<Obstacle>()
        var col = 0
        while (col < COLS) {
            if (columns[col] < 3) { col++; continue }
            val start = col
            var score = 0
            while (col < COLS && columns[col] >= 3) {
                score += columns[col]
                col++
            }
            if (score >= 8) {
                result.add(Obstacle(
                    0.22f + start * (0.70f / COLS),
                    0.22f + col * (0.70f / COLS),
                    score
                ))
            }
        }
        return result
    }

    fun analyze(frame: Bitmap, now: Long = System.currentTimeMillis()): Decision {
        if (frame.width < 200 || frame.height < 200) return Decision(false, "Кадр слишком маленький")
        val grid = buildGrid(frame)
        val target = detectObstacles(grid)
            .filter { it.right > PLAYER_X - 0.03f }
            .minByOrNull { it.left }

        if (target == null) {
            stableFrames = 0
            if (previousObstacleX >= 0f && previousObstacleX < PLAYER_X - 0.08f) {
                armed = true
                previousObstacleX = -1f
                estimatedVelocity = 0f
            }
            return Decision(false, if (lastGridHazards == 0) "Сканирую сетку препятствий" else "Проверяю препятствия впереди")
        }

        stableFrames++
        val oldX = previousObstacleX
        if (oldX >= 0f && previousFrameAt > 0L) {
            val dt = (now - previousFrameAt).coerceIn(35L, 300L).toFloat()
            val measured = (target.center - oldX) / dt
            if (measured < -0.00004f && measured > -0.005f) {
                estimatedVelocity = if (estimatedVelocity == 0f) measured
                    else estimatedVelocity * 0.60f + measured * 0.40f
            }
        }
        previousObstacleX = target.center
        previousFrameAt = now

        val speed = -estimatedVelocity
        if (speed <= 0.00005f) return Decision(false, "Сетка: измеряю скорость препятствия")
        val nearMs = (target.left - PLAYER_X).coerceAtLeast(0f) / speed
        val farMs = (target.right - PLAYER_X).coerceAtLeast(0f) / speed

        if (target.right < PLAYER_X - 0.035f && now >= airborneUntil) armed = true
        val airborne = now < airborneUntil
        val crossingFits = farMs <= JUMP_MS - 55f
        val canTap = stableFrames >= 2 && armed && !airborne &&
            nearMs in TAKEOFF_MIN_MS..TAKEOFF_MAX_MS &&
            arcHeight(nearMs) >= MIN_CLEARANCE &&
            arcHeight(farMs) >= MIN_CLEARANCE &&
            crossingFits && now - lastTapAt >= COOLDOWN_MS

        if (canTap) {
            armed = false
            lastTapAt = now
            airborneUntil = now + AIRBORNE_GUARD_MS
            return Decision(true, "Сетка рассчитала дугу — прыжок")
        }

        val status = when {
            airborne -> "Кубик в воздухе — слежу за следующим блоком"
            !armed -> "Жду, пока препятствие останется позади"
            nearMs > TAKEOFF_MAX_MS -> "Вижу препятствие — рассчитываю подход"
            !crossingFits -> "Группа блоков длиннее расчётного прыжка"
            else -> "Сетка проверяет дугу и высоту"
        }
        return Decision(false, status)
    }
}
