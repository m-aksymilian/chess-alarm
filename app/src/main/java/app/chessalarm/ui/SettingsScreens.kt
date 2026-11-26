package app.chessalarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chessalarm.Permissions
import app.chessalarm.data.Intensity
import app.chessalarm.data.Settings
import app.chessalarm.data.SolvingMode
import app.chessalarm.data.Stats
import app.chessalarm.data.StillAwakeConfig
import app.chessalarm.data.VibrationPattern
import app.chessalarm.data.WakeConfig
import app.chessalarm.data.WakeOverrides
import app.chessalarm.ring.WakeCurve
import app.chessalarm.spotify.SpotifyConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SpotifyStatus(val loggedIn: Boolean, val clientId: String, val sha1: String)

@Composable
fun SettingsScreen(
    settings: Settings,
    spotify: SpotifyStatus,
    permissionsOk: Boolean,
    onChange: (Settings) -> Unit,
    onBack: () -> Unit,
    onPickDefaultSound: () -> Unit,
    onPickSecondSound: () -> Unit,
    onPreviewVibration: (VibrationPattern, Intensity) -> Unit,
    onTestAlarm: () -> Unit,
    onTestSequence: () -> Unit,
    onPickStillAwakeSound: () -> Unit,
    onTestStillAwake: () -> Unit,
    onPermissions: () -> Unit,
    onSpotifyLogin: () -> Unit,
    onSpotifyLogout: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val w = settings.wake
    fun wake(f: (WakeOverrides) -> WakeOverrides) = onChange(settings.copy(wake = f(WakeOverrides()).applyTo(w)))
    fun wakeSet(c: WakeConfig) = onChange(settings.copy(wake = c))

    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding().imePadding()) {
        NavBar("Settings", "Back", onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Settings", style = Type.LargeTitle, modifier = Modifier.padding(start = 16.dp, top = 4.dp))

            Group {
                GroupRow("Permissions", value = if (permissionsOk) "All set" else "Action needed", icon = Icons.Shield,
                    chevron = true, divider = true, onClick = onPermissions)
                GroupRow("Test alarm in 10 seconds", icon = Icons.Bell, divider = true, onClick = onTestAlarm,
                    subtitle = "A real alarm: lock your phone to try it")
                GroupRow("Test the wake sequence", icon = Icons.Play, divider = false, onClick = onTestSequence,
                    subtitle = "Runs the whole sequence quickly")
            }

            Group("Sound") {
                GroupRow("Default sound", value = settings.defaultSound.title, chevron = true, onClick = onPickDefaultSound)
                SliderRow("Volume", w.volumePercent.toFloat(), 10f..100f, "${w.volumePercent}%", divider = false) {
                    wakeSet(w.copy(volumePercent = it.toInt()))
                }
            }

            Group("Wake sequence", footer = "Starts barely audible, rises on a smooth curve, then turns violent if you're still not up.") {
                CurvePreview(w)
                GroupDivider()
                SwitchRow("Fade in gently", w.fadeInEnabled, "Off: start at full volume") { wakeSet(w.copy(fadeInEnabled = it)) }
                if (w.fadeInEnabled) WakeSequenceControls(w, ::wake, onPickSecondSound)
                else {
                    ChoiceRow("While solving", SolvingMode.entries, w.solvingMode, { it.label }) { wakeSet(w.copy(solvingMode = it)) }
                    SliderRow("Inactivity guard", w.inactivitySec.toFloat(), 5f..60f, "${w.inactivitySec} s") { wakeSet(w.copy(inactivitySec = it.toInt())) }
                }
                GroupRow("Power button while solving", subtitle = "Turning the screen off while solving goes straight back to full blast.", divider = false)
            }

            Group("Vibration") {
                SwitchRow("Vibration", w.vibrationEnabled) { wakeSet(w.copy(vibrationEnabled = it)) }
                ChoiceRow("Pattern", VibrationPattern.entries, w.vibrationPattern, { it.label }) {
                    wakeSet(w.copy(vibrationPattern = it)); onPreviewVibration(it, w.intensity)
                }
                ChoiceRow("Intensity", Intensity.entries, w.intensity, { it.label }) {
                    wakeSet(w.copy(intensity = it)); onPreviewVibration(w.vibrationPattern, it)
                }
                SwitchRow("Smart escalation", w.smartEscalation, "Gets stronger every 30 s until Maximum") { wakeSet(w.copy(smartEscalation = it)) }
                GroupRow("Preview vibration", icon = Icons.Play, divider = false, onClick = { onPreviewVibration(w.vibrationPattern, w.intensity) })
            }

            Group("Puzzles", footer = "1000 short Lichess puzzles (mate in 1, mate in 2 and two-move tactics).") {
                RatingRange(settings.minRating, settings.maxRating) { lo, hi -> onChange(settings.copy(minRating = lo, maxRating = hi)) }
                SwitchRow("Adaptive difficulty", settings.adaptiveDifficulty, "Harder after quick solves, easier after slow ones", divider = false) {
                    onChange(settings.copy(adaptiveDifficulty = it))
                }
            }

            StillAwakeSection(settings, onChange, onPickStillAwakeSound, onTestStillAwake)

            Group("Extras") {
                SwitchRow("Wake-up stats", settings.statsEnabled) { onChange(settings.copy(statsEnabled = it)) }
                SwitchRow("Bedtime reminder", settings.bedtimeReminder, divider = true) { onChange(settings.copy(bedtimeReminder = it)) }
                if (settings.bedtimeReminder) {
                    SliderRow("Remind me", settings.bedtimeHoursBefore.toFloat(), 4f..12f, "${settings.bedtimeHoursBefore} h before", steps = 7) {
                        onChange(settings.copy(bedtimeHoursBefore = it.toInt()))
                    }
                }
                SwitchRow("Home screen widget", settings.widgetEnabled) { onChange(settings.copy(widgetEnabled = it)) }
                SwitchRow("Prevent escape", settings.preventEscape,
                    "Warns at bedtime after a force-stop, and puzzle-locks changes in the last 10 minutes") {
                    onChange(settings.copy(preventEscape = it))
                }
                SwitchRow("Emergency exit", settings.emergencyExitEnabled, "Hold the clock on the ringing screen for 3 s", divider = settings.emergencyExitEnabled) {
                    onChange(settings.copy(emergencyExitEnabled = it))
                }
                if (settings.emergencyExitEnabled) {
                    TextFieldRow("Code", settings.emergencyCode, divider = false) {
                        onChange(settings.copy(emergencyCode = it.ifBlank { Settings.DEFAULT_EMERGENCY_CODE }))
                    }
                }
            }

            Group("Spotify", footer = "Register these in the Spotify Developer Dashboard (Android package app.chessalarm). Tap a value to copy it.") {
                TextFieldRow("Client ID", spotify.clientId, placeholder = "Paste from the dashboard") { onChange(settings.copy(spotifyClientId = it.trim())) }
                GroupRow("Redirect URI", value = SpotifyConfig.REDIRECT_URI, onClick = { onCopy(SpotifyConfig.REDIRECT_URI) })
                GroupRow("SHA-1", subtitle = spotify.sha1, onClick = { onCopy(spotify.sha1) })
                if (spotify.loggedIn) GroupRow("Log out of Spotify", titleColor = Palette.Danger, divider = false, onClick = onSpotifyLogout)
                else GroupRow("Log in to Spotify", titleColor = Palette.Accent, divider = false, onClick = onSpotifyLogin)
            }
            Text(
                "Chess pieces: cburnett (Colin M.L. Burnett). Puzzles: Lichess database (CC0). Tunes: original.",
                style = Type.Footnote, modifier = Modifier.padding(32.dp),
            )
        }
    }
}

