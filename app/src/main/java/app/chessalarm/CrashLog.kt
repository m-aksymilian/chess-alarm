package app.chessalarm

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.chessalarm.data.AppStore
import java.io.File

/**
 * Saves any crash to a file, and shows it on the next launch with a Copy button (plain Android
 * views only, so it works even if the rest of the UI is what crashes).
 */
object CrashLog {
    private fun file(context: Context) = File(context.applicationContext.createDeviceProtectedStorageContext().filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    appendLine("Chess Alarm ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(error.stackTraceToString())
                }
                file(app).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** If the last run crashed, shows the report and returns true; [onContinue] runs when dismissed. */
    fun showIfCrashed(activity: Activity, onContinue: () -> Unit): Boolean {
        val f = file(activity)
        if (!f.exists()) return false
        val report = runCatching { f.readText() }.getOrDefault("(unreadable)")
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val title = TextView(activity).apply {
            text = "Chess Alarm crashed last time"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val hint = TextView(activity).apply {
            text = "Tap Copy and send this to the developer. Then tap Continue."
            setTextColor(Color.LTGRAY)
            setPadding(0, pad / 2, 0, pad)
        }
        val body = TextView(activity).apply {
            text = report
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
        }
        val copy = Button(activity).apply {
            text = "Copy"
            setOnClickListener {
                activity.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Chess Alarm crash", report))
                Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        val cont = Button(activity).apply {
            text = "Continue"
            setOnClickListener {
                f.delete()
                onContinue()
            }
        }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(copy)
            addView(cont)
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad * 3)
            addView(title)
            addView(hint)
            addView(buttons)
            addView(body)
        }
        activity.setContentView(ScrollView(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
        })
        return true
    }

    @Suppress("unused")
    private fun touch(context: Context) = AppStore.filesDir(context)
}
