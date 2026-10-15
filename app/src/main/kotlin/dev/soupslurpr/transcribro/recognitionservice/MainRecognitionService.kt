package dev.soupslurpr.transcribro.recognitionservice

import android.content.ContextParams
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import cz.vyvrnc.transcribronpu.npu.NpuModel
import cz.vyvrnc.transcribronpu.npu.NpuSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.soupslurpr.transcribro.recognitionservice.silerovad.SileroVadApi
import dev.soupslurpr.transcribro.recognitionservice.silerovad.SileroVadDetector
import dev.soupslurpr.transcribro.recognitionservice.silerovad.SileroVadLocalDataSource
import dev.soupslurpr.transcribro.recognitionservice.silerovad.SileroVadRepository
import dev.soupslurpr.transcribro.recognitionservice.whisper.WhisperRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.System.currentTimeMillis
import java.util.concurrent.atomic.AtomicBoolean
import cz.vyvrnc.transcribronpu.history.AppLog
import cz.vyvrnc.transcribronpu.history.DictationHistory

private data class Transcription(
    val audioData: MutableList<Short> = mutableListOf(),
    var start: Double?,
    var end: Double?,
    var text: String?,
)

class MainRecognitionService : RecognitionService() {
    companion object {
        const val EXTRA_AUTO_STOP = "dev.soupslurpr.transcribro.EXTRA_AUTO_STOP"

        /**
         * Request extra (with EXTRA_PARTIAL_RESULTS): also send unfinished text of the segment
         * being spoken. Such partial results carry [LIVE_PARTIAL] = true and replace each other
         * until the segment's final partial result (without the flag) arrives. Only our own
         * clients ask for it: other apps keep upstream's one-partial-per-segment behaviour.
         */
        const val EXTRA_LIVE_PARTIALS = "cz.vyvrnc.transcribronpu.EXTRA_LIVE_PARTIALS"
        const val LIVE_PARTIAL = "cz.vyvrnc.transcribronpu.LIVE_PARTIAL"

        /** Request extra: who dictates, for the history ("floating", "keyboard"); else the caller's package. */
        const val EXTRA_SOURCE = "cz.vyvrnc.transcribronpu.EXTRA_SOURCE"
        /** Result extra: id of the saved dictation in DictationHistory (when history is on). */
        const val HISTORY_ID = "cz.vyvrnc.transcribronpu.HISTORY_ID"

        /** Boolean in the results: some segment failed, so the text may be incomplete. */
        const val SEGMENT_FAILED = "cz.vyvrnc.transcribronpu.SEGMENT_FAILED"

        private const val TAG = "Rozpoznávání"
        private const val LIVE_INTERVAL_MS = 900L
        private const val LIVE_MIN_SAMPLES = 16000 * 3 / 4
    }

    private val recordAndTranscribeScope = CoroutineScope(Dispatchers.IO)

    private val transcribeScope = CoroutineScope(Dispatchers.IO)

    private val isRecording = AtomicBoolean(true)

    private var recordAndTranscribeJob: Job? = null

//    private var loadedModel: WhichModel? = null

    private var isSpeaking by mutableStateOf(false)

    private var stopListening by mutableStateOf(false)

    private val transcribeJobs = mutableListOf<Job>()

    @Volatile private var liveJob: Job? = null

    /** Recreated per session, so changed settings (vocabulary, replacements) apply at once. */
    @Volatile private var whisperRepository: WhisperRepository? = null

