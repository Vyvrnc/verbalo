package cz.vyvrnc.transcribronpu.calls

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.provider.DocumentsContract
import cz.vyvrnc.transcribronpu.file.FileTranscription
import cz.vyvrnc.transcribronpu.history.AppLog
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Automatic transcription of call recordings: new files in the chosen folder whose number is
 * on the allowlist are transcribed one at a time and written as Markdown to the output folder.
 * Audio never leaves the phone. [kick] is safe to call often (observer, worker, settings).
 */
object CallAuto {
    private const val TAG = "Hovory"
    /** A file changed this recently may still be recorded. */
    private const val SETTLE_MS = 60_000L
    private const val MAX_ATTEMPTS = 3

    data class Status(val done: Int = 0, val skipped: Int = 0, val failed: Int = 0, val waiting: Int = 0, val last: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var retry: Job? = null
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun kick(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { pass(app) }.onFailure { AppLog.e(app, TAG, "průchod selhal", it) } }
    }

    fun charging(context: Context): Boolean =
        context.getSystemService(BatteryManager::class.java)?.isCharging == true

    private suspend fun pass(app: Context) = mutex.withLock {
        if (!CallSettings.enabled(app)) return@withLock
        val input = CallSettings.input(app) ?: return@withLock
        val output = CallSettings.output(app) ?: return@withLock
        val state = State.load(app)
        val pattern = CallSettings.pattern(app)
        val contacts = CallSettings.contacts(app)
        val since = CallSettings.since(app)
        var settling = false
        for (doc in list(app, input)) {
            val known = state.entries[doc.id]
            if (known != null && (known.status != "failed" || known.attempts >= MAX_ATTEMPTS)) continue
            if (doc.modified < since) continue
            val info = CallName.parse(doc.name, pattern)
            if (info == null) { state.mark(doc.id, "skipped", "name"); continue }
            val contact = contacts.firstOrNull { PhoneNumbers.same(it.number, info.number) }
            if (contact == null) { state.mark(doc.id, "skipped", "contact"); continue }
            if (System.currentTimeMillis() - doc.modified < SETTLE_MS) { settling = true; continue }
            if (CallSettings.onlyCharging(app) && !charging(app)) break
            state.save(app)
            when (val result = FileTranscription.transcribeAndWait(app, doc.uri)) {
                null -> { settling = true; break }   // the user is transcribing something; later
                is FileTranscription.State.Done -> {
                    val written = runCatching { write(app, output, doc, info, contact, result) }
                    written.onFailure { AppLog.e(app, TAG, "${doc.name}: zápis přepisu selhal", it) }
                    if (written.isSuccess) {
                        state.mark(doc.id, "done", written.getOrNull())
                        AppLog.i(app, TAG, "${doc.name}: přepsáno -> ${written.getOrNull()}")
                    } else state.fail(doc.id, "write")
                    FileTranscription.reset()
                }
                is FileTranscription.State.Failed -> {
                    state.fail(doc.id, result.message)
                    AppLog.i(app, TAG, "${doc.name}: přepis selhal: ${result.message}")
                    FileTranscription.reset()
                }
                else -> state.fail(doc.id, "?")
            }
            state.save(app)
        }
        state.save(app)
        _status.value = state.status(app)
        if (settling) {
            retry?.cancel()
            retry = scope.launch { delay(SETTLE_MS + 5_000); kick(app) }
        }
    }

    /** Refreshes [status] from the stored state without a pass. */
    fun refreshStatus(context: Context) {
        val app = context.applicationContext
        scope.launch { _status.value = State.load(app).status(app) }
    }

