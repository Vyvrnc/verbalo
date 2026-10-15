package dev.soupslurpr.transcribro.ui.settings

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

    Column(Modifier.fillMaxWidth()) {
        if (model == LlmModel.NPU) {
            ModelDownloadItem(
                model = download,
                headline = "Model pro úpravu textu (Gemma 4 E2B, NPU)",
                readyText = "Staženo a ověřeno (SHA-256). ${model.label}, licence Apache 2.0.",
                idleText = "Není stažený. Klepnutím stáhnete 3,0 GB z Hugging Face (litert-community), " +
                    "doporučeno přes Wi-Fi. Stahuje se i na pozadí, běží jen v telefonu.",
            )
        } else {
            ModelDownloadItem(
                model = download,
                headline = "Model pro úpravu textu (Gemma 4 E2B)",
                readyText = "Staženo a ověřeno (SHA-256). ${model.label}, licence Apache 2.0.",
                idleText = "Není stažený. Klepnutím stáhnete 2,6 GB z Hugging Face (litert-community), " +
                    "doporučeno přes Wi-Fi. Stahuje se i na pozadí, běží jen v telefonu.",
            )
        }
        SettingsSwitchItem(
            name = "Úprava diktátu",
            description = when {
                !enabled -> "Text z plovoucího tlačítka se vkládá tak, jak ho Whisper přepsal " +
                    "(kromě aplikací, kde je úprava zapnutá níže)."
                !ready -> "Bez staženého modelu se text vloží neupravený."
                else -> "Opraví chyby přepisu, interpunkci a přeřeky. Výčet převede na odrážky, " +
                    "v e-mailu udělá odstavce – podle obsahu a aplikace, do které píšete. " +
                    "Text se vloží pár sekund po konci diktátu."
            },
            checked = enabled,
            onCheckedChange = {
                enabled = it
                NpuSettings.setCleanup(context, it)
            },
        )
        ListItem(
            modifier = Modifier.clickable {
                appRules = NpuSettings.cleanupApps(context)
                appsDialog = true
            },
            headlineContent = { Text("Podle aplikace", fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    if (appRules.isEmpty()) {
                        "Všude stejně. Klepnutím nastavíte aplikace, kde chcete jen přepis (třeba chat " +
                            "s AI), nebo naopak úpravu."
                    } else {
                        appRules.entries
                            .map { (pkg, on) -> appLabel(context, pkg) to on }
                            .sortedBy { it.first.lowercase() }
                            .joinToString(" · ") { (label, on) -> "$label: ${if (on) "úprava" else "jen přepis"}" }
                    }
                )
            },
        )
        ListItem(
            modifier = Modifier.clickable {
                // Re-read: an import may have replaced the prompt since this screen opened.
                customPrompt = NpuSettings.cleanupPrompt(context)
                promptDialog = true
            },
            headlineContent = { Text("Pokyny pro úpravu textu", fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    if (customPrompt == null) "Výchozí. Klepnutím je upravíte (styl, oslovení, co měnit a co ne)."
                    else "Vlastní (${customPrompt!!.length} znaků). Klepnutím upravíte nebo vrátíte výchozí."
                )
            },
        )
        SettingsSwitchItem(
            name = "Gemma na NPU (zkušební)",
            description = "Varianta pro Snapdragon 8 Elite (3,0 GB, stahuje se zvlášť). Měla by být rychlejší " +
                "a šetrnější k baterii než GPU. Když na NPU selže, vloží se text neupravený.",
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
    val defaultText = if (defaultOn) "výchozí (úprava)" else "výchozí (jen přepis)"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Úprava podle aplikace") },
        text = {
            Column {
                Text(
                    "Klepnutím přepínáte: výchozí → úprava → jen přepis. Jen přepis se vkládá už během " +
                        "mluvení, bez čekání na Gemmu. Aplikaci pozná jen zapnuté vkládání do pole.",
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Hledat aplikaci") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                val list = apps
                if (list == null) {
                    Text("Načítám aplikace…", Modifier.padding(top = 12.dp))
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
                                            true -> "úprava"
                                            false -> "jen přepis"
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Hotovo") } },
    )
}

@Composable
private fun PromptDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onReset: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pokyny pro úpravu textu") },
        text = {
            Column {
                Text(
                    "Systémový prompt pro Gemmu. Diktát dostane mezi značkami <dictation>, cílovou aplikaci " +
                        "a text před kurzorem nad nimi. Příklady v pokynech pomáhají malému modelu nejvíc.",
                )
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
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Uložit") } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("Výchozí") }
                TextButton(onClick = onDismiss) { Text("Zrušit") }
            }
        },
    )
}
