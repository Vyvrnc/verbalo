package dev.soupslurpr.transcribro.recognitionservice.whisper

import android.content.Context
import cz.vyvrnc.transcribronpu.npu.NpuEngine
import cz.vyvrnc.transcribronpu.npu.NpuSettings
import cz.vyvrnc.transcribronpu.npu.Replacements
import cz.vyvrnc.transcribronpu.npu.SpokenCommands
import cz.vyvrnc.transcribronpu.npu.SilenceSplitter
import cz.vyvrnc.transcribronpu.npu.WhisperTokenizer

/**
 * Speech -> text through the NPU engine (replaces the original whisper.cpp tiny.en).
 * Settings (vocabulary, replacements) are read when first needed and then kept, so create
 * one instance per recognition session.
 */
class WhisperRepository(private val context: Context) {
    private val prompt: IntArray? by lazy {
        val terms = NpuSettings.vocabularyTerms(NpuSettings.vocabulary(context))
        // Whisper continues the style of the prompt, so a plain comma list keeps punctuation
        // normal; the leading space matches how the terms would appear inside a sentence.
        if (terms.isEmpty()) null else WhisperTokenizer.encode(context, " " + terms.joinToString(", "))
    }
    private val replacements by lazy {
        Replacements(NpuSettings.replacementRules(NpuSettings.replacements(context)))
    }

    /** 16 kHz mono PCM16 -> text. [language] is a Whisper code ("cs", "en", ...) or "auto". */
    suspend fun transcribeAudio(data: ShortArray, language: String): String {
        val pcm = toFloat(data)
        // Long dictation is cut at pauses, one Whisper window per piece.
        val pieces = SilenceSplitter.split(pcm).map { range ->
            NpuEngine.transcribe(context, pcm.copyOfRange(range.first, range.last + 1), language, prompt).trim()
        }
        return SpokenCommands.apply(replacements.apply(pieces.filter { it.isNotEmpty() }.joinToString(" ")), language)
    }

    /**
     * One window (at most [SilenceSplitter.MAX_SAMPLES]) with segment times, for stitching
     * longer recordings back together. [previous] is the text before this audio (tokens); its
     * end is fed to Whisper after the vocabulary as context.
     */
    suspend fun transcribeTimed(data: ShortArray, language: String, previous: IntArray?): NpuEngine.Timed {
        val vocabulary = prompt?.takeLast(VOCABULARY_WITH_CONTEXT) ?: emptyList()
        val before = previous?.takeLast(CONTEXT_TOKENS) ?: emptyList()
        val combined = (vocabulary + before).toIntArray().takeIf { it.isNotEmpty() }
        val timed = NpuEngine.transcribeTimed(context, toFloat(data), language, combined)
        return timed.copy(segments = timed.segments.map {
            it.copy(text = SpokenCommands.apply(replacements.apply(it.text), language))
        }.filter { it.text.isNotBlank() })
    }

    fun encode(text: String): IntArray = WhisperTokenizer.encode(context, text)

    /**
     * Unfinished text for live display: the same model on the audio recorded so far. Null
     * when the NPU is busy (final transcriptions take priority) or the audio no longer fits
     * one window.
     */
    suspend fun transcribePartial(data: ShortArray, language: String): String? {
        if (data.size > SilenceSplitter.MAX_SAMPLES) return null
        val text = NpuEngine.tryTranscribe(context, toFloat(data), language, prompt) ?: return null
        return SpokenCommands.apply(replacements.apply(text.trim()), language)
    }

    fun preload() = NpuEngine.preload(context)

    private fun toFloat(data: ShortArray) = FloatArray(data.size) { (data[it] / 32767.0f).coerceIn(-1f..1f) }

    private companion object {
        /**
         * The native side keeps the last 64 prompt tokens, and every prompt token is one less for
         * the transcript (200 in all). Vocabulary and context share a smaller budget.
         */
        const val VOCABULARY_WITH_CONTEXT = 32
        const val CONTEXT_TOKENS = 24
    }
}
