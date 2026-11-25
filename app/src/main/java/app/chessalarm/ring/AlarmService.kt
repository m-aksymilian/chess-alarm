package app.chessalarm.ring

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings as SystemSettings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.chessalarm.alarm.AlarmScheduler
import app.chessalarm.alarm.Notifications
import app.chessalarm.audio.AlarmSoundPlayer
import app.chessalarm.audio.AlarmVibrator
import app.chessalarm.audio.VolumeGuard
import app.chessalarm.chess.Move
import app.chessalarm.data.ActiveRing
import app.chessalarm.data.AppStore
import app.chessalarm.data.BuiltInTunes
import app.chessalarm.data.EscapeEvent
import app.chessalarm.data.RingKind
import app.chessalarm.data.SoundChoice
import app.chessalarm.data.SoundType
import app.chessalarm.data.WakeConfig
import app.chessalarm.puzzle.AdaptiveDifficulty
import app.chessalarm.puzzle.PuzzlePicker
import app.chessalarm.puzzle.PuzzleSession
import app.chessalarm.spotify.SpotifyPlayer
import java.time.LocalDate

/**
 * Plays the alarm. It runs as a foreground service (not in the activity), so pressing the power
 * button, locking the screen, leaving the app or swiping it away does not stop the sound or the
 * vibration. The only ways out are the ones the state machine allows: solving the puzzle, the
 * skip button on a still-awake re-ring, the emergency code, or ending a test.
 */
