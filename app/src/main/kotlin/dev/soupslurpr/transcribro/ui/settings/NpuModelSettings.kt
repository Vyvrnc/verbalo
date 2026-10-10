package dev.soupslurpr.transcribro.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import cz.vyvrnc.transcribronpu.download.ModelDownloadService
import cz.vyvrnc.transcribronpu.npu.NpuModel

/** Status + download of the NPU model (2.0 GB from Qualcomm). */
@Composable
fun NpuModelItem() {
    ModelDownloadItem(
        model = ModelDownloadService.Model.WHISPER,
        headline = "NPU model (Whisper large-v3-turbo)",
        readyText = "Staženo a ověřeno (CRC-32). ${NpuModel.NAME}",
        idleText = "Není stažený. Klepnutím stáhnete 2,0 GB přímo od Qualcommu (na disku 2,2 GB), " +
            "doporučeno přes Wi-Fi. Stahuje se i na pozadí, přerušené stahování naváže.",
    )
}

/**
 * A model download row: tap to start, tap again to cancel. The download runs in
 * [ModelDownloadService], so it continues when the app is left; a cancelled or failed
 * download resumes where it stopped.
 */
@Composable
fun ModelDownloadItem(model: ModelDownloadService.Model, headline: String, readyText: String, idleText: String) {
    val context = LocalContext.current.applicationContext
    val states by ModelDownloadService.states.collectAsState()
    val state = states[model]
    val running = state?.running == true
    // Re-checked whenever the download state changes (e.g. it just finished).
    val ready = remember(state) { model.ready(context) }

    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(enabled = !ready) {
                if (running) ModelDownloadService.cancel(context, model)
                else ModelDownloadService.start(context, model)
            },
            headlineContent = { Text(headline, fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    when {
                        ready -> readyText
                        running -> (state.status ?: "Připojuji…") + "\nKlepnutím zrušíte."
                        state?.status != null -> state.status
                        else -> idleText
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
        if (running) {
            val p = state.progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
