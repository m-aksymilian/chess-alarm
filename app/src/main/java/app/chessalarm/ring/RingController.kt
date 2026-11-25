package app.chessalarm.ring

import android.content.Context
import app.chessalarm.chess.Move
import app.chessalarm.data.RingKind
import app.chessalarm.puzzle.Puzzle
import app.chessalarm.puzzle.PuzzleParser
import app.chessalarm.puzzle.PuzzleSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the ringing screen shows. */
data class RingUi(
    val kind: RingKind,
    val label: String,
    val session: PuzzleSession,
    /** Bumped whenever [session] changes, so Compose recomposes. */
    val version: Int,
    val phase: Phase,
    val canMute: Boolean,
    val skippable: Boolean,
    val emergencyEnabled: Boolean,
    val emergencyCode: String,
    val finished: EndReason? = null,
)

/**
 * The bridge between the ringing screen (UI) and [AlarmService] (which owns the state machine,
 * sound and vibration). Everything runs on the main thread.
 */
object RingController {
    private val _state = MutableStateFlow<RingUi?>(null)
    val state: StateFlow<RingUi?> = _state.asStateFlow()

    @Volatile
    var activityVisible: Boolean = false
        private set
    var lastVisibilityChange: Long = 0
        private set

    internal var service: AlarmService? = null

    internal fun publish(ui: RingUi?) {
        _state.value = ui
    }

    fun setActivityVisible(visible: Boolean) {
        activityVisible = visible
        lastVisibilityChange = android.os.SystemClock.elapsedRealtime()
        service?.onActivityVisibility(visible)
    }

    fun boardTouched() = service?.onUiEvent(RingEvent.BoardTouched)
    fun interaction() = service?.onUiEvent(RingEvent.Interaction)
    fun mute() = service?.onUiEvent(RingEvent.MuteRequested)

    fun submitMove(move: Move): PuzzleSession.Result = service?.submitMove(move) ?: PuzzleSession.Result.Illegal

    fun changePuzzle() = service?.changePuzzle()
    fun skip() = service?.requestEnd(EndReason.SKIPPED)
    fun endTest() = service?.requestEnd(EndReason.TEST_ENDED)

    /** Returns true if the code matched and the alarm was cancelled. */
    fun emergencyExit(code: String): Boolean = service?.emergencyExit(code) ?: false
}

/** Loads the bundled puzzles once. Assets are readable before first unlock (direct boot). */
object PuzzleRepository {
    @Volatile
    private var cache: List<Puzzle>? = null

    fun all(context: Context): List<Puzzle> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val text = context.assets.open("puzzles.json").bufferedReader().use { it.readText() }
            return PuzzleParser.parse(text).also { cache = it }
        }
    }
}
