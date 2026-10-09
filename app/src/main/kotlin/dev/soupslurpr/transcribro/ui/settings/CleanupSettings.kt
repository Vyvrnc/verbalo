package dev.soupslurpr.transcribro.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.vyvrnc.transcribronpu.llm.LlmCleaner
import cz.vyvrnc.transcribronpu.llm.LlmModel
import cz.vyvrnc.transcribronpu.npu.NpuSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gemma text cleanup: model download and mode. */
@Composable
fun CleanupSettings() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(LlmModel.ready(context)) }
    var mode by remember { mutableStateOf(NpuSettings.cleanupMode(context)) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf(false) }
    val running = job?.isActive == true

    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(enabled = !ready) {
                if (running) {
                    job?.cancel()
                    return@clickable
                }
                val started = System.nanoTime()
                lateinit var self: Job
                self = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        withContext(Dispatchers.IO) {
                            LlmModel.download(context) { phase, done, total ->
                                scope.launch {
                                    if (job !== self) return@launch
                                    val secs = ((System.nanoTime() - started) / 1e9).coerceAtLeast(0.1)
                                    progress = done.toFloat() / total.coerceAtLeast(1)
                                    status = "%s: %.0f / %.0f MB (%.1f MB/s). Klepnutím zrušíte.".format(
                                        phase, done / 1048576.0, total / 1048576.0, done / 1048576.0 / secs)
                                }
                            }
                        }
                        if (job === self) {
                            ready = LlmModel.ready(context)
                            status = null
                        }
                    } catch (e: CancellationException) {
                        if (job === self) status = "Stahování přerušeno, při dalším klepnutí naváže."
                    } catch (e: Exception) {
                        if (job === self) status = "Chyba: ${e.message}"
                    } finally {
                        if (job === self) {
                            progress = null
                            job = null
                        }
                    }
                }
                job = self
                self.start()
            },
            headlineContent = { Text("Model pro úpravu textu (Gemma 4 E2B)", fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    when {
                        ready -> "Staženo a ověřeno (SHA-256). ${LlmModel.NAME}, licence Apache 2.0."
                        running -> status ?: "Připojuji…"
                        status != null -> status!!
                        else -> "Není stažený. Klepnutím stáhnete 2,6 GB z Hugging Face (litert-community), " +
                            "doporučeno přes Wi-Fi. Běží jen v telefonu."
                    }
                )
            },
            trailingContent = {
                Icon(
                    imageVector = when {
                        ready -> Icons.Filled.CheckCircle
                        running -> Icons.Filled.Close
                        else -> Icons.Filled.Download
                    },
                    contentDescription = null,
                )
            },
        )
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        ListItem(
            modifier = Modifier.clickable { dialog = true },
            headlineContent = { Text("Úprava diktátu", fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    "${mode.label}. " + if (mode == LlmCleaner.Mode.OFF) {
                        "Text z plovoucího tlačítka se vkládá tak, jak ho Whisper přepsal."
                    } else if (!ready) {
                        "Bez staženého modelu se text vloží neupravený."
                    } else {
                        "Plovoucí tlačítko vloží text až po úpravě (pár sekund po konci diktátu)."
                    }
                )
            },
        )
    }

    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("Úprava diktátu") },
            text = {
                Column {
                    LlmCleaner.Mode.entries.forEach { m ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = m == mode) {
                                    mode = m
                                    NpuSettings.setCleanupMode(context, m)
                                    dialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = m == mode, onClick = null)
                            Text(
                                when (m) {
                                    LlmCleaner.Mode.OFF -> "Vypnuto"
                                    LlmCleaner.Mode.FIX -> "Oprava – překlepy, interpunkce, přeřeky"
                                    LlmCleaner.Mode.BULLETS -> "Odrážky – body pod sebou"
                                    LlmCleaner.Mode.EMAIL -> "E-mail – oslovení a odstavce"
                                },
                                Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = false }) { Text("Zavřít") } },
        )
    }
}
