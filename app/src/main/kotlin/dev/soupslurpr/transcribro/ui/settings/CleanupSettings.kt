package dev.soupslurpr.transcribro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.vyvrnc.transcribronpu.llm.LlmCleaner
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.soupslurpr.transcribro.R
import cz.vyvrnc.transcribronpu.download.ModelDownloadService
import cz.vyvrnc.transcribronpu.llm.LlmModel
import cz.vyvrnc.transcribronpu.npu.NpuSettings

/**
 * Gemma text cleanup: model download, an on/off switch (the format follows the content) and
 * the experimental NPU build.
 */
@Composable
fun CleanupSettings() {
    val context = LocalContext.current.applicationContext
    val states by ModelDownloadService.states.collectAsState()
    var model by remember { mutableStateOf(NpuSettings.cleanupModel(context)) }
    val download = if (model == LlmModel.NPU) ModelDownloadService.Model.GEMMA_NPU else ModelDownloadService.Model.GEMMA
    val ready = remember(states[download], model) { model.ready(context) }
    var enabled by remember { mutableStateOf(NpuSettings.cleanup(context)) }
    var customPrompt by remember { mutableStateOf(NpuSettings.cleanupPrompt(context)) }
    var promptDialog by remember { mutableStateOf(false) }
    var appRules by remember { mutableStateOf(NpuSettings.cleanupApps(context)) }
    var appsDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (model == LlmModel.NPU) {
            ModelDownloadItem(
                model = download,
                headline = stringResource(R.string.cleanup_model_title_npu),
                readyText = stringResource(R.string.cleanup_model_ready, model.label(context)),
                idleText = stringResource(R.string.cleanup_model_idle_npu),
            )
        } else {
            ModelDownloadItem(
                model = download,
                headline = stringResource(R.string.cleanup_model_title),
                readyText = stringResource(R.string.cleanup_model_ready, model.label(context)),
                idleText = stringResource(R.string.cleanup_model_idle),
            )
        }
        SettingsSwitchItem(
            name = stringResource(R.string.cleanup_switch_title),
            description = when {
                !enabled -> stringResource(R.string.cleanup_switch_off)
                !ready -> stringResource(R.string.cleanup_switch_no_model)
                else -> stringResource(R.string.cleanup_switch_on)
            },
            checked = enabled,
            onCheckedChange = {
                enabled = it
                NpuSettings.setCleanup(context, it)
            },
        )
        val cleanupLabel = stringResource(R.string.cleanup_rule_cleanup)
        val rawLabel = stringResource(R.string.cleanup_rule_raw)
        SettingsIconItem(
            name = stringResource(R.string.cleanup_per_app_title),
            description = if (appRules.isEmpty()) {
                stringResource(R.string.cleanup_per_app_description)
            } else {
                appRules.entries
                    .map { (pkg, on) -> appLabel(context, pkg) to on }
                    .sortedBy { it.first.lowercase() }
                    .joinToString(" · ") { (label, on) -> "$label: ${if (on) cleanupLabel else rawLabel}" }
            },
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            onClick = {
                appRules = NpuSettings.cleanupApps(context)
                appsDialog = true
            },
        )
        SettingsIconItem(
            name = stringResource(R.string.cleanup_prompt_title),
            description = if (customPrompt == null) stringResource(R.string.cleanup_prompt_default)
                else stringResource(R.string.cleanup_prompt_custom, customPrompt!!.length),
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            onClick = {
                // Re-read: an import may have replaced the prompt since this screen opened.
                customPrompt = NpuSettings.cleanupPrompt(context)
                promptDialog = true
            },
        )
        SettingsSwitchItem(
            name = stringResource(R.string.cleanup_npu_title),
            description = stringResource(R.string.cleanup_npu_description),
            checked = model == LlmModel.NPU,
            onCheckedChange = {
                model = if (it) LlmModel.NPU else LlmModel.GPU
                NpuSettings.setCleanupModel(context, model)
            },
        )
    }

    if (appsDialog) {
        AppRulesDialog(
            defaultOn = enabled,
            onChanged = { appRules = it },
            onDismiss = { appsDialog = false },
        )
    }

    if (promptDialog) {
        PromptDialog(
            initial = customPrompt ?: LlmCleaner.DEFAULT_PROMPT,
            onDismiss = { promptDialog = false },
            onSave = { text ->
                // Saving the unchanged default keeps following future default updates.
                val value = text.takeIf { it.isNotBlank() && it.trim() != LlmCleaner.DEFAULT_PROMPT.trim() }
                NpuSettings.setCleanupPrompt(context, value)
                customPrompt = value
                promptDialog = false
            },
            onReset = {
                NpuSettings.setCleanupPrompt(context, null)
                customPrompt = null
                promptDialog = false
            },
        )
    }
}

private data class AppEntry(val packageName: String, val label: String)

private fun appLabel(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)

/** Apps with a launcher icon (visible through the MAIN query in the manifest), plus apps with a rule. */
private fun launcherApps(context: Context, rules: Set<String>): List<AppEntry> {
    val pm = context.packageManager
    val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
    val missing = rules.filter { pkg -> launcher.none { it.packageName == pkg } }
        .map { AppEntry(it, appLabel(context, it)) }
    return launcher + missing
}

/**
 * Per-app cleanup: a tap cycles default -> cleanup -> raw transcript. Apps that had a rule when
 * the dialog opened stay on top, so rows do not jump while tapping.
 */
@Composable
private fun AppRulesDialog(defaultOn: Boolean, onChanged: (Map<String, Boolean>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var rules by remember { mutableStateOf(NpuSettings.cleanupApps(context)) }
    val pinned = remember { rules.keys }
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { launcherApps(context, pinned) }
    }
    var query by remember { mutableStateOf("") }
    val defaultText = stringResource(if (defaultOn) R.string.cleanup_rule_default_cleanup else R.string.cleanup_rule_default_raw)
    val cleanupLabel = stringResource(R.string.cleanup_rule_cleanup)
    val rawLabel = stringResource(R.string.cleanup_rule_raw)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cleanup_apps_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.cleanup_apps_dialog_hint))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.cleanup_apps_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                val list = apps
                if (list == null) {
                    Text(stringResource(R.string.cleanup_apps_loading), Modifier.padding(top = 12.dp))
                } else {
                    val shown = list
                        .filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
                        .sortedWith(compareBy({ it.packageName !in pinned }, { it.label.lowercase() }))
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .padding(top = 8.dp)
                    ) {
                        items(shown, key = { it.packageName }) { app ->
                            val rule = rules[app.packageName]
                            ListItem(
                                modifier = Modifier.clickable {
                                    val next = when (rule) {
                                        null -> true
                                        true -> false
                                        false -> null
                                    }
                                    NpuSettings.setCleanupApp(context, app.packageName, next)
                                    rules = NpuSettings.cleanupApps(context)
                                    onChanged(rules)
                                },
                                headlineContent = { Text(app.label) },
                                trailingContent = {
                                    Text(
                                        when (rule) {
                                            null -> defaultText
                                            true -> cleanupLabel
                                            false -> rawLabel
                                        },
                                        fontWeight = if (rule != null) FontWeight.SemiBold else null,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
}

@Composable
private fun PromptDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onReset: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cleanup_prompt_title)) },
        text = {
            Column {
                Text(stringResource(R.string.cleanup_prompt_dialog_hint))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .heightIn(min = 200.dp, max = 420.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.cleanup_prompt_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
            }
        },
    )
}
