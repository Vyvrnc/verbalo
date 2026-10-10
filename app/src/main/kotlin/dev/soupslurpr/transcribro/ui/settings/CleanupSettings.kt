package dev.soupslurpr.transcribro.ui.settings

import androidx.compose.foundation.layout.Column
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

/** Gemma text cleanup: model download and an on/off switch (the format follows the content). */
@Composable
fun CleanupSettings() {
    val context = LocalContext.current.applicationContext
    val states by ModelDownloadService.states.collectAsState()
    val ready = remember(states[ModelDownloadService.Model.GEMMA]) { LlmModel.ready(context) }
    var enabled by remember { mutableStateOf(NpuSettings.cleanup(context)) }

    Column(Modifier.fillMaxWidth()) {
        ModelDownloadItem(
            model = ModelDownloadService.Model.GEMMA,
            headline = "Model pro úpravu textu (Gemma 4 E2B)",
            readyText = "Staženo a ověřeno (SHA-256). ${LlmModel.NAME}, licence Apache 2.0.",
            idleText = "Není stažený. Klepnutím stáhnete 2,6 GB z Hugging Face (litert-community), " +
                "doporučeno přes Wi-Fi. Stahuje se i na pozadí, běží jen v telefonu.",
        )
        SettingsSwitchItem(
            name = "Úprava diktátu",
            description = when {
                !enabled -> "Text z plovoucího tlačítka se vkládá tak, jak ho Whisper přepsal."
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
    }
}
