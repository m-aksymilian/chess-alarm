package app.chessalarm.puzzle

import app.chessalarm.chess.Color
import app.chessalarm.chess.Move
import app.chessalarm.chess.Position

/**
 * Pure puzzle-solving state machine.
 *
 * Construction plays the opponent's setup move (as Lichess does). The solver then submits moves
 * with [attempt]. A move is correct if it matches the solution line, or if it delivers checkmate
 * (any mating move ends the puzzle, which covers alternative mates on a mate-in-one step).
 */
class PuzzleSession(val puzzle: Puzzle) {

    sealed interface Result {
        /** Not a legal move in the current position. Nothing changes. */
        data object Illegal : Result

        /** Legal but not the solution. The position is unchanged; the solver may try again. */
        data object Wrong : Result

        /** Correct, and the opponent replied with [reply]. The puzzle continues. */
        data class Correct(val move: Move, val reply: Move) : Result

        /** Correct, and the puzzle is complete. */
        data class Solved(val move: Move) : Result
    }

    /** Position before the opponent's setup move (used to animate it). */
    val initialPosition: Position = Position.fromFen(puzzle.fen)

    /** The opponent's setup move. */
    val setupMove: Move = requireNotNull(initialPosition.parseUci(puzzle.moves[0])) {
        "Puzzle ${puzzle.id}: illegal setup move ${puzzle.moves[0]}"
    }

    var position: Position = initialPosition.play(setupMove)
        private set

    /** Index into [Puzzle.moves] of the next expected solver move. */
    var nextIndex: Int = 1
        private set

    var lastMove: Move? = setupMove
        private set

    /** The position just before the opponent's latest reply (used to animate it). */
    var positionBeforeReply: Position? = null
        private set

    var correctMoves: Int = 0
        private set

    var wrongMoves: Int = 0
        private set

    val solverColor: Color = position.sideToMove

    val isSolved: Boolean get() = nextIndex >= puzzle.moves.size

    fun attempt(move: Move): Result {
        check(!isSolved) { "Puzzle already solved" }
        val legal = position.legalMoves().firstOrNull { it == move } ?: return Result.Illegal
        val expected = Move.fromUci(puzzle.moves[nextIndex])
        val after = position.play(legal)

        val matches = legal == expected
        val mates = after.isCheckmate()
        if (!matches && !mates) {
            wrongMoves++
            return Result.Wrong
        }
        correctMoves++
        position = after
        lastMove = legal
        if (mates || nextIndex + 1 >= puzzle.moves.size) {
            nextIndex = puzzle.moves.size
            return Result.Solved(legal)
        }
        val reply = requireNotNull(position.parseUci(puzzle.moves[nextIndex + 1])) {
            "Puzzle ${puzzle.id}: illegal reply ${puzzle.moves[nextIndex + 1]}"
        }
        positionBeforeReply = position
        position = position.play(reply)
        lastMove = reply
        nextIndex += 2
        return Result.Correct(legal, reply)
    }

    /** Convenience for tests and hints: the expected next solver move. */
    fun expectedMove(): Move? = if (isSolved) null else Move.fromUci(puzzle.moves[nextIndex])
}
