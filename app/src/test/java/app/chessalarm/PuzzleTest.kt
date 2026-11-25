package app.chessalarm

import app.chessalarm.chess.Move
import app.chessalarm.chess.Position
import app.chessalarm.puzzle.AdaptiveDifficulty
import app.chessalarm.puzzle.Puzzle
import app.chessalarm.puzzle.PuzzleParser
import app.chessalarm.puzzle.PuzzlePicker
import app.chessalarm.puzzle.PuzzleSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class PuzzleTest {

    // Lichess puzzle 0000D: two solver moves, opponent recaptures in between.
    private val tactic = Puzzle(
        "0000D", "5rk1/1p3ppp/pq3b2/8/8/1P1Q1N2/P4PPP/3R2K1 w - - 2 27",
        listOf("d3d6", "f8d8", "d6d8", "f6d8"), 1433, "advantage",
    )

    // Back-rank mate-in-1 that has two different mating moves (Ra8# and Re8#).
    private val mateInOne = Puzzle(
        "M1", "6k1/5ppp/8/8/8/8/r4PPP/R3R1K1 b - - 0 1",
        listOf("a2a7", "e1e8"), 1300, "mateIn1",
    )

    private fun uci(s: String) = Move.fromUci(s)!!

    @Test fun setupMoveIsPlayedFirst() {
        val s = PuzzleSession(tactic)
        assertEquals("d3d6", s.setupMove.uci)
        // After the setup move it is black (the solver) to move.
        assertEquals(app.chessalarm.chess.Color.BLACK, s.solverColor)
        assertEquals("f8d8", s.expectedMove()!!.uci)
    }

    @Test fun wrongMoveLeavesPositionUnchanged() {
        val s = PuzzleSession(tactic)
        val before = s.position.toFen()
        assertEquals(PuzzleSession.Result.Wrong, s.attempt(uci("g7g6")))
        assertEquals(before, s.position.toFen())
        assertEquals(1, s.wrongMoves)
        assertFalse(s.isSolved)
    }

    @Test fun illegalMoveIsRejected() {
        val s = PuzzleSession(tactic)
        assertEquals(PuzzleSession.Result.Illegal, s.attempt(uci("a1a8")))
    }

    @Test fun correctLineSolvesWithAutomaticReplies() {
        val s = PuzzleSession(tactic)
        val r1 = s.attempt(uci("f8d8"))
        assertTrue(r1 is PuzzleSession.Result.Correct)
        assertEquals("d6d8", (r1 as PuzzleSession.Result.Correct).reply.uci)
        val r2 = s.attempt(uci("f6d8"))
        assertTrue(r2 is PuzzleSession.Result.Solved)
        assertTrue(s.isSolved)
        assertEquals(2, s.correctMoves)
    }

    @Test fun alternativeMateIsAccepted() {
        // Both Rd8# and Re8# mate; the official solution is Re8#.
        val alt = Puzzle(
            "M1b", "6k1/5ppp/8/8/8/8/r4PPP/3RR1K1 b - - 0 1",
            listOf("a2a3", "e1e8"), 1300, "mateIn1",
        )
        val s = PuzzleSession(alt)
        assertTrue(s.attempt(uci("d1d8")) is PuzzleSession.Result.Solved)
        assertTrue(s.position.isCheckmate())
        assertTrue(PuzzleSession(alt).attempt(uci("e1e8")) is PuzzleSession.Result.Solved)
        assertTrue(PuzzleSession(mateInOne).attempt(uci("e1e8")) is PuzzleSession.Result.Solved)
        assertEquals(PuzzleSession.Result.Wrong, PuzzleSession(alt).attempt(uci("d1d7")))
    }

    @Test fun promotionMoveMustMatchPiece() {
        val p = Puzzle(
            "P1", "8/1P4k1/8/8/8/8/6K1/r7 b - - 0 1",
            listOf("a1a3", "b7b8q"), 1400, "promotion",
        )
        val s = PuzzleSession(p)
        assertEquals(PuzzleSession.Result.Wrong, s.attempt(uci("b7b8r")))
        assertTrue(s.attempt(uci("b7b8q")) is PuzzleSession.Result.Solved)
    }

    @Test fun pickerRespectsRangeAndAvoidsRecent() {
        val puzzles = (0 until 200).map { Puzzle("p$it", Position.START_FEN, listOf("e2e4", "e7e5"), 1000 + it * 5, "x") }
        val picker = PuzzlePicker(puzzles, Random(1))
        repeat(200) {
            val p = picker.pick(1200, 1400)
            assertTrue(p.rating in 1200..1400)
        }
        val recent = puzzles.filter { it.rating in 1200..1400 }.drop(1).map { it.id }
        val only = puzzles.first { it.rating in 1200..1400 }
        assertEquals(only.id, picker.pick(1200, 1400, recent = recent).id)
        val target = picker.pick(1000, 1995, targetRating = 1500)
        assertTrue(kotlin.math.abs(target.rating - 1500) <= 75)
        val other = picker.pick(1200, 1210, exclude = "p40")
        assertNotEquals("p40", other.id)
    }

    @Test fun adaptiveDifficultyMovesWithinRange() {
        assertEquals(1550, AdaptiveDifficulty.next(1500, 10_000, 1200, 1900))
        assertEquals(1450, AdaptiveDifficulty.next(1500, 120_000, 1200, 1900))
        assertEquals(1500, AdaptiveDifficulty.next(1500, 60_000, 1200, 1900))
        assertEquals(1900, AdaptiveDifficulty.next(1880, 5_000, 1200, 1900))
        assertEquals(1200, AdaptiveDifficulty.next(1210, 500_000, 1200, 1900))
    }

    /** Validates every bundled puzzle: legal setup, legal solution line, short, and mates really mate. */
    @Test fun bundledPuzzlesAreValid() {
        val file = listOf("src/main/assets/puzzles.json", "app/src/main/assets/puzzles.json")
            .map(::File).first { it.exists() }
        val puzzles = PuzzleParser.parse(file.readText())
        assertEquals(1000, puzzles.size)
        assertEquals(puzzles.size, puzzles.map { it.id }.toSet().size)
        for (p in puzzles) {
            assertTrue("${p.id} rating", p.rating in 1200..1900)
            assertTrue("${p.id} is short", p.solverMoveCount in 1..2)
            val s = PuzzleSession(p)
            var i = 1
            while (!s.isSolved) {
                val r = s.attempt(uci(p.moves[i]))
                assertTrue("${p.id} move ${p.moves[i]} -> $r", r is PuzzleSession.Result.Correct || r is PuzzleSession.Result.Solved)
                i += 2
            }
            if (p.theme.startsWith("mateIn")) assertTrue("${p.id} ends in mate", s.position.isCheckmate())
        }
    }
}
