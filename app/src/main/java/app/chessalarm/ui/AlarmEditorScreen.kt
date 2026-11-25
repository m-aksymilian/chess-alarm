package app.chessalarm.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.chessalarm.data.Alarm
import app.chessalarm.data.Intensity
import app.chessalarm.data.Settings
import app.chessalarm.data.VibrationPattern
import app.chessalarm.data.WakeOverrides
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Add / edit an alarm. [draft] is held by the caller so it survives visiting the sound picker.
 */
@Composable
fun AlarmEditorScreen(
    draft: Alarm,
    isNew: Boolean,
    settings: Settings,
    onChange: (Alarm) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onPickSound: () -> Unit,
    onPickSecondSound: () -> Unit,
    onPreviewVibration: (VibrationPattern, Intensity) -> Unit,
) {
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val locale: Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val eff = draft.wake.applyTo(settings.wake)
    fun wake(f: (WakeOverrides) -> WakeOverrides) = onChange(draft.copy(wake = f(draft.wake)))

    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding().imePadding()) {
        NavBar(if (isNew) "Add Alarm" else "Edit Alarm", "Cancel", onCancel, "Save", onSave)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TimeWheel(draft.hour, draft.minute, is24) { h, m -> onChange(draft.copy(hour = h, minute = m)) }

            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Repeat", style = Type.Body)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (d in 1..7) {
                            val on = d in draft.days
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(if (on) Palette.Accent else Palette.Elevated)
                                    .clickable { onChange(draft.copy(days = if (on) draft.days - d else draft.days + d)) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    DayOfWeek.of(d).getDisplayName(TextStyle.NARROW, locale),
                                    style = Type.Secondary.copy(color = if (on) Color.Black else Palette.Text, fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(app.chessalarm.alarm.AlarmTime.repeatSummary(draft.days), style = Type.Footnote)
                }
                GroupDivider()
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Label", style = Type.Body)
                    Spacer(Modifier.size(16.dp))
                    BasicTextField(
                        value = draft.label,
                        onValueChange = { onChange(draft.copy(label = it.take(40))) },
                        singleLine = true,
                        textStyle = Type.Body.copy(color = Palette.Secondary, textAlign = TextAlign.End),
                        cursorBrush = SolidColor(Palette.Accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterEnd) {
                                if (draft.label.isEmpty()) Text("Alarm", style = Type.Body.copy(color = Palette.Tertiary))
                                inner()
                            }
                        },
                    )
                }
                GroupDivider()
                GroupRow("Sound", value = (draft.sound ?: settings.defaultSound).title + if (draft.sound == null) " (default)" else "",
                    chevron = true, divider = false, onClick = onPickSound)
            }

            Group("Volume & vibration") {
                SliderRow("Volume", eff.volumePercent.toFloat(), 10f..100f, "${eff.volumePercent}%") {
                    wake { w -> w.copy(volumePercent = it.toInt()) }
                }
                SwitchRow("Fade in gently", eff.fadeInEnabled) { v -> wake { it.copy(fadeInEnabled = v) } }
                SwitchRow("Vibration", eff.vibrationEnabled, divider = eff.vibrationEnabled) { v -> wake { it.copy(vibrationEnabled = v) } }
                if (eff.vibrationEnabled) {
                    ChoiceRow("Pattern", VibrationPattern.entries, eff.vibrationPattern, { it.label }) { p ->
                        wake { it.copy(vibrationPattern = p) }
                        onPreviewVibration(p, eff.intensity)
                    }
                    ChoiceRow("Intensity", Intensity.entries, eff.intensity, { it.label }) { i ->
                        wake { it.copy(intensity = i) }
                        onPreviewVibration(eff.vibrationPattern, i)
                    }
                    SwitchRow("Smart escalation", eff.smartEscalation, "Stronger every 30 s until Maximum", divider = false) { v ->
                        wake { it.copy(smartEscalation = v) }
                    }
                }
            }

            val custom = draft.wake.gentleDurationSec != null || draft.wake.solvingMode != null || draft.wake.secondarySoundEnabled != null ||
                draft.wake.inactivitySec != null || draft.wake.violentPreset != null || draft.wake.gentleVibration != null ||
                draft.wake.gentleStartPercent != null || draft.wake.gentleMaxPercent != null || draft.stillAwakeCheck != null ||
                draft.adaptiveDifficulty != null
            Group("Wake sequence", footer = if (!custom) "Uses the wake sequence from Settings." else null) {
                SwitchRow("Customise for this alarm", custom, divider = custom) { on ->
                    if (on) {
                        val g = settings.wake
                        onChange(
                            draft.copy(
                                wake = draft.wake.copy(
                                    gentleDurationSec = g.gentleDurationSec, gentleStartPercent = g.gentleStartPercent,
                                    gentleMaxPercent = g.gentleMaxPercent, gentleVibration = g.gentleVibration,
                                    violentPreset = g.violentPreset, secondarySoundEnabled = g.secondarySoundEnabled,
                                    secondarySound = g.secondarySound, solvingMode = g.solvingMode, inactivitySec = g.inactivitySec,
                                ),
                                stillAwakeCheck = settings.stillAwakeCheck,
                                adaptiveDifficulty = settings.adaptiveDifficulty,
                            ),
                        )
                    } else {
                        onChange(
                            draft.copy(
                                wake = draft.wake.copy(
                                    gentleDurationSec = null, gentleStartPercent = null, gentleMaxPercent = null, gentleVibration = null,
                                    violentPreset = null, secondarySoundEnabled = null, secondarySound = null, solvingMode = null,
                                    inactivitySec = null,
                                ),
                                stillAwakeCheck = null,
                                adaptiveDifficulty = null,
                            ),
                        )
                    }
                }
                if (custom) {
                    WakeSequenceControls(eff, { f -> onChange(draft.copy(wake = f(draft.wake))) }, onPickSecondSound)
                    SwitchRow("Are you still awake check", draft.stillAwakeCheck ?: settings.stillAwakeCheck) {
                        onChange(draft.copy(stillAwakeCheck = it))
                    }
                    SwitchRow("Adaptive difficulty", draft.adaptiveDifficulty ?: settings.adaptiveDifficulty, divider = false) {
                        onChange(draft.copy(adaptiveDifficulty = it))
                    }
                }
            }

            if (!isNew) {
                Group {
                    GroupRow("Delete Alarm", titleColor = Palette.Danger, divider = false, onClick = onDelete)
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** Gentle/violent/solving controls, shared by the alarm editor (overrides) and Settings (defaults). */
@Composable
fun WakeSequenceControls(
    eff: app.chessalarm.data.WakeConfig,
    update: ((WakeOverrides) -> WakeOverrides) -> Unit,
    onPickSecondSound: () -> Unit,
) {
    SliderRow("Gentle phase", eff.gentleDurationSec.toFloat(), 15f..300f, formatSeconds(eff.gentleDurationSec)) { v ->
        update { it.copy(gentleDurationSec = (v / 15f).toInt() * 15) }
    }
    SliderRow("Starting volume", eff.gentleStartPercent.toFloat(), 1f..30f, "${eff.gentleStartPercent}%") { v ->
        update { it.copy(gentleStartPercent = v.toInt()) }
    }
    SliderRow("Gentle maximum", eff.gentleMaxPercent.toFloat(), 10f..90f, "${eff.gentleMaxPercent}%") { v ->
        update { it.copy(gentleMaxPercent = v.toInt().coerceAtLeast(eff.gentleStartPercent)) }
    }
    ChoiceRow("Gentle vibration", app.chessalarm.data.GentleVibration.entries, eff.gentleVibration, { it.label }) { g ->
        update { it.copy(gentleVibration = g) }
    }
    ChoiceRow("Violent vibration", listOf(Intensity.VIOLENT, Intensity.MAXIMUM), eff.violentPreset, { it.label }) { i ->
        update { it.copy(violentPreset = i) }
    }
    SwitchRow("Harsher sound in violent phase", eff.secondarySoundEnabled, divider = true) { v ->
        update { it.copy(secondarySoundEnabled = v) }
    }
    if (eff.secondarySoundEnabled) {
        GroupRow("Violent-phase sound", value = eff.secondarySound.title, chevron = true, onClick = onPickSecondSound)
    }
    ChoiceRow("While solving", app.chessalarm.data.SolvingMode.entries, eff.solvingMode, { it.label }) { m ->
        update { it.copy(solvingMode = m) }
    }
    SliderRow("Inactivity guard", eff.inactivitySec.toFloat(), 5f..60f, "${eff.inactivitySec} s") { v ->
        update { it.copy(inactivitySec = v.toInt()) }
    }
}

fun formatSeconds(s: Int): String = if (s < 60) "$s s" else if (s % 60 == 0) "${s / 60} min" else "${s / 60} min ${s % 60} s"
