package app.chessalarm.chess

/**
 * A complete, immutable chess position with legal move generation.
 *
 * Squares are 0..63 with a1 = 0, h1 = 7, a8 = 56, h8 = 63.
 * Pieces are encoded as [Piece] values; empty squares hold [Piece.NONE].
 */
class Position private constructor(
    private val board: IntArray,
    val whiteToMove: Boolean,
    /** Castling rights bitmask: 1 = white king side, 2 = white queen side, 4 = black king side, 8 = black queen side. */
    val castling: Int,
    /** En passant target square, or -1. */
    val epSquare: Int,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    operator fun get(square: Int): Int = board[square]

    val sideToMove: Color get() = if (whiteToMove) Color.WHITE else Color.BLACK

    fun kingSquare(color: Color): Int {
        val k = Piece.make(color, Piece.KING)
        for (i in 0 until 64) if (board[i] == k) return i
        return -1
    }

    // ---------------------------------------------------------------------------------- attacks

    /** True if [square] is attacked by any piece of [by]. */
    fun isAttacked(square: Int, by: Color): Boolean {
        val f = file(square)
        val r = rank(square)
        // Pawns
        val pawnRank = if (by == Color.WHITE) r - 1 else r + 1
        if (pawnRank in 0..7) {
            val pawn = Piece.make(by, Piece.PAWN)
            if (f > 0 && board[sq(f - 1, pawnRank)] == pawn) return true
            if (f < 7 && board[sq(f + 1, pawnRank)] == pawn) return true
        }
        // Knights
        val knight = Piece.make(by, Piece.KNIGHT)
        for ((df, dr) in KNIGHT_DELTAS) {
            val nf = f + df
            val nr = r + dr
            if (nf in 0..7 && nr in 0..7 && board[sq(nf, nr)] == knight) return true
        }
        // King
        val king = Piece.make(by, Piece.KING)
        for ((df, dr) in KING_DELTAS) {
            val nf = f + df
            val nr = r + dr
            if (nf in 0..7 && nr in 0..7 && board[sq(nf, nr)] == king) return true
        }
        // Sliders
        val bishop = Piece.make(by, Piece.BISHOP)
        val rook = Piece.make(by, Piece.ROOK)
        val queen = Piece.make(by, Piece.QUEEN)
        for ((df, dr) in BISHOP_DELTAS) {
            var nf = f + df
            var nr = r + dr
            while (nf in 0..7 && nr in 0..7) {
                val p = board[sq(nf, nr)]
                if (p != Piece.NONE) {
                    if (p == bishop || p == queen) return true
                    break
                }
                nf += df; nr += dr
            }
        }
        for ((df, dr) in ROOK_DELTAS) {
            var nf = f + df
            var nr = r + dr
            while (nf in 0..7 && nr in 0..7) {
                val p = board[sq(nf, nr)]
                if (p != Piece.NONE) {
                    if (p == rook || p == queen) return true
                    break
                }
                nf += df; nr += dr
            }
        }
        return false
    }

    fun isInCheck(): Boolean {
        val k = kingSquare(sideToMove)
        return k >= 0 && isAttacked(k, sideToMove.opposite)
    }

    // ---------------------------------------------------------------------------- move generation

    private fun pseudoLegalMoves(): List<Move> {
        val moves = ArrayList<Move>(48)
        val us = sideToMove
        val them = us.opposite
        for (from in 0 until 64) {
            val p = board[from]
            if (p == Piece.NONE || Piece.color(p) != us) continue
            val f = file(from)
            val r = rank(from)
            when (Piece.type(p)) {
                Piece.PAWN -> {
                    val dir = if (us == Color.WHITE) 1 else -1
                    val startRank = if (us == Color.WHITE) 1 else 6
                    val promoRank = if (us == Color.WHITE) 7 else 0
                    val r1 = r + dir
                    if (r1 in 0..7 && board[sq(f, r1)] == Piece.NONE) {
                        addPawnMove(moves, from, sq(f, r1), r1 == promoRank)
                        val r2 = r + 2 * dir
                        if (r == startRank && board[sq(f, r2)] == Piece.NONE) moves.add(Move(from, sq(f, r2)))
                    }
                    for (df in intArrayOf(-1, 1)) {
                        val nf = f + df
                        if (nf !in 0..7 || r1 !in 0..7) continue
                        val to = sq(nf, r1)
                        val target = board[to]
                        if (target != Piece.NONE && Piece.color(target) == them) {
                            addPawnMove(moves, from, to, r1 == promoRank)
                        } else if (to == epSquare) {
                            moves.add(Move(from, to))
                        }
                    }
                }
                Piece.KNIGHT -> addSteps(moves, from, KNIGHT_DELTAS, us)
                Piece.KING -> {
                    addSteps(moves, from, KING_DELTAS, us)
                    addCastling(moves, from, us)
                }
                Piece.BISHOP -> addSlides(moves, from, BISHOP_DELTAS, us)
                Piece.ROOK -> addSlides(moves, from, ROOK_DELTAS, us)
                Piece.QUEEN -> {
                    addSlides(moves, from, BISHOP_DELTAS, us)
                    addSlides(moves, from, ROOK_DELTAS, us)
                }
            }
        }
        return moves
    }

    private fun addPawnMove(moves: MutableList<Move>, from: Int, to: Int, promotes: Boolean) {
        if (promotes) {
            for (pt in PROMOTION_TYPES) moves.add(Move(from, to, pt))
        } else {
            moves.add(Move(from, to))
        }
    }

    private fun addSteps(moves: MutableList<Move>, from: Int, deltas: Array<IntArray>, us: Color) {
        val f = file(from)
        val r = rank(from)
        for ((df, dr) in deltas) {
            val nf = f + df
            val nr = r + dr
            if (nf !in 0..7 || nr !in 0..7) continue
            val to = sq(nf, nr)
            val t = board[to]
            if (t == Piece.NONE || Piece.color(t) != us) moves.add(Move(from, to))
        }
    }

    private fun addSlides(moves: MutableList<Move>, from: Int, deltas: Array<IntArray>, us: Color) {
        val f = file(from)
        val r = rank(from)
        for ((df, dr) in deltas) {
            var nf = f + df
            var nr = r + dr
            while (nf in 0..7 && nr in 0..7) {
                val to = sq(nf, nr)
                val t = board[to]
                if (t == Piece.NONE) {
                    moves.add(Move(from, to))
                } else {
                    if (Piece.color(t) != us) moves.add(Move(from, to))
                    break
                }
                nf += df; nr += dr
            }
        }
    }

    private fun addCastling(moves: MutableList<Move>, from: Int, us: Color) {
        val them = us.opposite
        if (us == Color.WHITE && from == E1) {
            if (castling and 1 != 0 && board[F1] == Piece.NONE && board[G1] == Piece.NONE &&
                board[H1] == Piece.make(Color.WHITE, Piece.ROOK) &&
                !isAttacked(E1, them) && !isAttacked(F1, them) && !isAttacked(G1, them)
            ) moves.add(Move(E1, G1))
            if (castling and 2 != 0 && board[D1] == Piece.NONE && board[C1] == Piece.NONE && board[B1] == Piece.NONE &&
                board[A1] == Piece.make(Color.WHITE, Piece.ROOK) &&
                !isAttacked(E1, them) && !isAttacked(D1, them) && !isAttacked(C1, them)
            ) moves.add(Move(E1, C1))
        } else if (us == Color.BLACK && from == E8) {
            if (castling and 4 != 0 && board[F8] == Piece.NONE && board[G8] == Piece.NONE &&
                board[H8] == Piece.make(Color.BLACK, Piece.ROOK) &&
                !isAttacked(E8, them) && !isAttacked(F8, them) && !isAttacked(G8, them)
            ) moves.add(Move(E8, G8))
            if (castling and 8 != 0 && board[D8] == Piece.NONE && board[C8] == Piece.NONE && board[B8] == Piece.NONE &&
                board[A8] == Piece.make(Color.BLACK, Piece.ROOK) &&
                !isAttacked(E8, them) && !isAttacked(D8, them) && !isAttacked(C8, them)
            ) moves.add(Move(E8, C8))
        }
    }

    private var cachedLegal: List<Move>? = null

    /** All legal moves for the side to move. */
    fun legalMoves(): List<Move> {
        cachedLegal?.let { return it }
        val us = sideToMove
        val legal = pseudoLegalMoves().filter { m ->
            val next = applyUnchecked(m)
            val k = next.kingSquare(us)
            k >= 0 && !next.isAttacked(k, us.opposite)
        }
        cachedLegal = legal
        return legal
    }

    fun legalMovesFrom(square: Int): List<Move> = legalMoves().filter { it.from == square }

    fun isLegal(move: Move): Boolean = legalMoves().contains(move)

    fun isCheckmate(): Boolean = isInCheck() && legalMoves().isEmpty()
    fun isStalemate(): Boolean = !isInCheck() && legalMoves().isEmpty()

    fun isCapture(move: Move): Boolean =
        board[move.to] != Piece.NONE ||
            (Piece.type(board[move.from]) == Piece.PAWN && move.to == epSquare && file(move.from) != file(move.to))

    fun isCastle(move: Move): Boolean =
        Piece.type(board[move.from]) == Piece.KING && kotlin.math.abs(file(move.from) - file(move.to)) == 2

    /** Applies a legal move. Throws [IllegalArgumentException] if the move is not legal. */
    fun play(move: Move): Position {
        require(isLegal(move)) { "Illegal move ${move.uci} in ${toFen()}" }
        return applyUnchecked(move)
    }

    private fun applyUnchecked(m: Move): Position {
        val b = board.copyOf()
        val p = b[m.from]
        val type = Piece.type(p)
        val us = Piece.color(p)
        val captured = b[m.to]
        var newEp = -1
        var cr = castling

        b[m.to] = if (m.promotion != Piece.NONE) Piece.make(us, m.promotion) else p
        b[m.from] = Piece.NONE

        if (type == Piece.PAWN) {
            if (m.to == epSquare && file(m.from) != file(m.to) && captured == Piece.NONE) {
                // En passant capture: remove the pawn behind the target square.
                val capSq = sq(file(m.to), rank(m.from))
                b[capSq] = Piece.NONE
            }
            if (kotlin.math.abs(rank(m.to) - rank(m.from)) == 2) {
                newEp = sq(file(m.from), (rank(m.from) + rank(m.to)) / 2)
            }
        }
        if (type == Piece.KING) {
            if (us == Color.WHITE) cr = cr and 3.inv() else cr = cr and 12.inv()
            if (m.from == E1 && m.to == G1) { b[F1] = b[H1]; b[H1] = Piece.NONE }
            if (m.from == E1 && m.to == C1) { b[D1] = b[A1]; b[A1] = Piece.NONE }
            if (m.from == E8 && m.to == G8) { b[F8] = b[H8]; b[H8] = Piece.NONE }
            if (m.from == E8 && m.to == C8) { b[D8] = b[A8]; b[A8] = Piece.NONE }
        }
        // Any move touching a rook corner removes the matching right.
        for (s in intArrayOf(m.from, m.to)) {
            when (s) {
                H1 -> cr = cr and 1.inv()
                A1 -> cr = cr and 2.inv()
                H8 -> cr = cr and 4.inv()
                A8 -> cr = cr and 8.inv()
            }
        }
        val resetClock = type == Piece.PAWN || captured != Piece.NONE
        return Position(
            b,
            !whiteToMove,
            cr,
            newEp,
            if (resetClock) 0 else halfmoveClock + 1,
            if (whiteToMove) fullmoveNumber else fullmoveNumber + 1,
        )
    }

    /** Parses a UCI move ("e2e4", "e7e8q") and returns the matching legal move, or null. */
    fun parseUci(uci: String): Move? {
        val m = Move.fromUci(uci) ?: return null
        return legalMoves().firstOrNull { it == m }
    }

    // --------------------------------------------------------------------------------------- FEN

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = board[sq(f, r)]
                if (p == Piece.NONE) {
                    empty++
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(Piece.toChar(p))
                }
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        if (castling == 0) sb.append('-') else {
            if (castling and 1 != 0) sb.append('K')
            if (castling and 2 != 0) sb.append('Q')
            if (castling and 4 != 0) sb.append('k')
            if (castling and 8 != 0) sb.append('q')
        }
        sb.append(' ').append(if (epSquare >= 0) squareName(epSquare) else "-")
        sb.append(' ').append(halfmoveClock).append(' ').append(fullmoveNumber)
        return sb.toString()
    }

    override fun toString(): String = toFen()

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.size >= 4) { "Bad FEN: $fen" }
            val b = IntArray(64)
            val rows = parts[0].split('/')
            require(rows.size == 8) { "Bad FEN board: $fen" }
            for ((i, row) in rows.withIndex()) {
                val r = 7 - i
                var f = 0
                for (c in row) {
                    if (c.isDigit()) {
                        f += c - '0'
                    } else {
                        require(f < 8) { "Bad FEN row: $row" }
                        b[sq(f, r)] = Piece.fromChar(c)
                        f++
                    }
                }
                require(f == 8) { "Bad FEN row length: $row" }
            }
            val white = when (parts[1]) {
                "w" -> true
                "b" -> false
                else -> throw IllegalArgumentException("Bad side to move: $fen")
            }
            var cr = 0
            if (parts[2] != "-") for (c in parts[2]) {
                cr = cr or when (c) {
                    'K' -> 1; 'Q' -> 2; 'k' -> 4; 'q' -> 8
                    else -> throw IllegalArgumentException("Bad castling: $fen")
                }
            }
            val ep = if (parts[3] == "-") -1 else parseSquare(parts[3])
            val half = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val full = parts.getOrNull(5)?.toIntOrNull() ?: 1
            return Position(b, white, cr, ep, half, full)
        }

        fun start(): Position = fromFen(START_FEN)

        private val KNIGHT_DELTAS = arrayOf(
            intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
            intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2),
        )
        private val KING_DELTAS = arrayOf(
            intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
            intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
        )
        private val BISHOP_DELTAS = arrayOf(intArrayOf(1, 1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(-1, -1))
        private val ROOK_DELTAS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        private val PROMOTION_TYPES = intArrayOf(Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT)

        private const val A1 = 0
        private const val B1 = 1
        private const val C1 = 2
        private const val D1 = 3
        private const val E1 = 4
        private const val F1 = 5
        private const val G1 = 6
        private const val H1 = 7
        private const val A8 = 56
        private const val B8 = 57
        private const val C8 = 58
        private const val D8 = 59
        private const val E8 = 60
        private const val F8 = 61
        private const val G8 = 62
        private const val H8 = 63
    }
}

