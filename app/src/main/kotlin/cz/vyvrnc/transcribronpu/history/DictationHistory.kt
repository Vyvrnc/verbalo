package cz.vyvrnc.transcribronpu.history

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved dictations: the recording (16 kHz mono WAV), the Whisper transcript and, for the
 * floating button with cleanup, the edited text. Kept only in the app's private files, so the
 * user can go back to a dictation and later use the pairs to tune the models. One
 * "<id>.json" per dictation plus "<id>.wav"; the oldest are deleted beyond [MAX_ENTRIES].
 */
object DictationHistory {
    private const val MAX_ENTRIES = 300
    const val SAMPLE_RATE = 16000

    data class Entry(
        val id: String,
        val time: Long,
        val source: String,
        val language: String,
        val durationSec: Double,
        val raw: String,
        val cleaned: String?,
        val cleanupModel: String?,
        val targetApp: String?,
        val cleanupLoadMs: Long?,
        val cleanupGenerateMs: Long?,
        val audio: File?,
    )

    private val _version = MutableStateFlow(0)
    /** Bumped on every change, for the history screen to reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val idFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)

    fun dir(context: Context) = File(context.applicationContext.filesDir, "history").also { it.mkdirs() }
    private fun json(context: Context, id: String) = File(dir(context), "$id.json")
    fun audio(context: Context, id: String) = File(dir(context), "$id.wav")

    fun newId(): String = synchronized(idFormat) { idFormat.format(Date()) }

    /**
     * Writes 16-bit PCM to "<id>.wav" while recording. The header is filled in by [finish];
     * a recorder that is [discard]ed leaves nothing behind.
     */
    class Recorder(context: Context, val id: String) {
        private val file = File(dir(context), "$id.wav.part")
        private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
        private val target = audio(context, id)
        private var samples = 0L
        private var closed = false

        @Synchronized
        fun write(buffer: ShortArray, count: Int) {
            if (closed || count <= 0) return
            val bytes = ByteArray(count * 2)
            for (i in 0 until count) {
                val s = buffer[i].toInt()
                bytes[2 * i] = s.toByte()
                bytes[2 * i + 1] = (s shr 8).toByte()
            }
            // Only what reached the file counts, so the header never claims more data.
            if (runCatching { out.write(bytes) }.isSuccess) samples += count
        }

        val durationSec: Double get() = samples.toDouble() / SAMPLE_RATE

        @Synchronized
        fun finish(): Boolean {
            if (closed) return target.exists()
            closed = true
            val ok = runCatching {
                out.seek(0)
                out.write(wavHeader(samples * 2))
            }.isSuccess
            runCatching { out.close() }
            if (ok && file.renameTo(target)) return true
            file.delete()
            return false
        }

        @Synchronized
        fun discard() {
            if (closed) return
            closed = true
            runCatching { out.close() }
            file.delete()
        }
    }

    fun saveTranscript(context: Context, id: String, source: String, language: String, durationSec: Double, raw: String) {
        val o = JSONObject()
            .put("id", id)
            .put("time", System.currentTimeMillis())
            .put("source", source)
            .put("language", language)
            .put("durationSec", durationSec)
            .put("raw", raw)
        writeJson(context, id, o)
        prune(context)
    }

    /** Read, update and write under one lock, so a delete in between cannot be undone. */
    @Synchronized
    fun saveCleanup(context: Context, id: String, cleaned: String, model: String, targetApp: String?, loadMs: Long, generateMs: Long) {
        val f = json(context, id)
        if (!f.exists()) return
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        o.put("cleaned", cleaned).put("cleanupModel", model).put("cleanupLoadMs", loadMs).put("cleanupGenerateMs", generateMs)
        if (targetApp != null) o.put("targetApp", targetApp)
        writeJson(context, id, o)
    }

    @Synchronized
    private fun writeJson(context: Context, id: String, o: JSONObject) {
        val f = json(context, id)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString(2))
        tmp.renameTo(f)
        _version.value++
    }

    fun list(context: Context): List<Entry> =
        (dir(context).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { parse(context, JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.time }

    private fun parse(context: Context, o: JSONObject): Entry {
        val id = o.getString("id")
        fun optLong(k: String) = if (o.has(k)) o.getLong(k) else null
        fun optStr(k: String) = if (o.has(k)) o.getString(k) else null
        return Entry(
            id = id,
            time = o.getLong("time"),
            source = o.optString("source"),
            language = o.optString("language"),
            durationSec = o.optDouble("durationSec", 0.0),
            raw = o.optString("raw"),
            cleaned = optStr("cleaned"),
            cleanupModel = optStr("cleanupModel"),
            targetApp = optStr("targetApp"),
            cleanupLoadMs = optLong("cleanupLoadMs"),
            cleanupGenerateMs = optLong("cleanupGenerateMs"),
            audio = audio(context, id).takeIf { it.exists() },
        )
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        json(context, id).delete()
        audio(context, id).delete()
        _version.value++
    }

    /** Keeps the recording of a dictation still in progress (".wav.part"). */
    @Synchronized
    fun deleteAll(context: Context) {
        dir(context).listFiles { f -> !f.name.endsWith(".wav.part") }?.forEach { it.delete() }
        _version.value++
    }

    @Synchronized
    private fun prune(context: Context) {
        val all = dir(context).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: return
        all.dropLast(MAX_ENTRIES).forEach { f ->
            val id = f.name.removeSuffix(".json")
            f.delete()
            audio(context, id).delete()
        }
        // Recordings of sessions that never got a transcript (killed process).
        dir(context).listFiles { f -> f.name.endsWith(".wav.part") && System.currentTimeMillis() - f.lastModified() > 3_600_000 }
            ?.forEach { it.delete() }
    }

    private fun wavHeader(dataBytes: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }
}
