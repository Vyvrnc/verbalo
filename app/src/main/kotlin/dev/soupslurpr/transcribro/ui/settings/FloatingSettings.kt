package dev.soupslurpr.transcribro.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import dev.soupslurpr.transcribro.R
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cz.vyvrnc.transcribronpu.floating.FloatingDictationService
import cz.vyvrnc.transcribronpu.floating.TextInsertionService

/** What the floating button needs and does; shared by Settings and the start screen. */
class FloatingController(
    val running: Boolean,
    val overlay: Boolean,
    val mic: Boolean,
    val insertion: Boolean,
    /** Turns the button on (asking for whatever is missing first) or off. */
    val toggle: (Boolean) -> Unit,
)

@Composable
fun rememberFloatingController(): FloatingController {
    val context = LocalContext.current
    val running by FloatingDictationService.running.collectAsState()
    // Bumped on resume: permissions are granted in system screens outside the app.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val overlay = remember(refresh) { Settings.canDrawOverlays(context) }
    val mic = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val insertion = remember(refresh) { TextInsertionService.enabled(context) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        FloatingDictationService.start(context)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (granted && Settings.canDrawOverlays(context)) startWithNotifications(context, notificationPermission::launch)
    }
    return FloatingController(running, overlay, mic, insertion) { on ->
        when {
            !on -> FloatingDictationService.stop(context)
            !overlay -> context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
            !mic -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else -> startWithNotifications(context, notificationPermission::launch)
        }
    }
}

/** One line on what the button does now, or what it still needs. */
@Composable
fun floatingStatus(c: FloatingController): String = when {
    !c.overlay -> stringResource(R.string.floating_settings_need_overlay)
    !c.mic -> stringResource(R.string.floating_settings_need_mic)
    c.running -> stringResource(R.string.floating_settings_running)
    else -> stringResource(R.string.floating_settings_idle)
}

/** Floating dictation button: switch, overlay/microphone permissions, text insertion. */
@Composable
fun FloatingSettings() {
    val context = LocalContext.current
    val c = rememberFloatingController()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSwitchItem(
            name = stringResource(R.string.floating_settings_title),
            description = floatingStatus(c),
            checked = c.running,
            onCheckedChange = c.toggle,
        )
        SettingsIconItem(
            name = stringResource(R.string.floating_settings_insertion_title),
            description = if (c.insertion) stringResource(R.string.floating_settings_insertion_on)
                else stringResource(R.string.floating_settings_insertion_off, stringResource(R.string.app_name)),
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        )
    }
}

private fun startWithNotifications(context: android.content.Context, request: (String) -> Unit) {
    // The notification only says the button is on; without the permission the service still runs.
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        request(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        FloatingDictationService.start(context)
    }
}
