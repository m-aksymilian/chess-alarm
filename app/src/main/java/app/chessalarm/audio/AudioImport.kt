package app.chessalarm.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import app.chessalarm.data.AppStore
import app.chessalarm.data.SoundChoice
import app.chessalarm.data.SoundType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Imported sounds live in device-protected app storage, so they can play even before the phone
 * is unlocked after a reboot.
 */
object AudioImport {
    fun soundsDir(context: Context): File = File(AppStore.filesDir(context), "sounds").apply { mkdirs() }

    fun imported(context: Context): List<SoundChoice> =
        soundsDir(context).listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedByDescending { it.lastModified() }?.map {
            SoundChoice(SoundType.FILE, it.absolutePath, it.nameWithoutExtension, "Imported")
        } ?: emptyList()

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.')
        }
        return "Sound"
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(60).ifBlank { "sound" }

    private fun uniqueFile(context: Context, base: String, ext: String): File {
        var f = File(soundsDir(context), "${safeName(base)}.$ext")
        var i = 2
        while (f.exists()) f = File(soundsDir(context), "${safeName(base)} $i.$ext").also { i++ }
        return f
    }

    /** Copies an audio file picked with the system picker into app storage. */
    suspend fun importAudioFile(context: Context, uri: Uri): SoundChoice = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val ext = context.contentResolver.getType(uri)?.substringAfter('/')?.let {
            when (it) { "mpeg" -> "mp3"; "mp4", "x-m4a", "aac" -> "m4a"; "ogg" -> "ogg"; "wav", "x-wav" -> "wav"; "flac" -> "flac"; else -> "audio" }
        } ?: "audio"
        val out = uniqueFile(context, name, ext)
        context.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        SoundChoice(SoundType.FILE, out.absolutePath, out.nameWithoutExtension, "Imported")
    }

    fun durationMs(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever() // not AutoCloseable before API 29
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    class UnsupportedAudio(msg: String) : Exception(msg)

    /**
     * Extracts the audio track of a video (no re-encoding) with MediaExtractor + MediaMuxer,
     * starting at [startMs]. AAC becomes .m4a, Opus/Vorbis .webm, MP3 is written as raw .mp3 frames.
     */
    suspend fun extractFromVideo(context: Context, uri: Uri, startMs: Long, title: String, maxMs: Long = 10 * 60_000L): SoundChoice =
        withContext(Dispatchers.IO) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                val track = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw UnsupportedAudio("This video has no audio track.")
                val format = ex.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                ex.selectTrack(track)
                val startUs = startMs * 1000
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val endUs = startUs + maxMs * 1000
                val buf = ByteBuffer.allocate(1 shl 20)
                val info = MediaCodec.BufferInfo()

                if (mime == MediaFormat.MIMETYPE_AUDIO_MPEG) {
                    val out = uniqueFile(context, title, "mp3")
                    FileOutputStream(out).use { fos ->
                        while (true) {
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0 || ex.sampleTime > endUs) break
                            if (ex.sampleTime >= startUs) fos.write(buf.array(), buf.arrayOffset(), n)
                            buf.clear()
                            ex.advance()
                        }
                    }
                    return@withContext SoundChoice(SoundType.FILE, out.absolutePath, out.nameWithoutExtension, "From video")
                }

                val (container, ext) = when (mime) {
                    MediaFormat.MIMETYPE_AUDIO_AAC -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 to "m4a"
                    MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM to "webm"
                    else -> throw UnsupportedAudio("This video's audio format ($mime) can't be extracted without re-encoding. Try an MP4 video.")
                }
                val out = uniqueFile(context, title, ext)
                val muxer = MediaMuxer(out.absolutePath, container)
                try {
                    val dst = muxer.addTrack(format)
                    muxer.start()
                    var first = -1L
                    while (true) {
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        val t = ex.sampleTime
                        if (t > endUs) break
                        if (t >= startUs) {
                            if (first < 0) first = t
                            info.set(0, n, t - first, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            muxer.writeSampleData(dst, buf, info)
                        }
                        buf.clear()
                        ex.advance()
                    }
                    muxer.stop()
                } finally {
                    runCatching { muxer.release() }
                }
                if (out.length() == 0L) {
                    out.delete()
                    throw UnsupportedAudio("No audio after the chosen start point.")
                }
                SoundChoice(SoundType.FILE, out.absolutePath, out.nameWithoutExtension, "From video")
            } finally {
                ex.release()
            }
        }
}
