package cz.vyvrnc.transcribronpu.file

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.vyvrnc.transcribronpu.history.TranscriptExport
import cz.vyvrnc.transcribronpu.history.TranscriptExportMenu
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.dataStore
import dev.soupslurpr.transcribro.preferences.PreferencesViewModel
import dev.soupslurpr.transcribro.ui.reusablecomposables.FeatureChip
import dev.soupslurpr.transcribro.ui.reusablecomposables.IconTile
import dev.soupslurpr.transcribro.ui.reusablecomposables.PrimaryButton
import dev.soupslurpr.transcribro.ui.reusablecomposables.SecondaryButton
import dev.soupslurpr.transcribro.ui.reusablecomposables.VerbaloCard
import dev.soupslurpr.transcribro.ui.reusablecomposables.VerbaloTextButton
import dev.soupslurpr.transcribro.ui.theme.JetBrainsMono
import dev.soupslurpr.transcribro.ui.theme.VerbaloColors
import dev.soupslurpr.transcribro.ui.theme.VerbaloTheme
import dev.soupslurpr.transcribro.ui.theme.VerbaloType
import dev.soupslurpr.transcribro.ui.theme.enterOnce

/**
 * "Transcribe a recording": a recording picked here, or shared to the app (a voice message from
 * Telegram or WhatsApp, a call recording, a video), transcribed on the NPU.
 */
class FileTranscriptionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val preferencesViewModel: PreferencesViewModel = viewModel(
                factory = PreferencesViewModel.PreferencesViewModelFactory(dataStore)
            )
            VerbaloTheme(preferencesViewModel = preferencesViewModel) {
                Surface(Modifier.fillMaxSize(), color = VerbaloTheme.colors.background) {
                    FileTranscriptionScreen(onPicked = ::startOn)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        // content:// only: a file:// path would be read with this app's rights, private files included.
        if (uri.scheme != "content") {
            Toast.makeText(this, getString(R.string.transcribe_toast_use_share), Toast.LENGTH_LONG).show()
            return
        }
        startOn(uri)
    }

    private fun startOn(uri: Uri) {
        if (FileTranscription.busy) {
            Toast.makeText(this, getString(R.string.transcribe_toast_busy), Toast.LENGTH_SHORT).show()
            return
        }
        FileTranscription.start(this, uri)
    }
}

private val MIME_TYPES = arrayOf("audio/*", "video/*")

