package dev.soupslurpr.transcribro.ui.settings

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import dev.soupslurpr.transcribro.ui.reusablecomposables.SectionLabel
import dev.soupslurpr.transcribro.ui.theme.VerbaloTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.dataStore
import dev.soupslurpr.transcribro.preferences.PreferencesViewModel
import dev.soupslurpr.transcribro.ui.reusablecomposables.ScreenLazyColumn

@Composable
fun SettingsStartScreen(
    onClickLicense: () -> Unit,
    onClickPrivacyPolicy: () -> Unit,
    onClickCredits: () -> Unit,
) {
    val preferencesViewModel: PreferencesViewModel = viewModel(
        factory = PreferencesViewModel.PreferencesViewModelFactory(LocalContext.current.dataStore)
    )

    val preferencesUiState by preferencesViewModel.uiState.collectAsState()

    val localUriHandler = LocalUriHandler.current

    ScreenLazyColumn(
        modifier = Modifier
            .fillMaxSize()
    ) {
        item {
            SettingsCategory(stringResource(R.string.settings_section_recognition))
        }
        item {
            NpuModelItem()
        }
        item {
            NpuRecognitionSettings()
        }
        item {
            SettingsCategory(stringResource(R.string.settings_section_floating))
        }
        item {
            FloatingSettings()
        }
        item {
            CleanupSettings()
        }
        item {
            SettingsCategory(
                stringResource(R.string.theme)
            )
        }
        item {
            val preference = preferencesUiState.pitchBlackBackground
            SettingsSwitchItem(
                name = stringResource(id = R.string.pitch_black_background_setting_name),
                description = stringResource(id = R.string.pitch_black_background_setting_description),
                checked = preference.second.value,
                onCheckedChange = {
                    preferencesViewModel.setPreference(
                        preference.first,
                        it
                    )
                }
            )
        }
        item {
            SettingsCategory(
                stringResource(R.string.voice_input_keyboard_setting_category)
            )
        }
        item {
            val preference = preferencesUiState.autoSwitchToPreviousInputMethod
            SettingsSwitchItem(
                name = stringResource(id = R.string.auto_switch_to_previous_input_method_setting_name),
                description = stringResource(id = R.string.auto_switch_to_previous_input_method_setting_description),
                checked = preference.second.value,
                onCheckedChange = {
                    preferencesViewModel.setPreference(
                        preference.first,
                        it
                    )
                }
            )
        }
        item {
            val preference = preferencesUiState.autoStopRecognition
            SettingsSwitchItem(
                name = stringResource(id = R.string.auto_stop_recognition_setting_name),
                description = stringResource(id = R.string.auto_stop_recognition_setting_description),
                checked = preference.second.value,
                onCheckedChange = {
                    preferencesViewModel.setPreference(
                        preference.first,
                        it
                    )
                }
            )
        }
        item {
            val preference = preferencesUiState.autoStartRecognition
            SettingsSwitchItem(
                name = stringResource(id = R.string.auto_start_recognition_setting_name),
                description = stringResource(id = R.string.auto_start_recognition_setting_description),
                checked = preference.second.value,
                onCheckedChange = {
                    preferencesViewModel.setPreference(
                        preference.first,
                        it
                    )
                }
            )
        }
        item {
            val preference = preferencesUiState.autoSendTranscription
            SettingsSwitchItem(
                name = stringResource(id = R.string.auto_send_transcription_setting_name),
                description = stringResource(id = R.string.auto_send_transcription_setting_description),
                checked = preference.second.value,
                onCheckedChange = {
                    preferencesViewModel.setPreference(
                        preference.first,
                        it
                    )
                }
            )
        }
        item {
            SettingsCategory(
                stringResource(R.string.about_setting_category)
            )
        }
        item {
            SettingsIconItem(
                name = stringResource(id = R.string.view_source_code_setting_name),
                description = stringResource(id = R.string.view_source_code_setting_description),
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                onClick = {
                    localUriHandler.openUri("https://github.com/Vyvrnc/Transcribro-NPU")
                }
            )
        }
        item {
            SettingsIconItem(
                name = stringResource(id = R.string.license_setting_name),
                description = stringResource(id = R.string.license_setting_description),
                icon = Icons.Filled.Info,
                onClick = onClickLicense
            )
        }
        item {
            SettingsIconItem(
                name = stringResource(id = R.string.privacy_policy_setting_name),
                description = stringResource(id = R.string.privacy_policy_setting_description),
                icon = Icons.Filled.Info,
                onClick = onClickPrivacyPolicy
            )
        }
        item {
            SettingsIconItem(
                name = stringResource(id = R.string.credits_setting_name),
                description = stringResource(id = R.string.credits_setting_description),
                icon = Icons.Filled.Info,
                onClick = onClickCredits
            )
        }
    }
}

/** Section label above a group of settings (docs/design-language.md, "Section label"). */
@Composable
fun SettingsCategory(category: String) {
    SectionLabel(category)
}

private val RowShape = RoundedCornerShape(12.dp)

/** One setting as a card row: title, one or two caption lines, a control at the end. */
@Composable
private fun SettingsRow(
    modifier: Modifier,
    name: String,
    description: String?,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(VerbaloTheme.colors.surface)
            .border(1.dp, VerbaloTheme.colors.border, RowShape)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = VerbaloTheme.colors.textPrimary)
            if (description != null) {
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = VerbaloTheme.colors.textMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
fun SettingsSwitchItem(
    modifier: Modifier = Modifier,
    name: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsRow(
        modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { onCheckedChange(it) }),
        name,
        description,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
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
}

@Composable
fun SettingsIconItem(
    modifier: Modifier = Modifier,
    name: String,
    description: String? = null,
    icon: ImageVector,
    onClick: () -> Unit
) {
    SettingsRow(modifier.clickable(role = Role.Button, onClick = onClick), name, description) {
        Icon(imageVector = icon, contentDescription = null, tint = VerbaloTheme.colors.textMuted)
    }
}
