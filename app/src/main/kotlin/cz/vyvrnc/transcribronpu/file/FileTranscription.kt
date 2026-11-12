package cz.vyvrnc.transcribronpu.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import cz.vyvrnc.transcribronpu.history.AppLog
import dev.soupslurpr.transcribro.R
import cz.vyvrnc.transcribronpu.history.DictationHistory
import cz.vyvrnc.transcribronpu.npu.NpuModel
import cz.vyvrnc.transcribronpu.npu.NpuSettings
import cz.vyvrnc.transcribronpu.npu.SilenceSplitter
import dev.soupslurpr.transcribro.recognitionservice.whisper.WhisperRepository
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Transcription of an existing recording (picked in the app or shared from another app).
 * Process-wide, so it survives the screen being rotated or left. The audio is decoded to a
 * 16 kHz wav in the history folder and transcribed one Whisper window at a time, cut at pauses.
 */
object FileTranscription {
    private const val TAG = "Soubor"
    /** Windows quieter than this are skipped: Whisper invents text ("Titulky vytvořil…") in silence. */
    private const val SILENCE_DBFS = -50.0
    /** Quiet speech is raised towards -3 dBFS peak, at most by this factor (+20 dB). */
    private const val MAX_GAIN = 10f

    sealed interface State {
        data object Idle : State
        data class Decoding(val name: String, val progress: Float) : State
        data class Transcribing(val name: String, val progress: Float, val text: String, val audioSec: Double) : State
        /** [failedWindows]: parts the NPU could not transcribe, marked in [text]. */
        data class Done(val name: String, val text: String, val audioSec: Double, val tookMs: Long, val failedWindows: Int) : State
        data class Failed(val name: String, val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    /** Until the job has really ended: a cancelled one may still sit in a native NPU call. */
    val busy: Boolean get() = job?.isCompleted == false

    fun start(context: Context, uri: Uri) {
        if (busy) return
        val app = context.applicationContext
        val name = displayName(app, uri)
        _state.value = State.Decoding(name, 0f)
        job = scope.launch { run(app, uri, name) }
        FileTranscriptionService.start(app)
    }

    fun cancel() {
        job?.cancel()
    }

    /** Back to the empty screen after a finished or failed transcription. */
    fun reset() {
        if (!busy) _state.value = State.Idle
    }

    private suspend fun run(app: Context, uri: Uri, name: String) {
        val t0 = System.currentTimeMillis()
        val id = DictationHistory.newId()
        var recorder: DictationHistory.Recorder? = null
        var kept = false
        // Both channels of a stereo source, interleaved 16 kHz PCM16, while the transcription runs.
        // Only one transcription runs at a time: older ones are leftovers of a killed process.
        app.cacheDir.listFiles { f -> f.name.startsWith("stereo-") }?.forEach { it.delete() }
        val stereo = java.io.File(app.cacheDir, "stereo-$id.raw")
        try {
            val rec = DictationHistory.Recorder(app, id).also { recorder = it }
            if (!NpuModel.ready(app)) throw AudioDecoder.Unsupported(app.getString(R.string.file_error_model_missing))
            // The decoder reports every input buffer, thousands a second: publish whole percents only.
            var shown = -1
            lateinit var levels: AudioDecoder.ChannelLevels
            val seconds = java.io.BufferedOutputStream(stereo.outputStream(), 1 shl 16).use { st ->
                var bytes = ByteArray(0)
                levels = AudioDecoder.ChannelLevels { l, r, n ->
                    if (bytes.size < n * 4) bytes = ByteArray(n * 4)
                    for (i in 0 until n) {
                        val a = l[i].toInt()
                        val b = r[i].toInt()
                        bytes[4 * i] = a.toByte(); bytes[4 * i + 1] = (a shr 8).toByte()
                        bytes[4 * i + 2] = b.toByte(); bytes[4 * i + 3] = (b shr 8).toByte()
                    }
                    st.write(bytes, 0, n * 4)
                }
                AudioDecoder.decode(app, uri, sink = { buf, n -> rec.write(buf, n) }, levels = levels) {
                    val percent = (it * 100).toInt()
                    if (percent != shown) {
                        shown = percent
                        _state.value = State.Decoding(name, percent / 100f)
                    }
                }
            }
            if (!rec.finish() || seconds < 0.3) throw AudioDecoder.Unsupported(app.getString(R.string.file_error_no_audio))
            AppLog.i(app, TAG, "%s: %.1f s zvuku dekódováno za %d ms".format(name, seconds, System.currentTimeMillis() - t0))

            val turns = if (levels.left.isEmpty()) null else SpeakerTurns.find(levels.left, levels.right)
            if (turns == null) stereo.delete()
            if (levels.left.isNotEmpty()) {
                AppLog.i(app, TAG, "$name: stereo, " + (turns?.let { "${it.size} promluv podle kanálů" } ?: "kanály mluvčí nerozliší"))
            }
            val language = NpuSettings.resolveLanguage(app, null)
            val (text, failed) = transcribe(app, id, language, name, seconds, turns, stereo)
            if (text.isBlank()) {
                throw AudioDecoder.Unsupported(app.getString(if (failed > 0) R.string.file_error_npu_failed else R.string.file_error_no_speech))
            }
            if (NpuSettings.saveHistory(app)) {
                DictationHistory.saveTranscript(app, id, "soubor: $name", language, seconds, text)
                kept = true
            }
            val took = System.currentTimeMillis() - t0
            AppLog.i(app, TAG, "%s: %.1f s přepsáno za %.1f s, %d znaků".format(name, seconds, took / 1000.0, text.length))
            _state.value = State.Done(name, text, seconds, took, failed)
        } catch (e: CancellationException) {
            _state.value = State.Idle
            throw e
        } catch (e: Throwable) {
            // Throwable: an OutOfMemoryError must end as a message, not crash the app mid-state.
            AppLog.e(app, TAG, "$name: přepis souboru selhal", e)
            _state.value = State.Failed(
                name,
                (e as? AudioDecoder.Unsupported)?.message ?: app.getString(R.string.file_error_generic, e.javaClass.simpleName),
            )
        } finally {
            recorder?.discard()
            stereo.delete()
            if (!kept) DictationHistory.delete(app, id)
        }
    }

    /**
     * One window (up to 29.5 s, ending in the quietest moment) at a time, read from the wav.
     * A window the NPU fails on twice is marked in the text and skipped, so an hour of work is
     * not lost to one error. Returns the text and the number of such windows.
     */
    private suspend fun transcribe(
        app: Context, id: String, language: String, name: String, seconds: Double,
        turns: List<SpeakerTurns.Turn>?, stereo: java.io.File,
    ): Pair<String, Int> {
        var failed = 0
        val whisper = WhisperRepository(app)
        val out = StringBuilder()
        RandomAccessFile(DictationHistory.audio(app, id), "r").use { wav ->
            val total = ((wav.length() - 44) / 2).toInt()
            fun publish(at: Int, preview: String) {
                _state.value = State.Transcribing(name, at.toFloat() / total, preview, seconds)
            }
            if (turns == null) {
                failed += transcribeRange(app, whisper, { s, n -> read(wav, s, n) }, 0, total, language, name) { at, part ->
                    join(out, part)
                    publish(at, out.toString())
                }
            } else RandomAccessFile(stereo, "r").use { st ->
                // Each turn from its speaker's own channel: no crosstalk, and Whisper is surer of a
                // clean channel than of the mix. The mono file stays for playback in the history.
                val channelTotal = (st.length() / 4).toInt()
                // "[mm:ss] Me: …" / "[mm:ss] Other side: …", one paragraph per turn.
                var lastSpeaker: SpeakerTurns.Speaker? = null
                for (turn in turns) {
                    val channel = if (turn.speaker == SpeakerTurns.Speaker.ME) 0 else 1
                    val from = (turn.startSec * AudioDecoder.RATE).toInt().coerceIn(0, total)
                    val to = (turn.endSec * AudioDecoder.RATE).toInt().coerceIn(from, minOf(total, channelTotal))
                    val head = if (turn.speaker == lastSpeaker) "" else
                        (if (out.isEmpty()) "" else "\n\n") + "[${clock(from)}] ${app.getString(turn.speaker.labelRes)}: "
                    val said = StringBuilder()
                    failed += transcribeRange(app, whisper, { s, n -> readChannel(st, channel, s, n) }, from, to, language, name) { at, part ->
                        join(said, part)
                        publish(at, if (said.isEmpty()) out.toString() else "$out$head$said")
                    }
                    // A turn with nothing recognised (noise, a cough) leaves no empty label behind.
                    if (said.isEmpty()) continue
                    if (head.isEmpty()) join(out, said.toString()) else out.append(head).append(said)
                    lastSpeaker = turn.speaker
                }
            }
        }
        return out.toString().trim() to failed
    }

    /**
     * Samples [from]..[to] one window (up to 29.5 s, ending in the quietest moment) at a time.
     * [onPart] gets the end of each window and its text ("" for silence). Returns failed windows.
     */
    private suspend fun transcribeRange(
        app: Context, whisper: WhisperRepository, source: (Int, Int) -> ShortArray, from: Int, to: Int,
        language: String, name: String, onPart: (Int, String) -> Unit,
    ): Int {
        var failed = 0
        var start = from
        while (start < to) {
            kotlin.coroutines.coroutineContext.ensureActive()
            // One sample past the window, so the splitter looks for a pause before the limit.
            val window = source(start, min(to - start, SilenceSplitter.MAX_SAMPLES + 1))
            val piece = SilenceSplitter.split(floats(window)).first()
            val pcm = window.copyOfRange(piece.first, piece.last + 1)
            val at = start
            start += pcm.size
            val part = if (loudestDbfs(pcm) > SILENCE_DBFS) {
                transcribeWindow(app, whisper, amplified(pcm), language, name, at)
                    ?: app.getString(R.string.file_window_failed, clock(at)).also { failed++ }
            } else ""
            onPart(start, part)
        }
        return failed
    }

    private fun join(out: StringBuilder, part: String) {
        if (part.isEmpty()) return
        if (out.isNotEmpty() && !out.endsWith('\n') && !part.startsWith('\n')) out.append(' ')
        out.append(part)
    }

    /** Two attempts; null when both fail. Cancellation is never swallowed. */
    private suspend fun transcribeWindow(
        app: Context, whisper: WhisperRepository, pcm: ShortArray, language: String, name: String, at: Int,
    ): String? {
        repeat(2) { attempt ->
            try {
                return whisper.transcribeAudio(pcm, language).trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(app, TAG, "$name: úsek ${clock(at)}, pokus ${attempt + 1} selhal", e)
            }
        }
        return null
    }

    private fun clock(sample: Int): String {
        val s = sample / AudioDecoder.RATE
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun read(wav: RandomAccessFile, start: Int, count: Int): ShortArray {
        val bytes = ByteArray(count * 2)
        wav.seek(44L + start * 2L)
        wav.readFully(bytes)
        return ShortArray(count) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
    }

    /** [count] samples of one channel of the interleaved stereo file, from sample [start]. */
    private fun readChannel(file: RandomAccessFile, channel: Int, start: Int, count: Int): ShortArray {
        val bytes = ByteArray(count * 4)
        file.seek(start * 4L)
        file.readFully(bytes)
        val o = channel * 2
        return ShortArray(count) { i -> ((bytes[4 * i + o].toInt() and 0xFF) or (bytes[4 * i + o + 1].toInt() shl 8)).toShort() }
    }

    private fun floats(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }

    /**
     * Level of the loudest 200 ms in the window. An average over 29.5 s would call two seconds of
     * quiet speech in an otherwise silent window silence.
     */
    private fun loudestDbfs(pcm: ShortArray): Double {
        val frame = AudioDecoder.RATE / 5
        var best = 0.0
        var i = 0
        while (i < pcm.size) {
            val end = min(pcm.size, i + frame)
            var s = 0.0
            for (k in i until end) s += pcm[k].toDouble() * pcm[k]
            best = max(best, s / (end - i))
            i = end
        }
        return 20 * log10(max(sqrt(best) / 32768.0, 1e-6))
    }

    /** Whisper reads quiet recordings worse than loud ones; raise the peak towards -3 dBFS. */
    private fun amplified(pcm: ShortArray): ShortArray {
        var peak = 1
        for (v in pcm) peak = max(peak, abs(v.toInt()))
        val gain = min(0.7f * 32767f / peak, MAX_GAIN)
        if (gain <= 1.05f) return pcm
        return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: context.getString(R.string.file_default_name)
}
