package cz.vyvrnc.transcribronpu.npu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide NPU Whisper. Loading both context binaries onto the HTP takes 3-4 s, so the
 * model stays loaded across recognition sessions and is released after [IDLE_RELEASE_MS]
 * without use (it holds ~2 GB of DSP-mapped memory). [preload] at the start of listening
 * hides the load behind the user's speech.
 */
object NpuEngine {
    private const val TAG = "NpuEngine"
    private const val IDLE_RELEASE_MS = 10 * 60 * 1000L
    /** Mel spectrogram threads; everything else runs on the NPU. */
    private const val MEL_THREADS = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var handle = 0L
    private var idleJob: Job? = null
    private var dspEnvSet = false

    fun preload(context: Context) {
        val app = context.applicationContext
        if (!NpuModel.ready(app)) return
        scope.launch {
            runCatching { mutex.withLock { ensureLoaded(app) } }.onFailure { Log.w(TAG, "preload failed", it) }
            // A session cancelled before any transcription must not keep ~2 GB loaded forever.
            scheduleIdleRelease()
        }
    }

    /**
     * 16 kHz mono PCM -> text. [language] is a Whisper code ("cs", "en", ...) or "auto";
     * [prompt] are vocabulary tokens fed after <|startofprev|>. Throws IllegalStateException
     * when the model is missing or the NPU fails.
     */
    suspend fun transcribe(context: Context, pcm: FloatArray, language: String, prompt: IntArray? = null): String =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            try {
                mutex.withLock { run(app, pcm, language, prompt) }
            } finally {
                scheduleIdleRelease()   // also after a failure, so the model is still released when idle
            }
        }

    /**
     * Like [transcribe] but returns null at once when the NPU is busy or not loaded yet:
     * live previews must never queue in front of a final transcription.
     */
    suspend fun tryTranscribe(context: Context, pcm: FloatArray, language: String, prompt: IntArray? = null): String? =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            if (!mutex.tryLock()) return@withContext null
            try {
                if (handle == 0L) null else run(app, pcm, language, prompt)
            } finally {
                mutex.unlock()
                scheduleIdleRelease()
            }
        }

    /** Call with [mutex] held. */
    private fun run(app: Context, pcm: FloatArray, language: String, prompt: IntArray?): String {
        val h = ensureLoaded(app)
        val t0 = System.nanoTime()
        val ids = NpuNative.transcribe(h, pcm, WhisperTokenizer.langToken(language), MEL_THREADS, prompt)
        Log.i(TAG, "%.1f s audio -> %d tokens in %d ms (lang %s, prompt %d, loop cuts %d)".format(
            pcm.size / 16000f, ids.size - 2, (System.nanoTime() - t0) / 1_000_000,
            WhisperTokenizer.langCode(ids[0]), prompt?.size ?: 0, ids[1]))
        return WhisperTokenizer.decode(app, ids.drop(2)).trim()
    }

    /** Call with [mutex] held. */
    private fun ensureLoaded(context: Context): Long {
        if (handle != 0L) return handle
        if (!NpuModel.ready(context)) throw IllegalStateException("NPU model není stažený (Nastavení → NPU model)")
        configureDspEnvironment(context)
        val t0 = System.nanoTime()
        handle = NpuNative.open(
            context.applicationInfo.nativeLibraryDir,
            NpuModel.encoder(context).absolutePath,
            NpuModel.decoder(context).absolutePath,
        )
        Log.i(TAG, "loaded in ${(System.nanoTime() - t0) / 1_000_000} ms\n${NpuNative.info(handle)}")
        return handle
    }

    @Synchronized
    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock {
                if (handle != 0L) {
                    NpuNative.close(handle)
                    handle = 0L
                    Log.i(TAG, "released after idle")
                }
            }
        }
    }

    /**
     * FastRPC finds the DSP-side skel (libQnnHtpV79Skel.so, shipped in our lib dir) through
     * ADSP_LIBRARY_PATH, read when libcdsprpc initialises, so it is set before the first open.
     */
    private fun configureDspEnvironment(context: Context) {
        if (dspEnvSet) return
        val dirs = listOf(
            context.applicationInfo.nativeLibraryDir,
            "/vendor/dsp/cdsp", "/vendor/lib/rfsa/adsp", "/system/lib/rfsa/adsp", "/vendor/dsp", "/dsp",
        )
        runCatching { android.system.Os.setenv("ADSP_LIBRARY_PATH", dirs.joinToString(";"), true) }
        dspEnvSet = true
    }
}
