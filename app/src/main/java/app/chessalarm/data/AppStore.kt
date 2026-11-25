package app.chessalarm.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Single source of truth for all persisted state. Files live in device-protected storage so the
 * alarm can be rescheduled and can ring after a reboot before the user unlocks the phone.
 * Writes are atomic (write to temp file, then rename).
 */
object AppStore {
    private const val TAG = "AppStore"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private lateinit var dir: File
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val dp = storageContext(context)
            dir = dp.filesDir
            _data.value = read("state.json", AppData.serializer()) ?: AppData()
            initialized = true
        }
    }

    /** Device-protected storage context (usable before first unlock). */
    fun storageContext(context: Context): Context =
        context.applicationContext.createDeviceProtectedStorageContext()

    fun filesDir(context: Context): File {
        init(context)
        return dir
    }

    val current: AppData get() = _data.value

    fun update(context: Context, transform: (AppData) -> AppData): AppData {
        init(context)
        synchronized(lock) {
            val next = transform(_data.value)
            if (next != _data.value) {
                write("state.json", AppData.serializer(), next)
                _data.value = next
            }
            return next
        }
    }

    // ------------------------------------------------------------------ active ring (separate file)

    fun activeRing(context: Context): ActiveRing? {
        init(context)
        synchronized(lock) { return read("ring.json", ActiveRing.serializer()) }
    }

    fun setActiveRing(context: Context, ring: ActiveRing?) {
        init(context)
        synchronized(lock) {
            if (ring == null) File(dir, "ring.json").delete() else write("ring.json", ActiveRing.serializer(), ring)
        }
    }

    // ----------------------------------------------------------------------------------- helpers

    private fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try {
            json.decodeFromString(serializer, f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Could not read $name", e)
            null
        }
    }

    private fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(File(dir, name))) {
            File(dir, name).writeText(json.encodeToString(serializer, value))
            tmp.delete()
        }
    }
}
