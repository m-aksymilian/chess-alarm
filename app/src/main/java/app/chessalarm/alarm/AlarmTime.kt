package app.chessalarm.alarm

import app.chessalarm.data.Alarm
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/** Pure time arithmetic for alarms. */
object AlarmTime {

    /** The next time [alarm] should ring strictly after [now], or null if it's disabled. */
    fun nextTrigger(alarm: Alarm, now: ZonedDateTime): ZonedDateTime? {
        if (!alarm.enabled) return null
        val time = LocalTime.of(alarm.hour, alarm.minute)
        for (offset in 0L..7L) {
            val date = now.toLocalDate().plusDays(offset)
            if (alarm.days.isNotEmpty() && date.dayOfWeek.value !in alarm.days) continue
            // ZonedDateTime.of moves times that fall into a DST gap forward, so the alarm still rings.
            val candidate = ZonedDateTime.of(date, time, now.zone)
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }

    /** The soonest upcoming alarm. */
    fun nextOf(alarms: List<Alarm>, now: ZonedDateTime): Pair<Alarm, ZonedDateTime>? =
        alarms.mapNotNull { a -> nextTrigger(a, now)?.let { a to it } }.minByOrNull { it.second }

    /** "7 h 12 min", "12 min", "less than a minute". */
    fun formatUntil(now: ZonedDateTime, then: ZonedDateTime): String {
        val totalMin = Duration.between(now, then).toMinutes().let { if (it < 0) 0 else it }
        // Round up partial minutes so 7:59:30 away reads "8 h".
        val secs = Duration.between(now, then).seconds
        val mins = if (secs % 60 > 0) totalMin + 1 else totalMin
        val d = mins / (24 * 60)
        val h = (mins % (24 * 60)) / 60
        val m = mins % 60
        return when {
            mins <= 0 -> "less than a minute"
            d > 0 -> "$d d $h h"
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            else -> "$m min"
        }
    }

    fun repeatSummary(days: Set<Int>): String {
        if (days.isEmpty()) return "Once"
        if (days.size == 7) return "Every day"
        if (days == setOf(1, 2, 3, 4, 5)) return "Weekdays"
        if (days == setOf(6, 7)) return "Weekends"
        return days.sorted().joinToString(" ") {
            DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        }
    }

    /** True if [alarm] rings within [windowMs] of [now] (used for the "changing within 10 min" lock). */
    fun isWithin(alarm: Alarm, now: ZonedDateTime, windowMs: Long): Boolean {
        val next = nextTrigger(alarm, now) ?: return false
        return Duration.between(now, next).toMillis() <= windowMs
    }
}