@Composable
private fun StillAwakeSection(
    settings: Settings,
    onChange: (Settings) -> Unit,
    onPickSound: () -> Unit,
    onTest: () -> Unit,
) {
    val sa = settings.stillAwake
    fun set(c: StillAwakeConfig) = onChange(settings.copy(stillAwake = c))
    val on = settings.stillAwakeCheck
    Group(
        "Are you still awake?",
        footer = if (on) "After ${sa.delayMin} min a notification asks you to tap \u201cI\u2019m up\u201d within ${formatSeconds(sa.responseSec)}. " +
            "If you don\u2019t, it rings again: ${if (sa.vibrationOnlySec > 0) "vibration only for ${formatSeconds(sa.vibrationOnlySec)}, then " else ""}" +
            "${if (sa.fadeSec > 0) "the sound fades into full blast over ${formatSeconds(sa.fadeSec)}" else "full blast"}. Can be turned off per alarm." else null,
    ) {
        SwitchRow("Still-awake check", on, "Make sure you didn\u2019t fall back asleep", divider = on) {
            onChange(settings.copy(stillAwakeCheck = it))
        }
        if (on) {
            SliderRow("Ask after", sa.delayMin.toFloat(), 1f..15f, "${sa.delayMin} min", steps = 13) { set(sa.copy(delayMin = it.toInt())) }
            SliderRow("Time to answer", sa.responseSec.toFloat(), 15f..300f, formatSeconds(sa.responseSec)) {
                set(sa.copy(responseSec = (it / 15f).toInt() * 15))
            }
            SliderRow("Number of checks", sa.checks.toFloat(), 1f..5f, if (sa.checks == 1) "1 check" else "${sa.checks} checks", steps = 3) {
                set(sa.copy(checks = it.toInt()))
            }
            SliderRow("Vibration only first", sa.vibrationOnlySec.toFloat(), 0f..120f,
                if (sa.vibrationOnlySec == 0) "Off" else formatSeconds(sa.vibrationOnlySec)) { set(sa.copy(vibrationOnlySec = (it / 5f).toInt() * 5)) }
            SliderRow("Fade into full blast", sa.fadeSec.toFloat(), 0f..300f, if (sa.fadeSec == 0) "Instant" else formatSeconds(sa.fadeSec)) {
                set(sa.copy(fadeSec = (it / 15f).toInt() * 15))
            }
            if (sa.fadeSec > 0) SliderRow("Starting volume", sa.startVolumePercent.toFloat(), 1f..50f, "${sa.startVolumePercent}%") {
                set(sa.copy(startVolumePercent = it.toInt()))
            }
            GroupRow("Sound", value = sa.sound?.title ?: "Default (${settings.defaultSound.title})", chevron = true, onClick = onPickSound)
            ChoiceRow("Vibration pattern", VibrationPattern.entries, sa.vibrationPattern, { it.label }) { set(sa.copy(vibrationPattern = it)) }
            ChoiceRow("Vibration intensity", Intensity.entries, sa.vibrationIntensity, { it.label }) { set(sa.copy(vibrationIntensity = it)) }
            SwitchRow("Smart escalation", sa.smartEscalation, "Vibration gets stronger every 30 s") { set(sa.copy(smartEscalation = it)) }
            ChoiceRow("While solving", SolvingMode.entries, sa.solvingMode, { it.label }) { set(sa.copy(solvingMode = it)) }
            SwitchRow("Skip button", sa.skippable, "Lets you stop the re-ring without solving") { set(sa.copy(skippable = it)) }
            GroupRow("Test the re-ring", icon = Icons.Play, divider = false, onClick = onTest, subtitle = "Plays it now, sped up")
        }
    }
}