    /** Forget the skipped and failed files, so they are looked at again (new contact, pattern). */
    fun forgetSkipped(context: Context) {
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                val s = State.load(app)
                s.entries.values.removeAll { it.status != "done" }
                s.save(app)
            }
            kick(app)
        }
    }

    private class Doc(val id: String, val uri: Uri, val name: String, val modified: Long)

    private fun list(app: Context, tree: Uri): List<Doc> {
        val parent = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Doc>()
        app.contentResolver.query(
            parent,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(3) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val id = c.getString(0)
                out.add(Doc(id, DocumentsContract.buildDocumentUriUsingTree(tree, id), c.getString(1) ?: id, c.getLong(2)))
            }
        }
        return out.sortedBy { it.modified }
    }

    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

    /** Markdown with frontmatter; written under a temporary name and renamed when complete. */
    private fun write(
        app: Context, tree: Uri, doc: Doc, info: CallName.Info, contact: CallSettings.Contact,
        done: FileTranscription.State.Done,
    ): String {
        val started = info.started ?: OffsetDateTime.ofInstant(Instant.ofEpochMilli(doc.modified), ZoneId.systemDefault())
        val who = contact.name.ifBlank { contact.number }.replace(Regex("""[\\/:*?"<>|]"""), "").trim()
        val name = "${started.format(FILE_TIME)}_${info.direction?.code ?: "call"}_$who.md"
        val text = buildString {
            append("---\n")
            append("source: verbalo\n")
            append("kind: call\n")
            info.direction?.let { append("direction: ${it.code}\n") }
            append("number: \"${PhoneNumbers.normalize(info.number)}\"\n")
            append("contact: \"${contact.name.replace("\"", "'")}\"\n")
            append("started: ${started.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}\n")
            append("duration_s: ${done.audioSec.toInt()}\n")
            append("recording: \"${doc.name.replace("\"", "'")}\"\n")
            append("failed_windows: ${done.failedWindows}\n")
            append("---\n\n")
            append(done.text.trim())
            append("\n")
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val temp = DocumentsContract.createDocument(app.contentResolver, parent, "application/octet-stream", "$name.part")
            ?: error("createDocument vrátil null")
        try {
            app.contentResolver.openOutputStream(temp, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val final = DocumentsContract.renameDocument(app.contentResolver, temp, name) ?: temp
            return DocumentsContract.getDocumentId(final).substringAfterLast('/')
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(app.contentResolver, temp) }
            throw e
        }
    }

    /** What was done with each recording, by document id; a small JSON file in the app's files. */
    private class State(val entries: MutableMap<String, Entry>) {
        class Entry(val status: String, val note: String?, val attempts: Int)

        fun mark(id: String, status: String, note: String?) {
            entries[id] = Entry(status, note, entries[id]?.attempts ?: 0)
        }

        fun fail(id: String, note: String?) {
            entries[id] = Entry("failed", note, (entries[id]?.attempts ?: 0) + 1)
        }

        fun status(app: Context): Status {
            val v = entries.values
            val lastDone = v.lastOrNull { it.status == "done" }?.note
            return Status(
                done = v.count { it.status == "done" },
                skipped = v.count { it.status == "skipped" },
                failed = v.count { it.status == "failed" && it.attempts >= MAX_ATTEMPTS },
                waiting = v.count { it.status == "failed" && it.attempts < MAX_ATTEMPTS },
                last = lastDone,
            )
        }

        fun save(app: Context) {
            val o = JSONObject()
            entries.forEach { (k, e) -> o.put(k, JSONObject().put("s", e.status).put("n", e.note ?: "").put("a", e.attempts)) }
            val f = file(app)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }

        companion object {
            private fun file(app: Context) = File(app.filesDir, "call-transcription.json")

            fun load(app: Context): State {
                val map = LinkedHashMap<String, Entry>()
                runCatching {
                    val o = JSONObject(file(app).readText())
                    o.keys().forEach { k ->
                        val e = o.getJSONObject(k)
                        map[k] = Entry(e.getString("s"), e.optString("n").ifEmpty { null }, e.optInt("a"))
                    }
                }
                return State(map)
            }
        }
    }
}
