package app.chessalarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.chessalarm.data.AppStore
import app.chessalarm.puzzle.PuzzlePicker
import app.chessalarm.puzzle.PuzzleSession
import app.chessalarm.ring.PuzzleRepository
import kotlinx.coroutines.delay

/** "Changing an alarm within 10 minutes of it ringing requires solving a puzzle." */
@Composable
fun PuzzleGateScreen(reason: String, onCancel: () -> Unit, onSolved: () -> Unit) {
    val context = LocalContext.current
    val sounds = rememberBoardSounds()
    var session by remember {
        val s = AppStore.current.settings
        mutableStateOf(PuzzleSession(PuzzlePicker(PuzzleRepository.all(context)).pick(s.minRating, s.maxRating)))
    }
    var version by remember { mutableIntStateOf(0) }
    var solved by remember { mutableStateOf(false) }
    LaunchedEffect(solved) {
        if (solved) {
            sounds.solved()
            delay(700)
            onSolved()
        }
    }
    Column(
        Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NavBar("Solve to continue", "Cancel", onCancel)
        Text(reason, style = Type.Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        Spacer(Modifier.height(24.dp))
        PuzzleBoard(
            session = session,
            version = version,
            submit = { m ->
                val r = session.attempt(m)
                if (r is PuzzleSession.Result.Solved) solved = true
                if (r is PuzzleSession.Result.Correct || r is PuzzleSession.Result.Solved) version++
                r
            },
            onTouch = {},
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp),
            sounds = sounds,
        )
        Spacer(Modifier.height(20.dp))
        PillButton("Change puzzle", Modifier.padding(horizontal = 16.dp)) {
            val s = AppStore.current.settings
            session = PuzzleSession(PuzzlePicker(PuzzleRepository.all(context)).pick(s.minRating, s.maxRating, exclude = session.puzzle.id))
            version++
        }
    }
}