    /**
     * Transcribes one segment; a failure is logged, flagged in the session's [failed] and
     * yields no text instead of a crash. Cancellation is not a failure and propagates.
     */
    private suspend fun transcribeSegment(audio: ShortArray, language: String, failed: AtomicBoolean): String =
        try {
            whisperRepository!!.transcribeAudio(audio, language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MainRecognitionService", "NPU transcription failed", e)
            failed.set(true)
            ""
        }

    private val sileroVadRepository = SileroVadRepository(
        SileroVadLocalDataSource(
            object : SileroVadApi {
                override fun getSileroVadDetector(): SileroVadDetector {
                    val SAMPLE_RATE = 16000
                    val START_THRESHOLD = 0.6f
                    val END_THRESHOLD = 0.45f
                    val MIN_SILENCE_DURATION_MS = 3000
                    val SPEECH_PAD_MS = 0

                    val model =
                        this@MainRecognitionService.assets.open("models/silero_vad/silero_vad.with_runtime_opt.ort")

                    val modelBytes = model.readBytes()

                    model.close()

                    return SileroVadDetector(
                        modelBytes,
                        START_THRESHOLD,
                        END_THRESHOLD,
                        SAMPLE_RATE,
                        MIN_SILENCE_DURATION_MS,
                        SPEECH_PAD_MS
                    )
                }
            },
            Dispatchers.IO
        )
    )

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val autoStopRecognition = recognizerIntent?.extras?.getBoolean(EXTRA_AUTO_STOP) ?: true
        val isPartialResults = recognizerIntent?.extras?.getBoolean(RecognizerIntent.EXTRA_PARTIAL_RESULTS)
        val speechStartPadMs = 24000
        val language = NpuSettings.resolveLanguage(this, recognizerIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        val live = isPartialResults == true && recognizerIntent?.getBooleanExtra(EXTRA_LIVE_PARTIALS, false) == true &&
            NpuSettings.livePartials(this)

        if (!NpuModel.ready(this)) {
            // Nothing to transcribe with; the app's settings screen downloads the model.
            listener?.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return
        }
        if (recordAndTranscribeJob?.isActive == true) {
            // The previous session still records or transcribes; a second one would share
            // its state (recording flag, job list).
            listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        val repository = WhisperRepository(applicationContext).also { whisperRepository = it }
        // Load the model onto the NPU while the user speaks (3-4 s on the first use).
        repository.preload()
        // Per session: a cancelled older session finishing late must not mark this one.
        val segmentFailed = AtomicBoolean(false)

        val attributionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.createContext(
                ContextParams.Builder()
                    .setNextAttributionSource(listener?.callingAttributionSource)
                    .build()
            )
        } else {
            null
        }

        val sampleRate = 16000
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding)

        val audioFormat = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .build()

        val audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && attributionContext != null) {
            try {
                AudioRecord.Builder()
                    .setContext(attributionContext)
                    .setAudioFormat(audioFormat)
                    .build()
            } catch (e: SecurityException) {
                throw SecurityException(e)
            }
        } else {
            try {
                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .build()
            } catch (e: SecurityException) {
                throw SecurityException(e)
            }
        }


//        val model = when (recognizerIntent?.extras?.getString(RecognizerIntent.EXTRA_LANGUAGE_MODEL)) {
//            "TINY_EN_Q8_0" -> WhichModel.TINY_EN_Q8_0
//            "TINY_EN_Q4_0" -> WhichModel.TINY_EN_Q4_0
//            "BASE_Q8_0" -> WhichModel.BASE_Q8_0
//            "BASE_Q4_0" -> WhichModel.BASE_Q4_0
//
//            // These k-quants are currently very slow and inaccurate
//            "BASE_Q4K" -> WhichModel.BASE_Q4K
//            "BASE_Q2K" -> WhichModel.BASE_Q2K
//            else -> WhichModel.TINY_EN_Q4_0
//        }
//
//        val isMultilingual = when (model) {
//            WhichModel.TINY_EN_Q8_0 -> false
//            WhichModel.TINY_EN_Q4_0 -> false
//            WhichModel.BASE_Q8_0 -> true
//            WhichModel.BASE_Q4_0 -> true
//            WhichModel.BASE_Q4K -> true
//            WhichModel.BASE_Q2K -> true
//        }
//
//        println(model)
//
//        if (!isWhisperLoaded() || loadedModel != model) {
//            val modelResId = when (model) {
//                WhichModel.TINY_EN_Q8_0 -> R.raw.whisper_tiny_en_q8_0_model
//                WhichModel.TINY_EN_Q4_0 -> R.raw.whisper_tiny_en_q4_0_model
//                WhichModel.BASE_Q8_0 -> R.raw.whisper_base_q8_0_model
//                WhichModel.BASE_Q4_0 -> R.raw.whisper_base_q4_0_model
//                WhichModel.BASE_Q4K -> R.raw.whisper_base_q2k_model
//                WhichModel.BASE_Q2K -> R.raw.whisper_base_q2k_model
//            }
//            val configResId = when (model) {
//                WhichModel.TINY_EN_Q8_0 -> R.raw.whisper_tiny_en_config
//                WhichModel.TINY_EN_Q4_0 -> R.raw.whisper_tiny_en_config
//                WhichModel.BASE_Q8_0 -> R.raw.whisper_base_config
//                WhichModel.BASE_Q4_0 -> R.raw.whisper_base_config
//                WhichModel.BASE_Q4K -> R.raw.whisper_base_config
//                WhichModel.BASE_Q2K -> R.raw.whisper_base_config
//            }
//            val tokenizerResId = when (model) {
//                WhichModel.TINY_EN_Q8_0 -> R.raw.whisper_tiny_en_tokenizer
//                WhichModel.TINY_EN_Q4_0 -> R.raw.whisper_tiny_en_tokenizer
//                WhichModel.BASE_Q8_0 -> R.raw.whisper_base_tokenizer
//                WhichModel.BASE_Q4_0 -> R.raw.whisper_base_tokenizer
//                WhichModel.BASE_Q4K -> R.raw.whisper_base_tokenizer
//                WhichModel.BASE_Q2K -> R.raw.whisper_base_tokenizer
//            }
//
//            println(recognizerIntent?.extras?.getString(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
//
//            val modelFileDescriptor = resources.openRawResourceFd(modelResId)
//            val configFileDescriptor = resources.openRawResourceFd(configResId)
//            val tokenizerFileDescriptor = resources.openRawResourceFd(tokenizerResId)
//
//            loadWhisper(
//                modelFileDescriptor.parcelFileDescriptor.detachFd(),
//                modelFileDescriptor.startOffset.toULong(),
//                modelFileDescriptor.length.toULong(),
//
//                configFileDescriptor.parcelFileDescriptor.detachFd(),
//                configFileDescriptor.startOffset.toULong(),
//                configFileDescriptor.length.toULong(),
//
//                tokenizerFileDescriptor.parcelFileDescriptor.detachFd(),
//                tokenizerFileDescriptor.startOffset.toULong(),
//                tokenizerFileDescriptor.length.toULong(),
//            )
//
//            modelFileDescriptor.close()
//            configFileDescriptor.close()
//            tokenizerFileDescriptor.close()
//
//            loadedModel = model
//
//            println("loaded model")
//        }


