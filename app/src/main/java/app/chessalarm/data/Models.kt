package app.chessalarm.data

import kotlinx.serialization.Serializable

@Serializable
enum class SoundType { BUILT_IN, SYSTEM, FILE, SPOTIFY }

/**
 * A sound source. [value] is: a raw resource name (BUILT_IN), a content URI (SYSTEM),
 * an absolute path in app storage (FILE), or a Spotify URI (SPOTIFY).
 */
@Serializable
data class SoundChoice(
    val type: SoundType,
    val value: String,
    val title: String,
    val subtitle: String = "",
    val imageUrl: String? = null,
    val shuffle: Boolean = false,
)

@Serializable
enum class VibrationPattern(val label: String) {
    PULSE("Pulse"), HEARTBEAT("Heartbeat"), CONTINUOUS("Continuous"),
    RAPID_BURST("Rapid burst"), ESCALATING("Escalating"), SOS("SOS")
}

@Serializable
enum class Intensity(val label: String) {
    GENTLE("Gentle"), NORMAL("Normal"), STRONG("Strong"), VIOLENT("Violent"), MAXIMUM("Maximum")
}

@Serializable
enum class SolvingMode(val label: String) {
    VIBRATE_ONLY("Vibration only, no sound"),
    LOW_SOUND("Low sound, vibration continues"),
    NO_CHANGE("No change until first correct move"),
}

@Serializable
enum class GentleVibration(val label: String) { OFF("Off"), SOFT("Very soft") }

/** The wake sequence and vibration configuration. Global defaults live in [Settings.wake]. */
@Serializable
data class WakeConfig(
    val fadeInEnabled: Boolean = true,
    val gentleDurationSec: Int = 60,
    val gentleStartPercent: Int = 5,
    val gentleMaxPercent: Int = 50,
    val gentleVibration: GentleVibration = GentleVibration.OFF,
    val violentPreset: Intensity = Intensity.VIOLENT,
    val secondarySoundEnabled: Boolean = false,
    val secondarySound: SoundChoice = BuiltInTunes.default(harsh = true),
    val solvingMode: SolvingMode = SolvingMode.VIBRATE_ONLY,
    val inactivitySec: Int = 20,
    val vibrationEnabled: Boolean = true,
    val vibrationPattern: VibrationPattern = VibrationPattern.PULSE,
    val intensity: Intensity = Intensity.NORMAL,
    val smartEscalation: Boolean = true,
    val volumePercent: Int = 100,
)

/** Per-alarm overrides; null means "use the global default". */
@Serializable
data class WakeOverrides(
    val fadeInEnabled: Boolean? = null,
    val gentleDurationSec: Int? = null,
    val gentleStartPercent: Int? = null,
    val gentleMaxPercent: Int? = null,
    val gentleVibration: GentleVibration? = null,
    val violentPreset: Intensity? = null,
    val secondarySoundEnabled: Boolean? = null,
    val secondarySound: SoundChoice? = null,
    val solvingMode: SolvingMode? = null,
    val inactivitySec: Int? = null,
    val vibrationEnabled: Boolean? = null,
    val vibrationPattern: VibrationPattern? = null,
    val intensity: Intensity? = null,
    val smartEscalation: Boolean? = null,
    val volumePercent: Int? = null,
) {
    fun applyTo(base: WakeConfig) = WakeConfig(
        fadeInEnabled = fadeInEnabled ?: base.fadeInEnabled,
        gentleDurationSec = gentleDurationSec ?: base.gentleDurationSec,
        gentleStartPercent = gentleStartPercent ?: base.gentleStartPercent,
        gentleMaxPercent = gentleMaxPercent ?: base.gentleMaxPercent,
        gentleVibration = gentleVibration ?: base.gentleVibration,
        violentPreset = violentPreset ?: base.violentPreset,
        secondarySoundEnabled = secondarySoundEnabled ?: base.secondarySoundEnabled,
        secondarySound = secondarySound ?: base.secondarySound,
        solvingMode = solvingMode ?: base.solvingMode,
        inactivitySec = inactivitySec ?: base.inactivitySec,
        vibrationEnabled = vibrationEnabled ?: base.vibrationEnabled,
        vibrationPattern = vibrationPattern ?: base.vibrationPattern,
        intensity = intensity ?: base.intensity,
        smartEscalation = smartEscalation ?: base.smartEscalation,
        volumePercent = volumePercent ?: base.volumePercent,
    )
}

