package dev.soupslurpr.transcribro.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cz.vyvrnc.transcribronpu.history.AppLog
import cz.vyvrnc.transcribronpu.history.DictationHistory
import cz.vyvrnc.transcribronpu.npu.NpuSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Saved dictations (recording, Whisper text, edited text) and the app's event log. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Diktáty") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Protokol") })
        }
        if (tab == 0) DictationList() else LogView()
    }
}

@Composable
private fun DictationList() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val version by DictationHistory.version.collectAsState()
    var entries by remember { mutableStateOf<List<DictationHistory.Entry>>(emptyList()) }
    var save by remember { mutableStateOf(NpuSettings.saveHistory(context)) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<String?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }

    androidx.compose.runtime.LaunchedEffect(version) {
        entries = withContext(Dispatchers.IO) { DictationHistory.list(context) }
    }
    DisposableEffect(Unit) {
        onDispose { player.value?.release(); player.value = null }
    }

    fun stop() {
        player.value?.release()
        player.value = null
        playing = null
    }

    fun play(entry: DictationHistory.Entry) {
        stop()
        val file = entry.audio ?: return
        val mp = MediaPlayer()
        runCatching {
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { stop() }
            mp.prepare()
            mp.start()
            player.value = mp
            playing = entry.id
        }.onFailure {
            mp.release()
            Toast.makeText(context, "Nahrávku nelze přehrát", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ukládat nahrávky a přepisy", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Zůstávají jen v telefonu, nejvýš posledních 300. Hodí se k opravám a později " +
                            "k doladění modelů (export).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = save, onCheckedChange = { save = it; NpuSettings.setSaveHistory(context, it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(enabled = entries.isNotEmpty() && !exporting, onClick = {
                    exporting = true
                    scope.launch {
                        val zip = withContext(Dispatchers.IO) { runCatching { exportZip(context) }.getOrNull() }
                        exporting = false
                        if (zip == null) {
                            Toast.makeText(context, "Export se nepovedl", Toast.LENGTH_SHORT).show()
                        } else {
                            share(context, zip)
                        }
                    }
                }) { Text(if (exporting) "Připravuji…" else "Exportovat vše") }
                TextButton(enabled = entries.isNotEmpty(), onClick = { confirmDeleteAll = true }) { Text("Smazat vše") }
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "Zatím žádné uložené diktáty.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(entries, key = { it.id }) { entry ->
            EntryCard(
                entry = entry,
                playing = playing == entry.id,
                onPlay = { if (playing == entry.id) stop() else play(entry) },
                onDelete = {
                    if (playing == entry.id) stop()
                    scope.launch(Dispatchers.IO) { DictationHistory.delete(context, entry.id) }
                },
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Smazat všechny diktáty?") },
            text = { Text("Smaže nahrávky i přepisy. Nejde to vrátit.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    stop()
                    scope.launch(Dispatchers.IO) { DictationHistory.deleteAll(context) }
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Zrušit") } },
        )
    }
}

private val dateFormat = SimpleDateFormat("d. M. yyyy H:mm", Locale("cs", "CZ"))

@Composable
private fun EntryCard(entry: DictationHistory.Entry, playing: Boolean, onPlay: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${dateFormat.format(Date(entry.time))} · ${entry.source}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "%.1f s · %s".format(entry.durationSec, entry.language) +
                            (entry.targetApp?.let { " · do: $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (entry.audio != null) {
                    IconButton(onClick = onPlay) {
                        Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = if (playing) "Zastavit" else "Přehrát")
                    }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Smazat") }
            }
            TextBlock(context, "Whisper", entry.raw)
            entry.cleaned?.let { cleaned ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                val timing = if (entry.cleanupLoadMs != null && entry.cleanupGenerateMs != null) {
                    " (%s, načtení %.1f s, úprava %.1f s)".format(
                        entry.cleanupModel ?: "", entry.cleanupLoadMs / 1000.0, entry.cleanupGenerateMs / 1000.0)
                } else ""
                TextBlock(context, "Po úpravě$timing", cleaned)
            }
        }
    }
}

@Composable
private fun TextBlock(context: Context, label: String, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(text.ifEmpty { "(prázdné)" }, style = MaterialTheme.typography.bodyMedium) }
        }
        IconButton(onClick = { copy(context, text) }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Kopírovat") }
    }
}

@Composable
private fun LogView() {
    val context = LocalContext.current.applicationContext
    var text by remember { mutableStateOf(AppLog.read(context)) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { text = AppLog.read(context) }) { Text("Obnovit") }
            TextButton(onClick = { copy(context, text) }) { Text("Kopírovat") }
            TextButton(onClick = { AppLog.clear(context); text = "" }) { Text("Smazat") }
        }
        Spacer(Modifier.height(8.dp))
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Text(
                text.ifEmpty { "Protokol je prázdný." },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Transcribro", text))
    Toast.makeText(context, "Zkopírováno", Toast.LENGTH_SHORT).show()
}

/** All dictations (JSON + WAV) and the log in one zip in the cache, for sharing. */
private fun exportZip(context: Context): File {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
    val zip = File(dir, "transcribro-historie-$stamp.zip")
    ZipOutputStream(zip.outputStream().buffered()).use { out ->
        DictationHistory.dir(context).listFiles { f -> f.name.endsWith(".json") || f.name.endsWith(".wav") }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                out.putNextEntry(ZipEntry("historie/${f.name}"))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        out.putNextEntry(ZipEntry("protokol.txt"))
        out.write(AppLog.read(context, Int.MAX_VALUE).toByteArray())
        out.closeEntry()
    }
    return zip
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export historie").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
