package app.chessalarm.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chessalarm.alarm.AlarmTime
import app.chessalarm.data.Alarm
import app.chessalarm.data.AppData
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun AlarmListScreen(
    data: AppData,
    permissionsOk: Boolean,
    onAdd: () -> Unit,
    onEdit: (Alarm) -> Unit,
    onToggle: (Alarm, Boolean) -> Unit,
    onDelete: (Alarm) -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onFixPermissions: () -> Unit,
) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = ZonedDateTime.now() } }
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val next = AlarmTime.nextOf(data.alarms, now)
    val sorted = data.alarms.sortedWith(compareBy({ it.hour }, { it.minute }))

    LazyColumn(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButtonText(Icons.Gear, onSettings)
                IconButtonText(Icons.Chart, onStats)
                Spacer(Modifier.weight(1f))
                IconButtonText(Icons.Plus, onAdd)
            }
            Text("Alarms", style = Type.LargeTitle, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            Text(
                if (next != null) "Next alarm in ${AlarmTime.formatUntil(now, next.second)}" else "No alarms set",
                style = Type.Secondary,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp),
            )
        }
        if (!permissionsOk) item {
            Banner("Finish setup", "Some permissions are missing, so alarms may not ring reliably. Tap to fix.", Palette.Danger, onFixPermissions)
        }
        data.stats.pendingEscapeWarning?.let { e ->
            if (data.settings.preventEscape) item {
                Banner("You escaped an alarm", "Last time, ${e.reason}. Only the puzzle should stop it — leave Chess Alarm running tonight.", Palette.Accent, null)
            }
        }
        if (sorted.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No Alarms", style = Type.Title.copy(fontSize = 22.sp, color = Palette.Secondary))
                Spacer(Modifier.height(8.dp))
                Text("Tap + to add one.", style = Type.Secondary)
            }
        }
        items(sorted, key = { it.id }) { alarm ->
            AlarmRow(alarm, is24, onEdit, onToggle, onDelete)
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun IconButtonText(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Palette.Accent, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun Banner(title: String, text: String, color: Color, onClick: (() -> Unit)?) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.16f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
    ) {
        Text(title, style = Type.Title.copy(color = color))
        Spacer(Modifier.height(2.dp))
        Text(text, style = Type.Secondary.copy(color = Palette.Text))
    }
}

@Composable
private fun AlarmRow(alarm: Alarm, is24: Boolean, onEdit: (Alarm) -> Unit, onToggle: (Alarm, Boolean) -> Unit, onDelete: (Alarm) -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete(alarm)
            dismiss.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(Palette.Danger).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Text("Delete", style = Type.Body.copy(fontWeight = FontWeight.SemiBold))
            }
        },
    ) {
        val textColor by animateColorAsState(if (alarm.enabled) Palette.Text else Palette.Secondary, label = "c")
        Column(Modifier.background(Palette.Background).animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { onEdit(alarm) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val t = LocalTime.of(alarm.hour, alarm.minute)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            t.format(DateTimeFormatter.ofPattern(if (is24) "H:mm" else "h:mm")),
                            style = Type.BigTime.copy(color = textColor),
                        )
                        if (!is24) Text(
                            if (alarm.hour < 12) "AM" else "PM",
                            style = Type.Title.copy(fontSize = 22.sp, fontWeight = FontWeight.Light, color = textColor),
                            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                        )
                    }
                    val sub = listOfNotNull(alarm.label.ifBlank { "Alarm" }, AlarmTime.repeatSummary(alarm.days).takeIf { alarm.days.isNotEmpty() })
                        .joinToString(", ")
                    Text(sub, style = Type.Secondary.copy(color = textColor))
                }
                IosSwitch(alarm.enabled, { onToggle(alarm, it) })
            }
            GroupDivider()
        }
    }
}

@Suppress("unused")
private val arrangement = Arrangement.Top