@Serializable
data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = true,
    /** ISO day-of-week numbers (1 = Monday ... 7 = Sunday). Empty = one-time alarm. */
    val days: Set<Int> = emptySet(),
    /** Null = use the default sound from Settings. */
    val sound: SoundChoice? = null,
    val wake: WakeOverrides = WakeOverrides(),
    val stillAwakeCheck: Boolean? = null,
    val adaptiveDifficulty: Boolean? = null,
)

@Serializable
data class Settings(
    val onboardingDone: Boolean = false,
    val defaultSound: SoundChoice = BuiltInTunes.default(harsh = false),
    val wake: WakeConfig = WakeConfig(),
    val minRating: Int = 1200,
    val maxRating: Int = 1900,
    // Extra features (all toggles)
    val adaptiveDifficulty: Boolean = true,
    val stillAwakeCheck: Boolean = false,
    val statsEnabled: Boolean = true,
    val bedtimeReminder: Boolean = false,
    val bedtimeHoursBefore: Int = 8,
    val emergencyExitEnabled: Boolean = true,
    val emergencyCode: String = DEFAULT_EMERGENCY_CODE,
    val widgetEnabled: Boolean = true,
    val preventEscape: Boolean = true,
    val spotifyClientId: String = "",
) {
    companion object {
        const val DEFAULT_EMERGENCY_CODE = "I am awake and this is a real emergency"
    }
}

@Serializable
data class EscapeEvent(val atMs: Long, val reason: String)

@Serializable
data class Stats(
    val solved: Int = 0,
    val totalSolveMs: Long = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    /** Epoch day of the last counted wake-up. */
    val lastWakeEpochDay: Long = -1,
    val emergencyExits: List<Long> = emptyList(),
    val escapes: List<EscapeEvent> = emptyList(),
    /** Escapes not yet warned about at bedtime. */
    val pendingEscapeWarning: EscapeEvent? = null,
) {
    val averageSolveMs: Long get() = if (solved == 0) 0 else totalSolveMs / solved
}

@Serializable
data class AppData(
    val alarms: List<Alarm> = emptyList(),
    val settings: Settings = Settings(),
    val stats: Stats = Stats(),
    val adaptiveTarget: Int? = null,
    val recentPuzzles: List<String> = emptyList(),
    val spotifyToken: String? = null,
    val spotifyTokenExpiresAt: Long = 0,
    val spotifyUser: String? = null,
)

/** Why the ringing screen was shown. */
@Serializable
enum class RingKind { ALARM, STILL_AWAKE, TEST_ALARM, TEST_SEQUENCE }

/** The currently ringing alarm, persisted so a reboot or process death resumes ringing. */
@Serializable
data class ActiveRing(
    val alarmId: Long,
    val kind: RingKind,
    val startedAtMs: Long,
    val puzzleId: String? = null,
    /** Set once the ring service has run for this session in the current boot. */
    val bootCount: Int = -1,
    val label: String = "",
)

object BuiltInTunes {
    data class Tune(val res: String, val title: String, val mood: String)

    val all = listOf(
        Tune("tune_dawn", "Dawn", "Gentle"),
        Tune("tune_meadow", "Meadow", "Gentle"),
        Tune("tune_lumen", "Lumen", "Gentle"),
        Tune("tune_tidal", "Tidal", "Medium"),
        Tune("tune_pulse", "Pulse", "Medium"),
        Tune("tune_ascent", "Ascent", "Harsh"),
        Tune("tune_klaxon", "Klaxon", "Harsh"),
        Tune("tune_siren", "Siren", "Harsh"),
    )

    fun choice(t: Tune) = SoundChoice(SoundType.BUILT_IN, t.res, t.title, t.mood)

    fun default(harsh: Boolean) = choice(if (harsh) all.first { it.res == "tune_klaxon" } else all.first())
}