class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        private const val ACTION_START = "app.chessalarm.ring.START"
        private const val ACTION_REPOST = "app.chessalarm.ring.REPOST"
        private const val EXTRA_RESUME = "resume"
        private const val ID_RELAUNCH = 5
        private const val TICK_MS = 100L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context, alarmId: Long, kind: RingKind, resume: Boolean = false) {
            val intent = Intent(context, AlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                .putExtra(AlarmScheduler.EXTRA_KIND, kind.name)
                .putExtra(EXTRA_RESUME, resume)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start alarm service", e)
                // Last resort: the full-screen notification still opens the ringing screen.
            }
        }

        fun bootCount(context: Context): Int =
            runCatching { SystemSettings.Global.getInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT) }.getOrDefault(-1)
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var local: AlarmSoundPlayer
    private lateinit var guard: VolumeGuard
    private lateinit var vibrator: AlarmVibrator
    private var spotify: SpotifyPlayer? = null
    private var spotifyFailed = false

    private var machine: RingStateMachine? = null
    private var ring: ActiveRing? = null
    private lateinit var wake: WakeConfig
    private lateinit var primary: SoundChoice
    private lateinit var secondary: SoundChoice
    private lateinit var fallback: SoundChoice
    private var session: PuzzleSession? = null
    private var version = 0
    private var ringStartElapsed = 0L
    private var puzzleShownElapsed = 0L
    private var screenOffAt = 0L
    private var lastWatchdog = 0L
    private var lastLaunch = 0L
    private var lastPublished: Triple<Phase, Boolean, Int>? = null
    private var escapeRecorded = false
    private var partialLock: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            step()
            if (machine?.isDone == false) handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                screenOffAt = SystemClock.elapsedRealtime()
                onUiEvent(RingEvent.ScreenOff)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppStore.init(this)
        Notifications.ensureChannels(this)
        local = AlarmSoundPlayer(this)
        guard = VolumeGuard(this)
        vibrator = AlarmVibrator(this)
        isRunning = true
        RingController.service = this
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first (the system requires it within seconds).
        goForeground("Alarm", "Solve the puzzle to stop the alarm")

        if (intent?.action == ACTION_REPOST) return START_STICKY
        if (machine?.isDone == false) {
            Log.i(TAG, "Already ringing; ignoring another start")
            return START_STICKY
        }

        val persisted = AppStore.activeRing(this)
        val alarmId: Long
        val kind: RingKind
        val resume: Boolean
        if (intent?.action == ACTION_START) {
            alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, 0)
            kind = runCatching { RingKind.valueOf(intent.getStringExtra(AlarmScheduler.EXTRA_KIND)!!) }.getOrDefault(RingKind.ALARM)
            resume = intent.getBooleanExtra(EXTRA_RESUME, false)
        } else if (persisted != null) {
            // Restarted by the system after the process died: resume the unsolved alarm.
            alarmId = persisted.alarmId
            kind = persisted.kind
            resume = true
        } else {
            stopSelf()
            return START_NOT_STICKY
        }
        begin(alarmId, kind, resume, persisted)
        return START_STICKY
    }

    private fun goForeground(title: String, text: String) {
        val repost = PendingIntent.getService(
            this, 1, Intent(this, AlarmService::class.java).setAction(ACTION_REPOST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notifications.ringing(this, title, text, repost)
        val types = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_RINGING, n, types)
        } catch (e: Exception) {
            Log.w(TAG, "systemExempted foreground failed, trying mediaPlayback", e)
            ServiceCompat.startForeground(this, Notifications.ID_RINGING, n,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        }
    }

    // ---------------------------------------------------------------------------------- start up

    private fun begin(alarmId: Long, kind: RingKind, resume: Boolean, persisted: ActiveRing?) {
        val data = AppStore.current
        val settings = data.settings
        val alarm = data.alarms.firstOrNull { it.id == alarmId }

        wake = when {
            kind == RingKind.ALARM && alarm != null -> alarm.wake.applyTo(settings.wake)
            kind == RingKind.STILL_AWAKE -> settings.wake.copy(fadeInEnabled = false)
            else -> settings.wake
        }
        primary = (if (kind == RingKind.ALARM) alarm?.sound else null) ?: settings.defaultSound
        secondary = wake.secondarySound.takeIf { it.type != SoundType.SPOTIFY } ?: BuiltInTunes.default(harsh = true)
        fallback = settings.defaultSound.takeIf { it.type != SoundType.SPOTIFY } ?: BuiltInTunes.default(harsh = false)

        val timeScale = if (kind == RingKind.TEST_SEQUENCE) 0.15f else 1f
        val now = SystemClock.elapsedRealtime()
        ringStartElapsed = now
        machine = RingStateMachine(RingConfig.from(wake, timeScale), now, resumeViolent = resume && kind != RingKind.TEST_SEQUENCE)

        val label = when (kind) {
            RingKind.ALARM -> alarm?.label?.ifBlank { "Alarm" } ?: persisted?.label ?: "Alarm"
            RingKind.STILL_AWAKE -> "Are you still awake?"
            RingKind.TEST_ALARM -> "Test alarm"
            RingKind.TEST_SEQUENCE -> "Wake sequence test"
        }
        ring = ActiveRing(alarmId, kind, persisted?.startedAtMs ?: System.currentTimeMillis(), null, bootCount(this), label)
        newPuzzle(exclude = null)
        AppStore.setActiveRing(this, ring)

        partialLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChessAlarm:ring")
            .apply { setReferenceCounted(false); acquire(60 * 60_000L) }
        guard.begin()

        if (primary.type == SoundType.SPOTIFY) startSpotify()

        goForeground(label, "Solve the puzzle to stop the alarm")
        wakeScreen()
        launchRingScreen()
        handler.removeCallbacks(tick)
        handler.post(tick)
        Log.i(TAG, "Ringing: $kind alarm=$alarmId resume=$resume")
    }

    private fun startSpotify() {
        val um = getSystemService(UserManager::class.java)
        if (!um.isUserUnlocked) {
            // Before first unlock Spotify can't run: use the fallback sound.
            spotifyFailed = true
            return
        }
        spotify = SpotifyPlayer(this).also {
            it.start(primary.value, primary.shuffle) { reason ->
                Log.w(TAG, "Spotify fallback: $reason")
                spotifyFailed = true
            }
        }
    }

    // ------------------------------------------------------------------------------- the loop

    private fun step() {
        val m = machine ?: return
        val now = SystemClock.elapsedRealtime()
        val out = m.output(now)
        applySound(out)
        vibrator.apply(wake.vibrationPattern, out.vibration)
        publishIfChanged(out)
        if (now - lastWatchdog >= 1000) {
            lastWatchdog = now
            watchdog(now)
        }
    }

    private fun applySound(out: RingOutput) {
        val choice = when (out.sound) {
            SoundSlot.PRIMARY -> primary
            SoundSlot.SECONDARY -> secondary
            SoundSlot.NONE -> null
        }
        if (choice == null || out.volume <= 0f) {
            local.pause()
            spotify?.pause()
            guard.enforceMax()
            return
        }
        if (choice.type == SoundType.SPOTIFY && !spotifyFailed && spotify != null) {
            local.stop()
            spotify?.resume()
            guard.enforceFraction(out.volume)
        } else {
            spotify?.pause()
            local.play(if (choice.type == SoundType.SPOTIFY) fallback else choice, out.volume)
            guard.enforceMax()
        }
    }

    private fun publishIfChanged(out: RingOutput) {
        val key = Triple(out.phase, out.canMute, version)
        if (key == lastPublished) return
        lastPublished = key
        publish(null)
    }

    private fun publish(finished: EndReason?) {
        val s = session ?: return
        val r = ring ?: return
        val m = machine ?: return
        val settings = AppStore.current.settings
        RingController.publish(
            RingUi(
                kind = r.kind,
                label = r.label,
                session = s,
                version = version,
                phase = m.phase,
                canMute = m.canMute,
                skippable = r.kind == RingKind.STILL_AWAKE,
                emergencyEnabled = settings.emergencyExitEnabled,
                emergencyCode = settings.emergencyCode,
                finished = finished,
            ),
        )
    }

    /** "If I leave it, it comes back", plus keeping the notification and escape checks alive. */
    private fun watchdog(now: Long) {
        val pm = getSystemService(PowerManager::class.java)
        if (!RingController.activityVisible) {
            val screenOn = pm.isInteractive
            val sinceLeft = now - RingController.lastVisibilityChange
            if (!screenOn && now - screenOffAt >= 1500) {
                wakeScreen()
                launchRingScreen()
            } else if (screenOn && sinceLeft >= 1500) {
                launchRingScreen()
            }
        } else {
            Notifications.cancel(this, ID_RELAUNCH)
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.activeNotifications.none { it.id == Notifications.ID_RINGING }) {
            goForeground(ring?.label ?: "Alarm", "Solve the puzzle to stop the alarm")
        }
        if (!escapeRecorded && !nm.areNotificationsEnabled() && ring?.kind == RingKind.ALARM) {
            escapeRecorded = true
            recordEscape("notifications were turned off while ringing")
        }
    }

    private fun wakeScreen() {
        @Suppress("DEPRECATION")
        getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "ChessAlarm:screen",
        ).acquire(5_000)
    }

    private fun launchRingScreen() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 2500) return
        lastLaunch = now
        try {
            startActivity(
                Intent(this, RingingActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Direct activity start blocked: ${e.message}")
        }
        // A fresh full-screen-intent notification also brings the screen back (and works when a
        // direct start is blocked by background-activity-start rules).
        try {
            val n = Notifications.ringing(this, ring?.label ?: "Alarm", "Solve the puzzle to stop the alarm",
                Notifications.ringIntent(this))
            getSystemService(NotificationManager::class.java).cancel(ID_RELAUNCH)
            getSystemService(NotificationManager::class.java).notify(ID_RELAUNCH, n)
        } catch (_: SecurityException) {
        }
    }

    // ----------------------------------------------------------------------------- UI callbacks

    fun onActivityVisibility(visible: Boolean) {
        if (!visible) onUiEvent(RingEvent.ScreenOff)
    }

    fun onUiEvent(event: RingEvent) {
        val m = machine ?: return
        m.onEvent(event, SystemClock.elapsedRealtime())
        step()
    }

    fun submitMove(move: Move): PuzzleSession.Result {
        val s = session ?: return PuzzleSession.Result.Illegal
        if (s.isSolved || machine?.isDone != false) return PuzzleSession.Result.Illegal
        onUiEvent(RingEvent.Interaction)
        val result = s.attempt(move)
        when (result) {
            is PuzzleSession.Result.Correct -> {
                version++
                onUiEvent(RingEvent.CorrectMove)
            }
            is PuzzleSession.Result.Solved -> {
                version++
                onUiEvent(RingEvent.CorrectMove)
                finish(EndReason.SOLVED)
            }
            else -> Unit
        }
        return result
    }

    fun changePuzzle() {
        if (machine?.isDone != false) return
        onUiEvent(RingEvent.Interaction)
        newPuzzle(exclude = session?.puzzle?.id)
        step()
    }

    private fun newPuzzle(exclude: String?) {
        val data = AppStore.current
        val settings = data.settings
        val alarm = data.alarms.firstOrNull { it.id == ring?.alarmId }
        val adaptive = alarm?.adaptiveDifficulty ?: settings.adaptiveDifficulty
        val target = if (adaptive) data.adaptiveTarget ?: AdaptiveDifficulty.initial(settings.minRating, settings.maxRating) else null
        val puzzles = PuzzleRepository.all(this)
        val persistedId = ring?.puzzleId?.takeIf { exclude == null }
        val p = puzzles.firstOrNull { it.id == persistedId }
            ?: PuzzlePicker(puzzles).pick(settings.minRating, settings.maxRating, target, data.recentPuzzles, exclude)
        session = PuzzleSession(p)
        version++
        puzzleShownElapsed = SystemClock.elapsedRealtime()
        ring = ring?.copy(puzzleId = p.id)
        ring?.let { AppStore.setActiveRing(this, it) }
        AppStore.update(this) { d -> d.copy(recentPuzzles = (listOf(p.id) + d.recentPuzzles).distinct().take(100)) }
        publish(null)
    }

    fun requestEnd(reason: EndReason) {
        val r = ring ?: return
        val allowed = when (reason) {
            EndReason.SKIPPED -> r.kind == RingKind.STILL_AWAKE
            EndReason.TEST_ENDED -> r.kind == RingKind.TEST_SEQUENCE
            else -> false
        }
        if (allowed) finish(reason)
    }

    fun emergencyExit(code: String): Boolean {
        val settings = AppStore.current.settings
        if (!settings.emergencyExitEnabled) return false
        if (code.trim() != settings.emergencyCode.trim()) return false
        finish(EndReason.EMERGENCY_EXIT)
        return true
    }

    // ----------------------------------------------------------------------------------- finish

    private fun finish(reason: EndReason) {
        val m = machine ?: return
        if (m.isDone) return
        m.onEvent(RingEvent.End(reason), SystemClock.elapsedRealtime())
        handler.removeCallbacks(tick)
        local.stop()
        spotify?.stop()
        vibrator.stop()
        guard.end()
        AppStore.setActiveRing(this, null)
        Notifications.cancel(this, ID_RELAUNCH)
        recordOutcome(reason)
        publish(reason)
        partialLock?.let { if (it.isHeld) it.release() }
        handler.postDelayed({
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 400)
    }

    private fun recordOutcome(reason: EndReason) {
        val r = ring ?: return
        val now = SystemClock.elapsedRealtime()
        val solveMs = now - puzzleShownElapsed
        val settings = AppStore.current.settings
        val alarm = AppStore.current.alarms.firstOrNull { it.id == r.alarmId }
        val counts = r.kind == RingKind.ALARM
        AppStore.update(this) { d ->
            var stats = d.stats
            var target = d.adaptiveTarget
            if (counts && reason == EndReason.SOLVED) {
                if (settings.statsEnabled) {
                    val today = LocalDate.now().toEpochDay()
                    val streak = when (stats.lastWakeEpochDay) {
                        today -> stats.streak.coerceAtLeast(1)
                        today - 1 -> stats.streak + 1
                        else -> 1
                    }
                    stats = stats.copy(
                        solved = stats.solved + 1,
                        totalSolveMs = stats.totalSolveMs + solveMs,
                        streak = streak,
                        bestStreak = maxOf(stats.bestStreak, streak),
                        lastWakeEpochDay = today,
                    )
                }
                if (alarm?.adaptiveDifficulty ?: settings.adaptiveDifficulty) {
                    val cur = target ?: AdaptiveDifficulty.initial(settings.minRating, settings.maxRating)
                    target = AdaptiveDifficulty.next(cur, solveMs, settings.minRating, settings.maxRating)
                }
            }
            if (reason == EndReason.EMERGENCY_EXIT && r.kind != RingKind.TEST_SEQUENCE) {
                stats = stats.copy(emergencyExits = stats.emergencyExits + System.currentTimeMillis(), streak = 0)
            }
            d.copy(stats = stats, adaptiveTarget = target)
        }
        if (counts && reason == EndReason.SOLVED && (alarm?.stillAwakeCheck ?: settings.stillAwakeCheck)) {
            AlarmScheduler.scheduleStillAwakePrompt(this)
        }
    }

    private fun recordEscape(reason: String) {
        if (!AppStore.current.settings.preventEscape) return
        val e = EscapeEvent(System.currentTimeMillis(), reason)
        AppStore.update(this) { it.copy(stats = it.stats.copy(escapes = it.stats.escapes + e, pendingEscapeWarning = e)) }
        AlarmScheduler.scheduleBedtime(this)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(screenReceiver) }
        local.stop()
        spotify?.stop()
        vibrator.stop()
        if (machine?.isDone == false) {
            // Killed while ringing: the ActiveRing record stays, so START_STICKY / boot / next launch resume it.
            Log.w(TAG, "Destroyed while ringing")
        } else {
            guard.end()
            RingController.publish(null)
        }
        partialLock?.let { if (it.isHeld) it.release() }
        RingController.service = null
        isRunning = false
        super.onDestroy()
    }
}
