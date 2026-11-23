package dev.soupslurpr.transcribro.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import cz.vyvrnc.transcribronpu.calls.CallSettings
import cz.vyvrnc.transcribronpu.calls.PhoneContacts
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.ui.reusablecomposables.PrimaryButton
import dev.soupslurpr.transcribro.ui.reusablecomposables.SecondaryButton
import dev.soupslurpr.transcribro.ui.theme.VerbaloTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One row of a contact list page. */
private class Row(val name: String, val company: String, val number: String) {
    val entry = PhoneContacts.Entry(name, company, number)
}

/**
 * The chosen contacts on a full page: search, tick many, remove them; with nothing ticked the
 * button adds contacts ([onAdd]). Companies come from the phone's contacts when allowed.
 */
@Composable
fun ChosenContactsPage(onAdd: () -> Unit, onChanged: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf(CallSettings.contacts(context)) }
    var companies by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            companies = withContext(Dispatchers.IO) {
                runCatching { PhoneContacts.load(context).associate { it.number to it.company } }.getOrDefault(emptyMap())
            }
        }
    }
    val rows = contacts.map { Row(it.name, it.company.ifBlank { companies[it.number] ?: "" }, it.number) }
    ContactListPage(
        title = stringResource(R.string.calls_contacts_title),
        rows = rows,
        loading = false,
        empty = stringResource(R.string.calls_contacts_description),
        onBack = onBack,
    ) { ticked, clear ->
        if (ticked.isEmpty()) {
            PrimaryButton(stringResource(R.string.calls_contact_add), onAdd, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
        } else {
            SecondaryButton(
                stringResource(R.string.calls_contacts_remove, ticked.size),
                {
                    CallSettings.removeContacts(context, ticked.toSet())
                    contacts = CallSettings.contacts(context)
                    clear()
                    onChanged()
                },
                Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete, destructive = true,
            )
        }
    }
}

/** The phone's contacts on a full page: search by name, company or number, tick many, add. */
@Composable
fun PickContactsPage(onAdded: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val chosen = remember { CallSettings.contacts(context).map { it.number }.toSet() }
    var all by remember { mutableStateOf<List<PhoneContacts.Entry>?>(null) }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { runCatching { PhoneContacts.load(context) }.getOrDefault(emptyList()) } }
    // Already chosen numbers are not offered again.
    val rows = all?.filter { it.number !in chosen }?.map { Row(it.name, it.company, it.number) } ?: emptyList()
    ContactListPage(
        title = stringResource(R.string.calls_pick_title),
        rows = rows,
        loading = all == null,
        empty = stringResource(R.string.calls_pick_none),
        onBack = onBack,
    ) { ticked, _ ->
        PrimaryButton(
            stringResource(R.string.calls_pick_add, ticked.size),
            {
                val set = ticked.toSet()
                CallSettings.addContacts(context, rows.filter { it.number in set }.map { CallSettings.Contact(it.name, it.number, it.company) })
                onAdded()
            },
            Modifier.fillMaxWidth(), enabled = ticked.isNotEmpty(), icon = Icons.Rounded.Add,
        )
    }
}

/**
 * Full-screen, opaque list with a search field, "tick all shown" and a tick box per row.
 * [bottom] gets the ticked numbers and a function that clears them.
 */
@Composable
private fun ContactListPage(
    title: String,
    rows: List<Row>,
    loading: Boolean,
    empty: String,
    onBack: () -> Unit,
    bottom: @Composable (List<String>, () -> Unit) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val ticked = remember { mutableStateListOf<String>() }
    val shown = if (query.isBlank()) rows else rows.filter { it.entry.matches(query) }
    val c = VerbaloTheme.colors
    val boxColors = CheckboxDefaults.colors(checkedColor = c.primary, checkmarkColor = c.onPrimary, uncheckedColor = c.textMuted)

    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.settings_close), tint = c.textPrimary)
                }
                Text(title, color = c.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("${rows.size}", color = c.textMuted, modifier = Modifier.padding(end = 16.dp))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.calls_pick_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            val allShownTicked = shown.isNotEmpty() && shown.all { it.number in ticked }
            Row(
                Modifier.fillMaxWidth().clickable(enabled = shown.isNotEmpty()) {
                    if (allShownTicked) shown.forEach { ticked.remove(it.number) }
                    else shown.forEach { if (it.number !in ticked) ticked.add(it.number) }
                }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = allShownTicked, onCheckedChange = null, colors = boxColors)
                Text(
                    stringResource(R.string.calls_pick_all_shown, shown.size) +
                        if (ticked.isNotEmpty()) " · " + stringResource(R.string.calls_pick_ticked, ticked.size) else "",
                    color = c.textSecondary, modifier = Modifier.padding(start = 12.dp),
                )
            }
            HorizontalDivider(color = c.border)
            when {
                loading -> Text(stringResource(R.string.calls_pick_loading), color = c.textMuted, modifier = Modifier.padding(16.dp).weight(1f))
                shown.isEmpty() -> Text(empty, color = c.textMuted, modifier = Modifier.padding(16.dp).weight(1f))
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.number }) { r ->
                        val on = r.number in ticked
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) ticked.remove(r.number) else ticked.add(r.number) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null, colors = boxColors)
                            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(r.name.ifBlank { r.number }, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(r.company, r.number).filter { it.isNotBlank() }.joinToString(" · "),
                                    color = c.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = c.border)
            Column(Modifier.fillMaxWidth().padding(16.dp)) { bottom(ticked.toList()) { ticked.clear() } }
        }
    }
}
