package app.chessalarm.screens

import androidx.test.core.app.ActivityScenario
import app.chessalarm.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Launches the real Application + MainActivity, as tapping the icon does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 29, 33, 34, 36])
class LaunchTest {
    @Test fun crashReportShowsThenContinues() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        java.io.File(ctx.createDeviceProtectedStorageContext().filesDir, "last_crash.txt").writeText("boom\nat Foo")
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a ->
                val root = a.findViewById<android.view.ViewGroup>(android.R.id.content)
                check(root.childCount == 1 && root.getChildAt(0) is android.widget.ScrollView) { "crash screen not shown" }
            }
        }
    }

    @Test fun appOpens() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a -> check(!a.isFinishing) }
        }
    }
}
