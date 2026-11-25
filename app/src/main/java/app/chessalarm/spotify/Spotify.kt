package app.chessalarm.spotify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import app.chessalarm.BuildConfig
import app.chessalarm.data.AppStore
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.PlayerApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SpotifyConfig {
    /** Register exactly this in the Spotify Developer Dashboard (Redirect URIs). */
    const val REDIRECT_URI = "chessalarm://spotify-callback"

    fun clientId(context: Context): String {
        AppStore.init(context)
        return AppStore.current.settings.spotifyClientId.trim().ifBlank { BuildConfig.SPOTIFY_CLIENT_ID }
    }
}

/** Login (Auth SDK) and search (Spotify Web API). Only Spotify features use the network. */
object SpotifyAccount {
    private val SCOPES = arrayOf("app-remote-control", "user-read-private", "playlist-read-private")

    fun loginIntent(activity: Activity): Intent? {
        val id = SpotifyConfig.clientId(activity)
        if (id.isBlank()) return null
        val request = AuthorizationRequest.Builder(id, AuthorizationResponse.Type.TOKEN, SpotifyConfig.REDIRECT_URI)
            .setScopes(SCOPES)
            .build()
        return AuthorizationClient.createLoginActivityIntent(activity, request)
    }

    /** Returns null on success, or an error message. */
    fun handleResult(context: Context, resultCode: Int, data: Intent?): String? {
        val response = AuthorizationClient.getResponse(resultCode, data)
        return when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                AppStore.update(context) {
                    it.copy(
                        spotifyToken = response.accessToken,
                        spotifyTokenExpiresAt = System.currentTimeMillis() + response.expiresIn * 1000L,
                    )
                }
                null
            }
            AuthorizationResponse.Type.ERROR -> response.error ?: "Spotify login failed"
            else -> "Spotify login cancelled"
        }
    }

    fun isLoggedIn(): Boolean = AppStore.current.spotifyToken != null

    fun tokenValid(): Boolean =
        AppStore.current.spotifyToken != null && System.currentTimeMillis() < AppStore.current.spotifyTokenExpiresAt - 60_000

    fun logout(context: Context) {
        AppStore.update(context) { it.copy(spotifyToken = null, spotifyTokenExpiresAt = 0, spotifyUser = null) }
    }

    data class Item(
        val uri: String,
        val title: String,
        val subtitle: String,
        val imageUrl: String?,
        val kind: String,
    )

    class TokenExpired : Exception("Spotify session expired")

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(path: String): JsonObject = withContext(Dispatchers.IO) {
        val token = AppStore.current.spotifyToken ?: throw TokenExpired()
        val conn = URL("https://api.spotify.com/v1/$path").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode == 401) throw TokenExpired()
            if (conn.responseCode !in 200..299) throw Exception("Spotify error ${conn.responseCode}")
            json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonElement?.str(key: String): String? =
        (this as? JsonObject)?.get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun image(obj: JsonObject?): String? = runCatching {
        val imgs = obj?.get("images")?.jsonArray ?: return null
        // Prefer a mid-sized image.
        (imgs.getOrNull(1) ?: imgs.firstOrNull()).str("url")
    }.getOrNull()

    suspend fun search(query: String): List<Item> {
        val q = URLEncoder.encode(query, "UTF-8")
        val root = get("search?q=$q&type=track,album,playlist&limit=8")
        val out = mutableListOf<Item>()
        root["tracks"]?.jsonObject?.get("items")?.jsonArray?.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val artists = o["artists"]?.jsonArray?.mapNotNull { it.str("name") }?.joinToString(", ") ?: ""
            out += Item(o.str("uri") ?: return@forEach, o.str("name") ?: "", "Song · $artists", image(o["album"] as? JsonObject), "track")
        }
        root["albums"]?.jsonObject?.get("items")?.jsonArray?.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val artists = o["artists"]?.jsonArray?.mapNotNull { it.str("name") }?.joinToString(", ") ?: ""
            out += Item(o.str("uri") ?: return@forEach, o.str("name") ?: "", "Album · $artists", image(o), "album")
        }
        root["playlists"]?.jsonObject?.get("items")?.jsonArray?.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val owner = (o["owner"] as? JsonObject).str("display_name") ?: ""
            out += Item(o.str("uri") ?: return@forEach, o.str("name") ?: "", "Playlist · $owner", image(o), "playlist")
        }
        // Interleave so each type shows near the top.
        return out.groupBy { it.kind }.values.let { groups ->
            val max = groups.maxOfOrNull { it.size } ?: 0
            (0 until max).flatMap { i -> groups.mapNotNull { it.getOrNull(i) } }
        }
    }

    suspend fun myPlaylists(): List<Item> {
        val root = get("me/playlists?limit=30")
        return root["items"]?.jsonArray?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Item(o.str("uri") ?: return@mapNotNull null, o.str("name") ?: "", "Your playlist", image(o), "playlist")
        } ?: emptyList()
    }
}

