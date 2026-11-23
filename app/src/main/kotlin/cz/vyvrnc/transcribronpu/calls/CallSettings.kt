package cz.vyvrnc.transcribronpu.calls

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Settings of the automatic transcription of call recordings. */
object CallSettings {
    private const val FILE = "call_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INPUT = "input_tree"
    private const val KEY_OUTPUT = "output_tree"
    private const val KEY_ONLY_CHARGING = "only_charging"
    private const val KEY_PATTERN = "name_pattern"
    private const val KEY_CONTACTS = "contacts"
    private const val KEY_SINCE = "since_ms"

    /** A chosen contact; [number] is in international form (+420…). */
    data class Contact(val name: String, val number: String)

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

    /** false: a new recording is transcribed at once; true: only while the phone charges. */
    fun onlyCharging(context: Context): Boolean = prefs(context).getBoolean(KEY_ONLY_CHARGING, false)
    fun setOnlyCharging(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ONLY_CHARGING, value).apply()

    /** A custom file-name pattern, or null for the built-in ones (see [CallName]). */
    fun pattern(context: Context): String? = prefs(context).getString(KEY_PATTERN, null)?.takeIf { it.isNotBlank() }
    fun setPattern(context: Context, value: String?) = prefs(context).edit().putString(KEY_PATTERN, value?.trim()).apply()

    /** Recordings last changed before this time are not transcribed (0 = all). */
    fun since(context: Context): Long = prefs(context).getLong(KEY_SINCE, Long.MAX_VALUE)
    fun setSince(context: Context, value: Long) = prefs(context).edit().putLong(KEY_SINCE, value).apply()

    fun contacts(context: Context): List<Contact> {
        val raw = prefs(context).getString(KEY_CONTACTS, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { a.getJSONObject(it).let { o -> Contact(o.getString("name"), o.getString("number")) } }
        }.getOrDefault(emptyList())
    }

    fun setContacts(context: Context, value: List<Contact>) {
        val a = JSONArray()
        value.forEach { a.put(JSONObject().put("name", it.name).put("number", it.number)) }
        prefs(context).edit().putString(KEY_CONTACTS, a.toString()).apply()
    }

    fun addContact(context: Context, contact: Contact) = addContacts(context, listOf(contact))

    fun addContacts(context: Context, added: List<Contact>) {
        val all = contacts(context).toMutableList()
        for (c in added) if (all.none { PhoneNumbers.same(it.number, c.number) }) all.add(c)
        setContacts(context, all)
    }

    fun removeContact(context: Context, number: String) =
        setContacts(context, contacts(context).filterNot { it.number == number })
}
