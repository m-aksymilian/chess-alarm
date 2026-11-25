package app.chessalarm.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.chessalarm.data.Intensity
import app.chessalarm.data.VibrationPattern

/** Builds vibration waveforms for every pattern x intensity combination. Pure, so it's testable. */
object VibrationWaveforms {
    data class Waveform(val timings: LongArray, val amplitudes: IntArray)

    /** Peak amplitude (1..255) per intensity. */
    fun amplitude(i: Intensity) = when (i) {
        Intensity.GENTLE -> 50
        Intensity.NORMAL -> 120
        Intensity.STRONG -> 180
        Intensity.VIOLENT -> 230
        Intensity.MAXIMUM -> 255
    }

    /** Pulses get longer and gaps shorter as intensity rises; Maximum is near-continuous. */
    private fun onScale(i: Intensity) = when (i) {
        Intensity.GENTLE -> 0.6
        Intensity.NORMAL -> 1.0
        Intensity.STRONG -> 1.2
        Intensity.VIOLENT -> 1.5
        Intensity.MAXIMUM -> 2.2
    }

    private fun offScale(i: Intensity) = when (i) {
        Intensity.GENTLE -> 1.6
        Intensity.NORMAL -> 1.0
        Intensity.STRONG -> 0.8
        Intensity.VIOLENT -> 0.55
        Intensity.MAXIMUM -> 0.15
    }

    fun build(pattern: VibrationPattern, intensity: Intensity): Waveform {
        val a = amplitude(intensity)
        val on = onScale(intensity)
        val off = offScale(intensity)
        // (duration ms, relative amplitude 0..1) segments, starting with a pause.
        val segs: List<Pair<Long, Double>> = when (pattern) {
            VibrationPattern.PULSE -> listOf(400L to 0.0, 500L to 1.0)
            VibrationPattern.HEARTBEAT -> listOf(600L to 0.0, 120L to 1.0, 120L to 0.0, 200L to 0.8)
            VibrationPattern.CONTINUOUS -> listOf(0L to 0.0, 5000L to 1.0)
            VibrationPattern.RAPID_BURST -> listOf(300L to 0.0) + List(6) { listOf(70L to 1.0, 60L to 0.0) }.flatten()
            VibrationPattern.ESCALATING -> listOf(300L to 0.0, 150L to 0.3, 80L to 0.0, 250L to 0.6, 80L to 0.0, 400L to 1.0)
            VibrationPattern.SOS -> listOf(500L to 0.0) +
                List(3) { listOf(120L to 1.0, 100L to 0.0) }.flatten() +
                List(3) { listOf(380L to 1.0, 100L to 0.0) }.flatten() +
                List(3) { listOf(120L to 1.0, 100L to 0.0) }.flatten()
        }
        val timings = segs.map { (d, rel) ->
            val scaled = if (rel == 0.0) d * off else d * on
            scaled.toLong().coerceAtLeast(if (rel == 0.0) 0 else 20)
        }.toLongArray()
        val amps = segs.map { (_, rel) -> if (rel == 0.0) 0 else (a * rel).toInt().coerceIn(1, 255) }.toIntArray()
        return Waveform(timings, amps)
    }
}

/** Plays alarm vibration with alarm usage so it continues in silent mode and DND. */
class AlarmVibrator(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private var current: Pair<VibrationPattern, Intensity>? = null
    private var lastStart = 0L

    fun apply(pattern: VibrationPattern, intensity: Intensity?) {
        if (intensity == null) {
            if (current != null) stop()
            return
        }
        val key = pattern to intensity
        // Restart periodically too: some OEMs silently cancel long repeating vibrations.
        if (key == current && System.currentTimeMillis() - lastStart < 60_000) return
        current = key
        lastStart = System.currentTimeMillis()
        vibrate(VibrationWaveforms.build(pattern, intensity), repeat = true)
    }

    /** A short preview (e.g. in Settings). */
    fun preview(pattern: VibrationPattern, intensity: Intensity) {
        current = null
        val w = VibrationWaveforms.build(pattern, intensity)
        // Play the pattern a few times, without repeating forever.
        val times = LongArray(w.timings.size * 3) { w.timings[it % w.timings.size] }
        val amps = IntArray(w.amplitudes.size * 3) { w.amplitudes[it % w.amplitudes.size] }
        vibrate(VibrationWaveforms.Waveform(times, amps), repeat = false)
    }

    private fun vibrate(w: VibrationWaveforms.Waveform, repeat: Boolean) {
        val repeatIndex = if (repeat) 0 else -1
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(w.timings, w.amplitudes, repeatIndex)
        } else {
            // No amplitude control: weaker levels get shorter pulses instead.
            val t = w.timings.mapIndexed { i, d ->
                if (w.amplitudes[i] == 0) d else (d * (0.4 + 0.6 * w.amplitudes[i] / 255.0)).toLong()
            }.toLongArray()
            VibrationEffect.createWaveform(t, repeatIndex)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
        }
    }

    fun stop() {
        current = null
        vibrator.cancel()
    }
}
