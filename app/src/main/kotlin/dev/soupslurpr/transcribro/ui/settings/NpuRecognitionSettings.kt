package dev.soupslurpr.transcribro.ui.settings

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
        Toast.makeText(context, if (ok) "Uloženo" else "Uložení se nepovedlo", Toast.LENGTH_SHORT).show()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val message = runCatching {
            val json = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            Customization.import(context, json)
        }.getOrElse { it.message ?: "Import se nepovedl" }
        vocabulary = NpuSettings.vocabulary(context)
        replacements = NpuSettings.replacements(context)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsItem(
            name = "Jazyk",
            description = NpuSettings.LANGUAGE_CHOICES.firstOrNull { it.first == language }?.second ?: language,
        ) { dialog = "language" }
        SettingsItem(
            name = "Slovník",
            description = NpuSettings.vocabularyTerms(vocabulary).takeIf { it.isNotEmpty() }
                ?.joinToString(", ")?.let { "Napovídá modelu: $it" }
                ?: "Jména a odborné výrazy, které má model upřednostnit (např. Flowbox, Claude, DI).",
        ) { dialog = "vocabulary" }
        SettingsItem(
            name = "Náhrady",
            description = NpuSettings.replacementRules(replacements).size.let {
                if (it == 0) "Opraví slova, která model slyší jinak: „dejíčko => DI“." else "Pravidel: $it"
            },
        ) { dialog = "replacements" }
        SettingsItem(
            name = "Exportovat slovník a náhrady",
            description = "Uloží slovník, náhrady a vlastní pokyny pro úpravu textu do souboru JSON.",
        ) { exportLauncher.launch("transcribro-prizpusobeni.json") }
        SettingsItem(
            name = "Importovat slovník a náhrady",
            description = "Doplní výrazy a náhrady ze souboru (stávající zůstanou), vlastní pokyny nahradí.",
        ) { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        SettingsSwitchItem(
            name = "Živý přepis",
            description = "Během mluvení ukazuje rozpracovaný text (klávesnice Transcribro a plovoucí tlačítko). " +
                "Víc zatěžuje NPU a baterii.",
            checked = live,
            onCheckedChange = { live = it; NpuSettings.setLivePartials(context, it) },
        )
    }

    when (dialog) {
        "language" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Jazyk diktování") },
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
                            Text(label, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Zavřít") } },
        )
        "vocabulary" -> TextDialog(
            title = "Slovník",
            hint = "Výrazy oddělené čárkou nebo na řádcích. Model je dostane jako kontext, takže je " +
                "spíš napíše správně. Stačí pár desítek slov, delší seznam se zkrátí.",
            initial = vocabulary,
            onDismiss = { dialog = null },
            onSave = { vocabulary = it; NpuSettings.setVocabulary(context, it); dialog = null },
        )
        "replacements" -> TextDialog(
            title = "Náhrady",
            hint = "Jedno pravidlo na řádek ve tvaru „slyšené => napsané“. Celá slova, velikost písmen nehraje roli.",
            initial = replacements,
            onDismiss = { dialog = null },
            onSave = { replacements = it; NpuSettings.setReplacements(context, it); dialog = null },
        )
    }
}

@Composable
private fun SettingsItem(name: String, description: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(description) },
    )
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
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Uložit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}
