package app.chessalarm.puzzle

import kotlin.random.Random

/**
 * Chooses puzzles within a rating range, optionally steering towards an adaptive target rating,
 * and avoiding recently seen puzzles.
 */
class PuzzlePicker(
    private val puzzles: List<Puzzle>,
    private val random: Random = Random.Default,
) {
    fun pick(
        minRating: Int,
        maxRating: Int,
        targetRating: Int? = null,
        recent: Collection<String> = emptyList(),
        exclude: String? = null,
    ): Puzzle {
        require(puzzles.isNotEmpty()) { "No puzzles loaded" }
        val lo = minOf(minRating, maxRating)
        val hi = maxOf(minRating, maxRating)
        var pool = puzzles.filter { it.rating in lo..hi && it.id != exclude }
        if (pool.isEmpty()) pool = puzzles.filter { it.id != exclude }.ifEmpty { puzzles }

        if (targetRating != null) {
            val t = targetRating.coerceIn(lo, hi)
            // Widen the window until there's a reasonable choice.
            for (window in intArrayOf(75, 150, 300, 600)) {
                val near = pool.filter { kotlin.math.abs(it.rating - t) <= window }
                if (near.size >= 8) { pool = near; break }
            }
        }
        val fresh = pool.filter { it.id !in recent }
        return (fresh.ifEmpty { pool }).random(random)
    }
}

/**
 * Adaptive difficulty: puzzles get slightly harder after quick solves and easier after slow ones,
 * always staying within the user's chosen range.
 */
object AdaptiveDifficulty {
    const val QUICK_SOLVE_MS = 30_000L
    const val SLOW_SOLVE_MS = 90_000L
    const val STEP = 50

    fun next(current: Int, solveMs: Long, minRating: Int, maxRating: Int): Int {
        val lo = minOf(minRating, maxRating)
        val hi = maxOf(minRating, maxRating)
        val adjusted = when {
            solveMs <= QUICK_SOLVE_MS -> current + STEP
            solveMs >= SLOW_SOLVE_MS -> current - STEP
            else -> current
        }
        return adjusted.coerceIn(lo, hi)
    }

    fun initial(minRating: Int, maxRating: Int): Int = (minRating + maxRating) / 2
}
