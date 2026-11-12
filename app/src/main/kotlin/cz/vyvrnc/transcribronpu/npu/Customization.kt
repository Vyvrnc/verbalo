package cz.vyvrnc.transcribronpu.npu

import android.content.Context
import dev.soupslurpr.transcribro.R
import org.json.JSONObject

/**
 * Export and import of the user's customisation (vocabulary, replacements, cleanup prompt,
 * per-app cleanup rules) as one JSON file, to move it to another phone or share it within a
 * team. Import merges the vocabulary, the replacements and the app rules (nothing is lost) and
 * replaces the prompt when the file has one.
 */
object Customization {
    private const val FORMAT = "transcribro-npu-customization"

    fun export(context: Context): String = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("vocabulary", NpuSettings.vocabulary(context))
        .put("replacements", NpuSettings.replacements(context))
        .apply { NpuSettings.cleanupPrompt(context)?.let { put("cleanupPrompt", it) } }
        .apply { NpuSettings.cleanupAppsText(context).takeIf { it.isNotEmpty() }?.let { put("cleanupApps", it) } }
        .toString(2)

    /** Returns a short summary for the user; throws IllegalArgumentException on a foreign file. */
    fun import(context: Context, json: String): String {
        val o = runCatching { JSONObject(json) }.getOrNull()
        require(o != null && o.optString("format") == FORMAT) { context.getString(R.string.npu_import_not_export) }

        val oldTerms = NpuSettings.vocabularyTerms(NpuSettings.vocabulary(context))
        val newTerms = NpuSettings.vocabularyTerms(o.optString("vocabulary"))
            .filter { t -> oldTerms.none { it.equals(t, ignoreCase = true) } }
        if (newTerms.isNotEmpty()) {
            NpuSettings.setVocabulary(context, (oldTerms + newTerms).joinToString(", "))
        }

        val oldRules = NpuSettings.replacementRules(NpuSettings.replacements(context))
        val newLines = o.optString("replacements").lines().filter { line ->
            NpuSettings.replacementRules(line).singleOrNull()?.let { rule ->
                oldRules.none { it.first.equals(rule.first, ignoreCase = true) }
            } ?: false
        }
        if (newLines.isNotEmpty()) {
            val current = NpuSettings.replacements(context).trimEnd()
            NpuSettings.setReplacements(context, (if (current.isEmpty()) "" else current + "\n") + newLines.joinToString("\n"))
        }

        val prompt = if (o.has("cleanupPrompt")) o.getString("cleanupPrompt") else null
        if (prompt != null) NpuSettings.setCleanupPrompt(context, prompt)

        // Like the replacements: rules already set on this phone win.
        val oldApps = NpuSettings.cleanupApps(context)
        val newApps = NpuSettings.parseCleanupApps(o.optString("cleanupApps")).filterKeys { it !in oldApps }
        if (newApps.isNotEmpty()) NpuSettings.setCleanupApps(context, oldApps + newApps)

        return context.getString(R.string.npu_import_summary, newTerms.size, newLines.size) +
            (if (newApps.isNotEmpty()) context.getString(R.string.npu_import_app_rules, newApps.size) else "") +
            (if (prompt != null) context.getString(R.string.npu_import_prompt_replaced) else ".")
    }
}
