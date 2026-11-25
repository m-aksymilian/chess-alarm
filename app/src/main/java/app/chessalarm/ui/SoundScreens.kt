package app.chessalarm.ui

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.chessalarm.audio.AudioImport
import app.chessalarm.audio.SoundPreview
import app.chessalarm.data.BuiltInTunes
import app.chessalarm.data.SoundChoice
import app.chessalarm.data.SoundType
import app.chessalarm.spotify.CoverArt
import app.chessalarm.spotify.SpotifyAccount
import kotlinx.coroutines.launch
import java.io.File

/**
 * Sound source picker: built-in tunes, phone sounds, an audio file, audio from a video, imported
 * sounds and (for the main alarm sound) Spotify.
 */
@Composable
fun SoundPickerScreen(
    current: SoundChoice?,
    allowSpotify: Boolean,
    allowDefault: Boolean,
    defaultSound: SoundChoice,
    onBack: () -> Unit,
    onPicked: (SoundChoice?) -> Unit,
    onPickSystem: () -> Unit,
    onPickFile: () -> Unit,
    onVideo: () -> Unit,
    onSpotify: () -> Unit,
) {
    val context = LocalContext.current
    var importedVersion by remember { mutableStateOf(0) }
    val imported = remember(importedVersion) { AudioImport.imported(context) }
    DisposableEffect(Unit) { onDispose { SoundPreview.stop() } }

    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding()) {
        NavBar("Sound", "Back", onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (allowDefault) Group {
                SoundRow("Default", defaultSound.title, current == null, divider = false, onPreview = null) { onPicked(null) }
            }
            if (current?.type == SoundType.SPOTIFY) Group("Spotify") {
                SoundRow(current.title, current.subtitle, true, divider = false, onPreview = null) { onSpotify() }
            }
            Group("Chess Alarm tunes") {
                BuiltInTunes.all.forEachIndexed { i, t ->
                    val c = BuiltInTunes.choice(t)
                    SoundRow(t.title, t.mood, current?.type == SoundType.BUILT_IN && current.value == t.res,
                        divider = i < BuiltInTunes.all.lastIndex, onPreview = { SoundPreview.play(context, c) }) { onPicked(c) }
                }
            }
            Group("More sounds") {
                if (allowSpotify) GroupRow("Spotify", subtitle = "Song, album or playlist", icon = Icons.Spotify, chevron = true, onClick = onSpotify)
                GroupRow("Phone sounds", subtitle = if (current?.type == SoundType.SYSTEM) current.title else "Alarm sounds and ringtones",
                    icon = Icons.Phone, chevron = true, onClick = onPickSystem)
                GroupRow("Audio file", subtitle = "Choose a file from your phone", icon = Icons.Folder, chevron = true, onClick = onPickFile)
                GroupRow("From a video", subtitle = "Extract the audio from an MP4", icon = Icons.Film, chevron = true, divider = false, onClick = onVideo)
            }
            if (imported.isNotEmpty()) Group("Imported") {
                imported.forEachIndexed { i, c ->
                    SoundRow(c.title, c.subtitle, current?.type == SoundType.FILE && current.value == c.value,
                        divider = i < imported.lastIndex, onPreview = { SoundPreview.play(context, c) },
                        onDelete = { File(c.value).delete(); importedVersion++ }) { onPicked(c) }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun SoundRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    divider: Boolean,
    onPreview: (() -> Unit)?,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    GroupRow(title, subtitle = subtitle.ifBlank { null }, divider = divider, onClick = onClick, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) {
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
                    Icon(Icons.Trash, "Delete", tint = Palette.Secondary, modifier = Modifier.size(18.dp))
                }
            }
            if (onPreview != null) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.Elevated).clickable(onClick = onPreview), contentAlignment = Alignment.Center) {
                    Icon(Icons.Play, "Preview", tint = Palette.Text, modifier = Modifier.size(16.dp))
                }
            }
            Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterEnd) {
                if (selected) Icon(Icons.Check, null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
            }
        }
    })
}

// ---------------------------------------------------------------------------------------- Spotify