        var totalTranscriptionTime = 0L
        val source = recognizerIntent?.getStringExtra(EXTRA_SOURCE)
            ?: listener?.let { runCatching { packageManager.getNameForUid(it.callingUid) }.getOrNull() }
            ?: "?"
        val recorder = if (NpuSettings.saveHistory(this)) {
            runCatching { DictationHistory.Recorder(this, DictationHistory.newId()) }
                .onFailure { AppLog.e(this, TAG, "nelze založit nahrávku", it) }.getOrNull()
        } else null

        recordAndTranscribeJob = recordAndTranscribeScope.launch recordAndTranscribe@{
          try {
            audioRecord.startRecording()
            isRecording.set(true)
            isSpeaking = false
            stopListening = false

            val audioRmsScope = CoroutineScope(Dispatchers.IO)
            transcribeJobs.clear()
            val transcriptions = mutableMapOf<Int, Transcription>()
            var transcriptionIndex by mutableIntStateOf(0)
            var lastLiveAt = 0L

            listener?.readyForSpeech(Bundle())

            while (isRecording.get() && isActive) {
                val buffer = ShortArray(bufferSize)

                val numberOfShorts = audioRecord.read(buffer, 0, bufferSize)
                recorder?.write(buffer, numberOfShorts)

                for (i in 0 until numberOfShorts) {
                    if (transcriptions[transcriptionIndex] == null) {
                        transcriptions[transcriptionIndex] = Transcription(start = null, end = null, text = null)
                    }
                    transcriptions[transcriptionIndex]!!.audioData.add(buffer[i])
                }

                if (live && liveJob?.isActive != true && currentTimeMillis() - lastLiveAt >= LIVE_INTERVAL_MS) {
                    // Snapshot here, in the loop that appends the audio. A segment whose end
                    // is set belongs to its final transcription job and is not touched.
                    val transcription = transcriptions[transcriptionIndex]
                    val start = transcription?.start
                    // Copied under the segment's lock: once end is set, the final job may clear
                    // the buffer, and it does so under the same lock.
                    val audio = if (transcription != null && start != null) synchronized(transcription) {
                        if (transcription.end != null) return@synchronized null
                        val data = transcription.audioData
                        val from = (start.toInt() - speechStartPadMs).coerceIn(0, data.size)
                        ShortArray(data.size - from) { data[from + it] }
                    } else null
                    if (audio != null && transcription != null) {
                        if (audio.size >= LIVE_MIN_SAMPLES) {
                            lastLiveAt = currentTimeMillis()
                            liveJob = transcribeScope.launch {
                                val text = try {
                                    repository.transcribePartial(audio, language)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w("MainRecognitionService", "live transcription failed", e)
                                    null
                                }
                                if (text.isNullOrEmpty() || !isActive) return@launch
                                // Under the segment's lock, so it cannot overtake the final
                                // result: once end is set no live text is sent for it.
                                synchronized(transcription) {
                                    if (transcription.end != null) return@launch
                                    runCatching {
                                        listener?.partialResults(Bundle().apply {
                                            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                                            putBoolean(LIVE_PARTIAL, true)
                                        })
                                    }
                                }
                            }
                        }
                    }
                }

                if (!isActive) {
                    audioRecord.stop()
                    audioRecord.release()
                    return@recordAndTranscribe
                }

                audioRmsScope.launch vadAndTranscribe@{
                    if (stopListening) {
                        isRecording.set(false)
                        listener?.endOfSpeech()

                        if ((transcriptionIndex != 0) && !autoStopRecognition && !isSpeaking) {
                            // break if we already transcribed once or more,
                            // is not auto stop recognition, and not speaking
                            // because we just want to stop then with the already transcribed parts.
                            // if there were no transcriptions then we assume VAD didn't work,
                            // so we transcribe everything all at once.
                            // if there were transcriptions, then VAD was working.
                            // in that case, we can break as if it was working at first it was
                            // probably working afterward, so processing the audio could
                            // be detrimental.
                            return@vadAndTranscribe
                        }

                        // Auto-stop raced the tap: VAD may have moved on before the next segment
                        // exists. The recording loop still ends and delivers the results.
                        val transcription = transcriptions[transcriptionIndex] ?: return@vadAndTranscribe

                        if ((transcriptionIndex == 0) && !isSpeaking) {
                            transcription.start = 0.toDouble()
                        }

                        // Auto-stop raced the tap: VAD already closed the last segment and this one
                        // has no speech start. Nothing to transcribe, and start!! below would throw.
                        if (transcription.start == null) {
                            return@vadAndTranscribe
                        }

                        synchronized(transcription) {
                            transcription.end = (transcription.audioData.size - 1).toDouble()
                        }

                        val transcribeJob = transcribeScope.launch {
                            val timeBeforeTranscription = currentTimeMillis()

                            val transcriptionText =
                                transcribeSegment(
                                    transcription.audioData.slice(
                                        ((transcription.start!!.toInt() - speechStartPadMs).coerceAtLeast(
                                            0
                                        ))..((transcription.end!!.toInt()).coerceAtMost(transcription.audioData.size - 1))
                                    )
                                        .toShortArray(),
                                    language,
                                    segmentFailed,
                                )

                            transcription.text = transcriptionText

                            totalTranscriptionTime += currentTimeMillis() - timeBeforeTranscription

                            for (job in transcribeJobs.iterator().withIndex()) {
                                if (job.index < transcriptionIndex - 1) {
                                    job.value.join()
                                }
                            }

                            synchronized(transcription) { transcription.audioData.clear() }

                            if (isPartialResults == true) {
                                val bundle = Bundle().apply {
                                    putStringArrayList(
                                        SpeechRecognizer.RESULTS_RECOGNITION,
                                        arrayListOf(
                                            segmentPartial(transcription.text!!)
                                        )
                                    )
                                }

                                if (!isActive) {
                                    return@launch
                                }

                                listener?.partialResults(bundle)
                            }
                        }

                        transcribeJobs.add(transcribeJob)

                        transcribeJob.start() // start transcribing this segment right now
                    } else {
                        sileroVadRepository.detect(buffer)?.forEach {
                            when (it.key) {
                                "start" -> {
                                    isSpeaking = true
                                    listener?.beginningOfSpeech()

                                    if (transcriptions[transcriptionIndex] == null) {
                                        transcriptions[transcriptionIndex] =
                                            Transcription(start = it.value, end = null, text = null)
                                    } else {
                                        transcriptions[transcriptionIndex]!!.start =
                                            it.value
                                    }
                                }

                                "end" -> {
                                    val transcription = transcriptions[transcriptionIndex]!!

                                    transcriptionIndex += 1
                                    sileroVadRepository.reset()

                                    if (transcription.end == null) {
                                        isSpeaking = false
                                        listener?.endOfSpeech()

                                        synchronized(transcription) { transcription.end = it.value }

                                        val transcribeJob = transcribeScope.launch {
                                            val timeBeforeTranscription = currentTimeMillis()

                                            transcription.text =
                                                transcribeSegment(
                                                    transcription.audioData.slice(
                                                        ((transcription.start!!.toInt() - speechStartPadMs).coerceAtLeast(
                                                            0
                                                        ))..((transcription.end!!.toInt()).coerceAtMost(transcription.audioData.size - 1))
                                                    )
                                                        .toShortArray(),
                                                    language,
                                                    segmentFailed,
                                                )

                                            totalTranscriptionTime += currentTimeMillis() - timeBeforeTranscription

                                            for (job in transcribeJobs.iterator().withIndex()) {
                                                if (job.index < transcriptionIndex - 1) {
                                                    job.value.join()
                                                }
                                            }

                                            synchronized(transcription) { transcription.audioData.clear() }

                                            if (isPartialResults == true) {
                                                val bundle = Bundle().apply {
                                                    putStringArrayList(
                                                        SpeechRecognizer.RESULTS_RECOGNITION,
                                                        arrayListOf(
                                                            segmentPartial(transcription.text!!)
                                                        )
                                                    )
                                                }

                                                if (!isActive) {
                                                    return@launch
                                                }

                                                listener?.partialResults(bundle)
                                            }
                                        }

                                        transcribeJobs.add(transcribeJob)

                                        transcribeJob.start() // start transcribing this segment right now

                                        if (autoStopRecognition) {
                                            isRecording.set(false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (stopListening) {
                    break
                }
            }

            audioRecord.stop()
            audioRecord.release()

            sileroVadRepository.reset()

            if (!isActive) {
                return@recordAndTranscribe
            }

            runBlocking {
                transcribeJobs.forEach {
                    if (!isActive) {
                        return@forEach
                    }
                    it.join()
                }
            }

            if (!isActive) {
                return@recordAndTranscribe
            }

            val transcription = transcriptions.toSortedMap().values
                .mapNotNull { it.text?.trim(' ', '\t')?.takeIf(String::isNotEmpty) }
                .joinToString(" ")
                .replace(" \n", "\n").replace("\n ", "\n")

            val historyId = recorder?.takeIf { it.finish() }?.let { rec ->
                runCatching {
                    DictationHistory.saveTranscript(this@MainRecognitionService, rec.id, source, language, rec.durationSec, transcription)
                    rec.id
                }.getOrNull()
            }
            AppLog.i(this@MainRecognitionService, TAG, "diktát (%s, %s): %.1f s zvuku, %d znaků%s".format(
                source, language, recorder?.durationSec ?: 0.0, transcription.length,
                if (segmentFailed.get()) ", NPU selhala" else ""))

            if (segmentFailed.get() && transcription.isEmpty()) {
                // Nothing usable came back because the NPU failed: say so instead of
                // delivering an empty "success". Partial text from other segments is kept.
                try {
                    listener?.error(SpeechRecognizer.ERROR_CLIENT)
                } catch (e: RemoteException) {
                    throw RuntimeException(e)
                }
                return@recordAndTranscribe
            }

            val bundle = Bundle().apply {
                putStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                    arrayListOf(
                        transcription
                    )
                )
                if (historyId != null) putString(HISTORY_ID, historyId)
                putBoolean(SEGMENT_FAILED, segmentFailed.get())
            }


            try {
                listener?.results(bundle)
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
          } finally {
            // Cancelled sessions keep no recording; a finished one is already saved.
            recorder?.discard()
          }
        }
    }

    /**
     * A segment's text for the per-segment partial result. The NPU engine trims its output,
     * so the separating space is put back here (the keyboard drops it where not needed),
     * otherwise consecutive segments were glued together ("Snad.Wow").
     */
    private fun segmentPartial(text: String): String =
        text.trim(' ', '\t').let { if (it.isEmpty() || it.startsWith('\n')) it else " $it" }

    override fun onCancel(listener: Callback?) {
        liveJob?.cancel()
        recordAndTranscribeJob?.cancel()
        transcribeJobs.forEach {
            it.cancel()
        }
        sileroVadRepository.reset()
    }

    override fun onStopListening(listener: Callback?) {
        stopListening = true
    }

    override fun onDestroy() {
        super.onDestroy()
        liveJob?.cancel()
        recordAndTranscribeJob?.cancel()
        transcribeJobs.forEach {
            it.cancel()
        }
        // The NPU model stays loaded process-wide (NpuEngine releases it when idle).
        runBlocking {
            sileroVadRepository.release()
        }
    }
}
