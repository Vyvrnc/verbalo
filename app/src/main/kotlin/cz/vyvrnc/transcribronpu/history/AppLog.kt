package cz.vyvrnc.transcribronpu.history

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own event log (dictation sessions, model loads, cleanup, text insertion, errors),
 * shown in the History screen so problems can be read without adb. Plain text in the app's
 * private files; the current file rotates to one older file at [MAX_BYTES].
 */
object AppLog {
    private const val MAX_BYTES = 512 * 1024L
    private val time = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.ROOT)

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "logs")
    private fun current(context: Context) = File(dir(context), "app.log")
    private fun previous(context: Context) = File(dir(context), "app.log.1")

    fun i(context: Context, tag: String, message: String) {
        Log.i(tag, message)
        append(context, "$tag: $message")
    }

    fun e(context: Context, tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        append(context, "$tag: CHYBA $message" + (error?.let { " (${it::class.java.simpleName}: ${it.message})" } ?: ""))
    }

    @Synchronized
    private fun append(context: Context, line: String) {
        runCatching {
            val file = current(context)
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) {
                previous(context).delete()
                file.renameTo(previous(context))
            }
            file.appendText("${synchronized(time) { time.format(Date()) }}  $line\n")
        }
    }

    /** Newest lines last, at most [maxLines]. */
    @Synchronized
    fun read(context: Context, maxLines: Int = 1000): String {
        val lines = listOf(previous(context), current(context))
            .filter { it.exists() }
            .flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }
        return lines.takeLast(maxLines).joinToString("\n")
    }

    @Synchronized
    fun clear(context: Context) {
        current(context).delete()
        previous(context).delete()
    }
}
