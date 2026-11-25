package app.chessalarm.ring

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chessalarm.data.AppStore
import app.chessalarm.data.RingKind
import app.chessalarm.ui.ChessAlarmTheme
import app.chessalarm.ui.Icons
import app.chessalarm.ui.Palette
import app.chessalarm.ui.PillButton
import app.chessalarm.ui.PuzzleBoard
import app.chessalarm.ui.Type
import app.chessalarm.ui.rememberBoardSounds
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The ringing screen: opens over the lock screen and turns the screen on. Back and volume keys do
 * nothing. If it's left, [AlarmService] brings it back. It never stops the alarm by itself.
 */
class RingingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.init(this)
        showOverLockScreen()
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit // Back does nothing while ringing.
        })
        ensureServiceRunning()
        setContent { ChessAlarmTheme { RingRoot(onFinished = { finishAndRemoveTask() }) } }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON,
        )
    }

    private fun ensureServiceRunning() {
        if (RingController.state.value == null && !AlarmService.isRunning) {
            val ring = AppStore.activeRing(this)
            if (ring != null) AlarmService.start(this, ring.alarmId, ring.kind, resume = true) else finish()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        showOverLockScreen()
    }

    override fun onStart() {
        super.onStart()
        RingController.setActivityVisible(true)
    }

    override fun onStop() {
        RingController.setActivityVisible(false)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS,
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> true
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> super.onKeyUp(keyCode, event)
    }
}

@Composable
private fun RingRoot(onFinished: () -> Unit) {
    val ui by RingController.state.collectAsState()
    val sounds = rememberBoardSounds()
    // Keep the last state so the "Solved" animation can finish after the service has stopped.
    var last by remember { mutableStateOf<RingUi?>(null) }
    if (ui != null) last = ui
    val state = last
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        if (state == null) {
            LaunchedEffect(Unit) {
                delay(3000)
                if (RingController.state.value == null && !AlarmService.isRunning) onFinished()
            }
            return@Box
        }
        if (state.finished != null) {
            SolvedOverlay(state.finished, sounds::solved, onFinished)
        } else {
            RingContent(state, sounds)
        }
    }
}

@Composable
private fun RingContent(ui: RingUi, sounds: app.chessalarm.ui.BoardSounds) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1000)
        }
    }
    var showEmergency by remember { mutableStateOf(false) }
    val is24h = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
    val fmt = remember(is24h) { DateTimeFormatter.ofPattern(if (is24h) "H:mm" else "h:mm") }

    Column(
        Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        // Large clock. Long-press for 3 seconds opens the hidden emergency exit (if enabled).
        var pressStart by remember { mutableLongStateOf(0L) }
        Text(
            now.format(fmt),
            style = Type.BigTime.copy(fontSize = 88.sp),
            modifier = Modifier.pointerInput(ui.emergencyEnabled) {
                if (!ui.emergencyEnabled) return@pointerInput
                detectTapGestures(onPress = {
                    pressStart = System.currentTimeMillis()
                    tryAwaitRelease()
                    if (System.currentTimeMillis() - pressStart >= 3000) showEmergency = true
                })
            },
        )
        Text(ui.label, style = Type.Secondary.copy(fontSize = 17.sp))
        Spacer(Modifier.weight(1f))
        PuzzleBoard(
            session = ui.session,
            version = ui.version,
            submit = { RingController.submitMove(it) },
            onTouch = { RingController.boardTouched() },
            modifier = Modifier.widthIn(max = 520.dp),
            sounds = sounds,
        )
        Spacer(Modifier.weight(1f))
        PhaseHint(ui)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Change puzzle", Modifier.weight(1f)) { RingController.changePuzzle() }
            AnimatedVisibility(
                visible = ui.canMute && ui.phase != app.chessalarm.ring.Phase.MUTED,
                modifier = Modifier.weight(1f),
                enter = fadeIn() + scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)),
            ) {
                PillButton("Mute", primary = true) { RingController.mute() }
            }
        }
        if (ui.skippable || ui.kind == RingKind.TEST_SEQUENCE) {
            Spacer(Modifier.height(12.dp))
            PillButton(
                if (ui.skippable) "Skip puzzle" else "End test",
                Modifier.fillMaxWidth(),
                color = Palette.Grouped,
            ) { if (ui.skippable) RingController.skip() else RingController.endTest() }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showEmergency) EmergencyDialog(ui.emergencyCode) { showEmergency = false }
}

@Composable
private fun PhaseHint(ui: RingUi) {
    val text = when (ui.phase) {
        app.chessalarm.ring.Phase.GENTLE -> "Good morning. Solve the puzzle to stop the alarm."
        app.chessalarm.ring.Phase.VIOLENT -> "Touch the board to start solving."
        app.chessalarm.ring.Phase.SOLVING -> if (ui.canMute) "Nice. Keep going — or mute." else "Find the best move."
        app.chessalarm.ring.Phase.MUTED -> "Muted. Finish the puzzle to dismiss."
        app.chessalarm.ring.Phase.DONE -> ""
    }
    Text(text, style = Type.Footnote, textAlign = TextAlign.Center)
}

@Composable
private fun EmergencyDialog(code: String, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Grouped,
        title = { Text("Emergency exit", style = Type.Title) },
        text = {
            Column {
                Text("Type the emergency code exactly to cancel this alarm. This is logged in your stats.", style = Type.Secondary)
                Spacer(Modifier.height(8.dp))
                Text("“$code”", style = Type.Secondary.copy(color = Palette.Text, fontWeight = FontWeight.Medium))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = false },
                    isError = error,
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!RingController.emergencyExit(input)) error = true }) {
                Text("Cancel alarm", color = Palette.Danger)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back", color = Palette.Accent) } },
    )
}

@Composable
private fun SolvedOverlay(reason: EndReason, playSound: () -> Unit, onFinished: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        shown = true
        if (reason == EndReason.SOLVED) playSound()
        delay(1900)
        onFinished()
    }
    val scale by animateFloatAsState(if (shown) 1f else 0.4f, spring(dampingRatio = 0.45f, stiffness = 260f), label = "solved")
    val ring by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 60f), label = "ring")
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(220.dp).scale(0.6f + ring * 0.6f)
                    .border(2.dp, Palette.Accent.copy(alpha = (1f - ring).coerceIn(0f, 1f)), CircleShape),
            )
            Box(
                Modifier.size(120.dp).scale(scale).background(Palette.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Check, null, tint = Color.Black, modifier = Modifier.size(64.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            when (reason) {
                EndReason.SOLVED -> "Solved"
                EndReason.SKIPPED -> "You’re up"
                EndReason.EMERGENCY_EXIT -> "Alarm cancelled"
                EndReason.TEST_ENDED -> "Test finished"
            },
            style = Type.LargeTitle.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.scale(scale),
        )
        Spacer(Modifier.height(6.dp))
        Text(if (reason == EndReason.SOLVED) "Good morning." else "", style = Type.Secondary)
    }
}


/** The ringing screen's content for a given state (used by screenshot tests). */
@Composable
internal fun RingPreview(ui: RingUi) = RingContent(ui, rememberBoardSounds())