@Composable
fun SpotifySearchScreen(
    current: SoundChoice?,
    loggedIn: Boolean,
    hasClientId: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onPicked: (SoundChoice) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SpotifyAccount.Item>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<SpotifyAccount.Item?>(null) }
    var shuffle by remember { mutableStateOf(current?.shuffle ?: true) }

    fun run(block: suspend () -> List<SpotifyAccount.Item>) {
        scope.launch {
            loading = true
            error = null
            try {
                results = block()
            } catch (e: SpotifyAccount.TokenExpired) {
                error = "Your Spotify session expired. Log in again."
            } catch (e: Exception) {
                error = "Couldn't reach Spotify. Check your connection."
            }
            loading = false
        }
    }

    LaunchedEffect(loggedIn) { if (loggedIn && SpotifyAccount.tokenValid()) run { SpotifyAccount.myPlaylists() } }

    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding().imePadding()) {
        NavBar("Spotify", "Back", onBack, if (selected != null) "Done" else null, {
            selected?.let { s ->
                onPicked(SoundChoice(SoundType.SPOTIFY, s.uri, s.title, s.subtitle, s.imageUrl, shuffle && s.kind != "track"))
            }
        })
        if (!hasClientId) {
            Banner("Spotify isn't set up", "Add your Spotify client ID in Settings › Spotify first.", Palette.Accent, null)
            return@Column
        }
        if (!loggedIn || !SpotifyAccount.tokenValid()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Spotify, null, tint = Palette.Accent, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(16.dp))
                Text(if (loggedIn) "Your Spotify session expired." else "Log in to Spotify to search for music.", style = Type.Secondary)
                Spacer(Modifier.height(20.dp))
                PillButton("Log in with Spotify", primary = true, onClick = onLogin)
            }
            return@Column
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(Palette.Grouped).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Search, null, tint = Palette.Secondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = Type.Body,
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) run { SpotifyAccount.search(query) } }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Songs, albums, playlists", style = Type.Body.copy(color = Palette.Tertiary))
                    inner()
                },
            )
        }
        selected?.let { s ->
            if (s.kind != "track") Group {
                SwitchRow("Shuffle", shuffle, divider = false) { shuffle = it }
            }
        }
        if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Palette.Accent)
        }
        error?.let { Banner("Spotify", it, Palette.Danger, if (it.contains("Log in")) onLogin else null) }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (query.isBlank() && results.isNotEmpty()) SectionHeader("Your playlists")
            Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Grouped)) {
                results.forEachIndexed { i, item ->
                    val isSel = selected?.uri == item.uri || (selected == null && current?.value == item.uri)
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = item }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(item.imageUrl, round = item.kind == "artist")
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = Type.Body, maxLines = 1)
                            Text(item.subtitle, style = Type.Footnote, maxLines = 1)
                        }
                        if (isSel) Icon(Icons.Check, null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
                    }
                    if (i < results.lastIndex) GroupDivider()
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun Cover(url: String?, round: Boolean) {
    val bmp by produceState<Bitmap?>(null, url) { value = url?.let { CoverArt.load(it) } }
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    Box(Modifier.size(52.dp).clip(shape).background(Palette.Elevated), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Music, null, tint = Palette.Secondary, modifier = Modifier.size(22.dp))
    }
}

// ------------------------------------------------------------------------------------------ video

@Composable
fun VideoImportScreen(
    video: Uri?,
    onChooseVideo: () -> Unit,
    onBack: () -> Unit,
    onDone: (SoundChoice) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val duration = remember(video) { video?.let { AudioImport.durationMs(context, it) } ?: 0L }
    var start by remember(video) { mutableFloatStateOf(0f) }
    var title by remember(video) { mutableStateOf(video?.let { AudioImport.displayName(context, it) } ?: "") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val preview = remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPreview() {
        preview.value?.runCatching { stop(); release() }
        preview.value = null
    }
    DisposableEffect(Unit) { onDispose { stopPreview() } }

    Column(Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding().imePadding()) {
        NavBar("From a video", "Back", { stopPreview(); onBack() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Group(footer = "The audio track is copied without re-encoding, starting at the point you choose (up to 10 minutes).") {
                GroupRow("Video", value = if (video == null) "Choose…" else title, chevron = true, onClick = onChooseVideo, divider = video != null)
                if (video != null) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Name", style = Type.Body)
                        Spacer(Modifier.width(16.dp))
                        BasicTextField(title, { title = it.take(40) }, singleLine = true, textStyle = Type.Body.copy(color = Palette.Secondary),
                            cursorBrush = SolidColor(Palette.Accent), modifier = Modifier.weight(1f))
                    }
                    GroupDivider()
                    SliderRow("Start at", start, 0f..(duration.coerceAtLeast(1000).toFloat()), formatMs(start.toLong()), divider = true) {
                        start = it
                    }
                    GroupRow("Preview from here", icon = Icons.Play, divider = false, onClick = {
                        stopPreview()
                        runCatching {
                            preview.value = MediaPlayer().apply {
                                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                                setDataSource(context, video)
                                prepare()
                                seekTo(start.toInt())
                                start()
                            }
                        }
                    })
                }
            }
            error?.let { Banner("Couldn't extract audio", it, Palette.Danger, null) }
            if (video != null) {
                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    if (working) CircularProgressIndicator(color = Palette.Accent)
                    else PillButton("Extract audio", Modifier.fillMaxWidth(), primary = true) {
                        stopPreview()
                        working = true
                        error = null
                        scope.launch {
                            try {
                                onDone(AudioImport.extractFromVideo(context, video, start.toLong(), title.ifBlank { "Video sound" }))
                            } catch (e: Exception) {
                                error = e.message ?: "Unknown error"
                            }
                            working = false
                        }
                    }
                }
            }
        }
    }
}

fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
