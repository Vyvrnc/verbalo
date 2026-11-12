package cz.vyvrnc.transcribronpu.history

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.ui.res.stringResource
import dev.soupslurpr.transcribro.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A transcript as a file (Markdown or plain text), for sharing as an attachment or saving
 * through the system file picker, instead of copying the text by hand.
 */
object TranscriptExport {
    enum class Format(val extension: String, val mime: String) {
        MARKDOWN("md", "text/markdown"),
        TEXT("txt", "text/plain"),
    }

    data class Transcript(
        /** File name of the recording, or null for a dictation. */
        val name: String?,
        val time: Long,
        val durationSec: Double,
        val language: String?,
        val source: String?,
        val text: String,
        /** Text after the cleanup, when there is one; the Whisper text is then kept below it. */
        val cleaned: String? = null,
    )

    private fun shownDate(time: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(time))

    private val fileDate = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT)

    fun fileName(t: Transcript, format: Format): String {
        val base = t.name?.substringBeforeLast('.')?.let(::slug)?.takeIf { it.isNotEmpty() }
        return "prepis-${fileDate.format(Date(t.time))}${base?.let { "-$it" } ?: ""}.${format.extension}"
    }

    fun render(context: Context, t: Transcript, format: Format): String {
        val title = t.name?.let { context.getString(R.string.export_title_transcript, it) }
            ?: context.getString(R.string.export_title_dictation)
        val meta = listOfNotNull(
            context.getString(R.string.export_label_date) to shownDate(t.time),
            context.getString(R.string.export_label_duration) to duration(context, t.durationSec),
            t.language?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.export_label_language) to it },
            t.source?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.export_label_source) to it },
        )
        val main = t.cleaned ?: t.text
        val original = context.getString(R.string.export_original_transcript)
        return when (format) {
            Format.MARKDOWN -> buildString {
                append("# ").append(title).append("\n\n")
                meta.forEach { (k, v) -> append("- **").append(k).append(":** ").append(v).append('\n') }
                append('\n').append(main.trim()).append('\n')
                if (t.cleaned != null) {
                    append("\n## ").append(original).append("\n\n").append(t.text.trim()).append('\n')
                }
            }
            Format.TEXT -> buildString {
                append(title).append('\n')
                meta.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
                append('\n').append(main.trim()).append('\n')
                if (t.cleaned != null) {
                    append("\n--- ").append(original).append(" ---\n\n").append(t.text.trim()).append('\n')
                }
            }
        }
    }

    /**
     * Writes the file to cache/share (only the latest one is kept) and returns the share sheet
     * for it. Call off the main thread; start the returned intent on it.
     */
    fun shareFileIntent(context: Context, t: Transcript, format: Format): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(t, format))
        file.writeText(render(context, t, format))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            // text/plain for both: more apps accept it, the extension keeps the format.
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.export_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareText(context: Context, t: Transcript) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, t.cleaned ?: t.text)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.export_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun write(context: Context, uri: Uri, t: Transcript, format: Format) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("nelze otevřít $uri")
        out.use { it.write(render(context, t, format).toByteArray(Charsets.UTF_8)) }
    }

    private fun duration(context: Context, seconds: Double): String {
        val s = seconds.toInt()
        return if (s < 60) context.getString(R.string.export_duration_seconds, s)
        else context.getString(R.string.export_duration_minutes, s / 60, s % 60)
    }

    /** ASCII file-name part: no diacritics, no characters some storage refuses. */
    private fun slug(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.')
            .take(60)
}

/**
 * "Sdílet / uložit" menu for one transcript: as text, as a .md/.txt attachment, or saved as a
 * .md/.txt file wherever the user picks (Downloads, Drive…). [anchor] gets the lambda that opens it.
 */
@Composable
fun TranscriptExportMenu(transcript: () -> TranscriptExport.Transcript, anchor: @Composable (open: () -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<TranscriptExport.Transcript?>(null) }

    fun saveTo(uri: Uri?, format: TranscriptExport.Format) {
        val t = pending ?: return
        pending = null
        if (uri == null) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { TranscriptExport.write(context, uri, t, format) }.isSuccess }
            Toast.makeText(context, context.getString(if (ok) R.string.export_saved else R.string.export_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    val saveMd = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TranscriptExport.Format.MARKDOWN.mime)
    ) { saveTo(it, TranscriptExport.Format.MARKDOWN) }
    val saveTxt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TranscriptExport.Format.TEXT.mime)
    ) { saveTo(it, TranscriptExport.Format.TEXT) }

    fun share(format: TranscriptExport.Format) {
        open = false
        val t = transcript()
        scope.launch {
            val intent = withContext(Dispatchers.IO) {
                runCatching { TranscriptExport.shareFileIntent(context, t, format) }.getOrNull()
            }
            if (intent != null) context.startActivity(intent)
            else Toast.makeText(context, context.getString(R.string.export_share_file_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun save(format: TranscriptExport.Format) {
        open = false
        val t = transcript().also { pending = it }
        val name = TranscriptExport.fileName(t, format)
        if (format == TranscriptExport.Format.MARKDOWN) saveMd.launch(name) else saveTxt.launch(name)
    }

    Box {
        anchor { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.export_menu_share_text)) }, onClick = {
                open = false
                TranscriptExport.shareText(context, transcript())
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.export_menu_share_md)) }, onClick = { share(TranscriptExport.Format.MARKDOWN) })
            DropdownMenuItem(text = { Text(stringResource(R.string.export_menu_share_txt)) }, onClick = { share(TranscriptExport.Format.TEXT) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.export_menu_save_md)) }, onClick = { save(TranscriptExport.Format.MARKDOWN) })
            DropdownMenuItem(text = { Text(stringResource(R.string.export_menu_save_txt)) }, onClick = { save(TranscriptExport.Format.TEXT) })
        }
    }
}
