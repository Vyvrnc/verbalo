package dev.soupslurpr.transcribro.ui.history

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.soupslurpr.transcribro.ui.theme.VerbaloMotion
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cz.vyvrnc.transcribronpu.history.AppLog
import cz.vyvrnc.transcribronpu.history.DictationHistory
import cz.vyvrnc.transcribronpu.history.TranscriptExport
import cz.vyvrnc.transcribronpu.history.TranscriptExportMenu
import cz.vyvrnc.transcribronpu.npu.NpuSettings
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.ui.reusablecomposables.SecondaryButton
import dev.soupslurpr.transcribro.ui.reusablecomposables.VerbaloCard
import dev.soupslurpr.transcribro.ui.reusablecomposables.VerbaloTextButton
import dev.soupslurpr.transcribro.ui.theme.VerbaloTheme
import dev.soupslurpr.transcribro.ui.theme.VerbaloType
import dev.soupslurpr.transcribro.ui.theme.enterOnce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Lines of a transcript shown before "Show all". */
private const val PREVIEW_LINES = 4

/** Saved dictations (recording, Whisper text, edited text) and the app's event log. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = tab,
            containerColor = VerbaloTheme.colors.background,
            contentColor = VerbaloTheme.colors.primarySoft,
            indicator = {
                TabRowDefaults.PrimaryIndicator(
                    Modifier.tabIndicatorOffset(tab, matchContentSize = true),
                    color = VerbaloTheme.colors.primary,
                )
            },
            divider = { HorizontalDivider(color = VerbaloTheme.colors.border) },
        ) {
            listOf(R.string.history_tab_dictations, R.string.history_tab_log).forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    selectedContentColor = VerbaloTheme.colors.primarySoft,
                    unselectedContentColor = VerbaloTheme.colors.textMuted,
                    text = { Text(stringResource(title), style = MaterialTheme.typography.titleSmall) },
                )
            }
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
    val cannotPlay = stringResource(R.string.history_cannot_play)
    val exportFailed = stringResource(R.string.history_export_failed)
    val exportTitle = stringResource(R.string.history_export_all)

    LaunchedEffect(version) {
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
            Toast.makeText(context, cannotPlay, Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            VerbaloCard(Modifier.enterOnce(0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.history_save_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = VerbaloTheme.colors.textPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.history_save_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = VerbaloTheme.colors.textMuted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = save,
                        onCheckedChange = { save = it; NpuSettings.setSaveHistory(context, it) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = VerbaloTheme.colors.primary,
                            checkedThumbColor = VerbaloTheme.colors.onPrimary,
                            checkedBorderColor = VerbaloTheme.colors.primary,
                            uncheckedTrackColor = VerbaloTheme.colors.surfaceRaised,
                            uncheckedThumbColor = VerbaloTheme.colors.textMuted,
                            uncheckedBorderColor = VerbaloTheme.colors.border,
                        ),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        if (exporting) stringResource(R.string.history_exporting) else exportTitle,
                        enabled = entries.isNotEmpty() && !exporting,
                        onClick = {
                            exporting = true
                            scope.launch {
                                val zip = withContext(Dispatchers.IO) { runCatching { exportZip(context) }.getOrNull() }
                                exporting = false
                                if (zip == null) {
                                    Toast.makeText(context, exportFailed, Toast.LENGTH_SHORT).show()
                                } else {
                                    share(context, zip, exportTitle)
                                }
                            }
                        },
                    )
                    SecondaryButton(
                        stringResource(R.string.history_delete_all),
                        enabled = entries.isNotEmpty(),
                        destructive = true,
                        onClick = { confirmDeleteAll = true },
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VerbaloTheme.colors.textMuted,
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
                modifier = Modifier.enterOnce((entries.indexOf(entry) + 1).coerceAtMost(6)),
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            containerColor = VerbaloTheme.colors.surface,
            title = { Text(stringResource(R.string.history_delete_all_title)) },
            text = { Text(stringResource(R.string.history_delete_all_body)) },
            // The safe action on the left, the destructive one on the right.
            dismissButton = { VerbaloTextButton(stringResource(R.string.history_cancel), onClick = { confirmDeleteAll = false }) },
            confirmButton = {
                SecondaryButton(stringResource(R.string.history_delete), destructive = true, onClick = {
                    confirmDeleteAll = false
                    stop()
                    scope.launch(Dispatchers.IO) { DictationHistory.deleteAll(context) }
                })
            },
        )
    }
}

/** The stored source ("soubor: x", "klávesnice"…) in the user's language. */
@Composable
private fun sourceLabel(source: String): String = when {
    source.startsWith("soubor: ") -> source.removePrefix("soubor: ")
    source == "klávesnice" -> stringResource(R.string.history_source_keyboard)
    source == "plovoucí tlačítko" -> stringResource(R.string.history_source_floating)
    else -> source
}

