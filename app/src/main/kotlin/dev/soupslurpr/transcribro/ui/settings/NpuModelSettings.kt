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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import cz.vyvrnc.transcribronpu.npu.NpuModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Status + download of the NPU model (2.0 GB from Qualcomm). Tap to start, tap again to
 * cancel; a cancelled or failed download resumes where it stopped.
 */
@Composable
fun NpuModelItem() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(NpuModel.ready(context)) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val running = job?.isActive == true
    val description = when {
        ready -> "Staženo a ověřeno (CRC-32). ${NpuModel.NAME}"
        running -> status ?: "Připojuji…"
        status != null -> status!!
        else -> "Není stažený. Klepnutím stáhnete 2,0 GB přímo od Qualcommu (na disku 2,2 GB), " +
            "doporučeno přes Wi-Fi. Přerušené stahování naváže."
    }

    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(enabled = !ready) {
                if (running) {
                    job?.cancel()
                    return@clickable
                }
                val started = System.nanoTime()
                // Every state write checks that this download is still the current one: a
                // cancelled download's blocking read ends later and must not overwrite the
                // state (or clear the handle) of a download started after it.
                lateinit var self: Job
                self = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        withContext(Dispatchers.IO) {
                            NpuModel.download(context) { phase, done, total ->
                                // Check and write on the main thread together, where the
                                // click handler swaps `job`.
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
                            ready = NpuModel.ready(context)
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
            headlineContent = { Text("NPU model (Whisper large-v3-turbo)", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(description) },
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
    }
}