@Composable
private fun FileTranscriptionScreen(onPicked: (Uri) -> Unit) {
    val context = LocalContext.current
    val state by FileTranscription.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    val working = state is FileTranscription.State.Decoding || state is FileTranscription.State.Transcribing

    // The phone must not sleep while the NPU works through a long recording.
    val window = (context as? ComponentActivity)?.window
    DisposableEffect(working) {
        if (working) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.transcribe_title),
            style = MaterialTheme.typography.headlineSmall,
            color = VerbaloTheme.colors.textPrimary,
            modifier = Modifier.enterOnce(0),
        )

        when (val s = state) {
            FileTranscription.State.Idle -> {
                VerbaloCard(Modifier.enterOnce(1)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Rounded.AudioFile)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.transcribe_idle_heading),
                            style = MaterialTheme.typography.titleSmall,
                            color = VerbaloTheme.colors.textPrimary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.transcribe_idle_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VerbaloTheme.colors.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "m4a · mp3 · ogg/opus · wav · flac · amr · mp4",
                        style = VerbaloType.monoSmall,
                        color = VerbaloTheme.colors.textMuted,
                    )
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(
                    stringResource(R.string.transcribe_pick),
                    onClick = { picker.launch(MIME_TYPES) },
                    modifier = Modifier.fillMaxWidth().enterOnce(2),
                )
            }

            is FileTranscription.State.Decoding -> {
                ProgressCard(
                    line = stringResource(R.string.transcribe_loading, s.name),
                    detail = null,
                    progress = s.progress,
                )
                SecondaryButton(stringResource(R.string.transcribe_cancel), onClick = FileTranscription::cancel)
            }

            is FileTranscription.State.Transcribing -> {
                ProgressCard(
                    line = s.name,
                    detail = stringResource(R.string.transcribe_progress, minutes(s.audioSec), (s.progress * 100).toInt()),
                    progress = s.progress,
                )
                SecondaryButton(stringResource(R.string.transcribe_cancel), onClick = FileTranscription::cancel)
                TranscriptCard(s.text, Modifier.weight(1f))
            }

            is FileTranscription.State.Done -> {
                StatusLine(
                    Icons.Rounded.CheckCircle,
                    VerbaloTheme.colors.success,
                    stringResource(R.string.transcribe_done, s.name, minutes(s.audioSec), minutes(s.tookMs / 1000.0)),
                )
                if (s.failedWindows > 0) {
                    StatusLine(
                        Icons.Rounded.Warning,
                        VerbaloTheme.colors.warning,
                        stringResource(R.string.transcribe_failed_windows, s.failedWindows),
                    )
                }
                TranscriptCard(s.text, Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TranscriptExportMenu(transcript = {
                        TranscriptExport.Transcript(
                            name = s.name,
                            time = System.currentTimeMillis(),
                            durationSec = s.audioSec,
                            language = null,
                            source = null,
                            text = s.text,
                        )
                    }) { open ->
                        PrimaryButton(stringResource(R.string.transcribe_share), onClick = open, icon = Icons.Rounded.IosShare)
                    }
                    val copied = stringResource(R.string.transcribe_copied)
                    SecondaryButton(stringResource(R.string.transcribe_copy), icon = Icons.Rounded.ContentCopy, onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(s.name, s.text))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    })
                    Spacer(Modifier.weight(1f))
                    VerbaloTextButton(stringResource(R.string.transcribe_next), onClick = FileTranscription::reset)
                }
            }

            is FileTranscription.State.Failed -> {
                VerbaloCard(borderColor = VerbaloTheme.colors.error) {
                    StatusLine(Icons.Rounded.Error, VerbaloTheme.colors.error, s.name)
                    Spacer(Modifier.height(4.dp))
                    Text(s.message, style = MaterialTheme.typography.bodyMedium, color = VerbaloTheme.colors.textSecondary)
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(
                    stringResource(R.string.transcribe_pick_other),
                    onClick = {
                        FileTranscription.reset()
                        picker.launch(MIME_TYPES)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** What happens now, the numbers in mono, and a teal bar on the raised surface. */
@Composable
private fun ProgressCard(line: String, detail: String?, progress: Float) {
    VerbaloCard {
        Text(line, style = MaterialTheme.typography.titleSmall, color = VerbaloTheme.colors.textPrimary, maxLines = 1)
        if (detail != null) {
            Text(detail, style = VerbaloType.mono, color = VerbaloTheme.colors.textMuted)
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = VerbaloTheme.colors.primary,
            trackColor = VerbaloTheme.colors.surfaceRaised,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
    }
}

/** Status: an icon and words, never colour alone. */
@Composable
private fun StatusLine(icon: ImageVector, color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = VerbaloTheme.colors.textSecondary)
    }
}

/** The transcript, selectable, in a card that scrolls by itself. */
@Composable
private fun TranscriptCard(text: String, modifier: Modifier) {
    val colors = VerbaloTheme.colors
    val styled = remember(text, colors) { styleTranscript(text, colors) }
    VerbaloCard(modifier) {
        SelectionContainer(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(styled, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
        }
    }
}

/** "[mm:ss] Speaker: …" paragraphs of a stereo call: the time in mono, the speaker in teal. */
private val TURN = Regex("""^\[(\d+:\d\d)] ([^:\n]{1,30}): """, RegexOption.MULTILINE)

private fun styleTranscript(text: String, c: VerbaloColors): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in TURN.findAll(text)) {
        append(text.substring(at, m.range.first))
        withStyle(SpanStyle(fontFamily = JetBrainsMono, color = c.textMuted)) { append(m.groupValues[1]) }
        append("  ")
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.primarySoft)) { append(m.groupValues[2]) }
        append("  ")
        at = m.range.last + 1
    }
    append(text.substring(at))
}

private fun minutes(seconds: Double): String {
    val s = seconds.toInt()
    return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
}