@Composable
private fun EntryCard(
    entry: DictationHistory.Entry,
    playing: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val date = remember(entry.time) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.time))
    }
    VerbaloCard(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                Text(
                    sourceLabel(entry.source),
                    style = MaterialTheme.typography.titleSmall,
                    color = VerbaloTheme.colors.textPrimary,
                    maxLines = 1,
                )
                Text(
                    "$date · %.1f s · %s".format(entry.durationSec, entry.language) +
                        (entry.targetApp?.let { " · → $it" } ?: ""),
                    style = VerbaloType.monoSmall,
                    color = VerbaloTheme.colors.textMuted,
                )
            }
            if (entry.audio != null) {
                IconButton(onClick = onPlay) {
                    Icon(
                        if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.history_stop else R.string.history_play),
                        tint = VerbaloTheme.colors.primarySoft,
                    )
                }
            }
            TranscriptExportMenu(transcript = {
                // File transcriptions are saved with source "soubor: <name>" (FileTranscription).
                val fileName = entry.source.removePrefix("soubor: ").takeIf { entry.source.startsWith("soubor: ") }
                TranscriptExport.Transcript(
                    name = fileName,
                    time = entry.time,
                    durationSec = entry.durationSec,
                    language = entry.language,
                    source = if (fileName != null) null else entry.source + (entry.targetApp?.let { " → $it" } ?: ""),
                    text = entry.raw,
                    cleaned = entry.cleaned,
                )
            }) { open ->
                IconButton(onClick = open) {
                    Icon(Icons.Rounded.IosShare, contentDescription = stringResource(R.string.history_share), tint = VerbaloTheme.colors.textMuted)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.history_delete), tint = VerbaloTheme.colors.textMuted)
            }
        }
        TextBlock(context, stringResource(R.string.history_label_whisper), entry.raw)
        entry.cleaned?.let { cleaned ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp).padding(end = 12.dp), color = VerbaloTheme.colors.border)
            val timing = if (entry.cleanupLoadMs != null && entry.cleanupGenerateMs != null) {
                "  ${entry.cleanupModel ?: ""} · %.1f s + %.1f s".format(entry.cleanupLoadMs / 1000.0, entry.cleanupGenerateMs / 1000.0)
            } else ""
            TextBlock(context, stringResource(R.string.history_label_cleaned) + timing, cleaned)
        }
    }
}

@Composable
private fun TextBlock(context: Context, label: String, text: String) {
    val copied = stringResource(R.string.history_copied)
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = VerbaloTheme.colors.textMuted)
            Spacer(Modifier.height(2.dp))
            // Long transcripts show a preview; the whole text is one tap away.
            var expanded by rememberSaveable(text) { mutableStateOf(false) }
            var overflows by remember(text) { mutableStateOf(false) }
            SelectionContainer {
                Text(
                    text.ifEmpty { stringResource(R.string.history_empty_text) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = VerbaloTheme.colors.textPrimary,
                    maxLines = if (expanded) Int.MAX_VALUE else PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                    modifier = Modifier.animateContentSize(tween(VerbaloMotion.STATE_MS)),
                )
            }
            if (overflows || expanded) {
                Text(
                    stringResource(if (expanded) R.string.history_collapse else R.string.history_expand),
                    style = MaterialTheme.typography.labelMedium,
                    color = VerbaloTheme.colors.primarySoft,
                    modifier = Modifier
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .padding(vertical = 8.dp),
                )
            }
        }
        IconButton(onClick = { copy(context, text, copied) }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.history_copy), tint = VerbaloTheme.colors.textMuted)
        }
    }
}

@Composable
private fun LogView() {
    val context = LocalContext.current.applicationContext
    var text by remember { mutableStateOf(AppLog.read(context)) }
    val copied = stringResource(R.string.history_copied)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.history_log_refresh), onClick = { text = AppLog.read(context) })
            VerbaloTextButton(stringResource(R.string.history_copy), onClick = { copy(context, text, copied) })
            VerbaloTextButton(stringResource(R.string.history_log_clear), onClick = { AppLog.clear(context); text = "" })
        }
        Spacer(Modifier.height(12.dp))
        VerbaloCard(Modifier.weight(1f)) {
            SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Text(
                    text.ifEmpty { stringResource(R.string.history_log_empty) },
                    style = VerbaloType.monoSmall,
                    color = VerbaloTheme.colors.textSecondary,
                )
            }
        }
    }
}

private fun copy(context: Context, text: String, toast: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/** All dictations (JSON + WAV) and the log in one zip in the cache, for sharing. */
private fun exportZip(context: Context): File {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
    val zip = File(dir, "verbalo-history-$stamp.zip")
    ZipOutputStream(zip.outputStream().buffered()).use { out ->
        DictationHistory.dir(context).listFiles { f -> f.name.endsWith(".json") || f.name.endsWith(".wav") }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                out.putNextEntry(ZipEntry("history/${f.name}"))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        out.putNextEntry(ZipEntry("log.txt"))
        out.write(AppLog.read(context, Int.MAX_VALUE).toByteArray())
        out.closeEntry()
    }
    return zip
}

private fun share(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
