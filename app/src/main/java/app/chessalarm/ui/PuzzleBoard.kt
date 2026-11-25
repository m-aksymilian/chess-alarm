package app.chessalarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chessalarm.chess.Color as Side
import app.chessalarm.chess.Move
import app.chessalarm.puzzle.PuzzleSession
import app.chessalarm.puzzle.themeLabel
import kotlinx.coroutines.delay

/**
 * A puzzle on a board: animates the opponent's setup move and replies, shakes on wrong moves.
 * [submit] performs the attempt (the alarm service owns the session while ringing).
 */
@Composable
fun PuzzleBoard(
    session: PuzzleSession,
    version: Int,
    submit: (Move) -> PuzzleSession.Result,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
    sounds: BoardSounds? = null,
    showInfo: Boolean = true,
) {
    var animation by remember { mutableStateOf<BoardAnimation?>(null) }
    var animId by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    // New puzzle: play the opponent's setup move first, as Lichess does.
    LaunchedEffect(session.puzzle.id) {
        busy = true
        animId++
        animation = BoardAnimation(animId, session.setupMove, session.initialPosition)
        delay(380)
        busy = false
    }

    var pendingReply by remember { mutableStateOf<Pair<Move, Move>?>(null) }
    // While waiting to play the opponent's reply, keep showing the position right after the solver's move.
    var hold by remember { mutableStateOf<Pair<app.chessalarm.chess.Position, Move>?>(null) }
    LaunchedEffect(pendingReply) {
        val (_, reply) = pendingReply ?: return@LaunchedEffect
        busy = true
        delay(260)
        animId++
        animation = BoardAnimation(animId, reply, session.positionBeforeReply ?: session.position)
        hold = null
        delay(360)
        busy = false
        pendingReply = null
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (showInfo) {
            SideToMove(session.solverColor)
            Spacer(Modifier.height(14.dp))
        }
        ChessBoard(
            position = hold?.first ?: session.position,
            lastMove = hold?.second ?: session.lastMove,
            flipped = session.solverColor == Side.BLACK,
            interactive = !busy && !session.isSolved,
            animation = animation,
            onMove = { m ->
                when (val r = submit(m)) {
                    is PuzzleSession.Result.Correct -> {
                        session.positionBeforeReply?.let { hold = it to r.move }
                        pendingReply = r.move to r.reply
                        MoveFeedback.ACCEPTED
                    }
                    is PuzzleSession.Result.Solved -> MoveFeedback.ACCEPTED
                    else -> MoveFeedback.REJECTED
                }
            },
            onTouch = onTouch,
            sounds = sounds,
        )
        if (showInfo) {
            Spacer(Modifier.height(10.dp))
            Text(
                "${themeLabel(session.puzzle.theme)}  ·  ${session.puzzle.rating}",
                style = Type.Footnote,
            )
        }
    }
    // [version] is a recomposition key for the mutable session.
    @Suppress("UNUSED_EXPRESSION") version
}