@Composable
private fun TextFieldRow(title: String, value: String, placeholder: String = "", divider: Boolean = true, onChange: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.Body)
        Spacer(Modifier.width(16.dp))
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = Type.Body.copy(color = Palette.Secondary),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = Type.Body.copy(color = Palette.Tertiary))
                inner()
            },
        )
    }
    if (divider) GroupDivider()
}

@Composable
private fun RatingRange(lo: Int, hi: Int, onChange: (Int, Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            Text("Difficulty", style = Type.Body, modifier = Modifier.weight(1f))
            Text("$lo – $hi", style = Type.Body.copy(color = Palette.Secondary))
        }
        RangeSlider(
            value = lo.toFloat()..hi.toFloat(),
            onValueChange = { r ->
                val a = (r.start / 50).toInt() * 50
                val b = (r.endInclusive / 50).toInt() * 50
                if (b - a >= 100) onChange(a, b)
            },
            valueRange = 1200f..1900f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SwitchOff),
        )
    }
    GroupDivider()
}

/** Volume over time: the gentle curve, then the jump to the violent phase. */
@Composable
fun CurvePreview(w: WakeConfig) {
    val total = (if (w.fadeInEnabled) w.gentleDurationSec else 0) + 60
    Column(Modifier.padding(16.dp)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val gentleW = if (w.fadeInEnabled) size.width * w.gentleDurationSec / total else 0f
            fun y(v: Float) = size.height - v * size.height
            // grid
            for (i in 1..3) drawLine(Palette.Separator, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val path = Path()
            val fill = Path()
            val start = if (w.fadeInEnabled) w.gentleStartPercent / 100f else 1f
            path.moveTo(0f, y(start * w.volumePercent / 100f))
            if (w.fadeInEnabled) {
                for (i in 0..60) {
                    val p = i / 60f
                    path.lineTo(gentleW * p, y(WakeCurve.gentle(p, w.gentleStartPercent / 100f, w.gentleMaxPercent / 100f) * w.volumePercent / 100f))
                }
            }
            path.lineTo(gentleW, y(w.volumePercent / 100f))
            path.lineTo(size.width, y(w.volumePercent / 100f))
            fill.addPath(path)
            fill.lineTo(size.width, size.height)
            fill.lineTo(0f, size.height)
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(Palette.Accent.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(path, Palette.Accent, style = Stroke(width = 3.dp.toPx()))
            if (w.fadeInEnabled) drawLine(Palette.Secondary, Offset(gentleW, 0f), Offset(gentleW, size.height), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(if (w.fadeInEnabled) "Gentle · ${formatSeconds(w.gentleDurationSec)}" else "No fade-in", style = Type.Footnote, modifier = Modifier.weight(1f))
            Text("Violent", style = Type.Footnote)
        }
    }
}

// ------------------------------------------------------------------------------------------ stats

@Composable
fun StatsScreen(stats: Stats, enabled: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding()) {
        NavBar("Wake-up stats", "Back", onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (!enabled) Banner("Stats are off", "Turn on wake-up stats in Settings to record solves.", Palette.Accent, null)
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Streak", "${stats.streak}", if (stats.streak == 1) "day" else "days", Modifier.weight(1f))
                StatTile("Best", "${stats.bestStreak}", "days", Modifier.weight(1f))
            }
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Solved", "${stats.solved}", "puzzles", Modifier.weight(1f))
                StatTile("Average", if (stats.solved == 0) "–" else formatMs(stats.averageSolveMs), "to solve", Modifier.weight(1f))
            }
            val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
            fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(fmt)
            Group("Emergency exits", footer = if (stats.emergencyExits.isEmpty()) "None. Well done." else null) {
                stats.emergencyExits.takeLast(20).reversed().forEachIndexed { i, ms ->
                    GroupRow(t(ms), divider = i < minOf(stats.emergencyExits.size, 20) - 1)
                }
            }
            Group("Escapes", footer = if (stats.escapes.isEmpty()) "None." else null) {
                stats.escapes.takeLast(20).reversed().forEachIndexed { i, e ->
                    GroupRow(t(e.atMs), subtitle = e.reason, divider = i < minOf(stats.escapes.size, 20) - 1)
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Grouped).padding(16.dp)) {
        Text(title, style = Type.Footnote)
        Spacer(Modifier.height(4.dp))
        Text(value, style = Type.BigTime.copy(fontSize = 44.sp, fontWeight = FontWeight.Light))
        Text(unit, style = Type.Footnote)
    }
}

// ------------------------------------------------------------------------------------- onboarding

@Composable
fun OnboardingScreen(
    items: List<Permissions.Item>,
    first: Boolean,
    onGrant: (Permissions.Kind) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding()) {
        if (!first) NavBar("Permissions", "Back", onDone)
        Column(Modifier.verticalScroll(rememberScrollState()).weight(1f)) {
            Spacer(Modifier.height(if (first) 48.dp else 8.dp))
            Text(if (first) "Welcome to\nChess Alarm" else "Permissions", style = Type.LargeTitle, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                "An alarm that only stops when you solve a chess puzzle. To ring on time with the screen locked, " +
                    "through Doze, after a reboot and over the power button, it needs a few permissions.",
                style = Type.Secondary, modifier = Modifier.padding(horizontal = 24.dp),
            )
            Group {
                items.forEachIndexed { i, item ->
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(if (item.granted) Palette.Accent else Palette.Elevated),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (item.granted) Icon(Icons.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title + if (!item.required) "  (recommended)" else "", style = Type.Body)
                            Text(item.why, style = Type.Footnote)
                        }
                        if (!item.granted) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Allow", style = Type.Secondary.copy(color = Color.Black, fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Accent)
                                    .clickableNoRipple { onGrant(item.kind) }.padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                    if (i < items.lastIndex) GroupDivider()
                }
            }
            SectionFooter(
                "On Samsung, Xiaomi, Huawei and OnePlus phones, also allow “Autostart” / “No restrictions” " +
                    "for Chess Alarm in the battery settings if your phone has them.",
            )
        }
        if (first) {
            PillButton(
                if (items.filter { it.required }.all { it.granted }) "Get started" else "Continue anyway",
                Modifier.fillMaxWidth().padding(16.dp), primary = true, onClick = onDone,
            )
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit) = this.then(
    Modifier.clickable(onClick = onClick),
)

@Suppress("unused")
private val mono = FontFamily.Monospace
