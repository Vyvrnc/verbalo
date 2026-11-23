package dev.soupslurpr.transcribro.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.vyvrnc.transcribronpu.calls.CallAuto
import cz.vyvrnc.transcribronpu.calls.CallName
import cz.vyvrnc.transcribronpu.calls.CallSettings
import cz.vyvrnc.transcribronpu.calls.CallTriggers
import cz.vyvrnc.transcribronpu.calls.PhoneNumbers
import dev.soupslurpr.transcribro.R

/** Automatic transcription of call recordings: folders, contacts, when, file names. */
@Composable
fun CallSettingsSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(CallSettings.enabled(context)) }
    var input by remember { mutableStateOf(CallSettings.input(context)) }
    var output by remember { mutableStateOf(CallSettings.output(context)) }
    var onlyCharging by remember { mutableStateOf(CallSettings.onlyCharging(context)) }
    var pattern by remember { mutableStateOf(CallSettings.pattern(context)) }
    var contacts by remember { mutableStateOf(CallSettings.contacts(context)) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val status by CallAuto.status.collectAsState()
    LaunchedEffect(Unit) { CallAuto.refreshStatus(context) }

    fun changed(forget: Boolean = false) {
        if (forget) CallAuto.forgetSkipped(context)
        CallTriggers.update(context)
    }

    val pickInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { keep(context, uri); CallSettings.setInput(context, uri); input = uri; changed(forget = true) }
    }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { keep(context, uri); CallSettings.setOutput(context, uri); output = uri; changed() }
    }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        val picked = readPhone(context, uri)
        if (picked == null) {
            Toast.makeText(context, R.string.calls_contact_failed, Toast.LENGTH_LONG).show()
        } else {
            CallSettings.addContact(context, picked)
            contacts = CallSettings.contacts(context)
            changed(forget = true)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSwitchItem(
            name = stringResource(R.string.calls_enabled_title),
            description = when {
                input == null || output == null -> stringResource(R.string.calls_enabled_need_folders)
                contacts.isEmpty() -> stringResource(R.string.calls_enabled_need_contacts)
                enabled -> stringResource(R.string.calls_status, status.done, status.waiting, status.failed, status.skipped)
                else -> stringResource(R.string.calls_enabled_description)
            },
            checked = enabled,
            onCheckedChange = {
                enabled = it
                CallSettings.setEnabled(context, it)
                changed()
            },
        )
        Item(
            stringResource(R.string.calls_input_title),
            input?.let { folderName(it) } ?: stringResource(R.string.calls_input_description),
        ) { pickInput.launch(null) }
        Item(
            stringResource(R.string.calls_output_title),
            output?.let { folderName(it) } ?: stringResource(R.string.calls_output_description),
        ) { pickOutput.launch(null) }
        Item(
            stringResource(R.string.calls_contacts_title),
            if (contacts.isEmpty()) stringResource(R.string.calls_contacts_description)
            else contacts.joinToString(", ") { it.name.ifBlank { it.number } },
        ) { dialog = "contacts" }
        Item(
            stringResource(R.string.calls_when_title),
            stringResource(if (onlyCharging) R.string.calls_when_charging else R.string.calls_when_now),
        ) { dialog = "when" }
        Item(
            stringResource(R.string.calls_pattern_title),
            if (pattern == null) stringResource(R.string.calls_pattern_scr) else stringResource(R.string.calls_pattern_custom),
        ) { dialog = "pattern" }
        Item(
            stringResource(R.string.calls_older_title),
            stringResource(R.string.calls_older_description),
        ) { dialog = "older" }
    }

    when (dialog) {
        "contacts" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.calls_contacts_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.calls_contacts_hint))
                    contacts.forEach { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name.ifBlank { c.number })
                                if (c.name.isNotBlank()) Text(c.number)
                            }
                            IconButton(onClick = {
                                CallSettings.removeContact(context, c.number)
                                contacts = CallSettings.contacts(context)
                                changed()
                            }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.calls_contact_remove)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pickContact.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE))
                }) { Text(stringResource(R.string.calls_contact_add)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.settings_close)) } },
        )
        "when" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.calls_when_title)) },
            text = {
                Column {
                    listOf(false to R.string.calls_when_now, true to R.string.calls_when_charging).forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = value == onlyCharging) {
                                onlyCharging = value
                                CallSettings.setOnlyCharging(context, value)
                                changed()
                                dialog = null
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == onlyCharging, onClick = null)
                            Text(stringResource(label), Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.settings_close)) } },
        )
        "pattern" -> {
            var custom by remember { mutableStateOf(pattern != null) }
            var text by remember { mutableStateOf(pattern ?: CallName.SHIZU_CALL_RECORDER) }
            val ok = !custom || CallName.valid(text)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.calls_pattern_title)) },
                text = {
                    Column {
                        listOf(false to R.string.calls_pattern_scr, true to R.string.calls_pattern_custom).forEach { (value, label) ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = value == custom) { custom = value }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = value == custom, onClick = null)
                                Text(stringResource(label), Modifier.padding(start = 12.dp))
                            }
                        }
                        if (custom) {
                            Text(stringResource(R.string.calls_pattern_hint), Modifier.padding(top = 8.dp))
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                isError = !ok,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        } else {
                            Text(stringResource(R.string.calls_pattern_scr_example), Modifier.padding(top = 8.dp))
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = ok, onClick = {
                        pattern = if (custom) text.trim() else null
                        CallSettings.setPattern(context, pattern)
                        changed(forget = true)
                        dialog = null
                    }) { Text(stringResource(R.string.settings_save)) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.settings_cancel)) } },
            )
        }
        "older" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.calls_older_title)) },
            text = { Text(stringResource(R.string.calls_older_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    CallSettings.setSince(context, 0)
                    changed(forget = true)
                    dialog = null
                }) { Text(stringResource(R.string.calls_older_go)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@Composable
private fun Item(name: String, description: String, onClick: () -> Unit) {
    SettingsIconItem(name = name, description = description, icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight, onClick = onClick)
}

/** Read access that survives a restart, and write access for the output folder. */
private fun keep(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}

/** "primary:Recordings/CallRecorder" -> "Recordings/CallRecorder". */
private fun folderName(uri: Uri): String =
    runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "/" } }.getOrDefault(uri.toString())

/** The picked phone row (a one-time grant from the contact picker, no contacts permission). */
private fun readPhone(context: Context, uri: Uri): CallSettings.Contact? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
        null, null, null,
    )?.use { c ->
        if (!c.moveToFirst()) null
        else c.getString(0)?.let { CallSettings.Contact(c.getString(1) ?: "", PhoneNumbers.normalize(it)) }
    }
}.getOrNull()
