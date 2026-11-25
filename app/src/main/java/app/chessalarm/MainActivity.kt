package app.chessalarm

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.chessalarm.alarm.AlarmScheduler
import app.chessalarm.alarm.AlarmTime
import app.chessalarm.audio.AlarmVibrator
import app.chessalarm.audio.AudioImport
import app.chessalarm.data.Alarm
import app.chessalarm.data.AppStore
import app.chessalarm.data.RingKind
import app.chessalarm.data.SoundChoice
import app.chessalarm.data.SoundType
import app.chessalarm.ring.AlarmService
import app.chessalarm.ring.RingController
import app.chessalarm.ring.RingingActivity
import app.chessalarm.spotify.SpotifyAccount
import app.chessalarm.spotify.SpotifyConfig
import app.chessalarm.ui.AlarmEditorScreen
import app.chessalarm.ui.AlarmListScreen
import app.chessalarm.ui.ChessAlarmTheme
import app.chessalarm.ui.OnboardingScreen
import app.chessalarm.ui.Palette
import app.chessalarm.ui.PuzzleGateScreen
import app.chessalarm.ui.SettingsScreen
import app.chessalarm.ui.SoundPickerScreen
import app.chessalarm.ui.SpotifySearchScreen
import app.chessalarm.ui.SpotifyStatus
import app.chessalarm.ui.StatsScreen
import app.chessalarm.ui.VideoImportScreen
import app.chessalarm.widget.NextAlarmWidget
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** Which sound a picker is choosing. */
enum class SoundTarget { ALARM, ALARM_SECOND, DEFAULT, DEFAULT_SECOND }

sealed interface Screen {
    data object List : Screen
    data class Edit(val isNew: Boolean) : Screen
    data class Sound(val target: SoundTarget) : Screen
    data class Spotify(val target: SoundTarget) : Screen
    data class Video(val target: SoundTarget) : Screen
    data object Settings : Screen
    data object Stats : Screen
    data object Onboarding : Screen
    data class Gate(val reason: String, val then: () -> Unit) : Screen
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TOGGLE_ALARM = "toggle_alarm"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.init(this)
        enableEdgeToEdge()
        val toggleId = intent.getLongExtra(EXTRA_TOGGLE_ALARM, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        setContent { ChessAlarmTheme { App(toggleId) } }
    }

