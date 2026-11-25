package app.chessalarm

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.chessalarm.alarm.AlarmScheduler
import java.security.MessageDigest

/** Everything the app needs to ring reliably, with a way to request each. */
object Permissions {
    enum class Kind { NOTIFICATIONS, EXACT_ALARMS, FULL_SCREEN, BATTERY, OVERLAY, DND }

    data class Item(
        val kind: Kind,
        val title: String,
        val why: String,
        val required: Boolean,
        val granted: Boolean,
    )

    fun items(context: Context): List<Item> {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        return listOf(
            Item(
                Kind.NOTIFICATIONS, "Notifications",
                "Needed to show the alarm over the lock screen and the still-awake check.",
                true,
                Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            ),
            Item(
                Kind.EXACT_ALARMS, "Alarms & reminders",
                "Lets the alarm ring at the exact minute, even in Doze.",
                true, AlarmScheduler.canScheduleExact(context),
            ),
            Item(
                Kind.FULL_SCREEN, "Full-screen alarm",
                "Lets the ringing screen open over the lock screen and turn the screen on.",
                true, Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
            ),
            Item(
                Kind.BATTERY, "Unrestricted battery",
                "Stops the system from delaying or killing the alarm to save battery.",
                true, pm.isIgnoringBatteryOptimizations(context.packageName),
            ),
            Item(
                Kind.OVERLAY, "Display over other apps",
                "Lets the ringing screen come straight back if you leave it.",
                true, Settings.canDrawOverlays(context),
            ),
            Item(
                Kind.DND, "Do Not Disturb access",
                "Lets the alarm ring even if your Do Not Disturb settings silence alarms.",
                false, nm.isNotificationPolicyAccessGranted,
            ),
        )
    }

    fun allRequiredGranted(context: Context) = items(context).filter { it.required }.all { it.granted }

    /** Opens the right system screen for [kind]. Notifications are requested by the caller (runtime permission). */
    fun request(activity: Activity, kind: Kind) {
        val pkg = Uri.parse("package:${activity.packageName}")
        val intent = when (kind) {
            Kind.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            Kind.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            Kind.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
            @Suppress("BatteryLife")
            Kind.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            Kind.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            Kind.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        } ?: return
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }

    /** SHA-1 of the signing certificate, formatted for the Spotify dashboard. */
    fun signingSha1(context: Context): String = runCatching {
        val pm = context.packageManager
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo!!.apkContentsSigners.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!!.map { it.toByteArray() }
        }
        MessageDigest.getInstance("SHA-1").digest(certs.first()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("unavailable")
}
