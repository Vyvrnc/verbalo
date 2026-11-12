package dev.soupslurpr.transcribro.ui.start

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import cz.vyvrnc.transcribronpu.file.FileTranscriptionActivity
import cz.vyvrnc.transcribronpu.npu.NpuSupport
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.ui.reusablecomposables.ActionRow
import dev.soupslurpr.transcribro.ui.reusablecomposables.ScreenLazyColumn
import dev.soupslurpr.transcribro.ui.reusablecomposables.SectionLabel
import dev.soupslurpr.transcribro.ui.reusablecomposables.VerbaloCard
import dev.soupslurpr.transcribro.ui.settings.FloatingController
import dev.soupslurpr.transcribro.ui.settings.floatingStatus
import dev.soupslurpr.transcribro.ui.settings.rememberFloatingController
import dev.soupslurpr.transcribro.ui.theme.VerbaloTheme
import dev.soupslurpr.transcribro.ui.theme.enterOnce

/**
 * Home. The floating button first (the thing used most: one tap, no trip to Settings), then
 * transcribing a recording, then what is still to set up for the keyboard and voice input.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun StartScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val microphone = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val floating = rememberFloatingController()
    var keyboardEnabled by rememberSaveable { mutableStateOf(isMyInputMethodEnabled(context)) }

    // The keyboard is turned on in system settings: check again whenever the user comes back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) keyboardEnabled = isMyInputMethodEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ScreenLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(Modifier.enterOnce(0)) }

        if (!NpuSupport.supported) {
            item {
                VerbaloCard(Modifier.enterOnce(1), borderColor = VerbaloTheme.colors.warning) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Warning, null, tint = VerbaloTheme.colors.warning, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.npu_unsupported_long, NpuSupport.socModel),
                            style = MaterialTheme.typography.bodySmall,
                            color = VerbaloTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }

        item { FloatingCard(floating, Modifier.enterOnce(2)) }
        if (floating.running && !floating.insertion) {
            item {
                ActionRow(
                    icon = Icons.Rounded.ContentPaste,
                    title = stringResource(R.string.start_insertion_title),
                    subtitle = stringResource(R.string.start_insertion_subtitle),
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.enterOnce(3),
                )
            }
        }

        item { SectionLabel(stringResource(R.string.start_section_transcribe), Modifier.enterOnce(4)) }
        item {
            ActionRow(
                icon = Icons.Rounded.AudioFile,
                title = stringResource(R.string.start_file_title),
                subtitle = stringResource(R.string.start_file_subtitle),
                onClick = { context.startActivity(Intent(context, FileTranscriptionActivity::class.java)) },
                modifier = Modifier.enterOnce(5),
            )
        }

        item { SectionLabel(stringResource(R.string.start_section_keyboard), Modifier.enterOnce(6)) }
        if (!microphone.status.isGranted) {
            item {
                ActionRow(
                    icon = Icons.Rounded.Mic,
                    title = stringResource(R.string.start_mic_title),
                    subtitle = stringResource(R.string.start_mic_subtitle),
                    onClick = { microphone.launchPermissionRequest() },
                    modifier = Modifier.enterOnce(7),
                )
            }
        }
        item {
            ActionRow(
                icon = Icons.Rounded.Keyboard,
                title = stringResource(if (keyboardEnabled) R.string.start_keyboard_on_title else R.string.start_keyboard_title),
                subtitle = stringResource(if (keyboardEnabled) R.string.start_keyboard_on_subtitle else R.string.start_keyboard_subtitle),
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                modifier = Modifier.enterOnce(8),
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** Mascot, name and tagline in one row: the screen is for actions, not a splash. */
@Composable
private fun Header(modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.verbalo_mascot), contentDescription = null, modifier = Modifier.size(64.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = VerbaloTheme.colors.textPrimary)
            Text(stringResource(R.string.start_tagline), style = MaterialTheme.typography.bodySmall, color = VerbaloTheme.colors.textMuted)
        }
    }
}

private val HeroShape = RoundedCornerShape(16.dp)

/** The floating button's switch, large: the whole card toggles it. */
@Composable
private fun FloatingCard(c: FloatingController, modifier: Modifier) {
    val colors = VerbaloTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(HeroShape)
            .background(colors.surface)
            .border(1.dp, if (c.running) colors.primary else colors.border, HeroShape)
            .toggleable(value = c.running, role = Role.Switch, onValueChange = c.toggle)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                .background(if (c.running) colors.primary else colors.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Mic,
                contentDescription = null,
                tint = if (c.running) colors.onPrimary else colors.primarySoft,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.floating_settings_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(floatingStatus(c), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = c.running,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.primary,
                checkedThumbColor = colors.onPrimary,
                checkedBorderColor = colors.primary,
                uncheckedTrackColor = colors.surfaceRaised,
                uncheckedThumbColor = colors.textMuted,
                uncheckedBorderColor = colors.border,
            ),
        )
    }
}

fun isMyInputMethodEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}