/** Tiny in-memory cover-art loader (avoids pulling in an image library). */
object CoverArt {
    private val cache = LruCache<String, Bitmap>(40)

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.inputStream.use { BitmapFactory.decodeStream(it) }?.also { cache.put(url, it) }
            }.getOrNull()
        }
    }
}

/**
 * Plays a Spotify URI on the ALARM stream through App Remote, and reports failure (not installed,
 * not logged in, offline, no start within the timeout, or paused by someone else) so the
 * service can switch to the fallback sound.
 */
class SpotifyPlayer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var remote: SpotifyAppRemote? = null
    private var failed = false
    private var started = false
    private var wantPlaying = true
    private var paused = false
    private var onFailure: ((String) -> Unit)? = null

    val isActive: Boolean get() = !failed

    fun start(uri: String, shuffle: Boolean, timeoutMs: Long = 5_000, onFailure: (String) -> Unit) {
        this.onFailure = onFailure
        val id = SpotifyConfig.clientId(context)
        if (id.isBlank()) return fail("No Spotify client ID")
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) return fail("Spotify not installed")
        main.postDelayed({ if (!started) fail("Spotify didn't start within ${timeoutMs / 1000} s") }, timeoutMs)
        val params = ConnectionParams.Builder(id)
            .setRedirectUri(SpotifyConfig.REDIRECT_URI)
            .showAuthView(false)
            .build()
        try {
            SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    if (failed) { SpotifyAppRemote.disconnect(appRemote); return }
                    remote = appRemote
                    val player = appRemote.playerApi
                    player.setShuffle(shuffle)
                    player.play(uri, PlayerApi.StreamType.ALARM).setErrorCallback { fail("Play failed: ${it.message}") }
                    player.subscribeToPlayerState().setEventCallback { state ->
                        if (failed) return@setEventCallback
                        paused = state.isPaused
                        if (!state.isPaused && state.track != null) started = true
                        if (!started) return@setEventCallback
                        if (state.isPaused && wantPlaying) fail("Spotify was paused or stopped")
                        else if (!state.isPaused && !wantPlaying) player.pause()
                    }
                }

                override fun onFailure(error: Throwable) = fail("Spotify connection failed: ${error.message}")
            })
        } catch (e: Exception) {
            fail("Spotify error: ${e.message}")
        }
    }

    private fun fail(reason: String) {
        main.post {
            if (failed) return@post
            failed = true
            Log.w("SpotifyPlayer", reason)
            stop()
            onFailure?.invoke(reason)
        }
    }

    /** Silences Spotify on purpose (solving phase / mute) without triggering the fallback. */
    fun pause() {
        if (failed || !wantPlaying) return
        wantPlaying = false
        if (started && !paused) remote?.playerApi?.pause()
    }

    fun resume() {
        if (failed || wantPlaying) return
        wantPlaying = true
        if (started && paused) remote?.playerApi?.resume()
    }

    fun stop() {
        wantPlaying = false
        remote?.let {
            runCatching { it.playerApi.pause() }
            SpotifyAppRemote.disconnect(it)
        }
        remote = null
    }
}
