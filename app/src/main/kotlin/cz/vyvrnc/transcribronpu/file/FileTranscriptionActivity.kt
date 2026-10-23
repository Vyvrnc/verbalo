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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.soupslurpr.transcribro.dataStore
import dev.soupslurpr.transcribro.preferences.PreferencesViewModel
import dev.soupslurpr.transcribro.ui.theme.TranscribroTheme

/**
 * "Přepsat nahrávku": a recording picked here, or shared to the app (a voice message from
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
            TranscribroTheme(preferencesViewModel = preferencesViewModel) {
                Surface(Modifier.fillMaxSize()) {
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
            Toast.makeText(this, "Nahrávku pošlete přes Sdílet nebo ji vyberte v appce", Toast.LENGTH_LONG).show()
            return
        }
        startOn(uri)
    }

    private fun startOn(uri: Uri) {
        if (FileTranscription.busy) {
            Toast.makeText(this, "Už se přepisuje jiná nahrávka", Toast.LENGTH_SHORT).show()
            return
        }
        FileTranscription.start(this, uri)
    }
}

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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Přepis nahrávky", style = MaterialTheme.typography.headlineSmall)

        when (val s = state) {
            FileTranscription.State.Idle -> {
                Text(
                    "Vyberte nahrávku (m4a, mp3, ogg/opus, wav, flac, amr nebo video), nebo ji do Transcribro " +
                        "pošlete přes Sdílet z jiné aplikace, třeba hlasovou zprávu z Telegramu. Přepisuje se " +
                        "v telefonu na NPU, nic se neodesílá. Jazyk a slovník platí podle nastavení.",
                )
                Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }) { Text("Vybrat nahrávku") }
            }
            is FileTranscription.State.Decoding -> {
                Text("Načítám ${s.name}…")
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = FileTranscription::cancel) { Text("Zrušit") }
            }
            is FileTranscription.State.Transcribing -> {
                Text("Přepisuji ${s.name} (${minutes(s.audioSec)}) – ${(s.progress * 100).toInt()} %")
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = FileTranscription::cancel) { Text("Zrušit") }
                TranscriptText(s.text, Modifier.weight(1f))
            }
            is FileTranscription.State.Done -> {
                Text("${s.name} · ${minutes(s.audioSec)} zvuku přepsáno za ${minutes(s.tookMs / 1000.0)}")
                if (s.failedWindows > 0) {
                    Text(
                        "${s.failedWindows}× se úsek nepřepsal, v textu je označený [úsek …].",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Přepis", s.text))
                        Toast.makeText(context, "Zkopírováno", Toast.LENGTH_SHORT).show()
                    }) { Text("Kopírovat") }
                    FilledTonalButton(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, s.text)
                        }, "Sdílet přepis"))
                    }) { Text("Sdílet") }
                    OutlinedButton(onClick = FileTranscription::reset) { Text("Další") }
                }
                TranscriptText(s.text, Modifier.weight(1f))
            }
            is FileTranscription.State.Failed -> {
                Text("${s.name}: ${s.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    FileTranscription.reset()
                    picker.launch(arrayOf("audio/*", "video/*"))
                }) { Text("Vybrat jinou nahrávku") }
            }
        }
    }
}

@Composable
private fun TranscriptText(text: String, modifier: Modifier) {
    SelectionContainer(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun minutes(seconds: Double): String {
    val s = seconds.toInt()
    return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
}
