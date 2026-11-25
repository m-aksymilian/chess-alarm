package app.chessalarm

import app.chessalarm.alarm.AlarmTime
import app.chessalarm.data.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmTimeTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)

    @Test fun oneTimeAlarmLaterToday() {
        val now = at(2026, 11, 25, 6, 0) // Wednesday
        assertEquals(at(2026, 11, 25, 7, 30), AlarmTime.nextTrigger(Alarm(1, 7, 30), now))
    }

    @Test fun oneTimeAlarmTomorrowIfPassed() {
        val now = at(2026, 11, 25, 8, 0)
        assertEquals(at(2026, 11, 26, 7, 30), AlarmTime.nextTrigger(Alarm(1, 7, 30), now))
    }

    @Test fun exactlyNowMeansNextOccurrence() {
        val now = at(2026, 11, 25, 7, 30)
        assertEquals(at(2026, 11, 26, 7, 30), AlarmTime.nextTrigger(Alarm(1, 7, 30), now))
    }

    @Test fun repeatingSkipsToMatchingDay() {
        val now = at(2026, 11, 27, 8, 0) // Friday after the alarm
        val weekdays = Alarm(1, 7, 0, days = setOf(1, 2, 3, 4, 5))
        assertEquals(at(2026, 11, 30, 7, 0), AlarmTime.nextTrigger(weekdays, now)) // Monday
    }

    @Test fun repeatingSameDayNextWeek() {
        val now = at(2026, 11, 25, 8, 0) // Wednesday
        val wed = Alarm(1, 7, 0, days = setOf(3))
        assertEquals(at(2026, 12, 2, 7, 0), AlarmTime.nextTrigger(wed, now))
    }

    @Test fun disabledHasNoTrigger() {
        assertNull(AlarmTime.nextTrigger(Alarm(1, 7, 0, enabled = false), at(2026, 1, 1, 0, 0)))
    }

    @Test fun dstGapStillRings() {
        // Europe/Warsaw skips 02:00-03:00 on 2026-03-29.
        val now = at(2026, 3, 28, 23, 0)
        val next = AlarmTime.nextTrigger(Alarm(1, 2, 30), now)!!
        assertEquals(29, next.dayOfMonth)
        assertEquals(3, next.hour)
    }

    @Test fun nextOfPicksSoonest() {
        val now = at(2026, 11, 25, 6, 0)
        val (a, _) = AlarmTime.nextOf(listOf(Alarm(1, 9, 0), Alarm(2, 6, 30), Alarm(3, 5, 0)), now)!!
        assertEquals(2L, a.id)
    }

    @Test fun formatting() {
        val now = at(2026, 11, 25, 0, 0)
        assertEquals("7 h 12 min", AlarmTime.formatUntil(now, now.plusHours(7).plusMinutes(12)))
        assertEquals("8 h", AlarmTime.formatUntil(now, now.plusHours(8)))
        assertEquals("5 min", AlarmTime.formatUntil(now, now.plusMinutes(5)))
        assertEquals("1 min", AlarmTime.formatUntil(now, now.plusSeconds(20)))
        assertEquals("Weekdays", AlarmTime.repeatSummary(setOf(1, 2, 3, 4, 5)))
    }

    @Test fun tenMinuteLockWindow() {
        val now = at(2026, 11, 25, 6, 52)
        assertTrue(AlarmTime.isWithin(Alarm(1, 7, 0), now, 10 * 60_000))
        assertTrue(!AlarmTime.isWithin(Alarm(1, 7, 5), now, 10 * 60_000))
    }
}
