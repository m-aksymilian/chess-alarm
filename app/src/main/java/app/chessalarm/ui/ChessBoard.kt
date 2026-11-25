package app.chessalarm.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import app.chessalarm.R
import app.chessalarm.chess.Color as Side
import app.chessalarm.chess.Move
import app.chessalarm.chess.Piece
import app.chessalarm.chess.Position
import app.chessalarm.chess.file
import app.chessalarm.chess.rank
import app.chessalarm.chess.sq
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val LightSquare = Color(0xFFC9D1D6)
private val DarkSquare = Color(0xFF7A8C98)
private val LastMove = Color(0xFFFF9F0A).copy(alpha = 0.32f)
private val Selected = Color(0xFFFF9F0A).copy(alpha = 0.5f)
private val Hint = Color(0xFF0E1A22).copy(alpha = 0.28f)

/** Result of a move attempt, so the board can react (shake on wrong, accept on correct). */
enum class MoveFeedback { ACCEPTED, REJECTED }

/** An animated move the board should play (e.g. the opponent's reply). */
data class BoardAnimation(val id: Int, val move: Move, val before: Position)

/** Board sounds and haptics. */
class BoardSounds(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    private val move = pool.load(context, R.raw.sfx_move, 1)
    private val capture = pool.load(context, R.raw.sfx_capture, 1)
    private val solved = pool.load(context, R.raw.sfx_solved, 1)

    fun move(isCapture: Boolean) { pool.play(if (isCapture) capture else move, 0.8f, 0.8f, 1, 0, 1f) }
    fun solved() { pool.play(solved, 1f, 1f, 2, 0, 1f) }
    fun release() = pool.release()
}

