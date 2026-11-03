package com.example.screenai

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Локальный expectimax-решатель для классической головоломки 2048.
 * Пустая клетка обозначается нулём.
 */
object Game2048 {
    private const val SIZE = 4
    private val directions = listOf("left", "right", "up", "down")

    fun bestMove(board: Array<IntArray>): String? {
        if (!valid(board)) return null
        var bestDirection: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (direction in directions) {
            val next = move(board, direction) ?: continue
            val score = expectimax(next, 3, false)
            if (score > bestScore) {
                bestScore = score
                bestDirection = direction
            }
        }
        return bestDirection
    }

    private fun valid(board: Array<IntArray>): Boolean =
        board.size == SIZE && board.all { row ->
            row.size == SIZE && row.all { it == 0 || (it >= 2 && (it and (it - 1)) == 0) }
        }

    private fun move(board: Array<IntArray>, direction: String): Array<IntArray>? {
        val result = Array(SIZE) { IntArray(SIZE) }
        var changed = false
        for (line in 0 until SIZE) {
            val values = IntArray(SIZE) { k ->
                when (direction) {
                    "left" -> board[line][k]
                    "right" -> board[line][SIZE - 1 - k]
                    "up" -> board[k][line]
                    else -> board[SIZE - 1 - k][line]
                }
            }
            val merged = mergeLine(values)
            for (k in 0 until SIZE) {
                val old = values[k]
                val value = merged[k]
                if (old != value) changed = true
                when (direction) {
                    "left" -> result[line][k] = value
                    "right" -> result[line][SIZE - 1 - k] = value
                    "up" -> result[k][line] = value
                    else -> result[SIZE - 1 - k][line] = value
                }
            }
        }
        return if (changed) result else null
    }

    private fun mergeLine(input: IntArray): IntArray {
        val compact = input.filter { it != 0 }
        val output = IntArray(SIZE)
        var write = 0
        var i = 0
        while (i < compact.size && write < SIZE) {
            if (i + 1 < compact.size && compact[i] == compact[i + 1]) {
                output[write++] = compact[i] * 2
                i += 2
            } else {
                output[write++] = compact[i]
                i++
            }
        }
        return output
    }

    private fun expectimax(board: Array<IntArray>, depth: Int, playerTurn: Boolean): Double {
        if (depth <= 0) return evaluate(board)
        if (playerTurn) {
            var best = Double.NEGATIVE_INFINITY
            for (direction in directions) {
                val next = move(board, direction) ?: continue
                best = max(best, expectimax(next, depth - 1, false))
            }
            return if (best.isFinite()) best else evaluate(board)
        }

        val empty = ArrayList<Pair<Int, Int>>()
        for (r in 0 until SIZE) for (c in 0 until SIZE) {
            if (board[r][c] == 0) empty.add(r to c)
        }
        if (empty.isEmpty()) return evaluate(board)

        var total = 0.0
        for ((r, c) in empty) {
            val withTwo = copyBoard(board)
            withTwo[r][c] = 2
            val withFour = copyBoard(board)
            withFour[r][c] = 4
            total += 0.9 * expectimax(withTwo, depth - 1, true)
            total += 0.1 * expectimax(withFour, depth - 1, true)
        }
        return total / empty.size
    }

    private fun evaluate(board: Array<IntArray>): Double {
        var empty = 0
        var maxTile = 0
        var smoothness = 0.0
        var monotonicity = 0.0

        fun rank(value: Int): Double = if (value <= 0) 0.0 else ln(value.toDouble()) / ln(2.0)

        for (r in 0 until SIZE) for (c in 0 until SIZE) {
            val value = board[r][c]
            if (value == 0) empty++ else maxTile = max(maxTile, value)
            if (value == 0) continue
            if (c + 1 < SIZE && board[r][c + 1] != 0) {
                smoothness -= abs(rank(value) - rank(board[r][c + 1]))
            }
            if (r + 1 < SIZE && board[r + 1][c] != 0) {
                smoothness -= abs(rank(value) - rank(board[r + 1][c]))
            }
        }

        for (r in 0 until SIZE) {
            var inc = 0.0
            var dec = 0.0
            for (c in 0 until SIZE - 1) {
                val a = rank(board[r][c])
                val b = rank(board[r][c + 1])
                if (a > b) dec += a - b else inc += b - a
            }
            monotonicity -= min(inc, dec)
        }
        for (c in 0 until SIZE) {
            var inc = 0.0
            var dec = 0.0
            for (r in 0 until SIZE - 1) {
                val a = rank(board[r][c])
                val b = rank(board[r + 1][c])
                if (a > b) dec += a - b else inc += b - a
            }
            monotonicity -= min(inc, dec)
        }

        val cornerBonus = if (
            board[0][0] == maxTile || board[0][SIZE - 1] == maxTile ||
            board[SIZE - 1][0] == maxTile || board[SIZE - 1][SIZE - 1] == maxTile
        ) 100.0 else 0.0

        return empty * 270.0 + smoothness * 7.0 + monotonicity * 45.0 +
            rank(maxTile) * 1.5 + cornerBonus
    }

    private fun copyBoard(board: Array<IntArray>) = Array(SIZE) { board[it].clone() }
}
