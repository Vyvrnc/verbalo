package cz.vyvrnc.transcribronpu.npu

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import dev.soupslurpr.transcribro.R
import cz.vyvrnc.transcribronpu.llm.LlmModel

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
    private const val KEY_CLEANUP_MODE = "cleanup_mode"
    private const val KEY_CLEANUP = "cleanup"
    private const val KEY_CLEANUP_NPU = "cleanup_npu"
    private const val KEY_CLEANUP_APPS = "cleanup_apps"
    private const val KEY_HISTORY = "save_history"
    private const val KEY_CLEANUP_PROMPT = "cleanup_prompt"

    /** Language from the calling app (RecognizerIntent.EXTRA_LANGUAGE), Czech without one. */
    const val LANGUAGE_FROM_CALLER = "caller"
    const val LANGUAGE_AUTO = "auto"
    const val DEFAULT_LANGUAGE = "cs"

    /** Choices offered in the settings: value to label string resource. */
    val LANGUAGE_CHOICES: List<Pair<String, Int>> = listOf(
        LANGUAGE_FROM_CALLER to R.string.npu_language_from_app,
        "cs" to R.string.npu_language_cs,
        "sk" to R.string.npu_language_sk,
        "en" to R.string.npu_language_en,
        "de" to R.string.npu_language_de,
        "pl" to R.string.npu_language_pl,
        LANGUAGE_AUTO to R.string.npu_language_auto,
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
     * Gemma text cleanup for the floating button. 0.2.0 stored a mode name under
     * [KEY_CLEANUP_MODE]; any mode other than "OFF" counts as on.
     */
    fun cleanup(context: Context): Boolean {
        val p = prefs(context)
        if (p.contains(KEY_CLEANUP)) return p.getBoolean(KEY_CLEANUP, false)
        return p.getString(KEY_CLEANUP_MODE, null).let { it != null && it != "OFF" }
    }
    fun setCleanup(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_CLEANUP, value).apply()

    /** Per-app override of [cleanup]: package to on/off. Apps not listed follow the switch. */
    fun cleanupApps(context: Context): Map<String, Boolean> =
        parseCleanupApps(prefs(context).getString(KEY_CLEANUP_APPS, null).orEmpty())
    fun cleanupAppsText(context: Context): String = formatCleanupApps(cleanupApps(context))
    fun setCleanupApps(context: Context, apps: Map<String, Boolean>) =
        prefs(context).edit().putString(KEY_CLEANUP_APPS, formatCleanupApps(apps)).apply()

    /** null removes the app's rule, so it follows the switch again. */
    fun setCleanupApp(context: Context, packageName: String, value: Boolean?) =
        setCleanupApps(context, cleanupApps(context).toMutableMap().apply {
            if (value == null) remove(packageName) else put(packageName, value)
        })

    /** Whether dictation into [packageName] gets cleaned up; null (target unknown) follows the switch. */
    fun cleanupFor(context: Context, packageName: String?): Boolean =
        packageName?.let { cleanupApps(context)[it] } ?: cleanup(context)

    /** "package=on" / "package=off" per line. */
    fun parseCleanupApps(text: String): Map<String, Boolean> =
        text.lines().mapNotNull { line ->
            val i = line.lastIndexOf('=')
            if (i <= 0) return@mapNotNull null
            when (line.substring(i + 1).trim()) {
                "on" -> line.substring(0, i).trim() to true
                "off" -> line.substring(0, i).trim() to false
                else -> null
            }
        }.toMap()

    private fun formatCleanupApps(apps: Map<String, Boolean>): String =
        apps.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${if (it.value) "on" else "off"}" }

    /** The user's own instructions for the cleanup model; null means the built-in prompt. */
    fun cleanupPrompt(context: Context): String? =
        prefs(context).getString(KEY_CLEANUP_PROMPT, null)?.takeIf { it.isNotBlank() }
    fun setCleanupPrompt(context: Context, value: String?) =
        prefs(context).edit().putString(KEY_CLEANUP_PROMPT, value?.takeIf { it.isNotBlank() }).apply()

    /** Keep recordings and transcripts of dictations in the app (History screen). */
    fun saveHistory(context: Context): Boolean = prefs(context).getBoolean(KEY_HISTORY, true)
    fun setSaveHistory(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_HISTORY, value).apply()

    /** Cleanup model build: the SM8750 NPU one (experimental) or the generic GPU one. */
    fun cleanupModel(context: Context): LlmModel =
        if (prefs(context).getBoolean(KEY_CLEANUP_NPU, false)) LlmModel.NPU else LlmModel.GPU
    fun setCleanupModel(context: Context, value: LlmModel) =
        prefs(context).edit().putBoolean(KEY_CLEANUP_NPU, value == LlmModel.NPU).apply()

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
