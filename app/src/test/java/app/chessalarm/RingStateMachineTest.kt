package app.chessalarm

import app.chessalarm.data.GentleVibration
import app.chessalarm.data.Intensity
import app.chessalarm.data.SolvingMode
import app.chessalarm.data.StillAwakeConfig
import app.chessalarm.data.WakeConfig
import app.chessalarm.ring.EndReason
import app.chessalarm.ring.Phase
import app.chessalarm.ring.RingConfig
import app.chessalarm.ring.RingEvent
import app.chessalarm.ring.RingStateMachine
import app.chessalarm.ring.SoundSlot
import app.chessalarm.ring.WakeCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingStateMachineTest {

    private fun machine(c: WakeConfig = WakeConfig(), resume: Boolean = false) =
        RingStateMachine(RingConfig.from(c), 0, resume)

    @Test fun gentlePhaseRisesSmoothlyFromStartToMax() {
        val m = machine()
        val start = m.output(0)
        assertEquals(Phase.GENTLE, start.phase)
        assertEquals(0.05f, start.volume, 0.001f)
        assertNull("gentle vibration off by default", start.vibration)
        var prev = 0f
        for (t in 0L..59_000L step 1000) {
            val v = m.output(t).volume
            assertTrue("monotonic at $t", v >= prev)
            assertTrue("no jumps at $t", v - prev < 0.05f || t == 0L)
            prev = v
        }
        assertEquals(0.5f, m.output(59_999).volume, 0.01f)
    }

    @Test fun violentPhaseAfterGentle() {
        val m = machine()
        val o = m.output(60_000)
        assertEquals(Phase.VIOLENT, o.phase)
        assertEquals(1f, o.volume, 0f)
        assertEquals(Intensity.VIOLENT, o.vibration)
        assertEquals(SoundSlot.PRIMARY, o.sound)
    }

    @Test fun secondarySoundTakesOverInViolentPhase() {
        val m = machine(WakeConfig(secondarySoundEnabled = true))
        assertEquals(SoundSlot.PRIMARY, m.output(1000).sound)
        assertEquals(SoundSlot.SECONDARY, m.output(61_000).sound)
    }

    @Test fun smartEscalationReachesMaximum() {
        val m = machine()
        assertEquals(Intensity.VIOLENT, m.output(60_000).vibration)
        assertEquals(Intensity.MAXIMUM, m.output(90_000).vibration)
        assertEquals(Intensity.MAXIMUM, m.output(600_000).vibration)
        val flat = machine(WakeConfig(smartEscalation = false))
        assertEquals(Intensity.VIOLENT, flat.output(600_000).vibration)
    }

    @Test fun fadeInOffStartsViolent() {
        val m = machine(WakeConfig(fadeInEnabled = false))
        assertEquals(Phase.VIOLENT, m.output(0).phase)
        assertEquals(1f, m.output(0).volume, 0f)
    }

    @Test fun touchingBoardEntersSolvingWithVibrationOnly() {
        val m = machine()
        m.onEvent(RingEvent.BoardTouched, 70_000)
        val o = m.output(71_000)
        assertEquals(Phase.SOLVING, o.phase)
        assertEquals(SoundSlot.NONE, o.sound)
        assertEquals(Intensity.VIOLENT, o.vibration)
    }

    @Test fun touchingBoardDuringGentlePhaseAlsoEntersSolving() {
        val m = machine()
        m.onEvent(RingEvent.BoardTouched, 5_000)
        assertEquals(Phase.SOLVING, m.output(6_000).phase)
    }

    @Test fun lowSoundSolvingMode() {
        val m = machine(WakeConfig(solvingMode = SolvingMode.LOW_SOUND))
        m.onEvent(RingEvent.BoardTouched, 70_000)
        val o = m.output(71_000)
        assertEquals(SoundSlot.PRIMARY, o.sound)
        assertEquals(0.15f, o.volume, 0.001f)
        assertTrue(o.vibration != null)
    }

    @Test fun noChangeSolvingModeKeepsFullBlastUntilMute() {
        val m = machine(WakeConfig(solvingMode = SolvingMode.NO_CHANGE))
        m.onEvent(RingEvent.BoardTouched, 70_000)
        assertEquals(1f, m.output(71_000).volume, 0f)
        m.onEvent(RingEvent.MuteRequested, 72_000)
        assertEquals("mute before a correct move is ignored", Phase.SOLVING, m.output(72_000).phase)
        m.onEvent(RingEvent.CorrectMove, 73_000)
        m.onEvent(RingEvent.MuteRequested, 74_000)
        val muted = m.output(74_500)
        assertEquals(Phase.MUTED, muted.phase)
        assertEquals(SoundSlot.NONE, muted.sound)
        assertNull(muted.vibration)
    }

    @Test fun inactivityGuardReturnsToViolent() {
        val m = machine()
        m.onEvent(RingEvent.BoardTouched, 70_000)
        m.onEvent(RingEvent.Interaction, 80_000)
        assertEquals(Phase.SOLVING, m.output(99_000).phase)
        assertEquals(Phase.VIOLENT, m.output(100_000).phase)
        assertEquals(1f, m.output(100_000).volume, 0f)
    }

    @Test fun inactivityAlsoUnmutes() {
        val m = machine()
        m.onEvent(RingEvent.BoardTouched, 70_000)
        m.onEvent(RingEvent.CorrectMove, 71_000)
        m.onEvent(RingEvent.MuteRequested, 72_000)
        assertEquals(Phase.MUTED, m.output(80_000).phase)
        assertEquals(Phase.VIOLENT, m.output(92_000).phase)
    }

    @Test fun screenOffDuringSolvingIsInstantFullBlast() {
        val m = machine()
        m.onEvent(RingEvent.BoardTouched, 70_000)
        m.onEvent(RingEvent.ScreenOff, 71_000)
        val o = m.output(71_000)
        assertEquals(Phase.VIOLENT, o.phase)
        assertEquals(1f, o.volume, 0f)
    }

    @Test fun screenOffNeverStopsTheAlarm() {
        val m = machine()
        m.onEvent(RingEvent.ScreenOff, 1_000)
        assertEquals(Phase.GENTLE, m.output(1_000).phase)
        m.onEvent(RingEvent.ScreenOff, 61_000)
        assertEquals(Phase.VIOLENT, m.output(61_000).phase)
        assertTrue(!m.isDone)
    }

    @Test fun onlyEndEventsStopIt() {
        val m = machine()
        listOf(RingEvent.BoardTouched, RingEvent.Interaction, RingEvent.ScreenOff, RingEvent.CorrectMove, RingEvent.MuteRequested)
            .forEachIndexed { i, e -> m.onEvent(e, 1000L * i) }
        assertTrue(!m.isDone)
        m.onEvent(RingEvent.End(EndReason.SOLVED), 10_000)
        assertTrue(m.isDone)
        assertEquals(EndReason.SOLVED, m.endReason)
        val o = m.output(20_000)
        assertEquals(SoundSlot.NONE, o.sound)
        assertNull(o.vibration)
        // Events after done are ignored.
        m.onEvent(RingEvent.ScreenOff, 21_000)
        assertEquals(Phase.DONE, m.output(22_000).phase)
    }

    @Test fun resumeAfterRebootStartsViolent() {
        val m = machine(resume = true)
        assertEquals(Phase.VIOLENT, m.output(0).phase)
    }

    @Test fun vibrationDisabledMeansNoVibration() {
        val m = machine(WakeConfig(vibrationEnabled = false, gentleVibration = GentleVibration.SOFT))
        assertNull(m.output(0).vibration)
        assertNull(m.output(100_000).vibration)
    }

    @Test fun softGentleVibration() {
        val m = machine(WakeConfig(gentleVibration = GentleVibration.SOFT))
        assertEquals(Intensity.GENTLE, m.output(0).vibration)
    }

    @Test fun volumeSettingScalesEverything() {
        val m = machine(WakeConfig(volumePercent = 50))
        assertEquals(0.025f, m.output(0).volume, 0.001f)
        assertEquals(0.5f, m.output(60_000).volume, 0.001f)
    }

    @Test fun compressedTestSequence() {
        val m = RingStateMachine(RingConfig.from(WakeConfig(), timeScale = 0.1f), 0)
        assertEquals(Phase.GENTLE, m.output(5_000).phase)
        assertEquals(Phase.VIOLENT, m.output(6_000).phase)
        assertEquals(Intensity.MAXIMUM, m.output(9_000).vibration)
    }

    @Test fun stillAwakeStartsWithVibrationOnlyThenFadesIntoViolent() {
        val m = RingStateMachine(RingConfig.stillAwake(WakeConfig(vibrationEnabled = false), StillAwakeConfig()), 0)
        val start = m.output(0)
        assertEquals(Phase.GENTLE, start.phase)
        assertEquals(SoundSlot.NONE, start.sound)
        assertEquals(0f, start.volume, 0f)
        assertEquals("vibrates even if alarm vibration is off", Intensity.NORMAL, start.vibration)
        assertEquals(SoundSlot.NONE, m.output(14_900).sound)
        val fadeStart = m.output(15_000)
        assertEquals(SoundSlot.PRIMARY, fadeStart.sound)
        assertEquals(0.02f, fadeStart.volume, 0.001f)
        var prev = 0f
        for (t in 15_000L..74_900L step 100) {
            val v = m.output(t).volume
            assertTrue("rises at $t", v >= prev)
            prev = v
        }
        assertTrue("reaches full volume smoothly", 1f - prev < 0.01f)
        val violent = m.output(75_000)
        assertEquals(Phase.VIOLENT, violent.phase)
        assertEquals(1f, violent.volume, 0f)
    }

    @Test fun stillAwakeIsConfigurable() {
        val sa = StillAwakeConfig(vibrationOnlySec = 0, fadeSec = 0, vibrationIntensity = Intensity.STRONG, smartEscalation = false,
            solvingMode = SolvingMode.LOW_SOUND)
        val m = RingStateMachine(RingConfig.stillAwake(WakeConfig(), sa), 0)
        val o = m.output(0)
        assertEquals("no lead, no fade: straight to violent", Phase.VIOLENT, o.phase)
        assertEquals(Intensity.VIOLENT, o.vibration)
        assertEquals(Intensity.VIOLENT, m.output(600_000).vibration)
        m.onEvent(RingEvent.BoardTouched, 1_000)
        assertEquals(0.15f, m.output(1_500).volume, 0.001f)

        val long = RingStateMachine(RingConfig.stillAwake(WakeConfig(), StillAwakeConfig(vibrationOnlySec = 30, fadeSec = 120, startVolumePercent = 10)), 0)
        assertEquals(SoundSlot.NONE, long.output(29_000).sound)
        assertEquals(0.1f, long.output(30_000).volume, 0.001f)
        assertEquals(Phase.GENTLE, long.output(149_000).phase)
        assertEquals(Phase.VIOLENT, long.output(150_000).phase)
    }

    @Test fun curveIsExponential() {
        assertEquals(0.05f, WakeCurve.gentle(0f, 0.05f, 0.5f), 1e-4f)
        assertEquals(0.5f, WakeCurve.gentle(1f, 0.05f, 0.5f), 1e-4f)
        // Midpoint is the geometric mean (linear in decibels).
        assertEquals(kotlin.math.sqrt(0.05f * 0.5f), WakeCurve.gentle(0.5f, 0.05f, 0.5f), 1e-4f)
    }
}
