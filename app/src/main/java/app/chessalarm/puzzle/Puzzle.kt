package app.chessalarm.puzzle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A Lichess puzzle. [moves] is in Lichess format: moves[0] is the opponent's setup move,
 * then the solver and opponent alternate; the solver plays moves[1], moves[3], ...
 */
@Serializable
data class Puzzle(
    val id: String,
    val fen: String,
    val moves: List<String>,
    val rating: Int,
    val theme: String,
    val themes: List<String> = emptyList(),
) {
    val solverMoveCount: Int get() = moves.size / 2
}

@Serializable
data class PuzzleFile(val source: String = "", val puzzles: List<Puzzle>)

object PuzzleParser {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): List<Puzzle> = json.decodeFromString(PuzzleFile.serializer(), text).puzzles
}

/** Human-readable theme names for display. */
fun themeLabel(theme: String): String = when (theme) {
    "mateIn1" -> "Mate in 1"
    "mateIn2" -> "Mate in 2"
    "mateIn3" -> "Mate in 3"
    else -> theme.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
}
