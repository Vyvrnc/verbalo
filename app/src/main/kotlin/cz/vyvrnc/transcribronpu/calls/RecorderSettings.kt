package cz.vyvrnc.transcribronpu.calls

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Automatic transcription of a voice recorder's folder (Samsung Voice Recorder and others):
 * every new audio file, no filter. When it runs is shared with calls ([CallSettings.onlyCharging]).
 */
object RecorderSettings {
    private const val FILE = "recorder_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INPUT = "input_tree"
    private const val KEY_OUTPUT = "output_tree"
    private const val KEY_SINCE = "since_ms"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) {
        // Recordings made before the switch was turned on are left alone unless asked for.
        val edit = prefs(context).edit().putBoolean(KEY_ENABLED, value)
        if (value && !prefs(context).contains(KEY_SINCE)) edit.putLong(KEY_SINCE, System.currentTimeMillis())
        edit.apply()
    }

    fun input(context: Context): Uri? = prefs(context).getString(KEY_INPUT, null)?.let(Uri::parse)
    fun setInput(context: Context, value: Uri) = prefs(context).edit().putString(KEY_INPUT, value.toString()).apply()

    fun output(context: Context): Uri? = prefs(context).getString(KEY_OUTPUT, null)?.let(Uri::parse)
    fun setOutput(context: Context, value: Uri) = prefs(context).edit().putString(KEY_OUTPUT, value.toString()).apply()

    fun since(context: Context): Long = prefs(context).getLong(KEY_SINCE, Long.MAX_VALUE)
    fun setSince(context: Context, value: Long) = prefs(context).edit().putLong(KEY_SINCE, value).apply()
}
