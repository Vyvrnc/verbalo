package cz.vyvrnc.transcribronpu.calls

import android.content.Context
import android.provider.ContactsContract
import java.text.Normalizer

/** The phone's contacts with numbers and company, for choosing many at once (needs READ_CONTACTS). */
object PhoneContacts {
    data class Entry(val name: String, val company: String, val number: String) {
        private val key = fold("$name $company $number")

        /** Every word of [query] in the name, company or number, without accents. */
        fun matches(query: String): Boolean = fold(query).split(' ').filter { it.isNotBlank() }.all { key.contains(it) }
    }

    fun load(context: Context): List<Entry> {
        val r = context.contentResolver
        val companies = HashMap<Long, String>()
        r.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Organization.COMPANY),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
            null,
        )?.use { c -> while (c.moveToNext()) c.getString(1)?.takeIf { it.isNotBlank() }?.let { companies[c.getLong(0)] = it } }
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        r.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(2)?.let { PhoneNumbers.normalize(it) }?.takeIf { it.isNotEmpty() } ?: continue
                val name = c.getString(1) ?: ""
                // The same number saved twice (SIM, Google, WhatsApp) is listed once.
                if (!seen.add(number)) continue
                out.add(Entry(name, companies[c.getLong(0)] ?: "", number))
            }
        }
        return out
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
