package cz.vyvrnc.transcribronpu.npu

import android.content.Context
import android.content.SharedPreferences

/**
 * Recognition settings read by the recognition service at the start of every session.
 * SharedPreferences rather than the upstream DataStore: the service needs them synchronously
 * and they are small.
 */
object NpuSettings {
    private const val FILE = "npu_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_VOCABULARY = "vocabulary"
    private const val KEY_REPLACEMENTS = "replacements"
    private const val KEY_LIVE = "live_partials"

    /** Language from the calling app (RecognizerIntent.EXTRA_LANGUAGE), Czech without one. */
    const val LANGUAGE_FROM_CALLER = "caller"
    const val LANGUAGE_AUTO = "auto"
    const val DEFAULT_LANGUAGE = "cs"

    /** Choices offered in the settings: value to label. */
    val LANGUAGE_CHOICES = listOf(
        LANGUAGE_FROM_CALLER to "Podle aplikace (jinak čeština)",
        "cs" to "Čeština",
        "sk" to "Slovenština",
        "en" to "Angličtina",
        "de" to "Němčina",
        "pl" to "Polština",
        LANGUAGE_AUTO to "Rozpoznat automaticky",
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun language(context: Context): String = prefs(context).getString(KEY_LANGUAGE, null) ?: LANGUAGE_FROM_CALLER
    fun setLanguage(context: Context, value: String) = prefs(context).edit().putString(KEY_LANGUAGE, value).apply()

    /** Terms Whisper should prefer (names, jargon), comma or newline separated. */
    fun vocabulary(context: Context): String = prefs(context).getString(KEY_VOCABULARY, null) ?: ""
    fun setVocabulary(context: Context, value: String) = prefs(context).edit().putString(KEY_VOCABULARY, value).apply()

    /** "heard => written" per line, applied after transcription. */
    fun replacements(context: Context): String = prefs(context).getString(KEY_REPLACEMENTS, null) ?: ""
    fun setReplacements(context: Context, value: String) = prefs(context).edit().putString(KEY_REPLACEMENTS, value).apply()

    /** Show unfinished text while speaking (our keyboard and floating button only). */
    fun livePartials(context: Context): Boolean = prefs(context).getBoolean(KEY_LIVE, true)
    fun setLivePartials(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_LIVE, value).apply()

    /**
     * Whisper language code for a session: the setting, or for [LANGUAGE_FROM_CALLER] the
     * caller's tag ("cs-CZ" -> "cs") when Whisper knows it, otherwise Czech.
     */
    fun resolveLanguage(context: Context, callerTag: String?): String {
        val setting = language(context)
        if (setting != LANGUAGE_FROM_CALLER) return setting
        return callerTag?.substringBefore('-')?.substringBefore('_')?.lowercase()
            ?.takeIf { WhisperTokenizer.langToken(it) >= 0 } ?: DEFAULT_LANGUAGE
    }

    fun vocabularyTerms(text: String): List<String> =
        text.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /** Parsed replacement rules; lines without "=>" or with an empty left side are skipped. */
    fun replacementRules(text: String): List<Pair<String, String>> =
        text.lines().mapNotNull { line ->
            val i = line.indexOf("=>")
            if (i < 0) return@mapNotNull null
            val from = line.substring(0, i).trim()
            if (from.isEmpty()) null else from to line.substring(i + 2).trim()
        }
}
