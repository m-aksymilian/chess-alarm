package app.chessalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.chessalarm.MainActivity
import app.chessalarm.data.AppStore
import app.chessalarm.data.RingKind
import app.chessalarm.widget.NextAlarmWidget
import java.time.ZonedDateTime

/**
 * Schedules alarms with AlarmManager.setAlarmClock: exact, shown in the status bar, and exempt from
 * Doze and app standby. Also schedules the helper alarms (still-awake check, bedtime reminder,
 * escape warning).
 */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"

    const val ACTION_FIRE = "app.chessalarm.action.FIRE"
    const val ACTION_STILL_AWAKE_PROMPT = "app.chessalarm.action.STILL_AWAKE_PROMPT"
    const val ACTION_STILL_AWAKE_TIMEOUT = "app.chessalarm.action.STILL_AWAKE_TIMEOUT"
    const val ACTION_IM_UP = "app.chessalarm.action.IM_UP"
    const val ACTION_BEDTIME = "app.chessalarm.action.BEDTIME"
    const val ACTION_ESCAPE_WARNING = "app.chessalarm.action.ESCAPE_WARNING"

    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_KIND = "kind"

    const val TEST_ALARM_ID = -1L

    private const val RC_STILL_AWAKE_PROMPT = 1_000_001
    private const val RC_STILL_AWAKE_TIMEOUT = 1_000_002
    private const val RC_BEDTIME = 1_000_003
    private const val RC_ESCAPE = 1_000_004
    private const val RC_TEST = 1_000_005

    private fun am(context: Context) = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(context).canScheduleExactAlarms()

    private fun firePendingIntent(context: Context, alarmId: Long, kind: RingKind, requestCode: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ALARM_ID, alarmId)
            .putExtra(EXTRA_KIND, kind.name)
        return PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun requestCodeFor(alarmId: Long) = (alarmId xor (alarmId ushr 32)).toInt() and 0x7FFFFFFF

    /** Reschedules every alarm from the store. Safe to call any time; idempotent. */
    fun rescheduleAll(context: Context) {
        AppStore.init(context)
        val data = AppStore.current
        val now = ZonedDateTime.now()
        for (alarm in data.alarms) {
            val pi = firePendingIntent(context, alarm.id, RingKind.ALARM, requestCodeFor(alarm.id))
            val next = AlarmTime.nextTrigger(alarm, now)
            if (next == null) {
                am(context).cancel(pi)
                continue
            }
            setAlarmClock(context, next.toInstant().toEpochMilli(), pi)
        }
        scheduleBedtime(context)
        NextAlarmWidget.refresh(context)
        Log.i(TAG, "Rescheduled ${data.alarms.size} alarms")
    }

    fun cancel(context: Context, alarmId: Long) {
        am(context).cancel(firePendingIntent(context, alarmId, RingKind.ALARM, requestCodeFor(alarmId)))
    }

    private fun setAlarmClock(context: Context, atMs: Long, pi: PendingIntent) {
        try {
            am(context).setAlarmClock(AlarmManager.AlarmClockInfo(atMs, showPendingIntent(context)), pi)
        } catch (e: SecurityException) {
            // Exact alarm permission missing: fall back to the best inexact-but-Doze-exempt option.
            Log.e(TAG, "No exact alarm permission", e)
            am(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    private fun setExact(context: Context, atMs: Long, pi: PendingIntent) {
        try {
            am(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (e: SecurityException) {
            am(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    /** "Test alarm in 10 seconds": a real alarm (same code path), not counted in stats. */
    fun scheduleTest(context: Context, delayMs: Long = 10_000) {
        val pi = firePendingIntent(context, TEST_ALARM_ID, RingKind.TEST_ALARM, RC_TEST)
        setAlarmClock(context, System.currentTimeMillis() + delayMs, pi)
    }

    // ------------------------------------------------------------------------- still-awake check

    private fun helperIntent(context: Context, action: String, rc: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, rc, Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun scheduleStillAwakePrompt(context: Context, delayMs: Long) {
        setExact(context, System.currentTimeMillis() + delayMs, helperIntent(context, ACTION_STILL_AWAKE_PROMPT, RC_STILL_AWAKE_PROMPT))
    }

    fun scheduleStillAwakeTimeout(context: Context, delayMs: Long) {
        // setAlarmClock so the re-ring is as reliable as a real alarm.
        setAlarmClock(context, System.currentTimeMillis() + delayMs, helperIntent(context, ACTION_STILL_AWAKE_TIMEOUT, RC_STILL_AWAKE_TIMEOUT))
    }

    fun cancelStillAwake(context: Context) {
        am(context).cancel(helperIntent(context, ACTION_STILL_AWAKE_PROMPT, RC_STILL_AWAKE_PROMPT))
        am(context).cancel(helperIntent(context, ACTION_STILL_AWAKE_TIMEOUT, RC_STILL_AWAKE_TIMEOUT))
    }

    // ------------------------------------------------------------------- bedtime + escape warning

    fun scheduleBedtime(context: Context) {
        val data = AppStore.current
        val bedtimePi = helperIntent(context, ACTION_BEDTIME, RC_BEDTIME)
        val now = ZonedDateTime.now()
        val next = AlarmTime.nextOf(data.alarms, now)
        if (data.settings.bedtimeReminder && next != null) {
            val at = next.second.minusHours(data.settings.bedtimeHoursBefore.toLong())
            if (at.isAfter(now)) setExact(context, at.toInstant().toEpochMilli(), bedtimePi) else am(context).cancel(bedtimePi)
        } else {
            am(context).cancel(bedtimePi)
        }

        val escapePi = helperIntent(context, ACTION_ESCAPE_WARNING, RC_ESCAPE)
        val pending = data.stats.pendingEscapeWarning
        if (data.settings.preventEscape && pending != null && !data.settings.bedtimeReminder) {
            setExact(context, escapeWarningTime(pending.atMs, now).toInstant().toEpochMilli(), escapePi)
        } else {
            am(context).cancel(escapePi)
        }
    }

    /** "Before bed the next night": 21:00, at least 6 hours after the escape. */
    fun escapeWarningTime(escapeMs: Long, now: ZonedDateTime): ZonedDateTime {
        val escape = java.time.Instant.ofEpochMilli(escapeMs).atZone(now.zone)
        var t = escape.withHour(21).withMinute(0).withSecond(0).withNano(0)
        while (t.isBefore(escape.plusHours(6))) t = t.plusDays(1)
        return if (t.isBefore(now)) now.plusMinutes(1) else t
    }
}
