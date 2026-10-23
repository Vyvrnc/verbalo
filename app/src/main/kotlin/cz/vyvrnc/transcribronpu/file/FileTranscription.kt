package cz.vyvrnc.transcribronpu.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import cz.vyvrnc.transcribronpu.history.AppLog
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
        data class Done(val name: String, val text: String, val audioSec: Double, val tookMs: Long) : State
        data class Failed(val name: String, val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    fun start(context: Context, uri: Uri) {
        if (busy) return
        val app = context.applicationContext
        val name = displayName(app, uri)
        _state.value = State.Decoding(name, 0f)
        job = scope.launch { run(app, uri, name) }
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
        val recorder = DictationHistory.Recorder(app, id)
        var kept = false
        try {
            if (!NpuModel.ready(app)) throw AudioDecoder.Unsupported("NPU model není stažený (Nastavení → NPU model)")
            val seconds = AudioDecoder.decode(app, uri, sink = { buf, n -> recorder.write(buf, n) }) {
                _state.value = State.Decoding(name, it)
            }
            if (!recorder.finish() || seconds < 0.3) throw AudioDecoder.Unsupported("V souboru není žádný zvuk")
            AppLog.i(app, TAG, "%s: %.1f s zvuku dekódováno za %d ms".format(name, seconds, System.currentTimeMillis() - t0))

            val language = NpuSettings.resolveLanguage(app, null)
            val text = transcribe(app, id, language, name, seconds)
            if (text.isBlank()) throw AudioDecoder.Unsupported("V nahrávce jsem nerozpoznal žádnou řeč")
            if (NpuSettings.saveHistory(app)) {
                DictationHistory.saveTranscript(app, id, "soubor: $name", language, seconds, text)
                kept = true
            }
            val took = System.currentTimeMillis() - t0
            AppLog.i(app, TAG, "%s: %.1f s přepsáno za %.1f s, %d znaků".format(name, seconds, took / 1000.0, text.length))
            _state.value = State.Done(name, text, seconds, took)
        } catch (e: CancellationException) {
            _state.value = State.Idle
            throw e
        } catch (e: Exception) {
            AppLog.e(app, TAG, "$name: přepis souboru selhal", e)
            _state.value = State.Failed(
                name,
                (e as? AudioDecoder.Unsupported)?.message ?: "Soubor se nepodařilo přečíst nebo přepsat (${e.javaClass.simpleName})",
            )
        } finally {
            recorder.discard()
            if (!kept) DictationHistory.delete(app, id)
        }
    }

    /** One window (up to 29.5 s, ending in the quietest moment) at a time, read from the wav. */
    private suspend fun transcribe(app: Context, id: String, language: String, name: String, seconds: Double): String {
        val whisper = WhisperRepository(app)
        val out = StringBuilder()
        RandomAccessFile(DictationHistory.audio(app, id), "r").use { wav ->
            val total = ((wav.length() - 44) / 2).toInt()
            var start = 0
            while (start < total) {
                kotlin.coroutines.coroutineContext.ensureActive()
                // One sample past the window, so the splitter looks for a pause before the limit.
                val window = read(wav, start, min(total - start, SilenceSplitter.MAX_SAMPLES + 1))
                val piece = SilenceSplitter.split(floats(window)).first()
                val pcm = window.copyOfRange(piece.first, piece.last + 1)
                start += pcm.size
                val level = rmsDbfs(pcm)
                if (level > SILENCE_DBFS) {
                    val part = whisper.transcribeAudio(amplified(pcm), language).trim()
                    if (part.isNotEmpty()) {
                        if (out.isNotEmpty() && !out.endsWith('\n') && !part.startsWith('\n')) out.append(' ')
                        out.append(part)
                    }
                }
                _state.value = State.Transcribing(name, start.toFloat() / total, out.toString(), seconds)
            }
        }
        return out.toString().trim()
    }

    private fun read(wav: RandomAccessFile, start: Int, count: Int): ShortArray {
        val bytes = ByteArray(count * 2)
        wav.seek(44L + start * 2L)
        wav.readFully(bytes)
        return ShortArray(count) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
    }

    private fun floats(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }

    private fun rmsDbfs(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return -120.0
        var s = 0.0
        for (v in pcm) s += v.toDouble() * v
        return 20 * log10(max(sqrt(s / pcm.size) / 32768.0, 1e-6))
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
        }.getOrNull() ?: uri.lastPathSegment ?: "nahrávka"
}
