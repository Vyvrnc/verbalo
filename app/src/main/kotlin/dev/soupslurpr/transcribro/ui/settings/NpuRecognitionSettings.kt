package dev.soupslurpr.transcribro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.soupslurpr.transcribro.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.vyvrnc.transcribronpu.npu.Customization
import cz.vyvrnc.transcribronpu.npu.NpuSettings

/** Language, vocabulary, replacements and live preview of the NPU recognition. */
@Composable
fun NpuRecognitionSettings() {
    val context = LocalContext.current.applicationContext
    var language by remember { mutableStateOf(NpuSettings.language(context)) }
    var vocabulary by remember { mutableStateOf(NpuSettings.vocabulary(context)) }
    var replacements by remember { mutableStateOf(NpuSettings.replacements(context)) }
    var live by remember { mutableStateOf(NpuSettings.livePartials(context)) }
    var dialog by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Customization.export(context).toByteArray()) }
        }.isSuccess
        Toast.makeText(context, context.getString(if (ok) R.string.settings_saved else R.string.settings_save_failed), Toast.LENGTH_SHORT).show()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val message = runCatching {
            val json = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            Customization.import(context, json)
        }.getOrElse { it.message ?: context.getString(R.string.settings_import_failed) }
        vocabulary = NpuSettings.vocabulary(context)
        replacements = NpuSettings.replacements(context)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsItem(
            name = stringResource(R.string.settings_language_title),
            description = NpuSettings.LANGUAGE_CHOICES.firstOrNull { it.first == language }?.second?.let { stringResource(it) } ?: language,
        ) { dialog = "language" }
        SettingsItem(
            name = stringResource(R.string.settings_vocabulary_title),
            description = NpuSettings.vocabularyTerms(vocabulary).takeIf { it.isNotEmpty() }
                ?.joinToString(", ")?.let { stringResource(R.string.settings_vocabulary_hints, it) }
                ?: stringResource(R.string.settings_vocabulary_description),
        ) { dialog = "vocabulary" }
        SettingsItem(
            name = stringResource(R.string.settings_replacements_title),
            description = NpuSettings.replacementRules(replacements).size.let {
                if (it == 0) stringResource(R.string.settings_replacements_description) else stringResource(R.string.settings_replacements_count, it)
            },
        ) { dialog = "replacements" }
        SettingsItem(
            name = stringResource(R.string.settings_export_title),
            description = stringResource(R.string.settings_export_description),
        ) { exportLauncher.launch("verbalo-customization.json") }
        SettingsItem(
            name = stringResource(R.string.settings_import_title),
            description = stringResource(R.string.settings_import_description),
        ) { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        SettingsSwitchItem(
            name = stringResource(R.string.settings_live_title),
            description = stringResource(R.string.settings_live_description),
            checked = live,
            onCheckedChange = { live = it; NpuSettings.setLivePartials(context, it) },
        )
    }

    when (dialog) {
        "language" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.settings_language_dialog_title)) },
            text = {
                Column {
                    NpuSettings.LANGUAGE_CHOICES.forEach { (value, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = value == language) {
                                    language = value
                                    NpuSettings.setLanguage(context, value)
                                    dialog = null
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == language, onClick = null)
                            Text(stringResource(label), Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.settings_close)) } },
        )
        "vocabulary" -> TextDialog(
            title = stringResource(R.string.settings_vocabulary_title),
            hint = stringResource(R.string.settings_vocabulary_dialog_hint),
            initial = vocabulary,
            onDismiss = { dialog = null },
            onSave = { vocabulary = it; NpuSettings.setVocabulary(context, it); dialog = null },
        )
        "replacements" -> TextDialog(
            title = stringResource(R.string.settings_replacements_title),
            hint = stringResource(R.string.settings_replacements_dialog_hint),
            initial = replacements,
            onDismiss = { dialog = null },
            onSave = { replacements = it; NpuSettings.setReplacements(context, it); dialog = null },
        )
    }
}

@Composable
private fun SettingsItem(name: String, description: String, onClick: () -> Unit) {
    SettingsIconItem(name = name, description = description, icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight, onClick = onClick)
}

@Composable
private fun TextDialog(title: String, hint: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .heightIn(min = 140.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
