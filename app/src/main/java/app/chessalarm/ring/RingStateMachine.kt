package app.chessalarm.ring

import app.chessalarm.data.GentleVibration
import app.chessalarm.data.Intensity
import app.chessalarm.data.SolvingMode
import app.chessalarm.data.StillAwakeConfig
import app.chessalarm.data.WakeConfig
import kotlin.math.pow

enum class Phase { GENTLE, VIOLENT, SOLVING, MUTED, DONE }

enum class SoundSlot { PRIMARY, SECONDARY, NONE }

enum class EndReason { SOLVED, SKIPPED, EMERGENCY_EXIT, TEST_ENDED }

sealed interface RingEvent {
    /** The user touched the board (starts the solving phase). */
    data object BoardTouched : RingEvent

    /** Any other interaction with the puzzle (refreshes the inactivity guard). */
    data object Interaction : RingEvent

    /** The screen turned off / power button pressed / the ringing screen was left. */
    data object ScreenOff : RingEvent

    data object CorrectMove : RingEvent

    data object MuteRequested : RingEvent

    data class End(val reason: EndReason) : RingEvent
}

data class RingOutput(
    val phase: Phase,
    /** Effective playback volume 0..1 (already scaled by the alarm's volume setting). */
    val volume: Float,
    val sound: SoundSlot,
    /** Vibration intensity, or null for no vibration. */
    val vibration: Intensity?,
    val canMute: Boolean,
)

data class RingConfig(
    val fadeIn: Boolean,
    val gentleMs: Long,
    val gentleStart: Float,
    val gentleMax: Float,
    val gentleVibration: GentleVibration,
    val violentPreset: Intensity,
    val baseIntensity: Intensity,
    val smartEscalation: Boolean,
    val escalationIntervalMs: Long = 30_000,
    val solvingMode: SolvingMode,
    val inactivityMs: Long,
    val vibrationEnabled: Boolean,
    val secondarySound: Boolean,
    val lowSoundLevel: Float = 0.15f,
    val masterVolume: Float,
    /** At the start of the gentle phase, vibrate only (no sound) for this long. */
    val silentLeadMs: Long = 0,
    /** Vibration level in the gentle phase when [gentleVibration] is SOFT. */
    val gentleVibrationIntensity: Intensity = Intensity.GENTLE,
) {
    companion object {
        /**
         * The "are you still awake?" re-ring: vibration only at first, then the sound fades in
         * smoothly into the violent phase. Every part comes from [StillAwakeConfig].
         */
        fun stillAwake(c: WakeConfig, sa: StillAwakeConfig, timeScale: Float = 1f): RingConfig {
            val lead = (sa.vibrationOnlySec * 1000L * timeScale).toLong()
            val fade = (sa.fadeSec * 1000L * timeScale).toLong()
            return from(c, timeScale).copy(
                fadeIn = true,
                gentleMs = lead + fade,
                silentLeadMs = lead,
                gentleStart = sa.startVolumePercent / 100f,
                gentleMax = 1f,
                gentleVibration = GentleVibration.SOFT,
                gentleVibrationIntensity = sa.vibrationIntensity,
                // The chosen intensity is where violent vibration starts (escalation goes up from there).
                violentPreset = sa.vibrationIntensity,
                baseIntensity = sa.vibrationIntensity,
                // One sound throughout: no switch to the global "harsher second sound".
                secondarySound = false,
                smartEscalation = sa.smartEscalation,
                solvingMode = sa.solvingMode,
                vibrationEnabled = true,
            )
        }

        fun from(c: WakeConfig, timeScale: Float = 1f) = RingConfig(
            fadeIn = c.fadeInEnabled,
            gentleMs = (c.gentleDurationSec * 1000L * timeScale).toLong(),
            gentleStart = c.gentleStartPercent / 100f,
            gentleMax = c.gentleMaxPercent / 100f,
            gentleVibration = c.gentleVibration,
            violentPreset = c.violentPreset,
            baseIntensity = c.intensity,
            smartEscalation = c.smartEscalation,
            escalationIntervalMs = (30_000L * timeScale).toLong(),
            solvingMode = c.solvingMode,
            inactivityMs = (c.inactivitySec * 1000L * timeScale).toLong(),
            vibrationEnabled = c.vibrationEnabled,
            secondarySound = c.secondarySoundEnabled,
            masterVolume = c.volumePercent / 100f,
        )
    }
}

/** The gentle-phase loudness curve: an exponential rise (linear in decibels), so it never jumps. */
object WakeCurve {
    fun gentle(progress: Float, start: Float, max: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val s = start.coerceIn(0.001f, 1f)
        val m = max.coerceIn(s, 1f)
        return s * (m / s).pow(p)
    }
}

