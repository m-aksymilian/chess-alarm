package app.chessalarm.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import app.chessalarm.data.BuiltInTunes
import app.chessalarm.data.SoundChoice
import app.chessalarm.data.SoundType
import java.io.File

/**
 * Loops a local sound (built-in tune, system ringtone or imported file) on the ALARM stream,
 * so it plays at alarm volume in silent mode and through Do Not Disturb (alarms category).
 * If a source can't be opened, it falls back to a built-in tune, so there is always sound.
 */
class AlarmSoundPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var currentKey: String? = null
    private var volume = -1f

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** Starts [choice] (no-op if it's already the current sound). Spotify choices are not handled here. */
    fun play(choice: SoundChoice, startVolume: Float) {
        val key = "${choice.type}:${choice.value}"
        if (key == currentKey && player != null) {
            setVolume(startVolume)
            if (!isPlaying) runCatching { player?.start() }
            return
        }
        stop()
        val mp = create(choice) ?: create(BuiltInTunes.default(harsh = false)) ?: return
        player = mp
        currentKey = key
        volume = -1f
        setVolume(startVolume)
        mp.start()
    }

    private fun create(choice: SoundChoice): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            when (choice.type) {
                SoundType.BUILT_IN -> {
                    val res = context.resources.getIdentifier(choice.value, "raw", context.packageName)
                    require(res != 0) { "Unknown tune ${choice.value}" }
                    context.resources.openRawResourceFd(res).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                }
                SoundType.SYSTEM -> mp.setDataSource(context, Uri.parse(choice.value))
                SoundType.FILE -> {
                    require(File(choice.value).exists()) { "Missing file ${choice.value}" }
                    mp.setDataSource(choice.value)
                }
                SoundType.SPOTIFY -> throw IllegalArgumentException("Spotify is played by SpotifyPlayer")
            }
            mp.isLooping = true
            mp.prepare()
            mp
        } catch (e: Exception) {
            Log.w("AlarmSoundPlayer", "Can't play ${choice.title}: ${e.message}")
            mp.release()
            null
        }
    }

    fun setVolume(v: Float) {
        val clamped = v.coerceIn(0f, 1f)
        if (kotlin.math.abs(clamped - volume) < 0.002f) return
        volume = clamped
        runCatching { player?.setVolume(clamped, clamped) }
    }

    fun pause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
    }

    fun stop() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        currentKey = null
    }
}

/** One-shot previews in the UI (built-in tunes, ringtones, files) on the alarm stream. */
object SoundPreview {
    private var player: AlarmSoundPlayer? = null
    private var stopAt = 0L

    fun play(context: Context, choice: SoundChoice, volume: Float = 0.6f) {
        stop()
        if (choice.type == SoundType.SPOTIFY) return
        player = AlarmSoundPlayer(context.applicationContext).also { it.play(choice, volume) }
        stopAt = System.currentTimeMillis() + 8000
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (System.currentTimeMillis() >= stopAt) stop()
        }, 8000)
    }

    fun stop() {
        player?.stop()
        player = null
    }
}
