package dev.soupslurpr.transcribro.recognitionservice.whisper

import android.content.Context
import cz.vyvrnc.transcribronpu.npu.NpuEngine

/** Speech -> text through the NPU engine (replaces the original whisper.cpp tiny.en). */
class WhisperRepository(private val context: Context) {

    /** 16 kHz mono PCM16 -> text. [language] is a Whisper code ("cs", "en", ...) or "auto". */
    suspend fun transcribeAudio(data: ShortArray, language: String): String {
        // assume we only have one channel
        val buffer = FloatArray(data.size) { index ->
            (data[index] / 32767.0f).coerceIn(-1f..1f)
        }
        // The NPU pads every window to 30 s itself, so short clips need no padding here.
        return NpuEngine.transcribe(context, buffer, language)
    }

    fun preload() = NpuEngine.preload(context)
}
