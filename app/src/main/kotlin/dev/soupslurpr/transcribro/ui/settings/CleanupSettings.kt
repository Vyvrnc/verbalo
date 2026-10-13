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
}
