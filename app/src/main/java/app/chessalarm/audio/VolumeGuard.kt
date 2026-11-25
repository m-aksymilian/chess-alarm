package app.chessalarm.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Owns the ALARM stream while ringing: forces it to the level we want (so the volume buttons can't
 * turn the alarm down, even with the screen off), lifts Do Not Disturb if it would silence alarms
 * and we have policy access, and restores everything afterwards.
 */
class VolumeGuard(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var originalVolume = -1
    private var originalFilter = -1
    private var lastIndex = -1

    val maxIndex: Int get() = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    fun begin() {
        if (originalVolume < 0) originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        liftDndIfNeeded()
    }

    /** Sets the stream to [fraction] of max (for Spotify, whose own volume we can't control). */
    fun enforceFraction(fraction: Float) {
        val idx = if (fraction <= 0f) 0 else (fraction * maxIndex).toInt().coerceIn(1, maxIndex)
        enforceIndex(idx)
    }

    /** Pins the stream at max (local players do the fine volume control themselves). */
    fun enforceMax() = enforceIndex(maxIndex)

    private fun enforceIndex(idx: Int) {
        try {
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) != idx || lastIndex != idx) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, idx, 0)
                lastIndex = idx
            }
        } catch (e: SecurityException) {
            Log.w("VolumeGuard", "Can't set alarm volume: ${e.message}")
        }
    }

    private fun liftDndIfNeeded() {
        if (!nm.isNotificationPolicyAccessGranted) return
        val filter = nm.currentInterruptionFilter
        val blocksAlarms = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> true
            // Before API 28, priority mode always let alarms through.
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> Build.VERSION.SDK_INT >= 28 &&
                nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS == 0
            else -> false
        }
        if (blocksAlarms) {
            originalFilter = filter
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS) }
        }
    }

    fun end() {
        if (originalVolume >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0) }
        @Suppress("WrongConstant")
        if (originalFilter >= 0 && nm.isNotificationPolicyAccessGranted) runCatching { nm.setInterruptionFilter(originalFilter) }
        originalVolume = -1
        originalFilter = -1
        lastIndex = -1
    }
}
