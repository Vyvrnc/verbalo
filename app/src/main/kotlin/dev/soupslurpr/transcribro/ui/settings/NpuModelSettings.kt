package dev.soupslurpr.transcribro.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.vyvrnc.transcribronpu.download.ModelDownloadService
import cz.vyvrnc.transcribronpu.npu.NpuModel
import cz.vyvrnc.transcribronpu.npu.NpuSupport
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.ui.theme.VerbaloTheme

/** Status + download of the NPU model (2.0 GB from Qualcomm). */
@Composable
fun NpuModelItem() {
    ModelDownloadItem(
        model = ModelDownloadService.Model.WHISPER,
        headline = stringResource(R.string.model_settings_whisper_title),
        readyText = stringResource(R.string.model_settings_whisper_ready, NpuModel.NAME),
        idleText = stringResource(R.string.model_settings_whisper_idle),
        warning = if (NpuSupport.supported) null else stringResource(R.string.npu_unsupported_short, NpuSupport.socModel),
    )
}

private val CardShape = RoundedCornerShape(12.dp)

/**
 * A model download card: tap to start, tap again to cancel. The download runs in
 * [ModelDownloadService], so it continues when the app is left; a cancelled or failed
 * download resumes where it stopped. [warning] (e.g. an unsupported chip) is shown first;
 * the download is still possible.
 */
@Composable
fun ModelDownloadItem(
    model: ModelDownloadService.Model,
    headline: String,
    readyText: String,
    idleText: String,
    warning: String? = null,
) {
    val context = LocalContext.current.applicationContext
    val states by ModelDownloadService.states.collectAsState()
    val state = states[model]
    val running = state?.running == true
    // Re-checked whenever the download state changes (e.g. it just finished).
    val ready = remember(state) { model.ready(context) }
    val c = VerbaloTheme.colors

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(c.surface)
            .border(1.dp, if (warning != null && !ready) c.warning else c.border, CardShape)
            .clickable(enabled = !ready, role = Role.Button) {
                if (running) ModelDownloadService.cancel(context, model)
                else ModelDownloadService.start(context, model)
            }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                if (warning != null && !ready) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Warning, null, tint = c.warning, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(warning, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        ready -> readyText
                        running -> (state.status ?: stringResource(R.string.model_settings_connecting)) + "\n" +
                            stringResource(R.string.model_settings_tap_to_cancel)
                        state?.status != null -> state.status
                        else -> idleText
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = when {
                    ready -> Icons.Rounded.CheckCircle
                    running -> Icons.Rounded.Close
                    else -> Icons.Rounded.Download
                },
                contentDescription = null,
                tint = when {
                    ready -> c.success
                    running -> c.textMuted
                    else -> c.primarySoft
                },
            )
        }
        if (running) {
            Spacer(Modifier.height(12.dp))
            val p = state.progress
            val bar = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = bar, color = c.primary, trackColor = c.surfaceRaised, drawStopIndicator = {}, gapSize = 0.dp)
            } else {
                LinearProgressIndicator(modifier = bar, color = c.primary, trackColor = c.surfaceRaised, gapSize = 0.dp)
            }
        }
    }
}
