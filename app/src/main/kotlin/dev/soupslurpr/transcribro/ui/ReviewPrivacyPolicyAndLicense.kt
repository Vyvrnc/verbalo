package dev.soupslurpr.transcribro.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.soupslurpr.transcribro.R
import dev.soupslurpr.transcribro.preferences.PreferencesViewModel
import dev.soupslurpr.transcribro.ui.reusablecomposables.PrimaryButton
import dev.soupslurpr.transcribro.ui.reusablecomposables.SectionLabel
import dev.soupslurpr.transcribro.ui.reusablecomposables.VerbaloCard
import dev.soupslurpr.transcribro.ui.theme.VerbaloTheme
import dev.soupslurpr.transcribro.ui.theme.VerbaloType
import kotlinx.coroutines.launch

/** First run: the privacy policy and the license, accepted once. */
@Composable
fun ReviewPrivacyPolicyAndLicense(
    preferencesViewModel: PreferencesViewModel,
) {
    val preferencesUiState by preferencesViewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var checked by remember { mutableStateOf(false) }
    val c = VerbaloTheme.colors

    Surface(Modifier.fillMaxSize(), color = c.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.verbalo_mascot), contentDescription = null, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.review_title), style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                        Text(stringResource(R.string.review_intro), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                }
                SectionLabel(stringResource(R.string.policy_title))
                VerbaloCard {
                    SelectionContainer {
                        Text(stringResource(R.string.policy_text), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    }
                }
                SectionLabel(stringResource(R.string.review_license_heading))
                VerbaloCard {
                    SelectionContainer {
                        Text(stringResource(R.string.full_license), style = VerbaloType.monoSmall, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // Always visible: the screen does not hide its only action below the fold.
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = c.primary, checkmarkColor = c.onPrimary, uncheckedColor = c.textMuted),
                        modifier = Modifier.padding(12.dp),
                    )
                    Text(stringResource(R.string.review_checkbox), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    stringResource(R.string.review_continue),
                    enabled = checked,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        coroutineScope.launch {
                            preferencesViewModel.setPreference(preferencesUiState.acceptedPrivacyPolicyAndLicense.first, checked)
                        }
                    },
                )
            }
        }
    }
}