/**
 * The alarm's state machine: gentle -> violent -> solving (and back on inactivity / screen off)
 * -> muted -> done. It is time-driven by [tick] and user-driven by [onEvent]; [output] says what
 * the service should be doing right now. It has no Android dependencies so it is fully unit tested.
 */
class RingStateMachine(
    private val config: RingConfig,
    startMs: Long,
    /** Resume straight into the violent phase (e.g. after a reboot with an unsolved alarm). */
    resumeViolent: Boolean = false,
) {
    var phase: Phase = if (resumeViolent || !config.fadeIn) Phase.VIOLENT else Phase.GENTLE
        private set
    var endReason: EndReason? = null
        private set
    var canMute: Boolean = false
        private set

    private val startedAt = startMs
    private var violentSince: Long = startMs
    private var lastInteraction: Long = startMs
    private var frozenLevel: Intensity? = null

    val isDone: Boolean get() = phase == Phase.DONE

    fun tick(now: Long) {
        when (phase) {
            Phase.GENTLE -> if (now - startedAt >= config.gentleMs) enterViolent(startedAt + config.gentleMs)
            Phase.SOLVING, Phase.MUTED -> if (now - lastInteraction >= config.inactivityMs) enterViolent(now)
            else -> Unit
        }
    }

    fun onEvent(event: RingEvent, now: Long) {
        if (phase == Phase.DONE) return
        tick(now)
        when (event) {
            RingEvent.BoardTouched -> {
                lastInteraction = now
                if (phase == Phase.GENTLE || phase == Phase.VIOLENT) {
                    frozenLevel = escalatedLevel(now)
                    phase = Phase.SOLVING
                }
            }
            RingEvent.Interaction -> lastInteraction = now
            RingEvent.ScreenOff -> if (phase == Phase.SOLVING || phase == Phase.MUTED) enterViolent(now)
            RingEvent.CorrectMove -> {
                lastInteraction = now
                canMute = true
            }
            RingEvent.MuteRequested -> if (canMute) {
                lastInteraction = now
                phase = Phase.MUTED
            }
            is RingEvent.End -> {
                phase = Phase.DONE
                endReason = event.reason
            }
        }
    }

    private fun enterViolent(at: Long) {
        if (phase == Phase.GENTLE) violentSince = at
        phase = Phase.VIOLENT
        frozenLevel = null
    }

    /** Vibration level in the violent phase, including smart escalation. */
    private fun escalatedLevel(now: Long): Intensity {
        val base = maxOf(config.violentPreset.ordinal, config.baseIntensity.ordinal)
        if (!config.smartEscalation) return Intensity.entries[base]
        val steps = ((now - violentSince).coerceAtLeast(0) / config.escalationIntervalMs).toInt()
        return Intensity.entries[(base + steps).coerceAtMost(Intensity.MAXIMUM.ordinal)]
    }

    fun output(now: Long): RingOutput {
        tick(now)
        val master = config.masterVolume.coerceIn(0f, 1f)
        val violentSlot = if (config.secondarySound) SoundSlot.SECONDARY else SoundSlot.PRIMARY
        fun vib(level: Intensity?) = if (config.vibrationEnabled) level else null
        return when (phase) {
            Phase.GENTLE -> {
                val elapsed = now - startedAt
                val silent = elapsed < config.silentLeadMs
                val fadeMs = config.gentleMs - config.silentLeadMs
                val progress = if (fadeMs <= 0) 1f else (elapsed - config.silentLeadMs).toFloat() / fadeMs
                RingOutput(
                    phase,
                    if (silent) 0f else WakeCurve.gentle(progress, config.gentleStart, config.gentleMax) * master,
                    if (silent) SoundSlot.NONE else SoundSlot.PRIMARY,
                    vib(if (config.gentleVibration == GentleVibration.SOFT) config.gentleVibrationIntensity else null),
                    canMute,
                )
            }
            Phase.VIOLENT -> RingOutput(phase, master, violentSlot, vib(escalatedLevel(now)), canMute)
            Phase.SOLVING -> when (config.solvingMode) {
                SolvingMode.VIBRATE_ONLY ->
                    RingOutput(phase, 0f, SoundSlot.NONE, vib(frozenLevel ?: escalatedLevel(now)), canMute)
                SolvingMode.LOW_SOUND ->
                    RingOutput(phase, config.lowSoundLevel * master, violentSlot, vib(frozenLevel ?: escalatedLevel(now)), canMute)
                SolvingMode.NO_CHANGE ->
                    RingOutput(phase, master, violentSlot, vib(escalatedLevel(now)), canMute)
            }
            Phase.MUTED, Phase.DONE -> RingOutput(phase, 0f, SoundSlot.NONE, null, canMute)
        }
    }
}