enum class Color {
    WHITE, BLACK;

    val opposite: Color get() = if (this == WHITE) BLACK else WHITE
}

/** Piece encoding helpers: colour in bit 3 (0 = white, 8 = black), type in bits 0..2. */
object Piece {
    const val NONE = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6

    fun make(color: Color, type: Int): Int = type or (if (color == Color.BLACK) 8 else 0)
    fun type(p: Int): Int = p and 7
    fun color(p: Int): Color = if (p and 8 != 0) Color.BLACK else Color.WHITE

    fun toChar(p: Int): Char {
        val c = "?pnbrqk"[type(p)]
        return if (color(p) == Color.WHITE) c.uppercaseChar() else c
    }

    fun fromChar(c: Char): Int {
        val type = when (c.lowercaseChar()) {
            'p' -> PAWN; 'n' -> KNIGHT; 'b' -> BISHOP; 'r' -> ROOK; 'q' -> QUEEN; 'k' -> KING
            else -> throw IllegalArgumentException("Bad piece char: $c")
        }
        return make(if (c.isUpperCase()) Color.WHITE else Color.BLACK, type)
    }
}

data class Move(val from: Int, val to: Int, val promotion: Int = Piece.NONE) {
    val uci: String
        get() = squareName(from) + squareName(to) +
            if (promotion != Piece.NONE) "?pnbrqk"[promotion].toString() else ""

    override fun toString(): String = uci

    companion object {
        fun fromUci(uci: String): Move? {
            if (uci.length !in 4..5) return null
            val from = runCatching { parseSquare(uci.substring(0, 2)) }.getOrNull() ?: return null
            val to = runCatching { parseSquare(uci.substring(2, 4)) }.getOrNull() ?: return null
            val promo = if (uci.length == 5) when (uci[4].lowercaseChar()) {
                'q' -> Piece.QUEEN; 'r' -> Piece.ROOK; 'b' -> Piece.BISHOP; 'n' -> Piece.KNIGHT
                else -> return null
            } else Piece.NONE
            return Move(from, to, promo)
        }
    }
}

fun sq(file: Int, rank: Int): Int = rank * 8 + file
fun file(square: Int): Int = square and 7
fun rank(square: Int): Int = square shr 3
fun squareName(square: Int): String = "${'a' + file(square)}${'1' + rank(square)}"
fun parseSquare(name: String): Int {
    require(name.length == 2 && name[0] in 'a'..'h' && name[1] in '1'..'8') { "Bad square: $name" }
    return sq(name[0] - 'a', name[1] - '1')
}