@Composable
fun rememberBoardSounds(): BoardSounds {
    val ctx = LocalContext.current
    val s = remember { BoardSounds(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { s.release() } }
    return s
}

@Composable
private fun piecePainters(): Map<Int, VectorPainter> {
    val ids = mapOf(
        Piece.make(Side.WHITE, Piece.KING) to R.drawable.piece_wk, Piece.make(Side.WHITE, Piece.QUEEN) to R.drawable.piece_wq,
        Piece.make(Side.WHITE, Piece.ROOK) to R.drawable.piece_wr, Piece.make(Side.WHITE, Piece.BISHOP) to R.drawable.piece_wb,
        Piece.make(Side.WHITE, Piece.KNIGHT) to R.drawable.piece_wn, Piece.make(Side.WHITE, Piece.PAWN) to R.drawable.piece_wp,
        Piece.make(Side.BLACK, Piece.KING) to R.drawable.piece_bk, Piece.make(Side.BLACK, Piece.QUEEN) to R.drawable.piece_bq,
        Piece.make(Side.BLACK, Piece.ROOK) to R.drawable.piece_br, Piece.make(Side.BLACK, Piece.BISHOP) to R.drawable.piece_bb,
        Piece.make(Side.BLACK, Piece.KNIGHT) to R.drawable.piece_bn, Piece.make(Side.BLACK, Piece.PAWN) to R.drawable.piece_bp,
    )
    return ids.mapValues { (_, res) -> rememberVectorPainter(ImageVector.vectorResource(res)) }
}

/**
 * A chess board with drag-and-drop and tap-to-move, legal-move dots, last-move and check
 * highlights, coordinates, a promotion picker, opponent-move animation and a shake on wrong moves.
 *
 * @param flipped true to show the board from black's side.
 * @param onMove called with a legal move by the player; return whether it was accepted.
 * @param onTouch called on any touch (used to start the solving phase / reset inactivity).
 */
@Composable
fun ChessBoard(
    position: Position,
    lastMove: Move?,
    flipped: Boolean,
    interactive: Boolean,
    animation: BoardAnimation?,
    onMove: (Move) -> MoveFeedback,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
    sounds: BoardSounds? = null,
) {
    val painters = piecePainters()
    val measurer = rememberTextMeasurer()
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var selected by remember(position) { mutableStateOf(-1) }
    var dragFrom by remember { mutableStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var promotion by remember { mutableStateOf<Pair<Int, Int>?>(null) } // from, to
    val shake = remember { Animatable(0f) }
    val anim = remember { Animatable(1f) }
    var animMove by remember { mutableStateOf<BoardAnimation?>(null) }

    // Play incoming animations (opponent replies).
    LaunchedEffect(animation?.id) {
        val a = animation ?: return@LaunchedEffect
        animMove = a
        anim.snapTo(0f)
        anim.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        sounds?.move(a.before.isCapture(a.move))
        animMove = null
    }

    fun squareAt(p: Offset, cell: Float): Int {
        val c = (p.x / cell).toInt().coerceIn(0, 7)
        val r = (p.y / cell).toInt().coerceIn(0, 7)
        return if (flipped) sq(7 - c, r) else sq(c, 7 - r)
    }

    fun origin(square: Int, cell: Float): Offset {
        val c = if (flipped) 7 - file(square) else file(square)
        val r = if (flipped) rank(square) else 7 - rank(square)
        return Offset(c * cell, r * cell)
    }

    fun ownPiece(square: Int): Boolean {
        val p = position[square]
        return p != Piece.NONE && Piece.color(p) == position.sideToMove
    }

    fun submit(m: Move) {
        val capture = position.isCapture(m)
        selected = -1
        when (onMove(m)) {
            MoveFeedback.ACCEPTED -> {
                sounds?.move(capture)
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            MoveFeedback.REJECTED -> {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                scope.launch {
                    for (x in listOf(18f, -16f, 12f, -8f, 4f, 0f)) shake.animateTo(x, tween(45))
                }
            }
        }
    }

    /** Resolves a from/to gesture into a legal move (handles castling by king-onto-rook and promotion). */
    fun tryMove(from: Int, to: Int) {
        val legal = position.legalMovesFrom(from)
        var candidates = legal.filter { it.to == to }
        if (candidates.isEmpty() && Piece.type(position[from]) == Piece.KING && ownPiece(to) && Piece.type(position[to]) == Piece.ROOK) {
            val castleTo = if (file(to) > file(from)) from + 2 else from - 2
            candidates = legal.filter { it.to == castleTo }
        }
        when {
            candidates.isEmpty() -> {
                // Not a legal destination: snap back.
                selected = if (ownPiece(to)) to else -1
            }
            candidates.size > 1 -> promotion = from to candidates.first().to
            else -> submit(candidates.first())
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f)) {
        val sizePx = with(LocalDensity.current) { maxWidth.toPx() }
        val cell = sizePx / 8f
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = shake.value * density }
                .shadow(18.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .pointerInput(position, interactive, flipped) {
                    if (!interactive) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onTouch()
                        val sqDown = squareAt(down.position, cell)
                        val startedOnOwn = ownPiece(sqDown)
                        val hadSelection = selected
                        if (startedOnOwn) {
                            selected = sqDown
                            dragFrom = sqDown
                            dragPos = down.position
                        }
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.positionChange() != Offset.Zero) {
                                if ((ch.position - down.position).getDistance() > cell * 0.15f) moved = true
                                dragPos = ch.position
                                ch.consume()
                            }
                            if (!ch.pressed) {
                                val sqUp = squareAt(ch.position, cell)
                                val from = dragFrom
                                dragFrom = -1
                                if (startedOnOwn && moved && sqUp != from) {
                                    tryMove(from, sqUp)
                                } else if (!startedOnOwn && hadSelection >= 0) {
                                    tryMove(hadSelection, sqDown)
                                } else if (startedOnOwn && !moved && hadSelection == sqDown) {
                                    selected = -1
                                } else if (startedOnOwn && !moved && hadSelection >= 0 && hadSelection != sqDown &&
                                    Piece.type(position[hadSelection]) == Piece.KING && Piece.type(position[sqDown]) == Piece.ROOK
                                ) {
                                    tryMove(hadSelection, sqDown)
                                }
                                break
                            }
                        }
                    }
                },
        ) {
            drawBoard(this, position, lastMove, selected, flipped, cell, measurer, ::origin)
            val targets = if (selected >= 0) position.legalMovesFrom(selected).map { it.to }.toSet() else emptySet()
            for (t in targets) {
                val o = origin(t, cell)
                val center = o + Offset(cell / 2, cell / 2)
                if (position[t] != Piece.NONE || (Piece.type(position[selected]) == Piece.PAWN && file(t) != file(selected))) {
                    drawCircle(Hint, radius = cell * 0.46f, center = center, style = Stroke(width = cell * 0.08f))
                } else {
                    drawCircle(Hint, radius = cell * 0.16f, center = center)
                }
            }
            // Pieces
            val am = animMove
            val showPos = am?.before?.play(am.move)?.takeIf { anim.value < 1f } ?: position
            for (s in 0 until 64) {
                val p = showPos[s]
                if (p == Piece.NONE || s == dragFrom) continue
                if (am != null && anim.value < 1f && s == am.move.to) continue
                drawPiece(painters[p] ?: continue, origin(s, cell), cell)
            }
            if (am != null && anim.value < 1f) {
                val p = am.before[am.move.from]
                val a = origin(am.move.from, cell)
                val b = origin(am.move.to, cell)
                painters[p]?.let { drawPiece(it, a + (b - a) * anim.value, cell) }
            }
            if (dragFrom >= 0) {
                val p = position[dragFrom]
                val big = cell * 1.25f
                painters[p]?.let { drawPiece(it, dragPos - Offset(big / 2, big * 0.75f), big) }
            }
        }

        // Promotion picker over the target square's file.
        promotion?.let { (from, to) ->
            val color = Piece.color(position[from])
            val o = origin(to, cell)
            val types = listOf(Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT)
            val towardsBottom = o.y < sizePx / 2
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { promotion = null })
            Column(
                Modifier
                    .offset { IntOffset(o.x.roundToInt(), if (towardsBottom) o.y.roundToInt() else (o.y - cell * 3).roundToInt()) }
                    .size(with(density) { cell.toDp() }, with(density) { (cell * 4).toDp() })
                    .shadow(12.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF2F2F7)),
            ) {
                for (t in if (towardsBottom) types else types.reversed()) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth().clickable {
                            promotion = null
                            submit(Move(from, to, t))
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(painters.getValue(Piece.make(color, t)), null, Modifier.fillMaxSize().padding(4.dp))
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawPiece(painter: VectorPainter, at: Offset, size: Float) {
    translate(at.x, at.y) {
        with(painter) { draw(Size(size, size)) }
    }
}

private fun drawBoard(
    scope: DrawScope,
    position: Position,
    lastMove: Move?,
    selected: Int,
    flipped: Boolean,
    cell: Float,
    measurer: TextMeasurer,
    origin: (Int, Float) -> Offset,
) = with(scope) {
    for (s in 0 until 64) {
        val light = (file(s) + rank(s)) % 2 == 1
        val o = origin(s, cell)
        drawRect(if (light) LightSquare else DarkSquare, o, Size(cell, cell))
        if (lastMove != null && (s == lastMove.from || s == lastMove.to)) drawRect(LastMove, o, Size(cell, cell))
        if (s == selected) drawRect(Selected, o, Size(cell, cell))
    }
    // Check highlight
    if (position.isInCheck()) {
        val k = position.kingSquare(position.sideToMove)
        val o = origin(k, cell)
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFFFF3B30), Color(0xFFFF3B30).copy(alpha = 0.55f), Color.Transparent),
                center = o + Offset(cell / 2, cell / 2),
                radius = cell * 0.7f,
            ),
            o, Size(cell, cell),
        )
    }
    // Coordinates on the edges
    val style = TextStyle(fontSize = (cell / 5.2f / density).sp, fontWeight = FontWeight.SemiBold)
    for (i in 0..7) {
        val fileIdx = if (flipped) 7 - i else i
        val bottomSq = if (flipped) sq(fileIdx, 7) else sq(fileIdx, 0)
        val fileLight = (file(bottomSq) + rank(bottomSq)) % 2 == 1
        drawText(
            measurer, "${'a' + fileIdx}", Offset(i * cell + cell - cell * 0.2f, 8 * cell - cell * 0.27f),
            style.copy(color = if (fileLight) DarkSquare else LightSquare),
        )
        val rankIdx = if (flipped) i else 7 - i
        val leftSq = if (flipped) sq(7, rankIdx) else sq(0, rankIdx)
        val rankLight = (file(leftSq) + rank(leftSq)) % 2 == 1
        drawText(
            measurer, "${rankIdx + 1}", Offset(cell * 0.06f, i * cell + cell * 0.03f),
            style.copy(color = if (rankLight) DarkSquare else LightSquare),
        )
    }
}

/** Small legend row ("White to move") with a piece glyph. */
@Composable
fun SideToMove(side: Side) {
    val painter = rememberVectorPainter(
        ImageVector.vectorResource(if (side == Side.WHITE) R.drawable.piece_wk else R.drawable.piece_bk),
    )
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.Grouped).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painter, null, Modifier.size(20.dp))
        androidx.compose.material3.Text(
            if (side == Side.WHITE) "  White to move" else "  Black to move",
            style = Type.Secondary.copy(color = Palette.Text, fontWeight = FontWeight.Medium),
        )
    }
}

