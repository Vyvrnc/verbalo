package cz.vyvrnc.transcribronpu.llm

import android.content.Context
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
 * repository on Hugging Face. Downloaded on the phone only when text cleanup is wanted.
 */
object LlmModel {
    const val NAME = "Gemma 4 E2B it (LiteRT-LM, litert-community)"
    private const val URL_FILE =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    const val SIZE = 2_588_147_712L
    private const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    private val lock = Mutex()

    fun file(context: Context): File =
        File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), "gemma-4-E2B-it.litertlm")

    /** The file is renamed into place only after its SHA-256 matched, so the size suffices here. */
    fun ready(context: Context): Boolean = file(context).length() == SIZE

    /** Resumable ("<file>.part"), checked against the repository's SHA-256. */
    suspend fun download(context: Context, onProgress: (phase: String, done: Long, total: Long) -> Unit) = lock.withLock {
        val target = file(context)
        if (target.length() == SIZE) return@withLock
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        if (part.length() > SIZE) part.delete()
        while (part.length() < SIZE) {
            val from = part.length()
            val conn = (URL(URL_FILE).openConnection() as HttpURLConnection).apply {
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
                                onProgress("Stahuji Gemmu", part.length(), SIZE)
                            }
                        }
                    }
                }
                if (got == 0L) error("Server neposlal žádná data")
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
                    onProgress("Kontroluji", done, SIZE)
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != SHA256) {
            part.delete()
            error("Kontrolní součet nesouhlasí, stáhne se znovu")
        }
        if (!part.renameTo(target)) error("Nelze přejmenovat ${part.name}")
    }
}
