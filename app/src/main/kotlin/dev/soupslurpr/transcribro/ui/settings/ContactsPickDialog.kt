package dev.soupslurpr.transcribro.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import cz.vyvrnc.transcribronpu.calls.PhoneContacts
import dev.soupslurpr.transcribro.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's contacts with a search (name, company, number) and a tick box per number; the
 * ticked ones go to [onAdd]. Numbers already chosen ([chosen]) start ticked and stay.
 */
@Composable
fun ContactsPickDialog(chosen: Set<String>, onDismiss: () -> Unit, onAdd: (List<PhoneContacts.Entry>) -> Unit) {
    val context = LocalContext.current
    var all by remember { mutableStateOf<List<PhoneContacts.Entry>?>(null) }
    var query by remember { mutableStateOf("") }
    val ticked = remember { mutableStateListOf<String>().apply { addAll(chosen) } }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { runCatching { PhoneContacts.load(context) }.getOrDefault(emptyList()) } }
    val shown = all?.filter { query.isBlank() || it.matches(query) } ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calls_pick_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.calls_pick_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val allShownTicked = shown.isNotEmpty() && shown.all { it.number in ticked }
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = shown.isNotEmpty()) {
                        if (allShownTicked) shown.forEach { if (it.number !in chosen) ticked.remove(it.number) }
                        else shown.forEach { if (it.number !in ticked) ticked.add(it.number) }
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = allShownTicked, onCheckedChange = null)
                    Text(stringResource(R.string.calls_pick_all_shown, shown.size), Modifier.padding(start = 12.dp))
                }
                when {
                    all == null -> Text(stringResource(R.string.calls_pick_loading))
                    shown.isEmpty() -> Text(stringResource(R.string.calls_pick_none))
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(shown, key = { it.number }) { e ->
                            val on = e.number in ticked
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = e.number !in chosen) {
                                    if (on) ticked.remove(e.number) else ticked.add(e.number)
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = null, enabled = e.number !in chosen)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(e.name.ifBlank { e.number })
                                    Text(listOf(e.company, e.number).filter { it.isNotBlank() }.joinToString(" · "))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val added = all?.filter { it.number in ticked && it.number !in chosen } ?: emptyList()
            TextButton(enabled = added.isNotEmpty(), onClick = { onAdd(added) }) {
                Text(stringResource(R.string.calls_pick_add, added.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
