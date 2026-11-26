package app.chessalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.chessalarm.data.AppStore
import app.chessalarm.data.RingKind
import app.chessalarm.ring.AlarmService
import java.time.ZonedDateTime

/** Receives every AlarmManager alarm the app sets. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppStore.init(context)
        Log.i("AlarmReceiver", "onReceive ${intent.action}")
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                val id = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, 0)
                val kind = runCatching { RingKind.valueOf(intent.getStringExtra(AlarmScheduler.EXTRA_KIND) ?: "") }
                    .getOrDefault(RingKind.ALARM)
                if (kind == RingKind.ALARM) {
                    // One-time alarms switch off once they fire; repeating ones move to their next day.
                    AppStore.update(context) { d ->
                        d.copy(alarms = d.alarms.map { if (it.id == id && it.days.isEmpty()) it.copy(enabled = false) else it })
                    }
                    if (AppStore.current.alarms.none { it.id == id }) {
                        Log.w("AlarmReceiver", "Alarm $id no longer exists")
                        AlarmScheduler.rescheduleAll(context)
                        return
                    }
                }
                AlarmService.start(context, id, kind)
                AlarmScheduler.rescheduleAll(context)
            }
            AlarmScheduler.ACTION_STILL_AWAKE_PROMPT -> {
                val sa = AppStore.current.settings.stillAwake
                AppStore.update(context) { it.copy(stillAwakeRemaining = (it.stillAwakeRemaining - 1).coerceAtLeast(0)) }
                Notifications.showStillAwake(context, sa.responseSec)
                AlarmScheduler.scheduleStillAwakeTimeout(context, sa.responseSec * 1000L)
            }
            AlarmScheduler.ACTION_IM_UP -> {
                AlarmScheduler.cancelStillAwake(context)
                Notifications.cancel(context, Notifications.ID_STILL_AWAKE)
                if (AppStore.current.stillAwakeRemaining > 0) {
                    AlarmScheduler.scheduleStillAwakePrompt(context, AppStore.current.settings.stillAwake.delayMin * 60_000L)
                }
            }
            AlarmScheduler.ACTION_STILL_AWAKE_TIMEOUT -> {
                Notifications.cancel(context, Notifications.ID_STILL_AWAKE)
                AlarmService.start(context, AlarmScheduler.TEST_ALARM_ID - 1, RingKind.STILL_AWAKE)
            }
            AlarmScheduler.ACTION_BEDTIME -> {
                val data = AppStore.current
                val next = AlarmTime.nextOf(data.alarms, ZonedDateTime.now())
                val warning = escapeWarningText(context)
                if (next != null) {
                    val text = "Alarm in ${AlarmTime.formatUntil(ZonedDateTime.now(), next.second)}" +
                        (if (warning != null) "\n\n$warning" else "")
                    Notifications.showReminder(context, Notifications.ID_BEDTIME, "Time for bed", text)
                }
                AlarmScheduler.scheduleBedtime(context)
            }
            AlarmScheduler.ACTION_ESCAPE_WARNING -> {
                escapeWarningText(context)?.let {
                    Notifications.showReminder(context, Notifications.ID_ESCAPE, "You escaped last night’s alarm", it)
                }
                AlarmScheduler.scheduleBedtime(context)
            }
        }
    }

    /** Returns (and clears) the pending escape warning text, if any. */
    private fun escapeWarningText(context: Context): String? {
        val pending = AppStore.current.stats.pendingEscapeWarning ?: return null
        if (!AppStore.current.settings.preventEscape) return null
        AppStore.update(context) { it.copy(stats = it.stats.copy(pendingEscapeWarning = null)) }
        return "Last time the alarm rang, it was stopped without solving the puzzle (${pending.reason}). " +
            "Leave Chess Alarm running tonight — the puzzle is the only way out."
    }
}

/** Reschedules after reboot / update / time changes, and resumes an unsolved alarm after boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppStore.init(context)
        Log.i("BootReceiver", "onReceive ${intent.action}")
        AlarmScheduler.rescheduleAll(context)
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val ring = AppStore.activeRing(context)
                if (ring != null && !AlarmService.isRunning) {
                    // The phone was shut down (or the app updated) while an alarm was unsolved: ring again now.
                    AlarmService.start(context, ring.alarmId, ring.kind, resume = true)
                }
            }
        }
    }
}
