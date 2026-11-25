package app.chessalarm.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.SwitchDefaults
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.chessalarm.MainActivity
import app.chessalarm.alarm.AlarmScheduler
import app.chessalarm.alarm.AlarmTime
import app.chessalarm.data.Alarm
import app.chessalarm.data.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Home screen widget: the next alarm with a quick on/off toggle. */
class NextAlarmWidget : GlanceAppWidget() {

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun refresh(context: Context) {
            scope.launch { runCatching { NextAlarmWidget().updateAll(context.applicationContext) } }
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, NextAlarmWidgetReceiver::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        /** The alarm the widget shows: the next enabled one, else the one that would ring soonest. */
        fun featured(alarms: List<Alarm>, now: ZonedDateTime): Alarm? =
            AlarmTime.nextOf(alarms, now)?.first
                ?: alarms.minByOrNull { a -> AlarmTime.nextTrigger(a.copy(enabled = true), now)?.toEpochSecond() ?: Long.MAX_VALUE }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        AppStore.init(context)
        provideContent { Content(context) }
    }

    @Composable
    private fun Content(context: Context) {
        val now = ZonedDateTime.now()
        val alarm = featured(AppStore.current.alarms, now)
        val is24 = android.text.format.DateFormat.is24HourFormat(context)
        val white = ColorProvider(Color.White, Color.White)
        val gray = ColorProvider(Color(0xFF8E8E93), Color(0xFF8E8E93))
        Row(
            GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF1C1C1E), Color(0xFF1C1C1E)))
                .cornerRadius(22.dp).padding(16.dp).clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(GlanceModifier.defaultWeight()) {
                if (alarm == null) {
                    Text("No alarms", style = TextStyle(color = white, fontSize = 20.sp))
                    Text("Tap to add one", style = TextStyle(color = gray, fontSize = 13.sp))
                } else {
                    val time = java.time.LocalTime.of(alarm.hour, alarm.minute)
                        .format(DateTimeFormatter.ofPattern(if (is24) "H:mm" else "h:mm a"))
                    Text(time, style = TextStyle(color = if (alarm.enabled) white else gray, fontSize = 34.sp, fontWeight = FontWeight.Normal))
                    val next = AlarmTime.nextTrigger(alarm, now)
                    Text(
                        if (next != null) "${alarm.label.ifBlank { "Alarm" }} · in ${AlarmTime.formatUntil(now, next)}" else "${alarm.label.ifBlank { "Alarm" }} · off",
                        style = TextStyle(color = gray, fontSize = 13.sp),
                        maxLines = 1,
                    )
                }
            }
            if (alarm != null) {
                Spacer(GlanceModifier.width(8.dp))
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = actionRunCallback<ToggleAlarmAction>(),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = white,
                        uncheckedThumbColor = white,
                        checkedTrackColor = ColorProvider(Color(0xFFFF9F0A), Color(0xFFFF9F0A)),
                        uncheckedTrackColor = ColorProvider(Color(0xFF39393D), Color(0xFF39393D)),
                    ),
                )
            }
        }
    }
}

class ToggleAlarmAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        AppStore.init(context)
        val now = ZonedDateTime.now()
        val alarm = NextAlarmWidget.featured(AppStore.current.alarms, now) ?: return
        val s = AppStore.current.settings
        if (alarm.enabled && s.preventEscape && AlarmTime.isWithin(alarm, now, 10 * 60_000L)) {
            // Changing an alarm within 10 minutes of it ringing needs a puzzle: open the app.
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_TOGGLE_ALARM, alarm.id),
            )
            NextAlarmWidget().updateAll(context)
            return
        }
        AppStore.update(context) { d -> d.copy(alarms = d.alarms.map { if (it.id == alarm.id) it.copy(enabled = !it.enabled) else it }) }
        AlarmScheduler.rescheduleAll(context)
        NextAlarmWidget().updateAll(context)
    }
}

class NextAlarmWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextAlarmWidget()
}
