package app.chessalarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.chessalarm.MainActivity
import app.chessalarm.R
import app.chessalarm.ring.RingingActivity

object Notifications {
    const val CHANNEL_RINGING = "ringing"
    const val CHANNEL_STILL_AWAKE = "still_awake"
    const val CHANNEL_REMINDERS = "reminders"

    const val ID_RINGING = 1
    const val ID_STILL_AWAKE = 2
    const val ID_BEDTIME = 3
    const val ID_ESCAPE = 4

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ringing = NotificationChannel(CHANNEL_RINGING, "Ringing alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown while an alarm is ringing. The app plays the sound itself."
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val stillAwake = NotificationChannel(CHANNEL_STILL_AWAKE, "Are you still awake?", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Asks you to confirm you're up a few minutes after solving."
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val reminders = NotificationChannel(CHANNEL_REMINDERS, "Bedtime and warnings", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Bedtime reminders and escape warnings."
        }
        nm.createNotificationChannels(listOf(ringing, stillAwake, reminders))
    }

    private fun builder(context: Context, channel: String) =
        Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF9F0A.toInt())

    fun ringIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 10,
        Intent(context, RingingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun ringing(context: Context, title: String, text: String, deleteIntent: PendingIntent): Notification =
        builder(context, CHANNEL_RINGING)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setFullScreenIntent(ringIntent(context), true)
            .setContentIntent(ringIntent(context))
            .setDeleteIntent(deleteIntent)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
            .apply { flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_INSISTENT }

    fun showStillAwake(context: Context, responseSec: Int) {
        val imUp = PendingIntent.getBroadcast(
            context, 20, Intent(context, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_IM_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = builder(context, CHANNEL_STILL_AWAKE)
            .setContentTitle("Are you still awake?")
            .setContentText("Tap “I’m up” within $responseSec seconds, or the alarm rings again.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(imUp)
            .setAutoCancel(true)
            .setTimeoutAfter(responseSec * 1000L + 10_000)
            .addAction(Notification.Action.Builder(null, "I’m up", imUp).build())
            .build()
        notify(context, ID_STILL_AWAKE, n)
    }

    fun cancel(context: Context, id: Int) = context.getSystemService(NotificationManager::class.java).cancel(id)

    fun showReminder(context: Context, id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 30, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = builder(context, CHANNEL_REMINDERS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(context, id, n)
    }

    private fun notify(context: Context, id: Int, n: Notification) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // Notification permission not granted.
        }
    }
}
