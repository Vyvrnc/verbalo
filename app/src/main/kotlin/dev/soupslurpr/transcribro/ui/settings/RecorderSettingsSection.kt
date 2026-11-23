package dev.soupslurpr.transcribro.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.vyvrnc.transcribronpu.calls.CallAuto
import cz.vyvrnc.transcribronpu.calls.CallTriggers
import cz.vyvrnc.transcribronpu.calls.RecorderSettings
import dev.soupslurpr.transcribro.R

/** Automatic transcription of a voice recorder's folder: every new recording, no filter. */
@Composable
fun RecorderSettingsSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(RecorderSettings.enabled(context)) }
    var input by remember { mutableStateOf(RecorderSettings.input(context)) }
    var output by remember { mutableStateOf(RecorderSettings.output(context)) }
    var confirmOlder by remember { mutableStateOf(false) }

    val pickInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { keepTree(context, uri); RecorderSettings.setInput(context, uri); input = uri; CallTriggers.update(context) }
    }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { keepTree(context, uri); RecorderSettings.setOutput(context, uri); output = uri; CallTriggers.update(context) }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSwitchItem(
            name = stringResource(R.string.recorder_enabled_title),
            description = if (input == null || output == null) stringResource(R.string.recorder_need_folders)
                else stringResource(R.string.recorder_enabled_description),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                RecorderSettings.setEnabled(context, it)
                CallTriggers.update(context)
            },
        )
        AutoItem(
            stringResource(R.string.recorder_input_title),
            input?.let { folderName(it) } ?: stringResource(R.string.recorder_input_description),
        ) { pickInput.launch(null) }
        AutoItem(
            stringResource(R.string.calls_output_title),
            output?.let { folderName(it) } ?: stringResource(R.string.calls_output_description),
        ) { pickOutput.launch(null) }
        AutoItem(
            stringResource(R.string.calls_older_title),
            stringResource(R.string.recorder_older_description),
        ) { confirmOlder = true }
    }

    if (confirmOlder) AlertDialog(
        onDismissRequest = { confirmOlder = false },
        title = { Text(stringResource(R.string.calls_older_title)) },
        text = { Text(stringResource(R.string.recorder_older_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                RecorderSettings.setSince(context, 0)
                CallAuto.forgetSkipped(context)
                CallTriggers.update(context)
                confirmOlder = false
            }) { Text(stringResource(R.string.calls_older_go)) }
        },
        dismissButton = { TextButton(onClick = { confirmOlder = false }) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
