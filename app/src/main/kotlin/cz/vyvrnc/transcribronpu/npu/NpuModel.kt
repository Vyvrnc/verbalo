package cz.vyvrnc.transcribronpu.npu

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Whisper large-v3-turbo for the NPU: Qualcomm AI Hub QNN context binaries (float16,
 * Snapdragon 8 Elite for Galaxy = SM8750). Downloaded straight from Qualcomm on the phone;
 * the files are under Qualcomm's AI Hub model licence and are not redistributed by us.
 * Same download as WhisperBench (ModelManager.downloadNpu).
 */
object NpuModel {
    const val NAME = "Qualcomm AI Hub whisper_large_v3_turbo v0.64.0, float16"
    private const val ZIP_URL = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/" +
        "models/whisper_large_v3_turbo/releases/v0.64.0/" +
        "whisper_large_v3_turbo-qnn_context_binary-float-qualcomm_snapdragon_8_elite_for_galaxy.zip"

    private data class Entry(val name: String, val dataOffset: Long, val compressedSize: Long, val size: Long, val crc32: Long)

    // Offsets of the deflated data inside that zip (local header + name already skipped).
    private val ENTRIES = listOf(
        Entry("decoder.bin", 245L, 415_825_928L, 452_445_696L, 1_189_458_723L),
        Entry("encoder.bin", 415_827_161L, 1_602_319_791L, 1_752_705_784L, 840_869_209L),
    )
    val DOWNLOAD_BYTES = ENTRIES.sumOf { it.compressedSize }

    /** A cancelled download's blocking read ends later; a new one must not share its .part. */
    private val lock = Mutex()

    /** App-specific external storage, so the files can also be pushed with adb. */
    fun dir(context: Context): File =
        File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), "npu-whisper-large-v3-turbo")

    fun encoder(context: Context) = File(dir(context), "encoder.bin")
    fun decoder(context: Context) = File(dir(context), "decoder.bin")

    fun ready(context: Context): Boolean = ENTRIES.all { File(dir(context), it.name).length() == it.size }

    /**
     * Fetches only the two deflated entries of Qualcomm's zip by HTTP Range (resumable,
     * "<name>.deflate.part"), inflates them and checks size + CRC-32.
     */
    suspend fun download(context: Context, onProgress: (phase: String, done: Long, total: Long) -> Unit) =
        lock.withLock { downloadLocked(dir(context).apply { mkdirs() }, onProgress) }

    private suspend fun downloadLocked(dir: File, onProgress: (String, Long, Long) -> Unit) {
        var before = 0L
        for (e in ENTRIES) {
            val target = File(dir, e.name)
            if (target.length() == e.size) { before += e.compressedSize; continue }
            val packed = File(dir, e.name + ".deflate.part")
            if (packed.length() > e.compressedSize) packed.delete()
            while (packed.length() < e.compressedSize) {
                val from = e.dataOffset + packed.length()
                val conn = (URL(ZIP_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("Range", "bytes=$from-${e.dataOffset + e.compressedSize - 1}")
                }
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                        error("HTTP ${conn.responseCode} ${conn.responseMessage} (čekáno 206)")
                    }
                    var got = 0L
                    FileOutputStream(packed, true).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(1 shl 16)
                            var lastReport = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()   // cancellable, keeps .part
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                if (got - lastReport > (1 shl 20)) {
                                    lastReport = got
                                    onProgress("Stahuji ${e.name}", before + packed.length(), DOWNLOAD_BYTES)
                                }
                            }
                        }
                    }
                    if (got == 0L) error("Server neposlal žádná data")
                } finally {
                    conn.disconnect()
                }
            }
            val tmp = File(dir, e.name + ".part")
            val crc = CRC32()
            var written = 0L
            try {
                InflaterInputStream(packed.inputStream().buffered(1 shl 20), Inflater(true), 1 shl 16).use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            crc.update(buf, 0, n)
                            written += n
                            if (written - lastReport > (16 shl 20)) {
                                lastReport = written
                                onProgress("Rozbaluji ${e.name}", written, e.size)
                            }
                        }
                    }
                }
            } catch (bad: java.io.IOException) {
                // Corrupt deflate data would fail the same way on every retry; other errors
                // (disk full) keep the download for resume.
                if (bad is java.util.zip.ZipException || bad is java.io.EOFException) {
                    tmp.delete()
                    packed.delete()
                    error("${e.name}: poškozená data (${bad.message}); stáhne se znovu")
                }
                throw bad
            }
            if (written != e.size || crc.value != e.crc32) {
                tmp.delete()
                packed.delete()
                error("${e.name}: kontrola selhala (velikost $written / ${e.size}, CRC ${crc.value} / ${e.crc32}); stáhne se znovu")
            }
            if (!tmp.renameTo(target)) error("Nelze přejmenovat ${tmp.name}")
            packed.delete()
            before += e.compressedSize
        }
    }
}
