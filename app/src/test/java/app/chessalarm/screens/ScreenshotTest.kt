package app.chessalarm.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.chessalarm.data.Alarm
import app.chessalarm.data.AppData
import app.chessalarm.data.RingKind
import app.chessalarm.data.Settings
import app.chessalarm.data.Stats
import app.chessalarm.data.WakeConfig
import app.chessalarm.puzzle.PuzzleParser
import app.chessalarm.puzzle.PuzzleSession
import app.chessalarm.ring.Phase
import app.chessalarm.ring.RingUi
import app.chessalarm.ring.RingPreview
import app.chessalarm.ui.AlarmEditorScreen
import app.chessalarm.ui.AlarmListScreen
import app.chessalarm.ui.ChessAlarmTheme
import app.chessalarm.ui.SettingsScreen
import app.chessalarm.ui.SpotifyStatus
import app.chessalarm.ui.StatsScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Renders key screens to PNGs (build/screenshots) for a visual check without a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { ChessAlarmTheme { Box(Modifier.fillMaxSize()) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private val alarms = listOf(
        Alarm(1, 6, 30, "Gym", true, setOf(1, 3, 5)),
        Alarm(2, 7, 15, "Work", true, setOf(1, 2, 3, 4, 5)),
        Alarm(3, 9, 0, "", false),
    )

    @Test fun alarmList() = shot("1_alarm_list") {
        AlarmListScreen(AppData(alarms = alarms), true, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }

    @Test fun editor() = shot("2_editor") {
        AlarmEditorScreen(alarms[1], false, Settings(), {}, {}, {}, {}, {}, {}, { _, _ -> })
    }

    @Test fun settings() = shot("3_settings") {
        SettingsScreen(Settings(), SpotifyStatus(false, "", "AB:CD"), true, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
    }

    @Test fun stats() = shot("5_stats") {
        StatsScreen(Stats(solved = 23, totalSolveMs = 23 * 41_000L, streak = 6, bestStreak = 11), true) {}
    }

    @Test fun ringing() {
        val text = listOf("src/main/assets/puzzles.json", "app/src/main/assets/puzzles.json").map(::File).first { it.exists() }.readText()
        val p = PuzzleParser.parse(text).first { it.theme == "mateIn2" && it.rating > 1500 }
        val ui = RingUi(RingKind.ALARM, "Work", PuzzleSession(p), 1, Phase.SOLVING, true, false, true, Settings.DEFAULT_EMERGENCY_CODE)
        shot("4_ringing") { RingPreview(ui) }
    }
}
