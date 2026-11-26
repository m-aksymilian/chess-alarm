package app.chessalarm

import android.app.Application
import android.util.Log
import app.chessalarm.alarm.AlarmScheduler
import app.chessalarm.alarm.Notifications
import app.chessalarm.data.AppStore
import app.chessalarm.data.EscapeEvent
import app.chessalarm.data.RingKind
import app.chessalarm.ring.AlarmService

class ChessAlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        AppStore.init(this)
        Notifications.ensureChannels(this)
        recoverUnfinishedRing()
        runCatching { AlarmScheduler.rescheduleAll(this) }
    }

    /**
     * If the process starts while an alarm is still marked as ringing but the service isn't
     * running, the app was force-stopped or killed mid-alarm. Ring again and remember the escape.
     */
    private fun recoverUnfinishedRing() {
        val ring = AppStore.activeRing(this) ?: return
        if (AlarmService.isRunning) return
        if (ring.kind == RingKind.TEST_SEQUENCE || ring.kind == RingKind.TEST_STILL_AWAKE) {
            AppStore.setActiveRing(this, null)
            return
        }
        val sameBoot = ring.bootCount == AlarmService.bootCount(this)
        if (sameBoot && ring.kind == RingKind.ALARM && AppStore.current.settings.preventEscape) {
            val e = EscapeEvent(System.currentTimeMillis(), "the app was force-stopped while ringing")
            AppStore.update(this) { it.copy(stats = it.stats.copy(escapes = it.stats.escapes + e, pendingEscapeWarning = e)) }
        }
        Log.w("ChessAlarmApp", "Resuming unsolved alarm (sameBoot=$sameBoot)")
        AlarmService.start(this, ring.alarmId, ring.kind, resume = true)
    }
}