    override fun onResume() {
        super.onResume()
        // While an alarm rings, the app opens on the ringing screen.
        if (RingController.state.value?.finished == null && (RingController.state.value != null || AppStore.activeRing(this) != null)) {
            startActivity(Intent(this, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    fun saveAlarm(alarm: Alarm) {
        AppStore.update(this) { d ->
            val exists = d.alarms.any { it.id == alarm.id }
            d.copy(alarms = if (exists) d.alarms.map { if (it.id == alarm.id) alarm else it } else d.alarms + alarm)
        }
        AlarmScheduler.rescheduleAll(this)
        val now = ZonedDateTime.now()
        AlarmTime.nextTrigger(alarm, now)?.let { toast("Next alarm in ${AlarmTime.formatUntil(now, it)}") }
    }

    fun deleteAlarm(alarm: Alarm) {
        AlarmScheduler.cancel(this, alarm.id)
        AppStore.update(this) { d -> d.copy(alarms = d.alarms.filter { it.id != alarm.id }) }
        AlarmScheduler.rescheduleAll(this)
    }

    fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Chess Alarm", text))
        toast("Copied")
    }
}

@Composable
private fun App(toggleFromWidget: Long?) {
    val activity = androidx.activity.compose.LocalActivity.current as MainActivity
    val data by AppStore.data.collectAsState()
    val scope = rememberCoroutineScope()
    val vibrator = remember { AlarmVibrator(activity) }
    var permVersion by remember { mutableIntStateOf(0) }
    val permissions = remember(permVersion) { Permissions.items(activity) }
    val permissionsOk = permissions.filter { it.required }.all { it.granted }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permVersion++ }

    val stack = remember {
        mutableStateListOf<Screen>(if (AppStore.current.settings.onboardingDone) Screen.List else Screen.Onboarding)
    }
    var draft by remember { mutableStateOf<Alarm?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    /** Runs [action], but behind a puzzle if [alarm] rings within 10 minutes (and the feature is on). */
    fun guarded(alarm: Alarm, what: String, action: () -> Unit) {
        val s = AppStore.current.settings
        if (s.preventEscape && alarm.enabled && AlarmTime.isWithin(alarm, ZonedDateTime.now(), 10 * 60_000L)) {
            push(Screen.Gate("This alarm rings in less than 10 minutes. Solve a puzzle to $what it.") { pop(); action() })
        } else action()
    }

    LaunchedEffect(toggleFromWidget) {
        val a = toggleFromWidget?.let { id -> AppStore.current.alarms.firstOrNull { it.id == id } } ?: return@LaunchedEffect
        guarded(a, "turn off") { activity.saveAlarm(a.copy(enabled = !a.enabled)) }
    }

    fun applySound(target: SoundTarget, choice: SoundChoice?) {
        when (target) {
            SoundTarget.ALARM -> draft = draft?.copy(sound = choice)
            SoundTarget.ALARM_SECOND -> draft = draft?.let { d -> d.copy(wake = d.wake.copy(secondarySound = choice)) }
            SoundTarget.DEFAULT -> if (choice != null) AppStore.update(activity) { it.copy(settings = it.settings.copy(defaultSound = choice)) }
            SoundTarget.DEFAULT_SECOND -> if (choice != null) AppStore.update(activity) {
                it.copy(settings = it.settings.copy(wake = it.settings.wake.copy(secondarySound = choice)))
            }
        }
    }

    fun currentSound(target: SoundTarget): SoundChoice? = when (target) {
        SoundTarget.ALARM -> draft?.sound
        SoundTarget.ALARM_SECOND -> draft?.wake?.secondarySound ?: data.settings.wake.secondarySound
        SoundTarget.DEFAULT -> data.settings.defaultSound
        SoundTarget.DEFAULT_SECOND -> data.settings.wake.secondarySound
    }

    // ----- launchers
    var pickTarget by remember { mutableStateOf(SoundTarget.ALARM) }
    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        @Suppress("DEPRECATION")
        val uri = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return@rememberLauncherForActivityResult
        val title = RingtoneManager.getRingtone(activity, uri)?.getTitle(activity) ?: "Phone sound"
        applySound(pickTarget, SoundChoice(SoundType.SYSTEM, uri.toString(), title, "Phone sound"))
        pop()
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { AudioImport.importAudioFile(activity, uri) }
                .onSuccess { applySound(pickTarget, it); pop() }
                .onFailure { activity.toast("Couldn't import that file") }
        }
    }
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) videoUri = uri }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permVersion++ }
    val spotifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        SpotifyAccount.handleResult(activity, r.resultCode, r.data)?.let { activity.toast(it) }
    }
    fun spotifyLogin() {
        val i = SpotifyAccount.loginIntent(activity)
        if (i == null) activity.toast("Add your Spotify client ID in Settings first") else spotifyLauncher.launch(i)
    }

    fun grant(kind: Permissions.Kind) {
        if (kind == Permissions.Kind.NOTIFICATIONS && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else Permissions.request(activity, kind)
    }

    BackHandler(stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = {
                val forward = stack.size > 1 && targetState == stack.last() && initialState in stack
                (slideInHorizontally(spring<IntOffset>(stiffness = 500f)) { if (forward) it else -it / 3 } + fadeIn())
                    .togetherWith(slideOutHorizontally(spring<IntOffset>(stiffness = 500f)) { if (forward) -it / 3 else it } + fadeOut())
            },
            label = "nav",
        ) { screen ->
            when (screen) {
                Screen.List -> AlarmListScreen(
                    data = data,
                    permissionsOk = permissionsOk,
                    onAdd = {
                        val now = java.time.LocalTime.now().plusMinutes(1)
                        draft = Alarm(id = System.currentTimeMillis(), hour = now.hour, minute = now.minute)
                        push(Screen.Edit(isNew = true))
                    },
                    onEdit = { a -> guarded(a, "change") { draft = a; push(Screen.Edit(isNew = false)) } },
                    onToggle = { a, on ->
                        if (!on) guarded(a, "turn off") { activity.saveAlarm(a.copy(enabled = false)) }
                        else activity.saveAlarm(a.copy(enabled = true))
                    },
                    onDelete = { a -> guarded(a, "delete") { activity.deleteAlarm(a) } },
                    onSettings = { push(Screen.Settings) },
                    onStats = { push(Screen.Stats) },
                    onFixPermissions = { push(Screen.Onboarding) },
                )
                is Screen.Edit -> draft?.let { d ->
                    AlarmEditorScreen(
                        draft = d,
                        isNew = screen.isNew,
                        settings = data.settings,
                        onChange = { draft = it },
                        onCancel = { pop() },
                        onSave = { activity.saveAlarm(d); pop() },
                        onDelete = { activity.deleteAlarm(d); pop() },
                        onPickSound = { push(Screen.Sound(SoundTarget.ALARM)) },
                        onPickSecondSound = { push(Screen.Sound(SoundTarget.ALARM_SECOND)) },
                        onPreviewVibration = { p, i -> vibrator.preview(p, i) },
                    )
                }
                is Screen.Sound -> SoundPickerScreen(
                    current = currentSound(screen.target),
                    allowSpotify = screen.target == SoundTarget.ALARM || screen.target == SoundTarget.DEFAULT,
                    allowDefault = screen.target == SoundTarget.ALARM,
                    defaultSound = data.settings.defaultSound,
                    onBack = { pop() },
                    onPicked = { applySound(screen.target, it); pop() },
                    onPickSystem = {
                        pickTarget = screen.target
                        val cur = currentSound(screen.target)?.takeIf { it.type == SoundType.SYSTEM }?.value?.let(Uri::parse)
                        ringtoneLauncher.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur),
                        )
                    },
                    onPickFile = { pickTarget = screen.target; fileLauncher.launch(arrayOf("audio/*")) },
                    onVideo = { videoUri = null; push(Screen.Video(screen.target)) },
                    onSpotify = { push(Screen.Spotify(screen.target)) },
                )
                is Screen.Spotify -> SpotifySearchScreen(
                    current = currentSound(screen.target)?.takeIf { it.type == SoundType.SPOTIFY },
                    loggedIn = data.spotifyToken != null,
                    hasClientId = SpotifyConfig.clientId(activity).isNotBlank(),
                    onLogin = ::spotifyLogin,
                    onBack = { pop() },
                    onPicked = { applySound(screen.target, it); pop(); pop() },
                )
                is Screen.Video -> VideoImportScreen(
                    video = videoUri,
                    onChooseVideo = { videoLauncher.launch(arrayOf("video/*")) },
                    onBack = { pop() },
                    onDone = { applySound(screen.target, it); activity.toast("Added “${it.title}”"); pop(); pop() },
                )
                Screen.Settings -> SettingsScreen(
                    settings = data.settings,
                    spotify = SpotifyStatus(data.spotifyToken != null, data.settings.spotifyClientId.ifBlank { BuildConfig.SPOTIFY_CLIENT_ID }, Permissions.signingSha1(activity)),
                    permissionsOk = permissionsOk,
                    onChange = { s ->
                        val widgetChanged = s.widgetEnabled != data.settings.widgetEnabled
                        AppStore.update(activity) { it.copy(settings = s) }
                        if (widgetChanged) NextAlarmWidget.setEnabled(activity, s.widgetEnabled)
                        AlarmScheduler.scheduleBedtime(activity)
                    },
                    onBack = { pop() },
                    onPickDefaultSound = { push(Screen.Sound(SoundTarget.DEFAULT)) },
                    onPickSecondSound = { push(Screen.Sound(SoundTarget.DEFAULT_SECOND)) },
                    onPreviewVibration = { p, i -> vibrator.preview(p, i) },
                    onTestAlarm = {
                        AlarmScheduler.scheduleTest(activity)
                        activity.toast("Test alarm in 10 seconds — lock your phone")
                    },
                    onTestSequence = { AlarmService.start(activity, AlarmScheduler.TEST_ALARM_ID, RingKind.TEST_SEQUENCE) },
                    onPermissions = { push(Screen.Onboarding) },
                    onSpotifyLogin = ::spotifyLogin,
                    onSpotifyLogout = { SpotifyAccount.logout(activity) },
                    onCopy = activity::copy,
                )
                Screen.Stats -> StatsScreen(data.stats, data.settings.statsEnabled) { pop() }
                Screen.Onboarding -> OnboardingScreen(
                    items = permissions,
                    first = !data.settings.onboardingDone,
                    onGrant = ::grant,
                    onDone = {
                        AppStore.update(activity) { it.copy(settings = it.settings.copy(onboardingDone = true)) }
                        if (stack.size > 1) pop() else { stack.clear(); stack.add(Screen.List) }
                    },
                )
                is Screen.Gate -> PuzzleGateScreen(screen.reason, onCancel = { pop() }, onSolved = screen.then)
            }
        }
    }
}
