package cz.vyvrnc.transcribronpu.llm

import android.content.Context
import androidx.annotation.StringRes
import dev.soupslurpr.transcribro.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Gemma 4 E2B (instruction tuned) in LiteRT-LM format, Apache 2.0, from the litert-community
 * repository on Hugging Face. Downloaded on the phone only when text cleanup is wanted. Two
 * builds: the generic one runs on the GPU (or CPU), the SM8750 one only on that NPU.
 */
enum class LlmModel(
    @StringRes private val labelRes: Int,
    private val fileName: String,
    val size: Long,
    private val sha256: String,
) {
    GPU(
        R.string.llm_model_label_gpu,
        "gemma-4-E2B-it.litertlm",
        2_588_147_712L,
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    ),
    NPU(
        R.string.llm_model_label_npu,
        "gemma-4-E2B-it_qualcomm_sm8750.litertlm",
        3_016_294_400L,
        "41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e",
    );

    /** Display name of the build, in the app language. */
    fun label(context: Context): String = context.getString(labelRes)

    private val url get() =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$fileName"

    private val lock = Mutex()

    fun file(context: Context): File =
        File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), fileName)

    /** The file is renamed into place only after its SHA-256 matched, so the size suffices here. */
    fun ready(context: Context): Boolean = file(context).length() == size

    /** Resumable ("<file>.part"), checked against the repository's SHA-256. */
    suspend fun download(context: Context, onProgress: (phase: String, done: Long, total: Long) -> Unit) = lock.withLock {
        val target = file(context)
        if (target.length() == size) return@withLock
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        if (part.length() > size) part.delete()
        while (part.length() < size) {
            val from = part.length()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            try {
                val append = when (conn.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> true
                    HttpURLConnection.HTTP_OK -> false   // range ignored: start over
                    else -> error("HTTP ${conn.responseCode} ${conn.responseMessage}")
                }
                var got = 0L
                FileOutputStream(part, append).use { out ->
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
                                onProgress(context.getString(R.string.llm_phase_downloading), part.length(), size)
                            }
                        }
                    }
                }
                if (got == 0L) error(context.getString(R.string.llm_error_no_data))
            } finally {
                conn.disconnect()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            var done = 0L
            var lastReport = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                done += n
                if (done - lastReport > (64 shl 20)) {
                    lastReport = done
                    onProgress(context.getString(R.string.llm_phase_verifying), done, size)
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != sha256) {
            part.delete()
            error(context.getString(R.string.llm_error_checksum))
        }
        if (!part.renameTo(target)) error(context.getString(R.string.llm_error_rename, part.name))
    }
}
