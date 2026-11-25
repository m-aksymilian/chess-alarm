package app.chessalarm

import app.chessalarm.chess.Move
import app.chessalarm.chess.Piece
import app.chessalarm.chess.Position
import app.chessalarm.chess.parseSquare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionTest {

    private fun perft(p: Position, depth: Int): Long {
        if (depth == 0) return 1
        val moves = p.legalMoves()
        if (depth == 1) return moves.size.toLong()
        return moves.sumOf { perft(p.play(it), depth - 1) }
    }

    @Test fun perftStartPosition() {
        val p = Position.start()
        assertEquals(20, perft(p, 1))
        assertEquals(400, perft(p, 2))
        assertEquals(8902, perft(p, 3))
        assertEquals(197281, perft(p, 4))
    }

    /** "Kiwipete": exercises castling, en passant, promotions and pins. */
    @Test fun perftKiwipete() {
        val p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48, perft(p, 1))
        assertEquals(2039, perft(p, 2))
        assertEquals(97862, perft(p, 3))
    }

    @Test fun perftPosition3EndgameEnPassantPins() {
        val p = Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14, perft(p, 1))
        assertEquals(191, perft(p, 2))
        assertEquals(2812, perft(p, 3))
        assertEquals(43238, perft(p, 4))
    }

    @Test fun perftPosition4Promotions() {
        val p = Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6, perft(p, 1))
        assertEquals(264, perft(p, 2))
        assertEquals(9467, perft(p, 3))
    }

    @Test fun perftPosition5() {
        val p = Position.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8")
        assertEquals(44, perft(p, 1))
        assertEquals(1486, perft(p, 2))
        assertEquals(62379, perft(p, 3))
    }

    @Test fun fenRoundTrip() {
        val fens = listOf(
            Position.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2",
            "8/8/8/8/8/8/8/K6k b - - 12 40",
        )
        for (f in fens) assertEquals(f, Position.fromFen(f).toFen())
    }

    @Test fun castlingMovesRookAndClearsRights() {
        val p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val after = p.play(Move.fromUci("e1g1")!!)
        assertEquals("r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1", after.toFen())
        val after2 = after.play(Move.fromUci("e8c8")!!)
        assertEquals("2kr3r/8/8/8/8/8/8/R4RK1 w - - 2 2", after2.toFen())
    }

    @Test fun cannotCastleThroughCheck() {
        // Black rook on f8 attacks f1.
        val p = Position.fromFen("5r1k/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        assertNull(p.parseUci("e1g1"))
        assertTrue(p.parseUci("e1c1") != null)
    }

    @Test fun enPassantCapture() {
        val p = Position.fromFen("rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3")
        val m = p.parseUci("e5f6")!!
        assertTrue(p.isCapture(m))
        val after = p.play(m)
        assertEquals(Piece.NONE, after[parseSquare("f5")])
        assertEquals(Piece.make(app.chessalarm.chess.Color.WHITE, Piece.PAWN), after[parseSquare("f6")])
    }

    @Test fun promotionRequiresPiece() {
        val p = Position.fromFen("8/P7/8/8/8/8/8/k6K w - - 0 1")
        assertNull("bare a7a8 is not a legal move", p.parseUci("a7a8"))
        val promoted = p.play(p.parseUci("a7a8n")!!)
        assertEquals(Piece.KNIGHT, Piece.type(promoted[parseSquare("a8")]))
        assertEquals(4, p.legalMovesFrom(parseSquare("a7")).size)
    }

    @Test fun checkmateAndStalemateDetection() {
        assertTrue(Position.fromFen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3").isCheckmate())
        assertTrue(Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1").isStalemate())
        assertFalse(Position.start().isCheckmate())
    }

    @Test fun pinnedPieceCannotMove() {
        // White knight on e2 is pinned by the rook on e8.
        val p = Position.fromFen("4r2k/8/8/8/8/8/4N3/4K3 w - - 0 1")
        assertTrue(p.legalMovesFrom(parseSquare("e2")).isEmpty())
    }
}
