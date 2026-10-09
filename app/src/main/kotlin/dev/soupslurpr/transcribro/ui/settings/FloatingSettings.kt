package dev.soupslurpr.transcribro.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cz.vyvrnc.transcribronpu.floating.FloatingDictationService
import cz.vyvrnc.transcribronpu.floating.TextInsertionService

/** Floating dictation button: switch, overlay/microphone permissions, text insertion. */
@Composable
fun FloatingSettings() {
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

    Column(Modifier.fillMaxWidth()) {
        SettingsSwitchItem(
            name = "Plovoucí tlačítko",
            description = when {
                !overlay -> "Tlačítko pro diktování nad všemi aplikacemi. Nejdřív povolte „Zobrazovat přes ostatní aplikace“ (klepnutím)."
                !mic -> "Tlačítko pro diktování nad všemi aplikacemi. Potřebuje přístup k mikrofonu."
                running -> "Zapnuto. Klepnutí na tlačítko spustí diktování, další klepnutí ho ukončí; tažením ho přesunete. " +
                    "Po restartu telefonu ho zapněte znovu tady."
                else -> "Tlačítko pro diktování nad všemi aplikacemi, bez přepínání klávesnice."
            },
            checked = running,
            onCheckedChange = { on ->
                when {
                    !on -> FloatingDictationService.stop(context)
                    !overlay -> context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                    !mic -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    else -> startWithNotifications(context, notificationPermission::launch)
                }
            },
        )
        ListItem(
            modifier = Modifier.clickable {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            headlineContent = { Text("Vkládání textu do polí", fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    if (insertion) "Zapnuto: diktát z plovoucího tlačítka se vloží na místo kurzoru."
                    else "Vypnuto: diktát skončí ve schránce. Klepnutím otevřete Přístupnost → Transcribro NPU " +
                        "a službu zapněte. Čte jen pole, do kterého se píše."
                )
            },
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
